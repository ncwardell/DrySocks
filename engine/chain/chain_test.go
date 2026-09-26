package chain

import (
	"bufio"
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

// Test servers. Each is the smallest thing that speaks its protocol honestly
// enough to catch a client that gets the bytes wrong.

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

func echoServer(t *testing.T) string {
	return listen(t, func(c net.Conn) { io.Copy(c, c) })
}

func splice(c net.Conn, target string) {
	up, err := net.Dial("tcp", target)
	if err != nil {
		return
	}
	defer up.Close()
	go io.Copy(up, c)
	io.Copy(c, up)
}

func socks5Server(t *testing.T, user, pass string) string {
	return listen(t, func(c net.Conn) {
		var hdr [2]byte
		io.ReadFull(c, hdr[:])
		methods := make([]byte, hdr[1])
		io.ReadFull(c, methods)
		if user != "" {
			c.Write([]byte{5, 2})
			var v [2]byte
			io.ReadFull(c, v[:])
			u := make([]byte, v[1])
			io.ReadFull(c, u)
			io.ReadFull(c, v[:1])
			p := make([]byte, v[0])
			io.ReadFull(c, p)
			if string(u) != user || string(p) != pass {
				c.Write([]byte{1, 1})
				return
			}
			c.Write([]byte{1, 0})
		} else {
			c.Write([]byte{5, 0})
		}

		var req [4]byte
		io.ReadFull(c, req[:])
		var host string
		switch req[3] {
		case 1:
			b := make([]byte, 4)
			io.ReadFull(c, b)
			host = net.IP(b).String()
		case 4:
			b := make([]byte, 16)
			io.ReadFull(c, b)
			host = net.IP(b).String()
		case 3:
			var n [1]byte
			io.ReadFull(c, n[:])
			b := make([]byte, n[0])
			io.ReadFull(c, b)
			host = string(b)
		}
		var port [2]byte
		io.ReadFull(c, port[:])
		target := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port[:]))))

		// Reply with a domain-typed bound address so the client has to parse
		// a variable-length reply, not assume ten bytes.
		c.Write([]byte{5, 0, 0, 3, 4, 'b', 'n', 'd', 'x', 0, 0})
		splice(c, target)
	})
}

func socks4aServer(t *testing.T) string {
	return listen(t, func(c net.Conn) {
		br := bufio.NewReader(c)
		var req [8]byte
		io.ReadFull(br, req[:])
		port := binary.BigEndian.Uint16(req[2:4])
		br.ReadString(0) // user id
		host := net.IP(req[4:8]).String()
		if req[4] == 0 && req[5] == 0 && req[6] == 0 && req[7] != 0 {
			name, _ := br.ReadString(0)
			host = strings.TrimSuffix(name, "\x00")
		}
		c.Write([]byte{0, 0x5a, 0, 0, 0, 0, 0, 0})
		splice(c, net.JoinHostPort(host, strconv.Itoa(int(port))))
	})
}

func httpServer(t *testing.T, wantAuth string) string {
	return listen(t, func(c net.Conn) {
		req, err := http.ReadRequest(bufio.NewReader(c))
		if err != nil || req.Method != http.MethodConnect {
			return
		}
		if wantAuth != "" && req.Header.Get("Proxy-Authorization") != wantAuth {
			io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
			return
		}
		io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
		splice(c, req.Host)
	})
}

// localhostAddr swaps 127.0.0.1 for a name, so the hop has to resolve it.
func localhostAddr(addr string) string {
	return strings.Replace(addr, "127.0.0.1", "localhost", 1)
}

func roundTrip(t *testing.T, c net.Conn) {
	t.Helper()
	msg := []byte("through the chain")
	if _, err := c.Write(msg); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(msg))
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != string(msg) {
		t.Fatalf("echo = %q, want %q", got, msg)
	}
}

func mustHops(t *testing.T, raws ...string) []Hop {
	t.Helper()
	hops, err := ParseHops(raws)
	if err != nil {
		t.Fatal(err)
	}
	return hops
}

func TestEachKindAlone(t *testing.T) {
	echo := echoServer(t)
	for name, hop := range map[string]string{
		"socks5":      "socks5://" + socks5Server(t, "", ""),
		"socks5 auth": "socks5://al:pw@" + socks5Server(t, "al", "pw"),
		"socks4a":     "socks4a://" + socks4aServer(t),
		"http":        "http://" + httpServer(t, ""),
		"http auth":   "http://al:pw@" + httpServer(t, "Basic YWw6cHc="),
	} {
		t.Run(name, func(t *testing.T) {
			c := &Chain{Hops: mustHops(t, hop)}
			conn, err := c.DialContext(context.Background(), echo)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			roundTrip(t, conn)
		})
	}
}

