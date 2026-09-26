// Package monitor keeps the record the network view shows: every connection
// through the VPN, every DNS lookup, and the names behind the addresses.
//
// It is memory only, and bounded. The log is, in effect, a browsing history;
// nothing here is written anywhere, and it goes when the engine stops.
package monitor

import (
	"net"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

const (
	keepClosed = 300 // finished connections kept for the view
	keepDNS    = 300 // DNS lookups kept
)

// Conn is one connection as the view sees it.
type Conn struct {
	ID      uint64 `json:"id"`
	Proto   string `json:"proto"` // tcp
	Src     string `json:"src"`   // the app's side, inside the tunnel
	Dst     string `json:"dst"`   // ip:port the app dialled
	Host    string `json:"host,omitempty"`
	Started int64  `json:"started"` // unix ms
	Ended   int64  `json:"ended,omitempty"`
	Up      int64  `json:"up"`
	Down    int64  `json:"down"`
	Blocked bool   `json:"blocked,omitempty"`
	Error   string `json:"error,omitempty"`
}

// Lookup is one DNS question and what came of it.
type Lookup struct {
	Time    int64    `json:"time"`
	Name    string   `json:"name"`
	Type    string   `json:"type"`
	Answers []string `json:"answers,omitempty"`
	Blocked bool     `json:"blocked,omitempty"`
	Error   string   `json:"error,omitempty"`
}

// Snapshot is what the view polls.
type Snapshot struct {
	Active     []Conn   `json:"active"`
	Recent     []Conn   `json:"recent"`
	DNS        []Lookup `json:"dns"`
	Up         int64    `json:"up"`
	Down       int64    `json:"down"`
	UDPDropped int64    `json:"udpDropped"`
	Blocked    int64    `json:"blocked"`
}

type Monitor struct {
	Names *Names
	Block *Blocklist

	mu      sync.Mutex
	next    uint64
	active  map[uint64]*Entry
	closed  []Conn // ring, oldest first
	lookups []Lookup

	up, down, udpDropped, blocked atomic.Int64
}

func New(block *Blocklist) *Monitor {
	if block == nil {
		block = NewBlocklist(nil)
	}
	return &Monitor{Names: NewNames(), Block: block, active: map[uint64]*Entry{}}
}

// Entry is a live connection's handle.
type Entry struct {
	m        *Monitor
	c        Conn
	up, down atomic.Int64
	once     sync.Once
}

// Open records a new connection and names its destination if DNS has.
func (m *Monitor) Open(proto, src, dst string) *Entry {
	e := &Entry{m: m, c: Conn{Proto: proto, Src: src, Dst: dst, Started: time.Now().UnixMilli()}}
	if host, _, err := net.SplitHostPort(dst); err == nil {
		e.c.Host = m.Names.Lookup(host)
	}
	m.mu.Lock()
	m.next++
	e.c.ID = m.next
	m.active[e.c.ID] = e
	m.mu.Unlock()
	return e
}

func (e *Entry) Host() string { return e.c.Host }

// Close ends the connection; err or blocked say why, if it never got going.
func (e *Entry) Close(err error, blocked bool) {
	e.once.Do(func() {
		m := e.m
		c := e.c
		c.Ended = time.Now().UnixMilli()
		c.Up, c.Down = e.up.Load(), e.down.Load()
		c.Blocked = blocked
		if err != nil {
			c.Error = err.Error()
		}
		if blocked {
			m.blocked.Add(1)
		}
		m.mu.Lock()
		delete(m.active, c.ID)
		m.closed = append(m.closed, c)
		if len(m.closed) > keepClosed {
			m.closed = append(m.closed[:0:0], m.closed[len(m.closed)-keepClosed:]...)
		}
		m.mu.Unlock()
	})
}

// Wrap counts the bytes on conn and closes e when conn closes.
func (e *Entry) Wrap(conn net.Conn) net.Conn { return &counted{Conn: conn, e: e} }

type counted struct {
	net.Conn
	e *Entry
}

func (c *counted) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.e.down.Add(int64(n))
	c.e.m.down.Add(int64(n))
	return n, err
}

func (c *counted) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	c.e.up.Add(int64(n))
	c.e.m.up.Add(int64(n))
	return n, err
}

func (c *counted) Close() error {
	c.e.Close(nil, false)
	return c.Conn.Close()
}

// CloseWrite passes through, so half-closes still reach the far end.
func (c *counted) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return nil
}

func (m *Monitor) DroppedUDP() { m.udpDropped.Add(1) }

func (m *Monitor) logLookup(l Lookup) {
	m.mu.Lock()
	m.lookups = append(m.lookups, l)
	if len(m.lookups) > keepDNS {
		m.lookups = append(m.lookups[:0:0], m.lookups[len(m.lookups)-keepDNS:]...)
	}
	m.mu.Unlock()
}

// Snapshot copies the current state, newest first.
func (m *Monitor) Snapshot() Snapshot {
	m.mu.Lock()
	s := Snapshot{
		Active: make([]Conn, 0, len(m.active)),
		Recent: make([]Conn, len(m.closed)),
		DNS:    make([]Lookup, len(m.lookups)),
	}
	for _, e := range m.active {
		c := e.c
		c.Up, c.Down = e.up.Load(), e.down.Load()
		s.Active = append(s.Active, c)
	}
	copy(s.Recent, m.closed)
	copy(s.DNS, m.lookups)
	m.mu.Unlock()

	sort.Slice(s.Active, func(i, j int) bool { return s.Active[i].ID > s.Active[j].ID })
	reverse(s.Recent)
	reverse(s.DNS)
	s.Up, s.Down = m.up.Load(), m.down.Load()
	s.UDPDropped, s.Blocked = m.udpDropped.Load(), m.blocked.Load()
	return s
}

func reverse[T any](s []T) {
	for i, j := 0, len(s)-1; i < j; i, j = i+1, j-1 {
		s[i], s[j] = s[j], s[i]
	}
}
