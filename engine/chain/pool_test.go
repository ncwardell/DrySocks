package chain

import (
	"context"
	"io"
	"net"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time      { return c.t }
func (c *clock) add(d time.Duration) { c.t = c.t.Add(d) }
func testPool(interval time.Duration) (*Pool, *clock) {
	c := &clock{t: time.Unix(1000, 0)}
	p := NewPool(Sequential, interval)
	p.now = c.now
	return p, c
}

func hopsAt(addrs ...string) []Hop {
	var hs []Hop
	for _, a := range addrs {
		hs = append(hs, Hop{Kind: SOCKS5, Addr: a})
	}
	return hs
}

func current(t *testing.T, p *Pool) string {
	t.Helper()
	h, err := p.Current()
	if err != nil {
		t.Fatal(err)
	}
	return h.Addr
}

func TestPoolRotatesOnItsInterval(t *testing.T) {
	p, c := testPool(30 * time.Second)
	p.Set(hopsAt("a:1", "b:1", "c:1"), Timed, 30*time.Second)

	first := current(t, p)
	c.add(29 * time.Second)
	if got := current(t, p); got != first {
		t.Fatalf("rotated early from %s to %s", first, got)
	}
	c.add(time.Second)
	if got := current(t, p); got == first {
		t.Fatalf("still on %s after 30s", got)
	}
	if s := p.Status(); s.Size != 3 || s.Healthy != 3 || s.Current == "" {
		t.Errorf("status %+v", s)
	}
}

func TestPoolBenchesFailuresAndMovesOn(t *testing.T) {
	p, c := testPool(time.Hour)
	p.Set(hopsAt("a:1", "b:1", "c:1"), Sequential, time.Hour)
	current(t, p)

	p.Failed("a:1")
	if got := current(t, p); got != "b:1" {
		t.Fatalf("after a failed: %s", got)
	}
	p.Failed("b:1")
	if got := current(t, p); got != "c:1" {
		t.Fatalf("after b failed: %s", got)
	}
	if s := p.Status(); s.Healthy != 1 {
		t.Errorf("healthy %d, want 1", s.Healthy)
	}
	c.add(BenchFor + time.Second)
	if s := p.Status(); s.Healthy != 3 {
		t.Errorf("benches should expire; healthy %d", s.Healthy)
	}
}

func TestPoolKeepsTheCurrentProxyAcrossARefill(t *testing.T) {
	p, _ := testPool(time.Hour)
	p.Set(hopsAt("a:1", "b:1"), Sequential, time.Hour)
	p.Failed("a:1") // now on b
	p.Set(hopsAt("x:1", "b:1", "y:1"), Sequential, time.Hour)
	if got := current(t, p); got != "b:1" {
		t.Fatalf("refill moved the current proxy to %s", got)
	}
}

func TestRotatingHopRetriesOnTheNextProxy(t *testing.T) {
	echo := echoServer(t)
	var deadHits atomic.Int32
	dead := listen(t, func(c net.Conn) { deadHits.Add(1) }) // accepts, says nothing, hangs up
	alive := socks5Server(t, "", "")

	name := "test-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	pool := PoolNamed(name)
	pool.Set(hopsAt(dead, alive), Sequential, time.Hour)

	c := &Chain{Hops: mustHops(t, "rotate://"+name)}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, steps, err := c.Probe(ctx, echo)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	roundTrip(t, conn)

	if deadHits.Load() != 1 {
		t.Errorf("dead proxy tried %d times, want once", deadHits.Load())
	}
	if len(steps) != 2 || steps[0].To != alive {
		t.Errorf("steps %+v; want the retry's legs only, through %s", steps, alive)
	}
	if got := current(t, pool); got != alive {
		t.Errorf("pool on %s after the failure, want %s", got, alive)
	}
}

func TestRotatingHopAfterAFixedOne(t *testing.T) {
	// fixed → rotating: the fixed hop cannot reach a dead pool proxy; the
	// pool proxy is to blame, not the fixed hop.
	echo := echoServer(t)
	fixed := socks5Server(t, "", "")
	alive := socks5Server(t, "", "")
	closed := listen(t, func(c net.Conn) {}) // an address; closing it makes it dead
	name := "test2-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	PoolNamed(name).Set(hopsAt(closed, alive), Sequential, time.Hour)

	c := &Chain{Hops: mustHops(t, "socks5://"+fixed, "rotate://"+name)}
	conn, err := c.DialContext(context.Background(), echo)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	roundTrip(t, conn)
}

func TestAHangingProxyDoesNotUseUpTheDial(t *testing.T) {
	// Accepts and never answers: the usual way a free proxy dies.
	hang := listen(t, func(c net.Conn) { time.Sleep(20 * time.Second) })
	alive := socks5Server(t, "", "")
	echo := echoServer(t)
	name := "test3-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	PoolNamed(name).Set(hopsAt(hang, alive), Sequential, time.Hour)

	// The whole dial has 15 s, as tun2socks' would have 30: the hang must cost one
	// attempt, not all of it.
	ctx, cancel := context.WithTimeout(context.Background(), attemptTimeout+5*time.Second)
	defer cancel()
	start := time.Now()
	conn, err := (&Chain{Hops: mustHops(t, "rotate://"+name)}).DialContext(ctx, echo)
	if err != nil {
		t.Fatalf("after %v: %v", time.Since(start), err)
	}
	defer conn.Close()
	roundTrip(t, conn)
}

func TestEmptyPoolSaysSo(t *testing.T) {
	c := &Chain{Hops: mustHops(t, "rotate://nothing-here-"+strconv.FormatInt(time.Now().UnixNano(), 10))}
	if _, err := c.DialContext(context.Background(), "example.com:80"); err == nil {
		t.Fatal("dialled through an empty pool")
	}
}

func TestCheckBenchesTheDeadInAdvance(t *testing.T) {
	target := echoServer(t)
	alive := socks5Server(t, "", "")
	hang := listen(t, func(c net.Conn) { time.Sleep(5 * time.Second) })
	refuse := listen(t, func(c net.Conn) {}) // accepts, hangs up

	p := NewPool(Sequential, time.Hour)
	p.Set(hopsAt(hang, refuse, alive), Sequential, time.Hour)

	ok, dead := p.Check(context.Background(), nil, target, 4, time.Second)
	if ok != 1 || dead != 2 {
		t.Fatalf("alive %d dead %d, want 1 and 2", ok, dead)
	}
	if got := current(t, p); got != alive {
		t.Errorf("current %s, want the live one", got)
	}
	if s := p.Status(); s.Healthy != 1 {
		t.Errorf("healthy %d", s.Healthy)
	}
}

func TestSequentialStaysUntilAFailureThenGoesInOrder(t *testing.T) {
	p, c := testPool(0)
	p.Set(hopsAt("a:1", "b:1", "c:1"), Sequential, time.Second) // an interval it must ignore
	for range 5 {
		c.add(time.Hour)
		if got := current(t, p); got != "a:1" {
			t.Fatalf("moved to %s without a failure", got)
		}
	}
	p.Failed("a:1")
	if got := current(t, p); got != "b:1" {
		t.Fatalf("after a failed: %s, want the next in order", got)
	}
	p.Failed("b:1")
	if got := current(t, p); got != "c:1" {
		t.Fatalf("after b failed: %s", got)
	}
	if s := p.Status(); s.Current == "" || s.NextInMs != 0 {
		t.Errorf("status %+v: sequential has a current proxy and no countdown", s)
	}
}

func TestScatteredPicksPerConnection(t *testing.T) {
	p, _ := testPool(0)
	var addrs []string
	for i := range 20 {
		addrs = append(addrs, "h"+strconv.Itoa(i)+":1")
	}
	p.Set(hopsAt(addrs...), Scattered, 0)
	seen := map[string]bool{}
	prev := ""
	repeats := 0
	for range 200 {
		got := current(t, p)
		seen[got] = true
		if got == prev {
			repeats++
		}
		prev = got
	}
	if len(seen) < 15 {
		t.Errorf("200 connections used only %d of 20 proxies", len(seen))
	}
	if repeats > 0 {
		t.Errorf("%d back-to-back repeats; each connection should move on", repeats)
	}
	p.Failed("h0:1")
	for range 200 {
		if current(t, p) == "h0:1" {
			t.Fatal("scattered picked a benched proxy")
		}
	}
	if s := p.Status(); s.Current != "" {
		t.Errorf("scattered has no single current proxy, status says %q", s.Current)
	}
}

// honestSocks5 dials the target first and reports the outcome, as a real proxy does:
// 0x05 "connection refused" when the target is not there.
func honestSocks5(t *testing.T) string {
	return listen(t, func(c net.Conn) {
		var b [262]byte
		io.ReadFull(c, b[:2])
		io.ReadFull(c, b[:b[1]])
		c.Write([]byte{5, 0})
		io.ReadFull(c, b[:4])
		var host string
		switch b[3] {
		case 1:
			io.ReadFull(c, b[:4])
			host = net.IP(b[:4]).String()
		case 3:
			io.ReadFull(c, b[:1])
			n := int(b[0])
			io.ReadFull(c, b[:n])
			host = string(b[:n])
		}
		io.ReadFull(c, b[:2])
		target := net.JoinHostPort(host, strconv.Itoa(int(b[0])<<8|int(b[1])))
		up, err := net.DialTimeout("tcp", target, time.Second)
		if err != nil {
			c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
			return
		}
		defer up.Close()
		c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
		go io.Copy(up, c)
		io.Copy(c, up)
	})
}

// closedAddr is an address nothing listens on.
func closedAddr(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	a := ln.Addr().String()
	ln.Close()
	return a
}

func TestADeadTargetBenchesNoProxy(t *testing.T) {
	name := "t-dead-target-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	p := PoolNamed(name)
	p.Set(hopsAt(honestSocks5(t), honestSocks5(t), honestSocks5(t)), Sequential, 0)
	if _, err := (&Chain{Hops: mustHops(t, "rotate://"+name)}).DialContext(context.Background(), closedAddr(t)); err == nil {
		t.Fatal("dialled a closed port")
	}
	if s := p.Status(); s.Healthy != 3 {
		t.Fatalf("healthy %d of 3: a refused target benched proxies that answered correctly", s.Healthy)
	}
}

func TestTheRightPoolTakesTheBlame(t *testing.T) {
	a := "t-a-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	b := "t-b-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	PoolNamed(a).Set(hopsAt(honestSocks5(t)), Sequential, 0)
	dead := closedAddr(t)
	PoolNamed(b).Set(hopsAt(dead), Sequential, 0)

	(&Chain{Hops: mustHops(t, "rotate://"+a, "rotate://"+b)}).DialContext(context.Background(), echoServer(t))
	if s := PoolNamed(a).Status(); s.Healthy != 1 {
		t.Errorf("pool A healthy %d of 1: blamed for B's dead proxy", s.Healthy)
	}
	if s := PoolNamed(b).Status(); s.Healthy != 0 {
		t.Errorf("pool B healthy %d of 1: its dead proxy was not benched", s.Healthy)
	}
}

func TestGivingUpBenchesNothing(t *testing.T) {
	slow := listen(t, func(c net.Conn) { time.Sleep(3 * time.Second) })
	name := "t-cancel-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	p := PoolNamed(name)
	p.Set(hopsAt(slow), Sequential, 0)
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	(&Chain{Hops: mustHops(t, "rotate://"+name)}).DialContext(ctx, "example.com:80")
	if s := p.Status(); s.Healthy != 1 {
		t.Fatalf("healthy %d: the caller's deadline benched the proxy", s.Healthy)
	}
}
