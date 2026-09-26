package monitor

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func TestBlocklistMatchesDomainsAndAddresses(t *testing.T) {
	b := NewBlocklist([]string{"Tracker.example", "*.ads.test", "203.0.113.7", "198.51.100.0/24", "2001:db8::/32", ""})

	for name, want := range map[string]string{
		"tracker.example":     "tracker.example",
		"cdn.tracker.example": "tracker.example",
		"x.ads.test":          "ads.test",
		"ads.test.":           "ads.test",
		"nottracker.example":  "",
		"example":             "",
	} {
		if got := b.Match(name); got != want {
			t.Errorf("Match(%q) = %q, want %q", name, got, want)
		}
	}
	for ip, want := range map[string]string{
		"203.0.113.7":        "203.0.113.7/32",
		"::ffff:203.0.113.7": "203.0.113.7/32",
		"198.51.100.200":     "198.51.100.0/24",
		"2001:db8:1::5":      "2001:db8::/32",
		"203.0.113.8":        "",
	} {
		if got := b.MatchIP(netip.MustParseAddr(ip)); got != want {
			t.Errorf("MatchIP(%s) = %q, want %q", ip, got, want)
		}
	}
}

func query(t *testing.T, name string) []byte {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 42, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name + "."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	msg, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return msg
}

// answer is an upstream that answers every A query with ip, via a CNAME.
func answer(ip [4]byte) Exchange {
	return func(_ context.Context, q []byte) ([]byte, error) {
		var p dnsmessage.Parser
		h, _ := p.Start(q)
		qq, _ := p.Question()
		h.Response = true
		b := dnsmessage.NewBuilder(nil, h)
		b.StartQuestions()
		b.Question(qq)
		b.StartAnswers()
		cdn := dnsmessage.MustNewName("edge.cdn.test.")
		b.CNAMEResource(dnsmessage.ResourceHeader{Name: qq.Name, Class: dnsmessage.ClassINET, TTL: 30}, dnsmessage.CNAMEResource{CNAME: cdn})
		b.AResource(dnsmessage.ResourceHeader{Name: cdn, Class: dnsmessage.ClassINET, TTL: 30}, dnsmessage.AResource{A: ip})
		return b.Finish()
	}
}

func TestResolveNamesAddressesByTheNameAsked(t *testing.T) {
	m := New(nil)
	if _, err := m.Resolve(context.Background(), query(t, "chat.example"), answer([4]byte{192, 0, 2, 10})); err != nil {
		t.Fatal(err)
	}
	// Not the CDN name the answer went through: the name a person recognises.
	if got := m.Names.Lookup("192.0.2.10"); got != "chat.example" {
		t.Errorf("192.0.2.10 named %q, want chat.example", got)
	}
	if e := m.Open("tcp", "198.18.0.1:5000", "192.0.2.10:443"); e.Host() != "chat.example" {
		t.Errorf("connection host %q", e.Host())
	}
	s := m.Snapshot()
	if len(s.DNS) != 1 || s.DNS[0].Name != "chat.example" || !strings.Contains(strings.Join(s.DNS[0].Answers, " "), "192.0.2.10") {
		t.Errorf("lookup log %+v", s.DNS)
	}
}

func TestResolveRefusesBlockedNamesWithoutAsking(t *testing.T) {
	m := New(NewBlocklist([]string{"tracker.example"}))
	asked := false
	resp, err := m.Resolve(context.Background(), query(t, "pixel.tracker.example"), func(context.Context, []byte) ([]byte, error) {
		asked = true
		return nil, errors.New("should not be asked")
	})
	if err != nil || asked {
		t.Fatalf("err %v, asked upstream %v", err, asked)
	}
	var p dnsmessage.Parser
	h, err := p.Start(resp)
	if err != nil || !h.Response || h.RCode != dnsmessage.RCodeNameError || h.ID != 42 {
		t.Fatalf("got %+v, %v; want an NXDOMAIN reply to id 42", h, err)
	}
	if s := m.Snapshot(); s.Blocked != 1 || !s.DNS[0].Blocked {
		t.Errorf("snapshot %+v", s)
	}
}

func TestConnectionsCountBytesAndMoveToRecent(t *testing.T) {
	m := New(nil)
	a, b := net.Pipe()
	e := m.Open("tcp", "198.18.0.1:5000", "192.0.2.1:80")
	c := e.Wrap(a)
	go func() { b.Write([]byte("hello")); io.ReadFull(b, make([]byte, 3)); b.Close() }()
	io.ReadFull(c, make([]byte, 5))
	c.Write([]byte("abc"))

	if s := m.Snapshot(); len(s.Active) != 1 || s.Active[0].Down != 5 || s.Active[0].Up != 3 {
		t.Fatalf("active %+v", s.Active)
	}
	c.Close()
	s := m.Snapshot()
	if len(s.Active) != 0 || len(s.Recent) != 1 || s.Recent[0].Ended == 0 || s.Down != 5 || s.Up != 3 {
		t.Fatalf("after close %+v", s)
	}
}
