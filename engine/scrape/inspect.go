package scrape

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"

	"sockschain/engine/chain"
)

// Report is what Inspect learns about one proxy: the things a free-proxy list
// claims, measured rather than taken on trust.
type Report struct {
	URL string `json:"url"`

	// Protocols the port answers to, whatever the list said it was.
	Protocols []string `json:"protocols,omitempty"`
	// NeedsAuth: its SOCKS5 greeting asked for a password.
	NeedsAuth bool `json:"needsAuth,omitempty"`

	// Ports it will open tunnels to. Web443/Web80 are what browsing needs;
	// AnyPort (a non-web port) is what testing other proxies, or Tor, needs.
	Web443  bool `json:"web443"`
	Web80   bool `json:"web80"`
	AnyPort bool `json:"anyPort"`
	// Tor: a TLS handshake with a real Tor relay's ORPort got through.
	Tor bool `json:"tor"`

	// AddedHeaders the proxy put into a plain-HTTP request passing through;
	// empty means it left it alone. Checked only if port 80 was reachable.
	AddedHeaders []string `json:"addedHeaders,omitempty"`
	Tampers      bool     `json:"tampers,omitempty"`

	// Where it exits, and what kind of network that is.
	ExitIP  string `json:"exitIp,omitempty"`
	Country string `json:"country,omitempty"`
	ISP     string `json:"isp,omitempty"`
	ASN     string `json:"asn,omitempty"`
	Hosting bool   `json:"hosting,omitempty"` // a datacenter, not a home or mobile line
	Mobile  bool   `json:"mobile,omitempty"`
	Flagged bool   `json:"flagged,omitempty"` // ip-api already lists the exit as a proxy or VPN

	// KBps is measured download throughput through the whole route.
	KBps int64 `json:"kbps,omitempty"`

	Errors map[string]string `json:"errors,omitempty"` // test → why it failed, for the curious
}

// Targets are the outside services Inspect leans on, overridable for tests.
type Targets struct {
	Trace         Target // TLS page returning ip= and loc= (Cloudflare)
	Plain80       string // host:port of a plain-HTTP server that echoes request headers as JSON
	HeaderURLPath string
	AnyPort       string   // host:port on a non-web port that answers "Port test successful"
	IPInfo        string   // host:port of ip-api
	TorRelays     []string // ORPort addresses to try a handshake with
	Speed         Target   // TLS page to download from
	SpeedPath     string
}

var DefaultTargets = Targets{
	Trace:         DefaultTarget,
	Plain80:       "httpbin.org:80",
	HeaderURLPath: "/headers",
	AnyPort:       "portquiz.net:8080",
	IPInfo:        "ip-api.com:80",
	Speed:         Target{Host: "speed.cloudflare.com"},
	SpeedPath:     "/__down?bytes=500000",
}

