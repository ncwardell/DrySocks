package chain

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"io"
	"net"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Server is a minimal SOCKS5 server, CONNECT only, that sends every connection
// through a chain. It exists so Tor can sit in the middle of a chain: Tor's
// Socks5Proxy points here, and here dials through the hops that come before Tor.
//
// It listens on loopback, where every app on the phone can reach it and where
// traffic bypasses the VPN; so it demands a username and password, random per
// session, that only Tor is given. Without them any app could use it to skip Tor.
type Server struct {
	Chain *Chain
	// DialTimeout bounds each dial through the chain.
	DialTimeout time.Duration
	// User and Pass are set by Listen.
	User, Pass string

	ln    net.Listener
	wg    sync.WaitGroup
	once  sync.Once
	mu    sync.Mutex
	conns map[net.Conn]struct{}
}

// Listen starts serving on 127.0.0.1 at a free port, which it returns.
func (s *Server) Listen() (int, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	s.ln = ln
	s.User, s.Pass = randomToken(), randomToken()
	s.conns = map[net.Conn]struct{}{}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			if !s.track(c, true) {
				c.Close()
				return
			}
			s.wg.Add(1)
			go func() {
				defer s.wg.Done()
				defer s.track(c, false)
				s.serve(c)
			}()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, nil
}

// Close stops listening and ends every tunnel still open through it: a hop the
// user has just removed must not keep carrying Tor's long-lived connections.
func (s *Server) Close() {
	s.once.Do(func() {
		if s.ln != nil {
			s.ln.Close()
		}
		s.mu.Lock()
		for c := range s.conns {
			c.Close()
		}
		s.mu.Unlock()
		s.wg.Wait()
	})
}

func (s *Server) track(c net.Conn, open bool) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.conns == nil {
		return false // closed
	}
	if open {
		s.conns[c] = struct{}{}
	} else {
		delete(s.conns, c)
	}
	return true
}

func randomToken() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func (s *Server) serve(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(30 * time.Second))

	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil || hdr[0] != 0x05 {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}
	// Username and password (RFC 1929), nothing else.
	if !slices.Contains(methods, 0x02) {
		c.Write([]byte{0x05, 0xff})
		return
	}
	c.Write([]byte{0x05, 0x02})
	var v [2]byte
	if _, err := io.ReadFull(c, v[:]); err != nil || v[0] != 0x01 {
		return
	}
	user := make([]byte, v[1])
	if _, err := io.ReadFull(c, user); err != nil {
		return
	}
	if _, err := io.ReadFull(c, v[:1]); err != nil {
		return
	}
	pass := make([]byte, v[0])
	if _, err := io.ReadFull(c, pass); err != nil {
		return
	}
	okUser := subtle.ConstantTimeCompare(user, []byte(s.User)) == 1
	okPass := subtle.ConstantTimeCompare(pass, []byte(s.Pass)) == 1
	if !okUser || !okPass {
		c.Write([]byte{0x01, 0x01})
		return
	}
	c.Write([]byte{0x01, 0x00})

	var req [4]byte
	if _, err := io.ReadFull(c, req[:]); err != nil {
		return
	}
	if req[1] != 0x01 {
		reply(c, 0x07) // command not supported
		return
	}
	var host string
	switch req[3] {
	case 0x01:
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 0x04:
		b := make([]byte, 16)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 0x03:
		var n [1]byte
		if _, err := io.ReadFull(c, n[:]); err != nil {
			return
		}
		b := make([]byte, n[0])
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		host = string(b)
		// A name goes on into the next hop's request line; nothing that could end or
		// extend that line gets through.
		if strings.IndexFunc(host, func(r rune) bool { return r <= ' ' || r == 0x7f }) >= 0 {
			reply(c, 0x08)
			return
		}
	default:
		reply(c, 0x08)
		return
	}
	var port [2]byte
	if _, err := io.ReadFull(c, port[:]); err != nil {
		return
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port[:]))))

	timeout := s.DialTimeout
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	up, err := s.Chain.DialContext(ctx, target)
	cancel()
	if err != nil {
		reply(c, 0x05) // connection refused: as good a summary as any
		return
	}
	defer up.Close()
	// Both ends are tracked, so Close can end the tunnel from either side: closing only
	// the client leaves the copy from upstream blocked on a read.
	if !s.track(up, true) {
		return
	}
	defer s.track(up, false)
	if err := reply(c, 0x00); err != nil {
		return
	}
	c.SetDeadline(time.Time{})

	done := make(chan struct{})
	go func() {
		io.Copy(up, c)
		if tc, ok := up.(interface{ CloseWrite() error }); ok {
			tc.CloseWrite()
		}
		close(done)
	}()
	io.Copy(c, up)
	c.Close()
	<-done
}

func reply(c net.Conn, code byte) error {
	_, err := c.Write([]byte{0x05, code, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}
