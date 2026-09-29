package vpn

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"

	"drysocks/engine/chain"
	"drysocks/engine/monitor"
)

// dnsClient carries DNS over TCP through the chain (RFC 7766), because UDP
// cannot follow a chain. Building a tunnel costs one handshake per hop, so
// idle ones are kept and reused, one query at a time each.
type dnsClient struct {
	chain  *chain.Chain
	server string
	idle   chan net.Conn
	mon    *monitor.Monitor
	cache  *dnsCache
	// doh is set when server is an https:// URL: DNS over HTTPS (RFC 8484).
	// Proxies that allow nothing but web ports still carry it, and it is
	// encrypted end to end, so the hops cannot read or alter the lookups.
	doh *http.Client
}

// Room for two dial attempts through a rotating hop (see chain.attemptTimeout),
// still inside the ~15 s Android's resolver waits before giving up.
const dnsTimeout = 15 * time.Second

func newDNSClient(c *chain.Chain, server string, mon *monitor.Monitor) *dnsClient {
	if mon == nil {
		mon = monitor.New(nil)
	}
	d := &dnsClient{chain: c, server: server, idle: make(chan net.Conn, 4), mon: mon, cache: newDNSCache()}
	if strings.HasPrefix(server, "https://") {
		d.doh = &http.Client{
			Timeout: dnsTimeout,
			Transport: &http.Transport{
				// net/http dials with a context stripped of the request's deadline, so
				// the dial gets its own: a proxy that accepts and then goes silent must
				// not hold a lookup, and a goroutine, forever.
				DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
					ctx, cancel := context.WithTimeout(ctx, dnsTimeout)
					defer cancel()
					return c.DialContext(ctx, addr)
				},
				ForceAttemptHTTP2:   true, // one tunnel, many lookups
				MaxIdleConnsPerHost: 2,
				IdleConnTimeout:     90 * time.Second,
				TLSHandshakeTimeout: dnsTimeout,
			},
		}
	}
	return d
}

func (d *dnsClient) exchangeDoH(ctx context.Context, query []byte) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.server, bytes.NewReader(query))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := d.doh.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DNS over HTTPS: %s", resp.Status)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 64<<10))
}

// resolve is what both DNS paths call: the blocklist, the log, then the cache and
// upstream.
func (d *dnsClient) resolve(ctx context.Context, query []byte) ([]byte, error) {
	return d.mon.Resolve(ctx, query, d.cachedExchange)
}

func (d *dnsClient) cachedExchange(ctx context.Context, query []byte) ([]byte, error) {
	if resp := d.cache.get(query, false); resp != nil {
		return resp, nil
	}
	resp, err := d.exchange(ctx, query)
	if err != nil {
		if stale := d.cache.get(query, true); stale != nil {
			return stale, nil
		}
		return nil, err
	}
	d.cache.put(query, resp)
	return resp, nil
}