// Inspect runs every test on proxyURL, reached through via. Tests run in
// parallel and each has its own deadline, so one hang costs one answer.
func Inspect(ctx context.Context, proxyURL string, via []chain.Hop, t Targets) Report {
	r := Report{URL: proxyURL, Errors: map[string]string{}}
	hop, err := chain.ParseHop(proxyURL)
	if err != nil {
		r.Errors["parse"] = err.Error()
		return r
	}
	c := &chain.Chain{Hops: append(append([]chain.Hop(nil), via...), hop)}
	var mu sync.Mutex
	fail := func(test string, err error) {
		mu.Lock()
		r.Errors[test] = err.Error()
		mu.Unlock()
	}
	run := func(wg *sync.WaitGroup, d time.Duration, f func(context.Context)) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			cctx, cancel := context.WithTimeout(ctx, d)
			defer cancel()
			f(cctx)
		}()
	}

	var wg sync.WaitGroup
	// A port that does not speak a protocol usually just goes quiet, so these
	// probes get less time than the rest.
	run(&wg, 6*time.Second, func(ctx context.Context) {
		protos, auth := probeProtocols(ctx, via, hop.Addr)
		mu.Lock()
		r.Protocols, r.NeedsAuth = protos, auth
		mu.Unlock()
	})
	run(&wg, 12*time.Second, func(ctx context.Context) {
		res := Check(ctx, proxyURL, via, t.Trace)
		mu.Lock()
		r.Web443 = res.OK
		r.ExitIP, r.Country = res.ExitIP, res.Country
		mu.Unlock()
		if !res.OK {
			fail("443", fmt.Errorf("%s", res.Error))
		}
	})
	run(&wg, 12*time.Second, func(ctx context.Context) {
		added, err := headerEcho(ctx, c, t.Plain80, t.HeaderURLPath)
		mu.Lock()
		if err == nil {
			r.Web80 = true
			r.AddedHeaders = added
			r.Tampers = len(added) > 0
		}
		mu.Unlock()
		if err != nil {
			fail("80", err)
		}
	})
	run(&wg, 12*time.Second, func(ctx context.Context) {
		err := portQuiz(ctx, c, t.AnyPort)
		mu.Lock()
		r.AnyPort = err == nil
		mu.Unlock()
		if err != nil {
			fail("anyport", err)
		}
	})
	run(&wg, 15*time.Second, func(ctx context.Context) {
		err := torHandshake(ctx, c, t.TorRelays)
		mu.Lock()
		r.Tor = err == nil
		mu.Unlock()
		if err != nil {
			fail("tor", err)
		}
	})
	run(&wg, 12*time.Second, func(ctx context.Context) {
		info, err := ipInfo(ctx, c, t.IPInfo)
		if err != nil {
			fail("ipinfo", err)
			return
		}
		mu.Lock()
		r.ISP, r.ASN = info.ISP, info.AS
		r.Hosting, r.Mobile, r.Flagged = info.Hosting, info.Mobile, info.Proxy
		if r.Country == "" {
			r.Country = info.CountryCode
		}
		mu.Unlock()
	})
	run(&wg, 25*time.Second, func(ctx context.Context) {
		kbps, err := speed(ctx, c, t.Speed, t.SpeedPath)
		mu.Lock()
		r.KBps = kbps
		mu.Unlock()
		if err != nil {
			fail("speed", err)
		}
	})
	wg.Wait()
	// A protocol that carried a working tunnel is spoken, whatever the probe
	// saw: some ports ignore a bare greeting yet serve CONNECT fine.
	if (r.Web443 || r.Web80 || r.AnyPort || r.Tor) && !slices.Contains(r.Protocols, hop.Kind.String()) {
		r.Protocols = append(r.Protocols, hop.Kind.String())
	}
	if len(r.Errors) == 0 {
		r.Errors = nil
	}
	return r
}

