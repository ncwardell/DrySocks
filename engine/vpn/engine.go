// Package vpn runs the gVisor netstack on the VPN's TUN device and sends every
// TCP connection out through a chain.
package vpn

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"time"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"github.com/xjasonlyu/tun2socks/v2/log"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"

	"drysocks/engine/chain"
	"drysocks/engine/monitor"
)

// Config is what the app sends across, as JSON.
type Config struct {
	Hops []string `json:"hops"`
	// DNS is the resolver queries are sent to, reached from the last hop:
	// host:port for DNS over TCP, or an https:// URL for DNS over HTTPS.
	DNS string `json:"dns"`
	MTU int    `json:"mtu"`
	// TunDNS is the DNS address the VPN gives Android, inside the tunnel. It is
	// not on the internet, so connections to it are answered here; see DialContext.
	TunDNS string `json:"tunDns"`
}

type Engine struct {
	dev    device.Device
	stack  *stack.Stack
	tunnel *tunnel.Tunnel
	dns    *dnsClient
	stop   context.CancelFunc
}

// Start takes ownership of fd, the TUN descriptor from VpnService, and closes
// it on Stop, or at once if Start fails. Left open after a failure, the VPN
// would stay up with nothing reading it, and every app would go dark.
//
// mon records connections and lookups and holds the blocklist; nil disables
// both.
func Start(fd int, cfg Config, mon *monitor.Monitor) (e *Engine, err error) {
	owned := false // true once fdbased.Open has it; from then, dev.Close closes it
	defer func() {
		if err != nil && !owned {
			unix.Close(fd)
		}
	}()

	hops, err := chain.ParseHops(cfg.Hops)
	if err != nil {
		return nil, err
	}
	if len(hops) == 0 {
		return nil, errors.New("no hops configured")
	}
	if u, err := url.Parse(cfg.DNS); err == nil && u.Scheme == "https" && u.Host != "" {
		// DNS over HTTPS
	} else if _, _, err := net.SplitHostPort(cfg.DNS); err != nil {
		return nil, errors.New("dns: want host:port or an https:// URL, got " + strconv.Quote(cfg.DNS))
	}
	mtu := cfg.MTU
	if mtu == 0 {
		mtu = 1500
	}

	log.SetLogger(log.Must(log.NewLeveled(log.WarnLevel)))

	if mon == nil {
		mon = monitor.New(nil)
	}
	c := &chain.Chain{Hops: hops}
	p := &chainProxy{chain: c, dns: newDNSClient(c, cfg.DNS, mon), mon: mon}
	if cfg.TunDNS != "" {
		if p.tunDNS, err = netip.ParseAddr(cfg.TunDNS); err != nil {
			return nil, errors.New("tunDns: " + err.Error())
		}
	}

	dev, err := fdbased.Open(strconv.Itoa(fd), uint32(mtu), 0)
	if err != nil {
		return nil, err
	}
	owned = true

	t := tunnel.New(p, statistic.DefaultManager)
	t.ProcessAsync()

	st, err := core.CreateStack(&core.Config{LinkEndpoint: dev, TransportHandler: t})
	if err != nil {
		t.Close()
		dev.Close()
		return nil, err
	}
	ctx, stop := context.WithCancel(context.Background())
	go checkPools(ctx, hops)
	return &Engine{dev: dev, stack: st, tunnel: t, dns: p.dns, stop: stop}, nil
}

// checkPools keeps rotating hops' pools honest while the engine runs: every
// proxy is tried from where its hop sits in the chain, at start and then every
// poolCheckEvery, and the dead ones are benched before traffic meets them.
func checkPools(ctx context.Context, hops []chain.Hop) {
	for {
		for i, h := range hops {
			if h.Kind == chain.Rotating && !h.Pool.Paused() {
				h.Pool.Check(ctx, hops[:i], poolCheckTarget, 16, 6*time.Second)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(poolCheckEvery):
		}
	}
}

const (
	poolCheckEvery = 2 * time.Minute
	// Cloudflare's DNS on 443: always up, and on the port proxies allow.
	poolCheckTarget = "1.1.1.1:443"
)

// WarmDNS makes one lookup through the chain, so the tunnel to the resolver is up
// before the first app asks. Call it once the chain can carry traffic.
func (e *Engine) WarmDNS(ctx context.Context) error { return e.dns.warm(ctx) }

func (e *Engine) Stop() {
	e.stop()
	e.dev.Close()
	e.stack.Close()
	e.stack.Wait()
	e.tunnel.Close()
	e.dns.close()
}

// Traffic returns bytes sent and received since the process started.
func Traffic() (up, down int64) {
	s := statistic.DefaultManager.Snapshot()
	return s.UploadTotal, s.DownloadTotal
}

type chainProxy struct {
	chain  *chain.Chain
	dns    *dnsClient
	mon    *monitor.Monitor
	tunDNS netip.Addr
}

var errNotHere = errors.New("nothing listens on the tunnel's DNS address but port 53")

var _ proxy.Proxy = (*chainProxy)(nil)

// dialTimeout replaces tun2socks' own 5 s connect deadline, which a chain with
// Tor in it, or a few slow hops, cannot meet. By the time tun2socks dials, the
// app's side of the TCP handshake is already done, so the app just waits.
const dialTimeout = 30 * time.Second

func (p *chainProxy) DialContext(ctx context.Context, m *M.Metadata) (net.Conn, error) {
	// The tunnel's DNS address. TCP 53 is DNS over TCP, answered here like the
	// UDP kind, so it is filtered and logged the same way. Anything else is
	// refused at once: Android's Private DNS probes port 853 there, and sent
	// into the chain that probe hangs until it times out, stalling lookups.
	if p.tunDNS.IsValid() && m.DstIP == p.tunDNS {
		if m.DstPort != 53 {
			return nil, errNotHere
		}
		return p.dns.serveTCP(), nil
	}

	e := p.mon.Open("tcp", m.SourceAddress(), m.DestinationAddress())
	// Blocked by address, or by name when DNS told us the name: the name check
	// catches apps that looked it up before it was blocked, or cached it.
	rule := p.mon.Block.MatchIP(m.DstIP)
	if rule == "" {
		rule = p.mon.Block.Match(e.Host())
	}
	if rule != "" {
		err := errors.New("blocked: " + rule)
		e.Close(err, true)
		return nil, err
	}

	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), dialTimeout)
	defer cancel()
	conn, err := p.chain.DialContext(ctx, m.DestinationAddress())
	if err != nil {
		e.Close(err, false)
		return nil, err
	}
	return e.Wrap(conn), nil
}

// DialUDP answers port 53, whatever address the app aimed at, from the
// configured resolver; that also catches apps with a hard-coded 8.8.8.8.
// Everything else is refused.
func (p *chainProxy) DialUDP(m *M.Metadata) (net.PacketConn, error) {
	if m.DstPort == 53 {
		return newDNSPacketConn(p.dns), nil
	}
	p.mon.DroppedUDP()
	return nil, errUDPBlocked
}
