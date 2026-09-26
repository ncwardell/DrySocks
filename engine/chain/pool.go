package chain

import (
	"context"
	"errors"
	"math/rand/v2"
	"sync"
	"time"
)

// Mode is how a rotating hop moves through its pool.
type Mode int

const (
	// Timed moves to a random proxy every interval ("Auto" in the app).
	Timed Mode = iota
	// Sequential stays on one proxy and moves to the next in order only when
	// it fails.
	Sequential
	// Scattered gives every new connection a random proxy.
	Scattered
)

// ParseMode reads the app's names; anything unknown is Timed.
func ParseMode(s string) Mode {
	switch s {
	case "sequential":
		return Sequential
	case "scattered":
		return Scattered
	}
	return Timed
}

// Pool is a rotating hop's proxies. Each new connection through the hop gets
// a proxy according to the pool's Mode. Connections already open keep the
// proxy they started on.
//
// A proxy that fails is benched for a while and the pool moves on at once, so
// a dead free proxy costs one connection a retry, not a minute of failures.
type Pool struct {
	mu       sync.Mutex
	hops     []Hop
	interval time.Duration
	mode     Mode
	paused   bool // no proactive health checks; live failures still bench
	cur      int
	since    time.Time
	benched  map[string]time.Time // by Addr
	now      func() time.Time
}

// BenchFor is how long a failed proxy sits out.
const BenchFor = 5 * time.Minute

func NewPool(mode Mode, interval time.Duration) *Pool {
	return &Pool{mode: mode, interval: interval, benched: map[string]time.Time{}, now: time.Now}
}

// Set replaces the proxies (rotating hops cannot nest) and settings. The
// current proxy stays current if it is still in the pool.
func (p *Pool) Set(hops []Hop, mode Mode, interval time.Duration) error {
	for _, h := range hops {
		if h.Kind == Rotating {
			return errors.New("a rotating pool cannot hold another rotating hop")
		}
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	var keep string
	if p.cur < len(p.hops) {
		keep = p.hops[p.cur].Addr
	}
	p.hops = append([]Hop(nil), hops...)
	p.interval, p.mode = interval, mode
	p.cur = 0
	for i, h := range p.hops {
		if h.Addr == keep {
			p.cur = i
			return nil
		}
	}
	p.since = p.now()
	return nil
}

// SetPaused stops or restarts the pool's proactive health checks.
func (p *Pool) SetPaused(v bool) {
	p.mu.Lock()
	p.paused = v
	p.mu.Unlock()
}

func (p *Pool) Paused() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.paused
}

// Current returns the proxy to use now, rotating first if its time is up.
func (p *Pool) Current() (Hop, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.hops) == 0 {
		return Hop{}, errors.New("rotating hop has no proxies")
	}
	now := p.now()
	if p.since.IsZero() {
		p.since = now
		if p.mode != Sequential {
			p.cur = rand.IntN(len(p.hops))
		}
	}
	switch {
	case p.mode == Scattered:
		p.advance(now) // a fresh pick for every connection
	case p.mode == Timed && p.interval > 0 && now.Sub(p.since) >= p.interval:
		p.advance(now)
	case p.isBenched(p.hops[p.cur], now):
		p.advance(now)
	}
	return p.hops[p.cur], nil
}

// Failed benches addr and moves on, unless the pool already has.
func (p *Pool) Failed(addr string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	p.benched[addr] = now.Add(BenchFor)
	if p.cur < len(p.hops) && p.hops[p.cur].Addr == addr {
		p.advance(now)
	}
}

// advance moves to the next proxy that is not benched; if every one is, the
// least recently benched is as good a bet as any, so it takes that.
func (p *Pool) advance(now time.Time) {
	n := len(p.hops)
	p.since = now
	if n < 2 {
		return
	}
	order := make([]int, 0, n-1)
	for i := 1; i < n; i++ {
		order = append(order, (p.cur+i)%n)
	}
	if p.mode != Sequential {
		rand.Shuffle(len(order), func(i, j int) { order[i], order[j] = order[j], order[i] })
	}
	for _, i := range order {
		if !p.isBenched(p.hops[i], now) {
			p.cur = i
			return
		}
	}
	oldest := order[0]
	for _, i := range order {
		if p.benched[p.hops[i].Addr].Before(p.benched[p.hops[oldest].Addr]) {
			oldest = i
		}
	}
	p.cur = oldest
}

func (p *Pool) isBenched(h Hop, now time.Time) bool {
	until, ok := p.benched[h.Addr]
	if ok && now.After(until) {
		delete(p.benched, h.Addr)
		return false
	}
	return ok
}

// Check tests every proxy in the pool through prefix (the hops before the
// rotating one, empty if it is first) and benches those that cannot reach
// target, so traffic finds the dead ones in advance rather than one failure at
// a time. It dials parallel at once, each with perCheck.
func (p *Pool) Check(ctx context.Context, prefix []Hop, target string, parallel int, perCheck time.Duration) (alive, dead int) {
	p.mu.Lock()
	hops := append([]Hop(nil), p.hops...)
	p.mu.Unlock()

	sem := make(chan struct{}, max(parallel, 1))
	var mu sync.Mutex
	var wg sync.WaitGroup
	for _, h := range hops {
		if ctx.Err() != nil {
			break
		}
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			c := &Chain{Hops: append(append([]Hop(nil), prefix...), h)}
			cctx, cancel := context.WithTimeout(ctx, perCheck)
			conn, err := c.DialContext(cctx, target)
			cancel()
			mu.Lock()
			defer mu.Unlock()
			if err != nil {
				if ctx.Err() == nil { // a cancelled check says nothing about the proxy
					p.Failed(h.Addr)
					dead++
				}
				return
			}
			conn.Close()
			alive++
		}()
	}
	wg.Wait()
	return alive, dead
}

// PoolStatus is what the app shows on a rotating hop.
type PoolStatus struct {
	Size     int    `json:"size"`
	Healthy  int    `json:"healthy"`
	Current  string `json:"current,omitempty"`
	NextInMs int64  `json:"nextInMs,omitempty"`
}

func (p *Pool) Status() PoolStatus {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	// Rotate on the clock here too, so what the app shows is what the next
	// connection will get, even while nothing is connecting.
	if p.mode == Timed && len(p.hops) > 0 && !p.since.IsZero() && p.interval > 0 && now.Sub(p.since) >= p.interval {
		p.advance(now)
	}
	s := PoolStatus{Size: len(p.hops)}
	for _, h := range p.hops {
		if !p.isBenched(h, now) {
			s.Healthy++
		}
	}
	if len(p.hops) > 0 && !p.since.IsZero() && p.mode != Scattered {
		s.Current = p.hops[p.cur].String()
		if p.mode == Timed && p.interval > 0 {
			s.NextInMs = max(0, (p.interval - now.Sub(p.since)).Milliseconds())
		}
	}
	return s
}

// Pools are named so a hop URL, rotate://name, can refer to one; the app
// fills them, the engine and the scraper dial through them.
var (
	poolsMu sync.Mutex
	pools   = map[string]*Pool{}
)

// PoolNamed returns the pool called name, creating an empty one if need be.
func PoolNamed(name string) *Pool {
	poolsMu.Lock()
	defer poolsMu.Unlock()
	p, ok := pools[name]
	if !ok {
		p = NewPool(Timed, time.Minute)
		pools[name] = p
	}
	return p
}