// probeProtocols opens a connection per protocol to addr (through via) and
// sees which handshakes it answers. It asks nothing of the far side beyond
// the greeting, so a proxy is not made to connect anywhere.
func probeProtocols(ctx context.Context, via []chain.Hop, addr string) (protos []string, needsAuth bool) {
	dial := func() (net.Conn, error) {
		if len(via) == 0 {
			var d net.Dialer
			return d.DialContext(ctx, "tcp", addr)
		}
		return (&chain.Chain{Hops: via}).DialContext(ctx, addr)
	}
	type probe struct {
		name string
		try  func(net.Conn) (bool, bool)
	}
	probes := []probe{
		{"socks5", func(c net.Conn) (bool, bool) {
			c.Write([]byte{5, 2, 0, 2})
			var b [2]byte
			if _, err := io.ReadFull(c, b[:]); err != nil || b[0] != 5 {
				return false, false
			}
			return b[1] == 0 || b[1] == 2, b[1] == 2
		}},
		{"socks4a", func(c net.Conn) (bool, bool) {
			// CONNECT to 0.0.0.1 (a 4a name follows) for "invalid.": a real
			// SOCKS4 server answers with an 8-byte reply, granted or not.
			req := []byte{4, 1, 0, 80, 0, 0, 0, 1, 0}
			req = append(append(req, "invalid."...), 0)
			c.Write(req)
			var b [8]byte
			if _, err := io.ReadFull(c, b[:]); err != nil {
				return false, false
			}
			return b[0] == 0 && b[1] >= 0x5a && b[1] <= 0x5d, false
		}},
		{"http", func(c net.Conn) (bool, bool) {
			// Any HTTP proxy answers a bare GET at once, usually with an error
			// page; a CONNECT would have it resolve and dial first.
			io.WriteString(c, "GET / HTTP/1.0\r\n\r\n")
			resp, err := http.ReadResponse(bufio.NewReader(c), nil)
			if err != nil {
				return false, false
			}
			resp.Body.Close()
			return true, resp.StatusCode == http.StatusProxyAuthRequired
		}},
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	for _, p := range probes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c, err := dial()
			if err != nil {
				return
			}
			defer c.Close()
			if d, ok := ctx.Deadline(); ok {
				c.SetDeadline(d)
			}
			ok, auth := p.try(c)
			if ok {
				mu.Lock()
				protos = append(protos, p.name)
				needsAuth = needsAuth || auth
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	order := map[string]int{"socks5": 0, "socks4a": 1, "http": 2}
	for i := range protos {
		for j := i + 1; j < len(protos); j++ {
			if order[protos[j]] < order[protos[i]] {
				protos[i], protos[j] = protos[j], protos[i]
			}
		}
	}
	return protos, needsAuth
}

// headerEcho sends a plain-HTTP request with a known set of headers and
// returns the ones that came back that were not sent: what the proxy added.
func headerEcho(ctx context.Context, c *chain.Chain, addr, path string) ([]string, error) {
	conn, err := c.DialContext(ctx, addr)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if d, ok := ctx.Deadline(); ok {
		conn.SetDeadline(d)
	}
	host, _, _ := net.SplitHostPort(addr)
	req, _ := http.NewRequest(http.MethodGet, "http://"+host+path, nil)
	req.Header.Set("User-Agent", "sockschain-inspect")
	req.Header.Set("Accept", "application/json")
	req.Close = true
	if err := req.Write(conn); err != nil {
		return nil, err
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var echo struct{ Headers map[string]string }
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&echo); err != nil {
		return nil, fmt.Errorf("echo page altered or unreadable: %w", err)
	}
	sent := map[string]bool{"host": true, "user-agent": true, "accept": true, "connection": true}
	var added []string
	for k := range echo.Headers {
		lk := strings.ToLower(k)
		// The echo service's own infrastructure adds a trace id; that is not the proxy.
		if sent[lk] || strings.HasPrefix(lk, "x-amzn-") {
			continue
		}
		added = append(added, k)
	}
	return added, nil
}

func portQuiz(ctx context.Context, c *chain.Chain, addr string) error {
	conn, err := c.DialContext(ctx, addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	if d, ok := ctx.Deadline(); ok {
		conn.SetDeadline(d)
	}
	host, _, _ := net.SplitHostPort(addr)
	fmt.Fprintf(conn, "GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", host)
	body, _ := io.ReadAll(io.LimitReader(conn, 4096))
	if !strings.Contains(string(body), "successful") {
		return fmt.Errorf("tunnel opened but no answer from %s", addr)
	}
	return nil
}

// torHandshake tries relays until one completes a TLS handshake: Tor's ORPort
// speaks TLS first, so a finished handshake means Tor itself would get in.
func torHandshake(ctx context.Context, c *chain.Chain, relays []string) error {
	if len(relays) == 0 {
		return fmt.Errorf("no Tor relays to try")
	}
	var last error
	for _, addr := range relays[:min(3, len(relays))] {
		conn, err := c.DialContext(ctx, addr)
		if err != nil {
			last = err
			continue
		}
		if d, ok := ctx.Deadline(); ok {
			conn.SetDeadline(d)
		}
		// Relays present self-signed certificates; the handshake is the test.
		tc := tls.Client(conn, &tls.Config{InsecureSkipVerify: true, ServerName: "www.example.com"})
		err = tc.HandshakeContext(ctx)
		conn.Close()
		if err == nil {
			return nil
		}
		last = err
	}
	return last
}

type ipAPI struct {
	CountryCode string `json:"countryCode"`
	ISP         string `json:"isp"`
	AS          string `json:"as"`
	Hosting     bool   `json:"hosting"`
	Mobile      bool   `json:"mobile"`
	Proxy       bool   `json:"proxy"`
}

func ipInfo(ctx context.Context, c *chain.Chain, addr string) (ipAPI, error) {
	var info ipAPI
	conn, err := c.DialContext(ctx, addr)
	if err != nil {
		return info, err
	}
	defer conn.Close()
	if d, ok := ctx.Deadline(); ok {
		conn.SetDeadline(d)
	}
	host, _, _ := net.SplitHostPort(addr)
	req, _ := http.NewRequest(http.MethodGet, "http://"+host+"/json/?fields=status,countryCode,isp,as,hosting,mobile,proxy", nil)
	req.Close = true
	if err := req.Write(conn); err != nil {
		return info, err
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), req)
	if err != nil {
		return info, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return info, fmt.Errorf("ip-api: %s", resp.Status)
	}
	return info, json.NewDecoder(io.LimitReader(resp.Body, 8<<10)).Decode(&info)
}

func speed(ctx context.Context, c *chain.Chain, t Target, path string) (int64, error) {
	addr := t.Addr
	if addr == "" {
		addr = t.Host + ":443"
	}
	conn, err := c.DialContext(ctx, addr)
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	if d, ok := ctx.Deadline(); ok {
		conn.SetDeadline(d)
	}
	tc := tls.Client(conn, &tls.Config{ServerName: t.Host, RootCAs: t.Roots})
	if err := tc.HandshakeContext(ctx); err != nil {
		return 0, err
	}
	req, _ := http.NewRequest(http.MethodGet, "https://"+t.Host+path, nil)
	req.Close = true
	if err := req.Write(tc); err != nil {
		return 0, err
	}
	resp, err := http.ReadResponse(bufio.NewReader(tc), req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	start := time.Now()
	n, err := io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	took := time.Since(start)
	if n < 64<<10 {
		if err == nil {
			err = fmt.Errorf("only %d bytes", n)
		}
		return 0, err
	}
	return int64(float64(n) / 1024 / max(took.Seconds(), 0.001)), nil
}

// TorRelays fetches a few running guard relays' IPv4 ORPorts from the Tor
// Project's Onionoo, through via, for torHandshake to try.
func TorRelays(ctx context.Context, via []chain.Hop) ([]string, error) {
	client := &http.Client{Timeout: 30 * time.Second}
	if len(via) > 0 {
		client.Transport = &http.Transport{DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
			return (&chain.Chain{Hops: via}).DialContext(ctx, addr)
		}}
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet,
		"https://onionoo.torproject.org/details?type=relay&running=true&flag=Guard&order=-consensus_weight&limit=20&fields=or_addresses", nil)
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var body struct {
		Relays []struct {
			OrAddresses []string `json:"or_addresses"`
		} `json:"relays"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&body); err != nil {
		return nil, err
	}
	var out []string
	for _, r := range body.Relays {
		for _, a := range r.OrAddresses {
			if !strings.HasPrefix(a, "[") { // IPv4 only: free proxies rarely carry IPv6
				out = append(out, a)
				break
			}
		}
	}
	// Spread the load, and the chance, across relays.
	rand.Shuffle(len(out), func(i, j int) { out[i], out[j] = out[j], out[i] })
	return out, nil
}
