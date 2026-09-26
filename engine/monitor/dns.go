package monitor

import (
	"context"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// Exchange is how a query gets answered upstream: through the chain.
type Exchange func(ctx context.Context, query []byte) ([]byte, error)

// Resolve answers one DNS query: refused locally if its name is blocked,
// otherwise sent upstream, with the answer's addresses learned for naming
// connections. Every lookup is logged.
func (m *Monitor) Resolve(ctx context.Context, query []byte, upstream Exchange) ([]byte, error) {
	var p dnsmessage.Parser
	hdr, err := p.Start(query)
	if err != nil {
		return upstream(ctx, query) // not ours to judge; pass it on untouched
	}
	q, err := p.Question()
	if err != nil {
		return upstream(ctx, query)
	}
	name := strings.TrimSuffix(q.Name.String(), ".")
	l := Lookup{Time: time.Now().UnixMilli(), Name: name, Type: typeName(q.Type)}

	if m.Block.Match(name) != "" {
		l.Blocked = true
		m.blocked.Add(1)
		m.logLookup(l)
		return refuse(hdr, q)
	}

	resp, err := upstream(ctx, query)
	if err != nil {
		l.Error = err.Error()
		m.logLookup(l)
		return nil, err
	}
	l.Answers = m.learn(name, resp)
	m.logLookup(l)
	return resp, nil
}

// refuse answers NXDOMAIN: the name does not exist, so apps give up at once
// rather than retrying as they would on a timeout.
func refuse(h dnsmessage.Header, q dnsmessage.Question) ([]byte, error) {
	h.Response = true
	h.RecursionAvailable = true
	h.RCode = dnsmessage.RCodeNameError
	b := dnsmessage.NewBuilder(nil, h)
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	if err := b.Question(q); err != nil {
		return nil, err
	}
	return b.Finish()
}

// learn maps every address in resp to the name the app asked for, which is
// the name a person recognises, not whatever CDN host it CNAMEs to.
func (m *Monitor) learn(asked string, resp []byte) []string {
	var p dnsmessage.Parser
	if _, err := p.Start(resp); err != nil {
		return nil
	}
	if err := p.SkipAllQuestions(); err != nil {
		return nil
	}
	var out []string
	for {
		h, err := p.AnswerHeader()
		if err != nil {
			break
		}
		ttl := time.Duration(h.TTL) * time.Second
		switch h.Type {
		case dnsmessage.TypeA:
			r, err := p.AResource()
			if err != nil {
				return out
			}
			ip := netipFrom4(r.A)
			m.Names.Learn(ip, asked, ttl)
			out = append(out, ip)
		case dnsmessage.TypeAAAA:
			r, err := p.AAAAResource()
			if err != nil {
				return out
			}
			ip := netipFrom16(r.AAAA)
			m.Names.Learn(ip, asked, ttl)
			out = append(out, ip)
		case dnsmessage.TypeCNAME:
			r, err := p.CNAMEResource()
			if err != nil {
				return out
			}
			out = append(out, "→ "+strings.TrimSuffix(r.CNAME.String(), "."))
		default:
			if err := p.SkipAnswer(); err != nil {
				return out
			}
		}
	}
	return out
}

func typeName(t dnsmessage.Type) string {
	s := t.String()
	return strings.TrimPrefix(s, "Type")
}
