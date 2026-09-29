//go:build live

// Against the real lists and the real Cloudflare: go test -tags live -v -run Live ./scrape
package scrape

import (
	"context"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestLiveScrapeAndCheck(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	res := Fetch(ctx, DefaultSources, nil)
	t.Logf("%d candidates; per source %v; errors %v", len(res.Candidates), res.PerSource, res.Errors)
	if len(res.Candidates) == 0 {
		t.Fatal("no candidates from any source")
	}

	start := time.Now()
	var last Progress
	CheckAll(ctx, res.Candidates, nil, DefaultTarget, CheckOpts{Workers: 128, Want: 8, PerCheck: 10 * time.Second},
		func(r Result) { t.Logf("OK %4d ms  %-2s  exit %-15s  %s", r.Ms, r.Country, r.ExitIP, r.URL) },
		func(p Progress) { last = p })
	t.Logf("tested %d of %d in %v, found %d", last.Tested, last.Total, time.Since(start).Round(time.Second), last.Found)
}

// go test -tags live -v -run LiveInspect ./scrape, with proxy URLs one per line in $SC_PROXIES.
func TestLiveInspect(t *testing.T) {
	b, err := os.ReadFile(os.Getenv("SC_PROXIES"))
	if err != nil {
		t.Skip("set SC_PROXIES to a file of proxy URLs")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	tg := DefaultTargets
	tg.TorRelays, err = TorRelays(ctx, nil)
	t.Logf("%d Tor relays to try (%v)", len(tg.TorRelays), err)
	urls := strings.Fields(string(b))
	var wg sync.WaitGroup
	for _, u := range urls[:min(8, len(urls))] {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r := Inspect(ctx, u, nil, tg)
			t.Logf("%-32s %-14v auth=%-5v 443=%-5v 80=%-5v any=%-5v tor=%-5v tamper=%v %s/%s host=%v flagged=%v %dKB/s",
				u, r.Protocols, r.NeedsAuth, r.Web443, r.Web80, r.AnyPort, r.Tor, r.AddedHeaders, r.Country, r.ISP, r.Hosting, r.Flagged, r.KBps)
		}()
	}
	wg.Wait()
}
