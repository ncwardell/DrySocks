package scrape

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestParse(t *testing.T) {
	list := `# free proxies, updated hourly
1.2.3.4:1080
socks4://5.6.7.8:4145
http://9.9.9.9:8080 US elite
https://4.4.4.4:443
socks5h://8.8.4.4:9050
10.0.0.1:1080
not a proxy
300.1.1.1:80
1.2.3.4:99999
`
	got := Parse(strings.NewReader(list), Source{URL: "u", Kind: "socks5"})
	want := []string{"socks5://1.2.3.4:1080", "socks4a://5.6.7.8:4145", "http://9.9.9.9:8080", "socks5://8.8.4.4:9050"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i].URL != want[i] {
			t.Errorf("[%d] = %s, want %s", i, got[i].URL, want[i])
		}
	}
}

func TestFetchDedupesAcrossSources(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/a":
			io.WriteString(w, "1.1.1.1:1080\n2.2.2.2:1080\n")
		case "/b":
			io.WriteString(w, "socks5://2.2.2.2:1080\n3.3.3.3:1080\n")
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	res := Fetch(context.Background(), []Source{
		{URL: srv.URL + "/a", Kind: "socks5"},
		{URL: srv.URL + "/b", Kind: "socks5"},
		{URL: srv.URL + "/gone", Kind: "socks5"},
	}, nil)
	if len(res.Candidates) != 3 {
		t.Errorf("got %d candidates, want 3: %+v", len(res.Candidates), res.Candidates)
	}
	if _, ok := res.Errors[srv.URL+"/gone"]; !ok {
		t.Errorf("a 404 source should be reported, got %v", res.Errors)
	}
}

// traceServer is a stand-in for Cloudflare's trace page.
func traceServer(t *testing.T) *httptest.Server {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "h=%s\nip=203.0.113.7\nloc=NL\n", r.Host)
	}))
	t.Cleanup(srv.Close)
	return srv
}

// socks5To is a SOCKS5 proxy that sends every CONNECT to handle.
func socks5To(t *testing.T, handle func(client net.Conn)) string {
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
			go func() {
				defer c.Close()
				var b [262]byte
				io.ReadFull(c, b[:2])
				io.ReadFull(c, b[:b[1]])
				c.Write([]byte{5, 0})
				io.ReadFull(c, b[:4])
				switch b[3] {
				case 1:
					io.ReadFull(c, b[:4+2])
				case 3:
					io.ReadFull(c, b[:1])
					io.ReadFull(c, b[:int(b[0])+2])
				}
				c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
				handle(c)
			}()
		}
	}()
	return ln.Addr().String()
}

func splice(a net.Conn, addr string) {
	b, err := net.Dial("tcp", addr)
	if err != nil {
		return
	}
	defer b.Close()
	go io.Copy(b, a)
	io.Copy(a, b)
}

func target(srv *httptest.Server) Target {
	roots := srv.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs
	return Target{Host: "example.com", Path: "/cdn-cgi/trace", Addr: srv.Listener.Addr().String(), Roots: roots}
}

func TestCheckPassesAnHonestProxy(t *testing.T) {
	srv := traceServer(t)
	honest := socks5To(t, func(c net.Conn) { splice(c, srv.Listener.Addr().String()) })

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	r := Check(ctx, "socks5://"+honest, nil, target(srv))
	if !r.OK || r.ExitIP != "203.0.113.7" || r.Country != "NL" {
		t.Fatalf("got %+v", r)
	}
}

func TestCheckCatchesTLSInterception(t *testing.T) {
	srv := traceServer(t)
	// Terminates TLS itself with its own certificate, as a snooping proxy does.
	// It must be its own: every httptest server shares one built-in certificate.
	mitm := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "ip=6.6.6.6\n")
	}))
	mitm.TLS = &tls.Config{Certificates: []tls.Certificate{selfSigned(t, "example.com")}}
	mitm.StartTLS()
	t.Cleanup(mitm.Close)
	snoop := socks5To(t, func(c net.Conn) { splice(c, mitm.Listener.Addr().String()) })

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	r := Check(ctx, "socks5://"+snoop, nil, target(srv))
	if r.OK || !strings.Contains(r.Error, "intercepts TLS") {
		t.Fatalf("got %+v, want it caught intercepting", r)
	}
}

func TestCheckAllStopsAtWant(t *testing.T) {
	srv := traceServer(t)
	honest := socks5To(t, func(c net.Conn) { splice(c, srv.Listener.Addr().String()) })
	dead := socks5To(t, func(c net.Conn) {})

	var cands []Candidate
	for i := 0; i < 20; i++ {
		cands = append(cands, Candidate{URL: "socks5://" + dead}, Candidate{URL: "socks5://" + honest})
	}

	var mu sync.Mutex
	var found []Result
	var last Progress
	CheckAll(context.Background(), cands, nil, target(srv), 4, 3, 3*time.Second,
		func(r Result) { mu.Lock(); found = append(found, r); mu.Unlock() },
		func(p Progress) { last = p })

	if len(found) < 3 || len(found) > 3+4 {
		t.Errorf("found %d, want 3 (a few more may be in flight)", len(found))
	}
	if last.Tested >= len(cands) {
		t.Errorf("tested all %d; should have stopped early", last.Tested)
	}
}

func selfSigned(t *testing.T, name string) tls.Certificate {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: name},
		DNSNames:     []string{name},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}
