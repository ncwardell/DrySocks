package mobile

import (
	"encoding/json"
	"errors"
	"time"

	"drysocks/engine/chain"
)

var upstream *chain.Server

// Upstream is where Tor's Socks5Proxy points, and the credentials it must use.
type Upstream struct {
	Port int    `json:"port"`
	User string `json:"user"`
	Pass string `json:"pass"`
}

// StartUpstream serves SOCKS5 on 127.0.0.1 through the hops in hopsJSON (a
// JSON array of hop URLs) and returns an Upstream, as JSON, for Tor's torrc.
func StartUpstream(hopsJSON string) (string, error) {
	var raws []string
	if err := json.Unmarshal([]byte(hopsJSON), &raws); err != nil {
		return "", err
	}
	hops, err := chain.ParseHops(raws)
	if err != nil {
		return "", err
	}
	if len(hops) == 0 {
		return "", errors.New("upstream needs at least one hop")
	}

	mu.Lock()
	defer mu.Unlock()
	if upstream != nil {
		upstream.Close()
	}
	upstream = &chain.Server{Chain: &chain.Chain{Hops: hops}, DialTimeout: 30 * time.Second}
	port, err := upstream.Listen()
	if err != nil {
		return "", err
	}
	b, _ := json.Marshal(Upstream{port, upstream.User, upstream.Pass})
	return string(b), nil
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
