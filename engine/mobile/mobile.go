// Package mobile is the surface gomobile binds for the app. It takes and
// returns only strings and numbers; JSON carries anything with structure.
package mobile

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"golang.org/x/sys/unix"

	"sockschain/engine/chain"
	"sockschain/engine/monitor"
	"sockschain/engine/vpn"
)

var (
	mu      sync.Mutex
	running *vpn.Engine
	// mon is the running engine's record; it goes when the engine stops.
	mon *monitor.Monitor
	// blocklist outlives engines, so the app can set it before Start.
	blocklist = monitor.NewBlocklist(nil)
)

// Start brings the engine up on fd, a detached TUN descriptor it now owns.
// configJSON is {"hops": ["socks5://…", …], "dns": "1.1.1.1:53", "mtu": 1500}.
// On any error fd has been closed; see vpn.Start.
func Start(fd int, configJSON string) error {
	var cfg vpn.Config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		unix.Close(fd)
		return err
	}

	mu.Lock()
	defer mu.Unlock()
	if running != nil {
		unix.Close(fd)
		return errors.New("already running")
	}
	m := monitor.New(blocklist)
	e, err := vpn.Start(fd, cfg, m)
	if err != nil {
		return err
	}
	running, mon = e, m
	return nil
}

// Stop is safe to call when nothing is running.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if running != nil {
		running.Stop()
		running, mon = nil, nil
	}
}

// SetBlocklist replaces the blocked domains; domainsJSON is a JSON array. A
// domain blocks itself and every name under it. Takes effect at once.
func SetBlocklist(domainsJSON string) error {
	var d []string
	if err := json.Unmarshal([]byte(domainsJSON), &d); err != nil {
		return err
	}
	blocklist.Set(d)
	return nil
}

// Snapshot returns the network view's data as JSON: see monitor.Snapshot.
// Empty when the engine is not running; the record does not outlive it.
func Snapshot() string {
	mu.Lock()
	m := mon
	mu.Unlock()
	if m == nil {
		return "{}"
	}
	b, _ := json.Marshal(m.Snapshot())
	return string(b)
}

func BytesUp() int64   { up, _ := vpn.Traffic(); return up }
func BytesDown() int64 { _, down := vpn.Traffic(); return down }

// ProbeResult is what Probe returns, as JSON.
type ProbeResult struct {
	Steps  []ProbeStep `json:"steps"`
	ExitIP string      `json:"exitIp,omitempty"`
	Error  string      `json:"error,omitempty"`
}

type ProbeStep struct {
	To string `json:"to"`
	Ms int64  `json:"ms"`
}

// exitIPHost answers a plain-HTTP GET with the caller's address and nothing
// else. Plain HTTP so the probe needs no TLS stack; it reveals nothing the
// exit hop does not already see.
const exitIPHost = "api.ipify.org"

// Probe dials through the hops in configJSON, times each leg, and asks a
// public service which address the traffic left from. It blocks; call it off
// the main thread.
func Probe(configJSON string) string {
	var cfg vpn.Config
	var res ProbeResult

	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		res.Error = err.Error()
		return encode(res)
	}
	hops, err := chain.ParseHops(cfg.Hops)
	if err != nil {
		res.Error = err.Error()
		return encode(res)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	c := &chain.Chain{Hops: hops}
	conn, steps, err := c.Probe(ctx, exitIPHost+":80")
	for _, s := range steps {
		res.Steps = append(res.Steps, ProbeStep{To: s.To, Ms: s.Took.Milliseconds()})
	}
	if err != nil {
		res.Error = err.Error()
		return encode(res)
	}
	defer conn.Close()

	if d, ok := ctx.Deadline(); ok {
		conn.SetDeadline(d)
	}
	req, _ := http.NewRequest(http.MethodGet, "http://"+exitIPHost+"/", nil)
	req.Close = true
	if err := req.Write(conn); err != nil {
		res.Error = err.Error()
		return encode(res)
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), req)
	if err != nil {
		res.Error = err.Error()
		return encode(res)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64))
	if resp.StatusCode != http.StatusOK {
		res.Error = "exit IP lookup: " + resp.Status
	} else {
		res.ExitIP = strings.TrimSpace(string(body))
	}
	return encode(res)
}

func encode(r ProbeResult) string {
	b, _ := json.Marshal(r)
	return string(b)
}