func TestThreeHopsMixed(t *testing.T) {
	echo := echoServer(t)
	c := &Chain{Hops: mustHops(t,
		"socks5://al:pw@"+socks5Server(t, "al", "pw"),
		"http://"+localhostAddr(httpServer(t, "")),
		"socks4a://"+localhostAddr(socks4aServer(t)),
	)}

	conn, steps, err := c.Probe(context.Background(), localhostAddr(echo))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	roundTrip(t, conn)

	// One TCP connect plus one handshake per hop.
	if len(steps) != 4 {
		t.Fatalf("got %d steps, want 4: %+v", len(steps), steps)
	}
	if steps[3].To != localhostAddr(echo) {
		t.Errorf("last step went to %s, want the target", steps[3].To)
	}
}

func TestBadPasswordNamesTheHop(t *testing.T) {
	good := socks5Server(t, "", "")
	bad := socks5Server(t, "al", "pw")
	c := &Chain{Hops: mustHops(t, "socks5://"+good, "socks5://al:wrong@"+bad)}

	_, steps, err := c.Probe(context.Background(), echoServer(t))
	if err == nil || !strings.Contains(err.Error(), bad) {
		t.Fatalf("err = %v, want it to name %s", err, bad)
	}
	// The connect and the first hop worked; the second did not.
	if len(steps) != 2 {
		t.Fatalf("got %d steps, want 2", len(steps))
	}
}

func TestHTTPKeepsBytesSentWithThe200(t *testing.T) {
	proxy := listen(t, func(c net.Conn) {
		http.ReadRequest(bufio.NewReader(c))
		io.WriteString(c, "HTTP/1.1 200 OK\r\n\r\nearly")
	})
	c := &Chain{Hops: mustHops(t, "http://"+proxy)}
	conn, err := c.DialContext(context.Background(), "example.com:80")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	got := make([]byte, 5)
	if _, err := io.ReadFull(conn, got); err != nil || string(got) != "early" {
		t.Fatalf("read %q, %v; want \"early\"", got, err)
	}
}

func TestContextCancelsAStalledHandshake(t *testing.T) {
	// Accepts, then says nothing.
	silent := listen(t, func(c net.Conn) { time.Sleep(5 * time.Second) })
	c := &Chain{Hops: mustHops(t, "socks5://"+silent)}

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	start := time.Now()
	if _, err := c.DialContext(ctx, "example.com:80"); err == nil {
		t.Fatal("dial succeeded against a silent server")
	}
	if took := time.Since(start); took > 2*time.Second {
		t.Fatalf("cancel took %v", took)
	}
}

func TestParseHop(t *testing.T) {
	for raw, want := range map[string]Hop{
		"socks5://h:1080":        {Kind: SOCKS5, Addr: "h:1080"},
		"socks5h://u:p@h:1080":   {Kind: SOCKS5, Addr: "h:1080", User: "u", Pass: "p"},
		"socks4://id@10.0.0.1:1": {Kind: SOCKS4, Addr: "10.0.0.1:1", User: "id"},
		"http://[::1]:8080":      {Kind: HTTP, Addr: "[::1]:8080"},
	} {
		got, err := ParseHop(raw)
		if err != nil || got != want {
			t.Errorf("ParseHop(%q) = %+v, %v; want %+v", raw, got, err, want)
		}
	}
	for _, raw := range []string{
		"socks5://h",          // no port
		"socks5://h:0",        // port out of range
		"https://h:443",       // TLS to the proxy is not a hop kind yet
		"socks4://u:p@h:1080", // socks4 has no password
	} {
		if _, err := ParseHop(raw); err == nil {
			t.Errorf("ParseHop(%q) succeeded, want an error", raw)
		}
	}
}

func TestServerChainsForTor(t *testing.T) {
	// What Tor sees: a plain SOCKS5 server on loopback. Behind it, the hops
	// that come before Tor in the user's chain.
	echo := echoServer(t)
	s := &Server{Chain: &Chain{Hops: mustHops(t,
		"http://"+httpServer(t, ""),
		"socks5://al:pw@"+socks5Server(t, "al", "pw"),
	)}}
	port, err := s.Listen()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	client := &Chain{Hops: mustHops(t, "socks5://127.0.0.1:"+strconv.Itoa(port))}
	conn, err := client.DialContext(context.Background(), echo)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	roundTrip(t, conn)
}

func TestServerRefusesWhenTheChainIsDown(t *testing.T) {
	dead := listen(t, func(c net.Conn) {}) // accepts, then hangs up
	s := &Server{Chain: &Chain{Hops: mustHops(t, "socks5://"+dead)}, DialTimeout: time.Second}
	port, err := s.Listen()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	client := &Chain{Hops: mustHops(t, "socks5://127.0.0.1:"+strconv.Itoa(port))}
	if _, err := client.DialContext(context.Background(), "example.com:80"); err == nil ||
		!strings.Contains(err.Error(), "refused") {
		t.Fatalf("err = %v, want a refusal", err)
	}
}
