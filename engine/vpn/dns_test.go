package vpn

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"golang.org/x/net/dns/dnsmessage"

	"drysocks/engine/chain"
	"drysocks/engine/monitor"
)

func listen(t *testing.T, serve func(net.Conn)) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); serve(c) }()
		}
	}()
	return ln.Addr().String()
}

// tcpDNS answers each length-prefixed query with the query reversed, and
// keeps the connection open for more, as a real resolver does.
func tcpDNS(t *testing.T) string {
	return listen(t, func(c net.Conn) {
		for {
			var n [2]byte
			if _, err := io.ReadFull(c, n[:]); err != nil {
				return
			}
			q := make([]byte, binary.BigEndian.Uint16(n[:]))
			io.ReadFull(c, q)
			for i, j := 0, len(q)-1; i < j; i, j = i+1, j-1 {
				q[i], q[j] = q[j], q[i]
			}
			c.Write(append(n[:], q...))
		}
	})
}

// countingProxy is an HTTP CONNECT hop that counts the tunnels it opens.
func countingProxy(t *testing.T, tunnels *atomic.Int32) string {
	return listen(t, func(c net.Conn) {
		req, err := http.ReadRequest(bufio.NewReader(c))
		if err != nil {
			return
		}
		up, err := net.Dial("tcp", req.Host)
		if err != nil {
			return
		}
		defer up.Close()
		tunnels.Add(1)
		io.WriteString(c, "HTTP/1.1 200 OK\r\n\r\n")
		go io.Copy(up, c)
		io.Copy(c, up)
	})
}

func TestDNSGoesThroughTheChainAndReusesTheTunnel(t *testing.T) {
	var tunnels atomic.Int32
	hop, _ := chain.ParseHop("http://" + countingProxy(t, &tunnels))
	client := newDNSClient(&chain.Chain{Hops: []chain.Hop{hop}}, tcpDNS(t), nil)
	defer client.close()

	// The address the app sent to; the answer must claim to come from it.
	asked := &net.UDPAddr{IP: net.IPv4(10, 111, 222, 2), Port: 53}

	for i, q := range []string{"abc", "hello"} {
		pc := newDNSPacketConn(client)
		pc.WriteTo([]byte(q), asked)
		pc.SetReadDeadline(time.Now().Add(3 * time.Second))

		buf := make([]byte, 512)
		n, from, err := pc.ReadFrom(buf)
		pc.Close()
		if err != nil {
			t.Fatalf("query %d: %v", i, err)
		}
		if from.String() != asked.String() {
			t.Errorf("answer from %s, want %s", from, asked)
		}
		want := []byte(q)
		for a, b := 0, len(want)-1; a < b; a, b = a+1, b-1 {
			want[a], want[b] = want[b], want[a]
		}
		if string(buf[:n]) != string(want) {
			t.Errorf("answer %q, want %q", buf[:n], want)
		}
	}

	if got := tunnels.Load(); got != 1 {
		t.Errorf("opened %d tunnels for two sequential queries, want 1", got)
	}
}

func TestDNSReadHonoursTheDeadline(t *testing.T) {
	// A hop that accepts and never answers.
	silent := listen(t, func(c net.Conn) { time.Sleep(3 * time.Second) })
	hop, _ := chain.ParseHop("http://" + silent)
	pc := newDNSPacketConn(newDNSClient(&chain.Chain{Hops: []chain.Hop{hop}}, "1.1.1.1:53", nil))
	defer pc.Close()

	pc.WriteTo([]byte("q"), &net.UDPAddr{})
	pc.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	_, _, err := pc.ReadFrom(make([]byte, 16))

	var ne net.Error
	if !errors.Is(err, os.ErrDeadlineExceeded) || !errors.As(err, &ne) || !ne.Timeout() {
		t.Fatalf("err = %v, want a timeout tun2socks treats as idle", err)
	}
}

func TestTunDNSAddressIsAnsweredHere(t *testing.T) {
	var tunnels atomic.Int32
	hop, _ := chain.ParseHop("http://" + countingProxy(t, &tunnels))
	resolver := tcpDNS(t)
	c := &chain.Chain{Hops: []chain.Hop{hop}}
	p := &chainProxy{chain: c, dns: newDNSClient(c, resolver, nil), mon: monitor.New(nil), tunDNS: netip.MustParseAddr("198.18.0.2")}

	// Private DNS's probe: refused at once, and nothing goes into the chain.
	start := time.Now()
	_, err := p.DialContext(context.Background(), &M.Metadata{Network: M.TCP, DstIP: p.tunDNS, DstPort: 853})
	if !errors.Is(err, errNotHere) || time.Since(start) > 100*time.Millisecond || tunnels.Load() != 0 {
		t.Fatalf("853: err %v after %v, %d tunnels; want an immediate refusal", err, time.Since(start), tunnels.Load())
	}

	// DNS over TCP to port 53 is answered, via the resolver through the chain.
	conn, err := p.DialContext(context.Background(), &M.Metadata{Network: M.TCP, DstIP: p.tunDNS, DstPort: 53})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	conn.Write([]byte{0, 3, 'a', 'b', 'c'})
	resp := make([]byte, 5)
	if _, err := io.ReadFull(conn, resp); err != nil || string(resp[2:]) != "cba" {
		t.Fatalf("got %q, %v; want the resolver's answer", resp, err)
	}
	if tunnels.Load() != 1 {
		t.Errorf("%d tunnels, want 1", tunnels.Load())
	}
}