// warm makes one lookup, so the first real one finds the tunnel to the resolver already
// built: through Tor that tunnel alone can take seconds.
func (d *dnsClient) warm(ctx context.Context) error {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 1, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName("one.one.one.one."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, err := b.Finish()
	if err != nil {
		return err
	}
	_, err = d.cachedExchange(ctx, q)
	return err
}

// serveTCP answers DNS over TCP in-process, for apps that use TCP to the
// tunnel's DNS address: one conn, any number of length-prefixed queries.
func (d *dnsClient) serveTCP() net.Conn {
	app, us := net.Pipe()
	go func() {
		defer us.Close()
		for {
			us.SetReadDeadline(time.Now().Add(2 * dnsTimeout))
			var n [2]byte
			if _, err := io.ReadFull(us, n[:]); err != nil {
				return
			}
			q := make([]byte, binary.BigEndian.Uint16(n[:]))
			if _, err := io.ReadFull(us, q); err != nil {
				return
			}
			ctx, cancel := context.WithTimeout(context.Background(), dnsTimeout)
			resp, err := d.resolve(ctx, q)
			cancel()
			if err != nil {
				return
			}
			out := binary.BigEndian.AppendUint16(make([]byte, 0, 2+len(resp)), uint16(len(resp)))
			us.SetWriteDeadline(time.Now().Add(dnsTimeout))
			if _, err := us.Write(append(out, resp...)); err != nil {
				return
			}
		}
	}()
	return app
}

func (d *dnsClient) exchange(ctx context.Context, query []byte) ([]byte, error) {
	if d.doh != nil {
		return d.exchangeDoH(ctx, query)
	}
	// A pooled tunnel may have been closed by the server while it sat idle, so
	// a failure on one earns exactly one retry on a fresh tunnel.
	select {
	case conn := <-d.idle:
		if resp, err := d.roundTrip(conn, query); err == nil {
			return resp, nil
		}
	default:
	}

	conn, err := d.chain.DialContext(ctx, d.server)
	if err != nil {
		return nil, err
	}
	return d.roundTrip(conn, query)
}

func (d *dnsClient) roundTrip(conn net.Conn, query []byte) ([]byte, error) {
	conn.SetDeadline(time.Now().Add(dnsTimeout))
	msg := binary.BigEndian.AppendUint16(make([]byte, 0, 2+len(query)), uint16(len(query)))
	if _, err := conn.Write(append(msg, query...)); err != nil {
		conn.Close()
		return nil, err
	}
	var n [2]byte
	if _, err := io.ReadFull(conn, n[:]); err != nil {
		conn.Close()
		return nil, err
	}
	resp := make([]byte, binary.BigEndian.Uint16(n[:]))
	if _, err := io.ReadFull(conn, resp); err != nil {
		conn.Close()
		return nil, err
	}

	select {
	case d.idle <- conn:
	default:
		conn.Close()
	}
	return resp, nil
}

func (d *dnsClient) close() {
	if d.doh != nil {
		d.doh.CloseIdleConnections()
	}
	for {
		select {
		case c := <-d.idle:
			c.Close()
		default:
			return
		}
	}
}

// dnsPacketConn is the UDP session tun2socks sees for one app's socket to
// port 53. Each datagram written becomes a TCP query; each answer is handed
// back as though it came from the address the app asked.
type dnsPacketConn struct {
	client  *dnsClient
	answers chan answer

	ctx    context.Context
	cancel context.CancelFunc

	mu       sync.Mutex
	deadline time.Time
	moved    chan struct{} // closed, and replaced, whenever the deadline changes
}

type answer struct {
	msg  []byte
	from net.Addr
}

func newDNSPacketConn(c *dnsClient) *dnsPacketConn {
	ctx, cancel := context.WithCancel(context.Background())
	return &dnsPacketConn{client: c, answers: make(chan answer, 8), ctx: ctx, cancel: cancel, moved: make(chan struct{})}
}

func (p *dnsPacketConn) WriteTo(b []byte, to net.Addr) (int, error) {
	if p.ctx.Err() != nil {
		return 0, net.ErrClosed
	}
	query := append([]byte(nil), b...)
	go func() {
		ctx, cancel := context.WithTimeout(p.ctx, dnsTimeout)
		defer cancel()
		resp, err := p.client.resolve(ctx, query)
		if err != nil {
			return // the app's resolver retries or times out, as with lost UDP
		}
		select {
		case p.answers <- answer{resp, to}:
		case <-p.ctx.Done():
		}
	}()
	return len(b), nil
}

// ReadFrom waits for an answer until the read deadline, which tun2socks moves forward
// from another goroutine while this one is blocked; a move is noticed, not missed.
func (p *dnsPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	for {
		p.mu.Lock()
		dl, moved := p.deadline, p.moved
		p.mu.Unlock()

		var timeout <-chan time.Time
		var t *time.Timer
		if !dl.IsZero() {
			d := time.Until(dl)
			if d <= 0 {
				return 0, nil, os.ErrDeadlineExceeded
			}
			t = time.NewTimer(d)
			timeout = t.C
		}
		select {
		case a := <-p.answers:
			stop(t)
			return copy(b, a.msg), a.from, nil
		case <-timeout:
			return 0, nil, os.ErrDeadlineExceeded
		case <-p.ctx.Done():
			stop(t)
			return 0, nil, net.ErrClosed
		case <-moved:
			stop(t) // look again, at the new deadline
		}
	}
}

func stop(t *time.Timer) {
	if t != nil {
		t.Stop()
	}
}

func (p *dnsPacketConn) Close() error { p.cancel(); return nil }

func (p *dnsPacketConn) LocalAddr() net.Addr { return &net.UDPAddr{} }

func (p *dnsPacketConn) SetDeadline(t time.Time) error { return p.SetReadDeadline(t) }

func (p *dnsPacketConn) SetReadDeadline(t time.Time) error {
	p.mu.Lock()
	p.deadline = t
	close(p.moved)
	p.moved = make(chan struct{})
	p.mu.Unlock()
	return nil
}

func (p *dnsPacketConn) SetWriteDeadline(time.Time) error { return nil }

var errUDPBlocked = errors.New("UDP other than DNS is blocked: it cannot follow a chain, and sending it direct would leak your address")
