package monitor

import (
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Names maps addresses back to the names DNS gave for them. The TUN sees
// only addresses; this is how the view shows "discord.com" instead.
//
// A name is kept for at least an hour whatever its TTL: apps hold connections
// open, and reuse addresses, long after the answer expired.
type Names struct {
	mu sync.Mutex
	m  map[string]named
}

type named struct {
	name    string
	expires time.Time
}

const (
	minKeep  = time.Hour
	maxNames = 8192
)

func NewNames() *Names { return &Names{m: map[string]named{}} }

func (n *Names) Learn(ip, name string, ttl time.Duration) {
	keep := max(ttl, minKeep)
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.m) >= maxNames {
		now := time.Now()
		for k, v := range n.m {
			if now.After(v.expires) {
				delete(n.m, k)
			}
		}
		if len(n.m) >= maxNames {
			n.m = map[string]named{} // everything is fresh; start over rather than grow
		}
	}
	n.m[ip] = named{name, time.Now().Add(keep)}
}

func (n *Names) Lookup(ip string) string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.m[ip].name
}

// Blocklist holds domains and addresses. A domain blocks itself and every
// name under it; an entry that parses as an IP or CIDR blocks those
// addresses, whatever name they came by, or none. It is swapped whole, so
// matching never takes a lock.
type Blocklist struct {
	set atomic.Pointer[rules]
}

type rules struct {
	domains  map[string]bool
	prefixes []netip.Prefix
}

func NewBlocklist(domains []string) *Blocklist {
	b := &Blocklist{}
	b.Set(domains)
	return b
}

func (b *Blocklist) Set(entries []string) {
	r := rules{domains: make(map[string]bool, len(entries))}
	for _, e := range entries {
		e = strings.TrimSpace(e)
		if p, err := netip.ParsePrefix(e); err == nil {
			r.prefixes = append(r.prefixes, p.Masked())
		} else if a, err := netip.ParseAddr(e); err == nil {
			a = a.Unmap()
			r.prefixes = append(r.prefixes, netip.PrefixFrom(a, a.BitLen()))
		} else if d := normalize(e); d != "" {
			r.domains[d] = true
		}
	}
	b.set.Store(&r)
}

// MatchIP reports the blocked address or range that covers ip, or "".
func (b *Blocklist) MatchIP(ip netip.Addr) string {
	ip = ip.Unmap()
	for _, p := range b.set.Load().prefixes {
		if p.Contains(ip) {
			return p.String()
		}
	}
	return ""
}

// Match reports the blocked domain that covers name, or "".
func (b *Blocklist) Match(name string) string {
	m := b.set.Load().domains
	if len(m) == 0 {
		return ""
	}
	name = normalize(name)
	for name != "" {
		if m[name] {
			return name
		}
		_, rest, ok := strings.Cut(name, ".")
		if !ok {
			return ""
		}
		name = rest
	}
	return ""
}

func normalize(d string) string {
	d = strings.ToLower(strings.TrimSpace(d))
	d = strings.TrimPrefix(d, "*.")
	return strings.TrimSuffix(d, ".")
}
