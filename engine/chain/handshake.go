package chain

import (
	"bufio"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
)

// connect asks the proxy at the far end of conn to open a tunnel to target
// (host:port). The returned conn carries the tunnel; it may wrap conn when the
// handshake read past its own end.
func (h Hop) connect(conn net.Conn, target string) (net.Conn, error) {
	var err error
	switch h.Kind {
	case SOCKS5:
		err = socks5Connect(conn, h, target)
	case SOCKS4:
		err = socks4Connect(conn, h, target)
	case HTTP:
		return httpConnect(conn, h, target)
	default:
		err = fmt.Errorf("unsupported hop kind %d", h.Kind)
	}
	if err != nil {
		return nil, err
	}
	return conn, nil
}

// nextError marks a handshake failure where the proxy did its part — it spoke the
// protocol and answered — but could not reach what it was asked to connect to. Blame
// for it belongs to whatever is next, not to the proxy.
type nextError struct{ err error }

func (e *nextError) Error() string { return e.err.Error() }
func (e *nextError) Unwrap() error { return e.err }

func next(err error) error { return &nextError{err} }

func splitTarget(target string) (string, uint16, error) {
	host, port, err := net.SplitHostPort(target)
	if err != nil {
		return "", 0, err
	}
	p, err := strconv.ParseUint(port, 10, 16)
	if err != nil {
		return "", 0, fmt.Errorf("bad port in %q", target)
	}
	return host, uint16(p), nil
}

// --- SOCKS5, RFC 1928 and RFC 1929 ---------------------------------------------

func socks5Connect(conn net.Conn, h Hop, target string) error {
	host, port, err := splitTarget(target)
	if err != nil {
		return err
	}

	methods := []byte{0x00}
	if h.User != "" {
		methods = []byte{0x00, 0x02}
	}
	if _, err := conn.Write(append([]byte{0x05, byte(len(methods))}, methods...)); err != nil {
		return err
	}

	var sel [2]byte
	if _, err := io.ReadFull(conn, sel[:]); err != nil {
		return err
	}
	if sel[0] != 0x05 {
		return fmt.Errorf("%s: not a SOCKS5 server", h)
	}
	switch sel[1] {
	case 0x00:
	case 0x02:
		if h.User == "" {
			return fmt.Errorf("%s: server wants a password", h)
		}
		if len(h.User) > 255 || len(h.Pass) > 255 {
			return fmt.Errorf("%s: credentials longer than 255 bytes", h)
		}
		req := []byte{0x01, byte(len(h.User))}
		req = append(req, h.User...)
		req = append(req, byte(len(h.Pass)))
		req = append(req, h.Pass...)
		if _, err := conn.Write(req); err != nil {
			return err
		}
		var st [2]byte
		if _, err := io.ReadFull(conn, st[:]); err != nil {
			return err
		}
		if st[1] != 0x00 {
			return fmt.Errorf("%s: authentication rejected", h)
		}
	case 0xff:
		return fmt.Errorf("%s: no acceptable auth method", h)
	default:
		return fmt.Errorf("%s: server chose unknown method %#x", h, sel[1])
	}

	req := []byte{0x05, 0x01, 0x00}
	if ip, err := netip.ParseAddr(host); err == nil {
		if ip.Is4() || ip.Is4In6() {
			b := ip.Unmap().As4()
			req = append(append(req, 0x01), b[:]...)
		} else {
			b := ip.As16()
			req = append(append(req, 0x04), b[:]...)
		}
	} else {
		if len(host) > 255 {
			return fmt.Errorf("hostname too long: %q", host)
		}
		req = append(append(req, 0x03, byte(len(host))), host...)
	}
	req = binary.BigEndian.AppendUint16(req, port)
	if _, err := conn.Write(req); err != nil {
		return err
	}

	// VER REP RSV ATYP, then a bound address we have to read past and ignore. The
	// proxy has answered the greeting by now, so silence or a refusal here is about
	// the target — except refusals of the request itself.
	var rep [4]byte
	if _, err := io.ReadFull(conn, rep[:]); err != nil {
		return next(fmt.Errorf("%s: connect %s: %w", h, target, err))
	}
	if rep[1] != 0x00 {
		err := fmt.Errorf("%s: connect %s: %s", h, target, socks5Reply(rep[1]))
		switch rep[1] {
		case 0x02, 0x07, 0x08: // not allowed, command or address type unsupported: the proxy's own doing
			return err
		}
		return next(err)
	}
	var skip int
	switch rep[3] {
	case 0x01:
		skip = 4
	case 0x04:
		skip = 16
	case 0x03:
		var n [1]byte
		if _, err := io.ReadFull(conn, n[:]); err != nil {
			return err
		}
		skip = int(n[0])
	default:
		return fmt.Errorf("%s: bad address type %#x in reply", h, rep[3])
	}
	_, err = io.CopyN(io.Discard, conn, int64(skip+2))
	return err
}

