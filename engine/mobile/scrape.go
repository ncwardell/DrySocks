package mobile

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"sockschain/engine/chain"
	"sockschain/engine/scrape"
)

// ScrapeListener receives a scrape's progress. gomobile turns it into a Java
// interface; calls arrive on Go's threads, one at a time.
type ScrapeListener interface {
	// OnFetched reports the downloaded lists: a JSON scrape.FetchResult
	// without the candidates themselves, which can run to tens of thousands.
	OnFetched(candidates int, summaryJSON string)
	// OnResult reports one working proxy, as a JSON scrape.Result.
	OnResult(resultJSON string)
	OnProgress(tested, total, found int)
	// OnDone ends every scrape, cancelled or not; error is empty on success.
	OnDone(error string)
}

// ScrapeConfig is what ScrapeStart takes, as JSON.
type ScrapeConfig struct {
	Sources []scrape.Source `json:"sources"`
	// Via are hop URLs placed before every candidate, and used to fetch the
	// lists, so neither the list hosts nor the proxies see the phone.
	Via       []string `json:"via"`
	Want      int      `json:"want"`
	Workers   int      `json:"workers"`
	TimeoutMs int      `json:"timeoutMs"`
	// Shuffle spreads the checks across sources instead of exhausting the
	// first list before touching the next.
	Shuffle bool `json:"shuffle"`
}

var (
	scrapeMu     sync.Mutex
	scrapeCancel context.CancelFunc
)

// ScrapeStart begins a scrape in the background, cancelling any running one.
func ScrapeStart(configJSON string, l ScrapeListener) {
	var cfg ScrapeConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		l.OnDone(err.Error())
		return
	}
	via, err := chain.ParseHops(cfg.Via)
	if err != nil {
		l.OnDone(err.Error())
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	scrapeMu.Lock()
	if scrapeCancel != nil {
		scrapeCancel()
	}
	scrapeCancel = cancel
	scrapeMu.Unlock()

	go func() {
		defer cancel()

		fetched := scrape.Fetch(ctx, cfg.Sources, &chain.Chain{Hops: via})
		cands := fetched.Candidates
		if cfg.Shuffle {
			shuffle(cands)
		}
		summary := fetched
		summary.Candidates = nil
		b, _ := json.Marshal(summary)
		l.OnFetched(len(cands), string(b))
		if ctx.Err() != nil {
			l.OnDone("cancelled")
			return
		}

		timeout := time.Duration(cfg.TimeoutMs) * time.Millisecond
		if timeout <= 0 {
			timeout = 10 * time.Second
		}
		scrape.CheckAll(ctx, cands, via, scrape.DefaultTarget, cfg.Workers, cfg.Want, timeout,
			func(r scrape.Result) {
				b, _ := json.Marshal(r)
				l.OnResult(string(b))
			},
			func(p scrape.Progress) { l.OnProgress(p.Tested, p.Total, p.Found) },
		)
		if ctx.Err() != nil { // the user cancelled; reaching Want cancels only CheckAll's own context
			l.OnDone("cancelled")
			return
		}
		l.OnDone("")
	}()
}

// ScrapeCancel stops a running scrape; its OnDone still arrives.
func ScrapeCancel() {
	scrapeMu.Lock()
	defer scrapeMu.Unlock()
	if scrapeCancel != nil {
		scrapeCancel()
		scrapeCancel = nil
	}
}

// DefaultSources returns the built-in list sources as JSON.
func DefaultSources() string {
	b, _ := json.Marshal(scrape.DefaultSources)
	return string(b)
}