func TestDNSOverHTTPSThroughTheChain(t *testing.T) {
	// A DoH server that answers each query with itself reversed.
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.Header.Get("Content-Type") != "application/dns-message" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		q, _ := io.ReadAll(r.Body)
		for i, j := 0, len(q)-1; i < j; i, j = i+1, j-1 {
			q[i], q[j] = q[j], q[i]
		}
		w.Header().Set("Content-Type", "application/dns-message")
		w.Write(q)
	}))
	defer srv.Close()

	var tunnels atomic.Int32
	hop, _ := chain.ParseHop("http://" + countingProxy(t, &tunnels))
	d := newDNSClient(&chain.Chain{Hops: []chain.Hop{hop}}, srv.URL+"/dns-query", nil)
	// Trust the test server's certificate, as the system roots trust Cloudflare's.
	d.doh.Transport.(*http.Transport).TLSClientConfig = srv.Client().Transport.(*http.Transport).TLSClientConfig
	defer d.close()

	for _, q := range []string{"abc", "hello"} {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		resp, err := d.exchange(ctx, []byte(q))
		cancel()
		if err != nil {
			t.Fatal(err)
		}
		if len(resp) != len(q) || resp[0] != q[len(q)-1] {
			t.Errorf("answer %q for %q", resp, q)
		}
	}
	if n := tunnels.Load(); n != 1 {
		t.Errorf("%d tunnels for two lookups, want 1: DoH should keep its connection", n)
	}
}

func dnsQuery(t *testing.T, id uint16, name string) []byte {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: id, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return q
}

func dnsAnswer(t *testing.T, q []byte, ttl uint32) []byte {
	var p dnsmessage.Parser
	h, _ := p.Start(q)
	qq, _ := p.Question()
	h.Response = true
	b := dnsmessage.NewBuilder(nil, h)
	b.StartQuestions()
	b.Question(qq)
	b.StartAnswers()
	b.AResource(dnsmessage.ResourceHeader{Name: qq.Name, Class: dnsmessage.ClassINET, TTL: ttl}, dnsmessage.AResource{A: [4]byte{192, 0, 2, 1}})
	r, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestCacheAnswersWithTheQuerysIDAndServesStaleWhenUpstreamFails(t *testing.T) {
	c := newDNSCache()
	q1 := dnsQuery(t, 7, "Example.COM.")
	c.put(q1, dnsAnswer(t, q1, 300))

	// Same name, any case, new ID: answered from the cache, carrying the new ID.
	q2 := dnsQuery(t, 99, "example.com.")
	got := c.get(q2, false)
	if got == nil || got[0] != 0 || got[1] != 99 {
		t.Fatalf("cached answer %v, want one with ID 99", got)
	}

	// Expired: not fresh, but good enough when the chain cannot answer.
	c.mu.Lock()
	for k, e := range c.m {
		e.expires = time.Now().Add(-time.Minute)
		c.m[k] = e
	}
	c.mu.Unlock()
	if c.get(q2, false) != nil {
		t.Error("an expired answer was served as fresh")
	}
	if c.get(q2, true) == nil {
		t.Error("an expired answer was not available as stale")
	}
}

func TestCachedExchangeFallsBackToStale(t *testing.T) {
	var calls atomic.Int32
	hop, _ := chain.ParseHop("http://" + countingProxy(t, &calls))
	d := newDNSClient(&chain.Chain{Hops: []chain.Hop{hop}}, tcpDNS(t), nil)
	q := dnsQuery(t, 1, "stale.example.")
	d.cache.put(q, dnsAnswer(t, q, 60))
	d.cache.mu.Lock()
	for k, e := range d.cache.m {
		e.expires = time.Now().Add(-time.Minute)
		d.cache.m[k] = e
	}
	d.cache.mu.Unlock()
	// Upstream "answers" by reversing the query, which does not parse as an answer
	// for the cache but does come back: a live upstream wins over stale.
	d.server = "127.0.0.1:1" // nothing listens: upstream fails
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	resp, err := d.cachedExchange(ctx, q)
	if err != nil || resp == nil {
		t.Fatalf("got %v, %v; want the stale answer", resp, err)
	}
}
