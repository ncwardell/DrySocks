package mobile

import (
	"encoding/json"
	"time"

	"sockschain/engine/chain"
)

// PoolConfig is what SetPool takes, as JSON.
type PoolConfig struct {
	Hops []string `json:"hops"`
	// Mode: "sequential", "timed" (the app's Auto) or "scattered".
	Mode        string `json:"mode"`
	IntervalSec int    `json:"intervalSec"` // timed only
	// Paused stops the engine's own health checks of the pool.
	Paused bool `json:"paused"`
}

// SetPool fills the rotating pool name (hop URL rotate://name). Safe while the
// engine runs: new connections use the new pool at once, open ones carry on.
// Hops that do not parse are dropped rather than failing the whole pool, since
// scraped lists are refilled in bulk; the count kept is returned.
func SetPool(name string, configJSON string) (int, error) {
	var cfg PoolConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return 0, err
	}
	var hops []chain.Hop
	for _, raw := range cfg.Hops {
		if h, err := chain.ParseHop(raw); err == nil && h.Kind != chain.Rotating {
			hops = append(hops, h)
		}
	}
	interval := time.Duration(cfg.IntervalSec) * time.Second
	p := chain.PoolNamed(name)
	p.SetPaused(cfg.Paused)
	return len(hops), p.Set(hops, chain.ParseMode(cfg.Mode), interval)
}

// PoolStatus returns chain.PoolStatus for the pool name, as JSON.
func PoolStatus(name string) string {
	b, _ := json.Marshal(chain.PoolNamed(name).Status())
	return string(b)
}
