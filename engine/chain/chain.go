package chain

import (
	"context"
	"errors"
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

	retry bool // marks the start of a retry, not a leg
}

// Probe dials like DialContext but also times every leg. On failure the steps
// that did succeed are returned, which says which hop broke the chain. Steps
// name the proxy a rotating hop actually used.
func (c *Chain) Probe(ctx context.Context, target string) (net.Conn, []Step, error) {
	var steps []Step
	conn, err := c.dial(ctx, target, func(s Step) {
		if s.retry {
			steps = nil // the failed attempt's legs say nothing about this one
			return
		}
		steps = append(steps, s)
	})
	return conn, steps, err
}

// A dead free proxy usually fails by hanging, not refusing. With a rotating hop
// in the chain each attempt gets its own deadline, so a hang costs one attempt
// rather than the whole dial, and the next proxy still has time.
const (
	attemptTimeout = 6 * time.Second // working free proxies answer in under 3
	maxAttempts    = 3
)

// dial resolves rotating hops to their pools' current proxies and dials. If a
// pool's proxy is to blame for a failure it is benched and the dial is tried
// again, on the pool's next proxy, up to maxAttempts in all.
func (c *Chain) dial(ctx context.Context, target string, onStep func(Step)) (net.Conn, error) {
	if len(c.Hops) == 0 {
		return nil, errNoHops
	}
	rotating := false
	for _, h := range c.Hops {
		rotating = rotating || h.Kind == Rotating
	}
	for attempt := 0; ; attempt++ {
		hops := make([]Hop, len(c.Hops))
		for i, h := range c.Hops {
			if h.Kind != Rotating {
				hops[i] = h
				continue
			}
			cur, err := h.Pool.Current()
			if err != nil {
				return nil, err
			}
			hops[i] = cur
		}

		actx, cancel := ctx, context.CancelFunc(func() {})
		if rotating && attempt < maxAttempts-1 {
			actx, cancel = context.WithTimeout(ctx, attemptTimeout)
		}
		conn, failedAt, err := c.dialOnce(actx, hops, target, onStep)
		cancel()
		if err == nil {
			return conn, nil
		}
		// Blame: the hop whose own handshake broke, or — when it answered but could not
		// reach what came next — the next hop. The target is nobody's pool's fault, and
		// a caller giving up is nobody's fault at all.
		if ctx.Err() != nil {
			return nil, err
		}
		blame := failedAt
		var ne *nextError
		switch {
		case failedAt < 0:
			blame = 0 // the TCP connect to the first hop
		case errors.As(err, &ne):
			blame = failedAt + 1 // == len(c.Hops) when it was the target
		}
		if blame >= len(c.Hops) || c.Hops[blame].Kind != Rotating {
			return nil, err
		}
		c.Hops[blame].Pool.Failed(hops[blame].Addr)
		if attempt >= maxAttempts-1 {
			return nil, err
		}
		if onStep != nil {
			onStep(Step{retry: true})
		}
	}
}

// dialOnce dials through concrete hops. failedAt is the hop whose handshake
// failed, or -1 if the connect to the first hop did.
func (c *Chain) dialOnce(ctx context.Context, hops []Hop, target string, onStep func(Step)) (net.Conn, int, error) {
	start := time.Now()
	conn, err := c.Dialer.DialContext(ctx, "tcp", hops[0].Addr)
	if err != nil {
		return nil, -1, err
	}
	if onStep != nil {
		onStep(Step{To: hops[0].Addr, Took: time.Since(start)})
	}

	// Handshakes are plain blocking reads; tie them to ctx by moving the
	// deadline into the past when ctx ends.
	if d, ok := ctx.Deadline(); ok {
		conn.SetDeadline(d)
	}
	raw := conn // conn is rewrapped below; the callback must not race that
	stop := context.AfterFunc(ctx, func() { raw.SetDeadline(time.Unix(1, 0)) })

	for i, h := range hops {
		next := target
		if i+1 < len(hops) {
			next = hops[i+1].Addr
		}
		start = time.Now()
		tunneled, err := h.connect(conn, next)
		if err != nil {
			stop()
			conn.Close()
			if ctx.Err() != nil {
				err = ctx.Err()
			}
			return nil, i, err
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
		return nil, len(hops) - 1, ctx.Err()
	}
	conn.SetDeadline(time.Time{})
	return conn, 0, nil
}