func socks5Reply(code byte) string {
	switch code {
	case 0x01:
		return "general failure"
	case 0x02:
		return "not allowed by ruleset"
	case 0x03:
		return "network unreachable"
	case 0x04:
		return "host unreachable"
	case 0x05:
		return "connection refused"
	case 0x06:
		return "TTL expired"
	case 0x07:
		return "command not supported"
	case 0x08:
		return "address type not supported"
	}
	return fmt.Sprintf("error %#x", code)
}

// --- SOCKS4a -------------------------------------------------------------------

func socks4Connect(conn net.Conn, h Hop, target string) error {
	host, port, err := splitTarget(target)
	if err != nil {
		return err
	}

	req := []byte{0x04, 0x01}
	req = binary.BigEndian.AppendUint16(req, port)

	var name string
	if ip, err := netip.ParseAddr(host); err == nil {
		if !ip.Unmap().Is4() {
			return fmt.Errorf("%s: SOCKS4 cannot reach IPv6 %s", h, host)
		}
		b := ip.Unmap().As4()
		req = append(req, b[:]...)
	} else {
		// 0.0.0.x with x != 0 tells a 4a server the name follows the user id.
		req = append(req, 0, 0, 0, 1)
		name = host
	}
	req = append(append(req, h.User...), 0)
	if name != "" {
		req = append(append(req, name...), 0)
	}
	if _, err := conn.Write(req); err != nil {
		return err
	}

	var rep [8]byte
	if _, err := io.ReadFull(conn, rep[:]); err != nil {
		return err
	}
	if rep[1] != 0x5a {
		return next(fmt.Errorf("%s: connect %s: rejected (%#x)", h, target, rep[1]))
	}
	return nil
}

// --- HTTP CONNECT --------------------------------------------------------------

func httpConnect(conn net.Conn, h Hop, target string) (net.Conn, error) {
	if strings.ContainsAny(target, "\r\n ") {
		return nil, fmt.Errorf("%s: bad target %q", h, target)
	}
	req := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n"
	if h.User != "" {
		cred := base64.StdEncoding.EncodeToString([]byte(h.User + ":" + h.Pass))
		req += "Proxy-Authorization: Basic " + cred + "\r\n"
	}
	req += "\r\n"
	if _, err := io.WriteString(conn, req); err != nil {
		return nil, err
	}

	// Headers come through a 16 KB limit: a hostile "proxy" streaming one endless
	// header line would otherwise grow memory until the per-check deadline.
	limited := &io.LimitedReader{R: conn, N: maxHeaderBytes}
	br := bufio.NewReader(limited)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		return nil, fmt.Errorf("%s: %w", h, err)
	}
	if resp.StatusCode/100 != 2 {
		resp.Body.Close()
		err := fmt.Errorf("%s: connect %s: %s", h, target, resp.Status)
		switch resp.StatusCode {
		case http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
			return nil, next(err) // it tried, and could not get there
		}
		return nil, err
	}
	// A 2xx to CONNECT has no body; whatever follows the headers is the tunnel, and a
	// server may have sent some of it already. Body.Close would drain it, so is not called.
	return &bufferedConn{Conn: conn, r: br}, nil
}

const maxHeaderBytes = 16 << 10

// bufferedConn reads what the handshake buffered first, then the conn itself: the
// buffer sits on a size-limited reader, which the tunnel must not stay behind.
type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) {
	if c.r != nil && c.r.Buffered() > 0 {
		return c.r.Read(p)
	}
	c.r = nil
	return c.Conn.Read(p)
}

var errNoHops = errors.New("chain has no hops")
