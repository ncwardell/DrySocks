package chain

import (
	"context"
	"net"
	"time"
)

// Chain dials through its hops in order. The first hop is dialled directly; the
// app keeps its own traffic out of the VPN, so that socket goes to the network.
type Chain struct {
	Hops   []Hop
	Dialer net.Dialer
}

// DialContext opens a TCP tunnel to target (host:port) through every hop.
func (c *Chain) DialContext(ctx context.Context, target string) (net.Conn, error) {
	return c.dial(ctx, target, nil)
}

// Step is how long one leg of a chain took to come up: leg 0 is the TCP
// connect to the first hop, leg i the handshake that reached hop i (or, for
// the last leg, the target).
type Step struct {
	Via  Hop
	To   string
	Took time.Duration
}

// Probe dials like DialContext but also times every leg. On failure the steps
// that did succeed are returned, which says which hop broke the chain.
func (c *Chain) Probe(ctx context.Context, target string) (net.Conn, []Step, error) {
	var steps []Step
	conn, err := c.dial(ctx, target, func(s Step) { steps = append(steps, s) })
	return conn, steps, err
}

func (c *Chain) dial(ctx context.Context, target string, onStep func(Step)) (net.Conn, error) {
	if len(c.Hops) == 0 {
		return nil, errNoHops
	}

	start := time.Now()
	conn, err := c.Dialer.DialContext(ctx, "tcp", c.Hops[0].Addr)
	if err != nil {
		return nil, err
	}
	if onStep != nil {
		onStep(Step{To: c.Hops[0].Addr, Took: time.Since(start)})
	}

	// Handshakes are plain blocking reads; tie them to ctx by moving the
	// deadline into the past when ctx ends.
	if d, ok := ctx.Deadline(); ok {
		conn.SetDeadline(d)
	}
	raw := conn // conn is rewrapped below; the callback must not race that
	stop := context.AfterFunc(ctx, func() { raw.SetDeadline(time.Unix(1, 0)) })

	for i, h := range c.Hops {
		next := target
		if i+1 < len(c.Hops) {
			next = c.Hops[i+1].Addr
		}
		start = time.Now()
		tunneled, err := h.connect(conn, next)
		if err != nil {
			stop()
			conn.Close()
			if ctx.Err() != nil {
				err = ctx.Err()
			}
			return nil, err
		}
		conn = tunneled
		if onStep != nil {
			onStep(Step{Via: h, To: next, Took: time.Since(start)})
		}
	}

	if !stop() {
		// ctx ended after the last handshake, so the deadline is already in
		// the past; the tunnel is useless.
		conn.Close()
		return nil, ctx.Err()
	}
	conn.SetDeadline(time.Time{})
	return conn, nil
}
