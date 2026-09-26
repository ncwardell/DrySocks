//go:build live

// Against the real lists and the real Cloudflare: go test -tags live -v -run Live ./scrape
package scrape

import (
	"context"
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
