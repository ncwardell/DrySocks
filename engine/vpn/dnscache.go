package vpn

import (
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// dnsCache keeps answers for their TTL, so a slow chain pays for each name once, and
// serves an expired answer when the chain cannot answer at all: over a flaky route an
// address that was right a few minutes ago beats no address.
type dnsCache struct {
	mu sync.Mutex
	m  map[string]cached
}

type cached struct {
	resp    []byte
	expires time.Time
	stored  time.Time
}

const (
	cacheMin   = 30 * time.Second // floor, for names that ask for none at all
	cacheMax   = 10 * time.Minute // ceiling, so a changed address is not missed for long
	staleFor   = time.Hour        // how long an expired answer may still stand in
	cacheLimit = 4096
)

func newDNSCache() *dnsCache { return &dnsCache{m: map[string]cached{}} }

// key is the question, lower-cased: name, type and class.
func questionKey(query []byte) (string, uint16, bool) {
	var p dnsmessage.Parser
	h, err := p.Start(query)
	if err != nil {
		return "", 0, false
	}
	q, err := p.Question()
	if err != nil {
		return "", 0, false
	}
	return strings.ToLower(q.Name.String()) + "|" + q.Type.String() + "|" + q.Class.String(), h.ID, true
}

// get returns a fresh answer for query, with the query's ID. With stale, an expired
// one within staleFor will do.
func (c *dnsCache) get(query []byte, stale bool) []byte {
	key, id, ok := questionKey(query)
	if !ok {
		return nil
	}
	c.mu.Lock()
	e, found := c.m[key]
	c.mu.Unlock()
	if !found {
		return nil
	}
	now := time.Now()
	if now.After(e.expires) && (!stale || now.Sub(e.expires) > staleFor) {
		return nil
	}
	out := append([]byte(nil), e.resp...)
	out[0], out[1] = byte(id>>8), byte(id)
	return out
}

// put stores a successful answer for the smallest TTL in it, within the floor and ceiling.
func (c *dnsCache) put(query, resp []byte) {
	key, _, ok := questionKey(query)
	if !ok {
		return
	}
	var p dnsmessage.Parser
	h, err := p.Start(resp)
	if err != nil || h.RCode != dnsmessage.RCodeSuccess {
		return // failures and NXDOMAIN are not worth keeping
	}
	if err := p.SkipAllQuestions(); err != nil {
		return
	}
	ttl := cacheMax
	answers := 0
	for {
		ah, err := p.AnswerHeader()
		if err != nil {
			break
		}
		answers++
		if d := time.Duration(ah.TTL) * time.Second; d < ttl {
			ttl = d
		}
		if err := p.SkipAnswer(); err != nil {
			break
		}
	}
	if answers == 0 {
		ttl = cacheMin
	}
	ttl = min(max(ttl, cacheMin), cacheMax)
	now := time.Now()
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.m) >= cacheLimit {
		for k, v := range c.m {
			if now.Sub(v.expires) > staleFor {
				delete(c.m, k)
			}
		}
		if len(c.m) >= cacheLimit {
			c.m = map[string]cached{}
		}
	}
	c.m[key] = cached{resp: append([]byte(nil), resp...), expires: now.Add(ttl), stored: now}
}
