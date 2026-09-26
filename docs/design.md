# Design notes and what is next

## Decisions

**Go core, not C.** The alternative was hev-socks5-tunnel (C) with a chaining SOCKS server in
Kotlin: a smaller binary, but two network stacks to maintain and a loopback hop on every
connection. xjasonlyu/tun2socks takes a `proxy.Proxy` interface, so the chain plugs straight
in and the whole thing is one process with no local listener. The cost is ~8 MB per ABI.

**Own handshakes, not tun2socks' proxies.** Its SOCKS/HTTP clients dial their own sockets;
chaining needs a handshake run over an existing `net.Conn`. The three protocols are ~250
lines in `engine/chain/handshake.go` and tested against in-process servers.

**Excluding the app from its own VPN** instead of `VpnService.protect()` per socket. It needs
no callback from Go into Java, and it covers every socket the app opens, including the probe's.

**Fail closed only under always-on.** A failed start the user asked for reports and stops;
one always-on asked for holds a blackhole VPN. Under always-on the user has already said
"through the VPN or not at all"; for a manual connect, silently cutting the phone off over a
typo would be worse than the leak it prevents.

**Tor in-process, not Orbot.** Orbot is another app, so lockdown cut it off, and it had to be
excluded from the VPN by package name. tor-android runs Tor as a service in this process;
Tor anywhere in the chain comes from the engine serving the pre-Tor hops as Tor's
`Socks5Proxy`, changed live with SETCONF rather than by restarting Tor.

**Dial deadline is ours.** tun2socks gives a dial 5 s; the proxy wraps its context with
`context.WithoutCancel` and 30 s. Safe because gVisor has already completed the app's
handshake before the handler runs (`core/tcp.go`, CreateEndpoint then HandleTCP).

**The scraper's pass mark is a verified TLS handshake**, to Cloudflare's trace page, which
also reports the exit address and country in the same response.

**`specialUse` foreground service.** `systemExempted` is meant for VPN apps but crashes on
`startForeground` if the system does not yet consider the app the active VPN; `specialUse`
cannot.

## Fingerprinting

Asked for: hide screen size, device type and the like, or replace them with generics. A VPN
cannot, and this one should not pretend to:

- Those values are sent by the app or page itself, inside TLS. The engine only ever sees
  ciphertext. Rewriting them means decrypting everything: a root CA on the phone (which apps
  targeting Android 7+ ignore unless rooted, and pinned apps reject), and a key in this app
  that can impersonate every site — a worse risk than the fingerprint.
- Screen size and model mostly come from JavaScript at run time, not headers; no proxy sees
  them even for plain HTTP.
- Changing one value while the rest stay real makes a device *more* unique, not less.

What the chain already does at the network level: sites see the exit, not the phone's IP;
the phone's TCP/IP stack is never seen, because the engine re-originates every connection
(sites see the exit's TTL, window and options); DNS follows the chain; and WebRTC/STUN,
being UDP, is dropped rather than leaking the real address.

What does make a phone generic is a browser built for it: Tor Browser (uniform screen sizes
by letterboxing, one user agent, UTC) or Mull/Firefox with resistFingerprinting, run through
this VPN. Recommended in the app, not reimplemented.

## Tested

2026-09-26, Waydroid (Android 13, x86_64), on a network that blocks direct Tor: a free
SOCKS5 proxy from the scraper, then Tor. Tor bootstrapped through the proxy in ~3 s,
restarted cleanly within one process, and apps in the VPN left from listed Tor exits with
DNS resolved through the chain. Found there: Android 13's Private DNS probes port 853 on the
tunnel's DNS address; the engine now refuses that at once instead of sending it into the chain.

## Next

1. **Test the rest on a device**: a Tor → proxy chain;
   boot with always-on + lockdown, airplane mode on then off; a broken profile under
   always-on (expect Blocked); the scraper through Tor; the tile with the app closed.
2. **Fake-IP DNS.** Answer lookups with addresses from 198.18.0.0/15, remember the name, and
   send the name to the chain on connect. Makes `.onion` work through Tor, stops exits from
   seeing only IPs, and saves a DNS round trip through the chain.
3. **Tor bridges** (obfs4, webtunnel, Snowflake) for networks that block Tor, as a
   steadier option than a free proxy in front of Tor. Build lyrebird and the Snowflake
   client into the Go engine (a second gomobile AAR such as IPtProxy would clash with it)
   and point Tor at them with ClientTransportPlugin … socks5 127.0.0.1:PORT.
4. **UDP for single-hop chains** via SOCKS5 UDP ASSOCIATE.
5. **Keystore-encrypted credentials.**
6. Scraper: remember results, re-test saved free hops, drop dead ones from chains.
7. targetSdk 36/37, once tested; drag-and-drop hop order; profile import/export.
