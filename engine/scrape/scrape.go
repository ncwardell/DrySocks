// Package scrape collects free proxies from public lists and finds the ones
// that work.
//
// "Work" is strict on purpose. A free proxy is run by a stranger, and many are
// honeypots, misconfigured machines, or tamper with traffic. A candidate passes
// only if a TLS handshake through it to a known host verifies against the
// system's roots, which a proxy that intercepts TLS cannot fake.
package scrape

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"drysocks/engine/chain"
)

// Source is a URL serving one proxy per line, as ip:port or scheme://ip:port.
// Kind is the scheme assumed for bare ip:port lines.
type Source struct {
	URL  string `json:"url"`
	Kind string `json:"kind"` // socks5, socks4a or http
}

// Candidate is a proxy URL a source listed, not yet known to work.
type Candidate struct {
	URL    string `json:"url"`
	Source string `json:"source"`
}

// FetchResult is what Fetch found, and which sources failed and why.
type FetchResult struct {
	Candidates []Candidate       `json:"candidates"`
	Errors     map[string]string `json:"errors,omitempty"`
	PerSource  map[string]int    `json:"perSource"`
}

// maxListBytes caps one source; the largest known lists are a few MB.
const maxListBytes = 16 << 20

// Fetch downloads every source in parallel, through via if it has hops
// (so the list hosts see the chain, not the phone), and returns the
// candidates deduplicated in source order.
func Fetch(ctx context.Context, sources []Source, via *chain.Chain) FetchResult {
	client := &http.Client{Timeout: 30 * time.Second}
	if via != nil && len(via.Hops) > 0 {
		client.Transport = &http.Transport{
			DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
				return via.DialContext(ctx, addr)
			},
		}
	}

	lists := make([][]Candidate, len(sources))
	errs := make([]error, len(sources))
	var wg sync.WaitGroup
	for i, src := range sources {
		wg.Add(1)
		go func() {
			defer wg.Done()
			lists[i], errs[i] = fetchOne(ctx, client, src)
		}()
	}
	wg.Wait()

	res := FetchResult{Errors: map[string]string{}, PerSource: map[string]int{}}
	seen := map[string]bool{}
	for i, src := range sources {
		if errs[i] != nil {
			res.Errors[src.URL] = errs[i].Error()
			continue
		}
		res.PerSource[src.URL] = len(lists[i])
		for _, c := range lists[i] {
			if !seen[c.URL] {
				seen[c.URL] = true
				res.Candidates = append(res.Candidates, c)
			}
		}
	}
	return res
}

func fetchOne(ctx context.Context, client *http.Client, src Source) ([]Candidate, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, src.URL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s", resp.Status)
	}
	return Parse(io.LimitReader(resp.Body, maxListBytes), src), nil
}

var line = regexp.MustCompile(`^(?:(socks5h?|socks4a?|https?)://)?(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})\b`)

// Parse reads a proxy list. Lines it cannot read are skipped, not fatal: lists
// carry headers, comments and the odd row of junk.
func Parse(r io.Reader, src Source) []Candidate {
	var out []Candidate
	sc := bufio.NewScanner(r)
	for sc.Scan() {
		m := line.FindStringSubmatch(strings.TrimSpace(sc.Text()))
		if m == nil {
			continue
		}
		scheme := m[1]
		if scheme == "" {
			scheme = src.Kind
		}
		switch scheme {
		case "socks5h":
			scheme = "socks5"
		case "socks4":
			scheme = "socks4a"
		case "https":
			// A proxy reached over TLS is not a hop kind; its CONNECT is the same
			// as http's, but the connection to it is not.
			continue
		}
		if p, err := strconv.Atoi(m[3]); err != nil || p < 1 || p > 65535 {
			continue
		}
		if !validIPv4(m[2]) {
			continue
		}
		out = append(out, Candidate{URL: scheme + "://" + m[2] + ":" + m[3], Source: src.URL})
	}
	return out
}

func validIPv4(s string) bool {
	ip := net.ParseIP(s).To4()
	return ip != nil && !ip.IsPrivate() && !ip.IsLoopback() && !ip.IsUnspecified()
}
