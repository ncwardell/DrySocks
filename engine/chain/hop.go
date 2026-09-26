// Package chain dials TCP through an ordered list of proxies, each one reached
// through the tunnel the previous hops have already built.
package chain

import (
	"fmt"
	"net"
	"net/url"
	"strconv"
)

type Kind int

const (
	SOCKS5 Kind = iota
	SOCKS4      // SOCKS4a: the target may be a name, resolved by the proxy
	HTTP        // HTTP CONNECT
	// Rotating stands for its Pool's current proxy, resolved at each dial.
	Rotating
)

func (k Kind) String() string {
	switch k {
	case SOCKS5:
		return "socks5"
	case SOCKS4:
		return "socks4a"
	case HTTP:
		return "http"
	case Rotating:
		return "rotate"
	}
	return "unknown"
}

// Hop is one proxy in a chain.
type Hop struct {
	Kind Kind
	Addr string // host:port; a name here is resolved by the hop before it
	User string
	Pass string
	Pool *Pool // Rotating only
}

func (h Hop) String() string { return h.Kind.String() + "://" + h.Addr }

// ParseHop reads a proxy URL: socks5://user:pass@host:port, socks4a://host:port
// or http://user:pass@host:port. The port is required; guessing one hides typos.
// rotate://name is a rotating hop over the pool of that name.
func ParseHop(raw string) (Hop, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return Hop{}, fmt.Errorf("hop %q: %w", raw, err)
	}
	if u.Scheme == "rotate" {
		if u.Host == "" {
			return Hop{}, fmt.Errorf("hop %q: want rotate://name", raw)
		}
		return Hop{Kind: Rotating, Addr: u.Host, Pool: PoolNamed(u.Host)}, nil
	}

	var h Hop
	switch u.Scheme {
	case "socks5", "socks5h":
		h.Kind = SOCKS5
	case "socks4", "socks4a":
		h.Kind = SOCKS4
	case "http":
		h.Kind = HTTP
	default:
		return Hop{}, fmt.Errorf("hop %q: unsupported scheme %q", raw, u.Scheme)
	}

	host, port := u.Hostname(), u.Port()
	if host == "" || port == "" {
		return Hop{}, fmt.Errorf("hop %q: want host:port", raw)
	}
	if p, err := strconv.Atoi(port); err != nil || p < 1 || p > 65535 {
		return Hop{}, fmt.Errorf("hop %q: bad port %q", raw, port)
	}
	h.Addr = net.JoinHostPort(host, port)

	if u.User != nil {
		if h.Kind == SOCKS4 {
			// SOCKS4 has a user id but no password; sending one silently would
			// mislead whoever typed it.
			if _, hasPass := u.User.Password(); hasPass {
				return Hop{}, fmt.Errorf("hop %q: socks4 takes no password", raw)
			}
		}
		h.User = u.User.Username()
		h.Pass, _ = u.User.Password()
	}
	return h, nil
}

// ParseHops parses every hop, failing on the first bad one.
func ParseHops(raws []string) ([]Hop, error) {
	hops := make([]Hop, 0, len(raws))
	for _, r := range raws {
		h, err := ParseHop(r)
		if err != nil {
			return nil, err
		}
		hops = append(hops, h)
	}
	return hops, nil
}
