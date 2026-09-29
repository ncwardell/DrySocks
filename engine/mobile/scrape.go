package mobile

import (
	"context"
	"encoding/json"
	"slices"
	"strings"
	"sync"
	"time"

	"drysocks/engine/chain"
	"drysocks/engine/scrape"
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
	// Protocols keeps only candidates of these schemes (socks5, socks4a,
	// http); empty keeps all. Applied before testing, so it saves time.
	Protocols []string `json:"protocols"`
	// Countries keeps only proxies leaving from these country codes; empty
	// keeps all. Known only after testing, so rejects still cost a test.
	Countries []string `json:"countries"`
	// Candidates, when set, are tested instead of fetching Sources: a pool
	// checking the proxies it already has.
	Candidates []string `json:"candidates"`
	// ReportAll sends failures to OnResult too (with ok false).
	ReportAll bool `json:"reportAll"`
}

func (cfg ScrapeConfig) keep(c scrape.Candidate) bool {
	if len(cfg.Protocols) == 0 {
		return true
	}
	scheme, _, _ := strings.Cut(c.URL, "://")
	return slices.Contains(cfg.Protocols, scheme)
}

func (cfg ScrapeConfig) accept(r scrape.Result) bool {
	return len(cfg.Countries) == 0 || slices.Contains(cfg.Countries, strings.ToUpper(r.Country))
}

var (
	scrapeMu sync.Mutex
	scrapes  = map[string]context.CancelFunc{}
)

// ScrapeStart begins a scrape in the background under session, cancelling any
// scrape already running in that session. Sessions keep the user's scrape and
// a rotating hop's background refill from cancelling each other.
func ScrapeStart(session string, configJSON string, l ScrapeListener) {
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
	if prev := scrapes[session]; prev != nil {
		prev()
	}
	scrapes[session] = cancel
	scrapeMu.Unlock()

	go func() {
		defer cancel()

		var fetched scrape.FetchResult
		if len(cfg.Candidates) > 0 {
			for _, u := range cfg.Candidates {
				fetched.Candidates = append(fetched.Candidates, scrape.Candidate{URL: u, Source: "pool"})
			}
		} else {
			fetched = scrape.Fetch(ctx, cfg.Sources, &chain.Chain{Hops: via})
		}
		var cands []scrape.Candidate
		for _, c := range fetched.Candidates {
			if cfg.keep(c) {
				cands = append(cands, c)
			}
		}
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
		opts := scrape.CheckOpts{
			Workers: cfg.Workers, Want: cfg.Want, PerCheck: timeout,
			Accept: cfg.accept, ReportAll: cfg.ReportAll,
		}
		scrape.CheckAll(ctx, cands, via, scrape.DefaultTarget, opts,
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

// ScrapeCancel stops the scrape running in session; its OnDone still arrives.
func ScrapeCancel(session string) {
	scrapeMu.Lock()
	defer scrapeMu.Unlock()
	if cancel := scrapes[session]; cancel != nil {
		cancel()
		delete(scrapes, session)
	}
}

// DefaultSources returns the built-in list sources as JSON.
func DefaultSources() string {
	b, _ := json.Marshal(scrape.DefaultSources)
	return string(b)
}
