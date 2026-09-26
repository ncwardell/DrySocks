package vpn

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"os"
	"sync"
	"time"

	"sockschain/engine/chain"
	"sockschain/engine/monitor"
)

// dnsClient carries DNS over TCP through the chain (RFC 7766), because UDP
// cannot follow a chain. Building a tunnel costs one handshake per hop, so
// idle ones are kept and reused, one query at a time each.
type dnsClient struct {
	chain  *chain.Chain
	server string
	idle   chan net.Conn
	mon    *monitor.Monitor
}

const dnsTimeout = 8 * time.Second

func newDNSClient(c *chain.Chain, server string, mon *monitor.Monitor) *dnsClient {
	if mon == nil {
		mon = monitor.New(nil)
	}
	return &dnsClient{chain: c, server: server, idle: make(chan net.Conn, 4), mon: mon}
}

// resolve is what both DNS paths call: the blocklist, the log, then upstream.
func (d *dnsClient) resolve(ctx context.Context, query []byte) ([]byte, error) {
	return d.mon.Resolve(ctx, query, d.exchange)
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
}

type answer struct {
	msg  []byte
	from net.Addr
}

func newDNSPacketConn(c *dnsClient) *dnsPacketConn {
	ctx, cancel := context.WithCancel(context.Background())
	return &dnsPacketConn{client: c, answers: make(chan answer, 8), ctx: ctx, cancel: cancel}
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

func (p *dnsPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	p.mu.Lock()
	dl := p.deadline
	p.mu.Unlock()

	var timeout <-chan time.Time
	if !dl.IsZero() {
		t := time.NewTimer(time.Until(dl))
		defer t.Stop()
		timeout = t.C
	}
	select {
	case a := <-p.answers:
		return copy(b, a.msg), a.from, nil
	case <-timeout:
		return 0, nil, os.ErrDeadlineExceeded
	case <-p.ctx.Done():
		return 0, nil, net.ErrClosed
	}
}

func (p *dnsPacketConn) Close() error { p.cancel(); return nil }

func (p *dnsPacketConn) LocalAddr() net.Addr { return &net.UDPAddr{} }

func (p *dnsPacketConn) SetDeadline(t time.Time) error { return p.SetReadDeadline(t) }

// SetReadDeadline takes effect on the next ReadFrom, which is how tun2socks
// uses it: it re-arms the deadline before every read.
func (p *dnsPacketConn) SetReadDeadline(t time.Time) error {
	p.mu.Lock()
	p.deadline = t
	p.mu.Unlock()
	return nil
}

func (p *dnsPacketConn) SetWriteDeadline(time.Time) error { return nil }

var errUDPBlocked = errors.New("UDP other than DNS is blocked: it cannot follow a chain, and sending it direct would leak your address")
