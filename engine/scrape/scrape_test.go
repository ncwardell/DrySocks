package scrape

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
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
	CheckAll(context.Background(), cands, nil, target(srv), CheckOpts{Workers: 4, Want: 3, PerCheck: 3 * time.Second},
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

func TestCheckAllFiltersAndReportsEverything(t *testing.T) {
	srv := traceServer(t) // every proxy exits "in" NL
	honest := socks5To(t, func(c net.Conn) { splice(c, srv.Listener.Addr().String()) })
	dead := socks5To(t, func(c net.Conn) {})
	cands := []Candidate{{URL: "socks5://" + honest}, {URL: "socks5://" + dead}}

	var mu sync.Mutex
	var got []Result
	collect := func(r Result) { mu.Lock(); got = append(got, r); mu.Unlock() }

	// Wanting only DE: the NL proxy is rejected, and with ReportAll both come back.
	CheckAll(context.Background(), cands, nil, target(srv), CheckOpts{
		Workers: 2, PerCheck: 3 * time.Second, ReportAll: true,
		Accept: func(r Result) bool { return r.Country == "DE" },
	}, collect, func(Progress) {})
	if len(got) != 2 {
		t.Fatalf("got %d results, want both reported", len(got))
	}
	for _, r := range got {
		if r.OK {
			t.Errorf("%s passed a filter it should fail", r.URL)
		}
		if r.URL == "socks5://"+honest && r.Error != "filtered out" {
			t.Errorf("honest proxy error %q, want filtered out", r.Error)
		}
	}

	// Without ReportAll, only accepted proxies come back: here, none.
	got = nil
	CheckAll(context.Background(), cands, nil, target(srv), CheckOpts{Workers: 2, PerCheck: 3 * time.Second,
		Accept: func(r Result) bool { return r.Country == "DE" }}, collect, func(Progress) {})
	if len(got) != 0 {
		t.Errorf("got %+v, want nothing", got)
	}
}

// socks5Dialer is a SOCKS5 proxy that really connects where it is asked, but
// sends each port to the local server standing in for it.
func socks5Dialer(t *testing.T, portTo map[string]string, addHeader bool) string {
	return func() string {
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
					var port uint16
					switch b[3] {
					case 1:
						io.ReadFull(c, b[:6])
						port = uint16(b[4])<<8 | uint16(b[5])
					case 3:
						io.ReadFull(c, b[:1])
						n := int(b[0])
						io.ReadFull(c, b[:n+2])
						port = uint16(b[n])<<8 | uint16(b[n+1])
					}
					to, ok := portTo[fmt.Sprint(port)]
					if !ok {
						c.Write([]byte{5, 2, 0, 1, 0, 0, 0, 0, 0, 0}) // not allowed
						return
					}
					c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
					if addHeader && port == 80 {
						// A tampering proxy: rewrites the request on its way.
						br := bufio.NewReader(c)
						req, err := http.ReadRequest(br)
						if err != nil {
							return
						}
						req.Header.Set("Via", "1.1 snoop")
						up, err := net.Dial("tcp", to)
						if err != nil {
							return
						}
						defer up.Close()
						req.Write(up)
						io.Copy(c, up)
						return
					}
					splice(c, to)
				}()
			}
		}()
		return ln.Addr().String()
	}()
}

func TestInspectMeasuresWhatAListOnlyClaims(t *testing.T) {
	trace := traceServer(t)

	echo := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := map[string]string{}
		for k := range r.Header {
			h[k] = r.Header.Get(k)
		}
		json.NewEncoder(w).Encode(map[string]any{"headers": h})
	}))
	t.Cleanup(echo.Close)
	quiz := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "Port test successful!") }))
	t.Cleanup(quiz.Close)
	ipapi := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"status":"success","countryCode":"NL","isp":"Example Hosting","as":"AS64500 Example","hosting":true,"mobile":false,"proxy":true}`)
	}))
	t.Cleanup(ipapi.Close)
	relay := httptest.NewUnstartedServer(http.NotFoundHandler())
	relay.TLS = &tls.Config{Certificates: []tls.Certificate{selfSigned(t, "relay.invalid")}}
	relay.StartTLS()
	t.Cleanup(relay.Close)
	big := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(make([]byte, 300<<10)) }))
	t.Cleanup(big.Close)

	targets := Targets{
		Trace:         func() Target { tg := target(trace); tg.Addr = "trace.invalid:443"; return tg }(),
		Plain80:       "echo.invalid:80",
		HeaderURLPath: "/headers",
		AnyPort:       "quiz.invalid:8080",
		IPInfo:        "ipapi.invalid:81",
		TorRelays:     []string{"relay.invalid:9001"},
		Speed:         Target{Host: "example.com", Addr: "speed.invalid:444", Roots: srvRoots(big)},
		SpeedPath:     "/",
	}
	everywhere := map[string]string{
		"443": trace.Listener.Addr().String(), "80": echo.Listener.Addr().String(),
		"8080": quiz.Listener.Addr().String(), "81": ipapi.Listener.Addr().String(),
		"9001": relay.Listener.Addr().String(), "444": big.Listener.Addr().String(),
	}

	t.Run("an open, honest proxy", func(t *testing.T) {
		p := socks5Dialer(t, everywhere, false)
		r := Inspect(context.Background(), "socks5://"+p, nil, targets)
		if !r.Web443 || !r.Web80 || !r.AnyPort || !r.Tor || r.Tampers || r.KBps == 0 {
			t.Errorf("report %+v", r)
		}
		if len(r.Protocols) != 1 || r.Protocols[0] != "socks5" || r.NeedsAuth {
			t.Errorf("protocols %v auth %v", r.Protocols, r.NeedsAuth)
		}
		if r.Country != "NL" || !r.Hosting || !r.Flagged || r.ISP != "Example Hosting" {
			t.Errorf("network %+v", r)
		}
	})

	t.Run("web ports only, and tampering", func(t *testing.T) {
		webOnly := map[string]string{"443": everywhere["443"], "80": everywhere["80"], "81": everywhere["81"], "444": everywhere["444"]}
		p := socks5Dialer(t, webOnly, true)
		r := Inspect(context.Background(), "socks5://"+p, nil, targets)
		if !r.Web443 || !r.Web80 || r.AnyPort || r.Tor {
			t.Errorf("ports: %+v", r)
		}
		if !r.Tampers || len(r.AddedHeaders) != 1 || r.AddedHeaders[0] != "Via" {
			t.Errorf("tampering: %v %v", r.Tampers, r.AddedHeaders)
		}
		if r.Errors["anyport"] == "" || r.Errors["tor"] == "" {
			t.Errorf("errors should say why: %v", r.Errors)
		}
	})
}

func srvRoots(s *httptest.Server) *x509.CertPool {
	return s.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs
}
