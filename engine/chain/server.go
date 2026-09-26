package chain

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

// Server is a minimal SOCKS5 server, CONNECT only, no auth, that sends every
// connection through a chain. It exists so Tor can sit in the middle of a
// chain: Tor's Socks5Proxy points here, and here dials through the hops that
// come before Tor. Bind it to loopback only; it asks no password.
type Server struct {
	Chain *Chain
	// DialTimeout bounds each dial through the chain.
	DialTimeout time.Duration

	ln   net.Listener
	wg   sync.WaitGroup
	once sync.Once
}

// Listen starts serving on 127.0.0.1 at a free port, which it returns.
func (s *Server) Listen() (int, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	s.ln = ln
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.serve(c)
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, nil
}

func (s *Server) Close() {
	s.once.Do(func() {
		if s.ln != nil {
			s.ln.Close()
		}
		s.wg.Wait()
	})
}

func (s *Server) serve(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(30 * time.Second))

	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil || hdr[0] != 0x05 {
		return
	}
	if _, err := io.CopyN(io.Discard, c, int64(hdr[1])); err != nil {
		return
	}
	c.Write([]byte{0x05, 0x00})

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
