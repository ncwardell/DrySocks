package mobile

import (
	"encoding/json"
	"errors"
	"time"

	"sockschain/engine/chain"
)

var upstream *chain.Server

// StartUpstream serves SOCKS5 on 127.0.0.1 through the hops in hopsJSON (a
// JSON array of hop URLs) and returns the port. Tor's Socks5Proxy points
// here when the user's chain has hops before Tor.
func StartUpstream(hopsJSON string) (int, error) {
	var raws []string
	if err := json.Unmarshal([]byte(hopsJSON), &raws); err != nil {
		return 0, err
	}
	hops, err := chain.ParseHops(raws)
	if err != nil {
		return 0, err
	}
	if len(hops) == 0 {
		return 0, errors.New("upstream needs at least one hop")
	}

	mu.Lock()
	defer mu.Unlock()
	if upstream != nil {
		upstream.Close()
	}
	upstream = &chain.Server{Chain: &chain.Chain{Hops: hops}, DialTimeout: 30 * time.Second}
	return upstream.Listen()
}

// StopUpstream is safe to call when nothing is serving.
func StopUpstream() {
	mu.Lock()
	defer mu.Unlock()
	if upstream != nil {
		upstream.Close()
		upstream = nil
	}
}
