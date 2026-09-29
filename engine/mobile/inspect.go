package mobile

import (
	"context"
	"encoding/json"
	"sync"
	"sync/atomic"

	"drysocks/engine/chain"
	"drysocks/engine/scrape"
)

// InspectConfig is what InspectStart takes, as JSON.
type InspectConfig struct {
	URLs     []string `json:"urls"`
	Via      []string `json:"via"`
	Parallel int      `json:"parallel"`
}

// InspectStart runs scrape.Inspect on every URL, through via, under session
// (ScrapeCancel stops it). Each report arrives as JSON through OnResult.
func InspectStart(session string, configJSON string, l ScrapeListener) {
	var cfg InspectConfig
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
		l.OnFetched(len(cfg.URLs), "{}")
		t := scrape.DefaultTargets
		// One relay list for the run; if it cannot be had, the Tor test says so.
		t.TorRelays, _ = scrape.TorRelays(ctx, via)

		sem := make(chan struct{}, max(cfg.Parallel, 1))
		var done atomic.Int32
		var report sync.Mutex
		var wg sync.WaitGroup
		for _, u := range cfg.URLs {
			if ctx.Err() != nil {
				break
			}
			sem <- struct{}{}
			wg.Add(1)
			go func() {
				defer wg.Done()
				defer func() { <-sem }()
				r := scrape.Inspect(ctx, u, via, t)
				if ctx.Err() != nil {
					return
				}
				b, _ := json.Marshal(r)
				report.Lock()
				l.OnResult(string(b))
				l.OnProgress(int(done.Add(1)), len(cfg.URLs), 0)
				report.Unlock()
			}()
		}
		wg.Wait()
		if ctx.Err() != nil {
			l.OnDone("cancelled")
			return
		}
		l.OnDone("")
	}()
}
