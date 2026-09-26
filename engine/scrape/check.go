package scrape

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"sockschain/engine/chain"
)

// Target is where a candidate is asked to connect. Cloudflare's trace page
// answers over TLS with the address it saw and that address's country, so one
// request proves the tunnel, rules out TLS interception, and says where the
// proxy leaves from.
type Target struct {
	Host string // TLS server name, and CONNECT target on port 443
	Path string
	// Addr overrides the CONNECT target; tests aim it at a local server.
	Addr string
	// Roots verifies the handshake. Nil means the system's.
	Roots *x509.CertPool
}

var DefaultTarget = Target{Host: "www.cloudflare.com", Path: "/cdn-cgi/trace"}

// Result is one candidate's verdict.
type Result struct {
	URL     string `json:"url"`
	OK      bool   `json:"ok"`
	Ms      int64  `json:"ms,omitempty"` // through the proxy to the target's first byte
	ExitIP  string `json:"exitIp,omitempty"`
	Country string `json:"country,omitempty"`
	Error   string `json:"error,omitempty"`
}

// Check dials target through via (may be empty) then the candidate, and
// fetches the trace page.
func Check(ctx context.Context, candidate string, via []chain.Hop, t Target) Result {
	res := Result{URL: candidate}
	hop, err := chain.ParseHop(candidate)
	if err != nil {
		res.Error = err.Error()
		return res
	}
	c := &chain.Chain{Hops: append(append([]chain.Hop(nil), via...), hop)}

	addr := t.Addr
	if addr == "" {
		addr = t.Host + ":443"
	}
	start := time.Now()
	conn, err := c.DialContext(ctx, addr)
	if err != nil {
		res.Error = err.Error()
		return res
	}
	defer conn.Close()
	if d, ok := ctx.Deadline(); ok {
		conn.SetDeadline(d)
	}

	tc := tls.Client(conn, &tls.Config{ServerName: t.Host, RootCAs: t.Roots})
	if err := tc.HandshakeContext(ctx); err != nil {
		var verr *tls.CertificateVerificationError
		if errors.As(err, &verr) {
			res.Error = "intercepts TLS: presented a certificate that does not verify"
		} else {
			res.Error = "TLS: " + err.Error()
		}
		return res
	}

	req, _ := http.NewRequest(http.MethodGet, "https://"+t.Host+t.Path, nil)
	req.Close = true
	if err := req.Write(tc); err != nil {
		res.Error = err.Error()
		return res
	}
	resp, err := http.ReadResponse(bufio.NewReader(tc), req)
	if err != nil {
		res.Error = err.Error()
		return res
	}
	defer resp.Body.Close()
	res.Ms = time.Since(start).Milliseconds()
	if resp.StatusCode != http.StatusOK {
		res.Error = "trace: " + resp.Status
		return res
	}

	sc := bufio.NewScanner(resp.Body)
	for sc.Scan() {
		k, v, _ := strings.Cut(sc.Text(), "=")
		switch k {
		case "ip":
			res.ExitIP = v
		case "loc":
			res.Country = v
		}
	}
	res.OK = res.ExitIP != ""
	if !res.OK {
		res.Error = "trace page had no address"
	}
	return res
}

// Progress is reported after every candidate.
type Progress struct {
	Tested int `json:"tested"`
	Total  int `json:"total"`
	Found  int `json:"found"`
}

// CheckAll tests candidates with workers in parallel, calling onResult for
// every working one and onProgress after each, until all are tested, want
// have worked (want <= 0: no limit), or ctx ends.
func CheckAll(
	ctx context.Context, candidates []Candidate, via []chain.Hop, t Target,
	workers, want int, perCheck time.Duration,
	onResult func(Result), onProgress func(Progress),
) {
	ctx, stop := context.WithCancel(ctx)
	defer stop()

	jobs := make(chan Candidate)
	var tested, found atomic.Int32
	var report sync.Mutex // callbacks cross into Java; keep them in order

	var wg sync.WaitGroup
	for range max(workers, 1) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for c := range jobs {
				cctx, cancel := context.WithTimeout(ctx, perCheck)
				r := Check(cctx, c.URL, via, t)
				cancel()
				if ctx.Err() != nil {
					return // stopped mid-check; the verdict means nothing
				}

				report.Lock()
				n := tested.Add(1)
				if r.OK {
					f := found.Add(1)
					onResult(r)
					if want > 0 && int(f) >= want {
						stop()
					}
				}
				onProgress(Progress{Tested: int(n), Total: len(candidates), Found: int(found.Load())})
				report.Unlock()
			}
		}()
	}

feed:
	for _, c := range candidates {
		select {
		case jobs <- c:
		case <-ctx.Done():
			break feed
		}
	}
	close(jobs)
	wg.Wait()
}
