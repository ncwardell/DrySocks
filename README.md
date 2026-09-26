# SocksChain

A modern rebuild of SocksDroid: sends a phone's traffic through a proxy, without root, using
Android's `VpnService`. Unlike SocksDroid it takes a **chain** of proxies — SOCKS5, SOCKS4a,
HTTP CONNECT, and a built-in Tor — each one reached through the ones before it. It can also
find working free proxies for you: see *Free proxies*.

```
apps ─► TUN ─► gVisor netstack ─► chain dialer ─► hop 1 ─► hop 2 ─► … ─► target
               (tun2socks, Go)    (engine/chain)
```

## Layout

| Path | What it is |
|---|---|
| `engine/` | Go. `chain/` dials through the hops and serves Tor's upstream; `vpn/` runs tun2socks' netstack on the TUN fd and carries DNS over TCP; `monitor/` records connections and lookups and holds the blocklist; `scrape/` fetches and verifies free proxies; `mobile/` is the surface gomobile binds. |
| `core/` | Pure Kotlin/JVM: profiles, hop URLs, validation, IPv4 route maths. Unit-tested without a device. |
| `app/` | The Android app: `ChainVpnService`, `TorController`, Quick Settings tile, Compose UI. |

## Build

```sh
nix develop                          # Go, JDK 17, Gradle
(cd engine && go test -race ./...)   # the chain, DNS and engine
gradle :core:test

nix develop .#android                # adds SDK 37.1, build-tools 37, NDK r28
engine/build-aar.sh                  # → app/libs/engine.aar (not checked in)
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `.aar` must be rebuilt whenever anything under `engine/` changes; Gradle does not know
about it.

## How a connection travels

- **TCP**: tun2socks hands each connection to `chainProxy`, which dials hop 1 directly and
  asks each hop in turn to connect to the next, the last to the destination. Hop addresses
  after the first are sent as names, so each hop resolves the next.
- **DNS**: any UDP to port 53, whatever address the app used, is answered by sending the query
  over TCP through the chain to the profile's resolver. Tunnels are pooled, because each costs
  one handshake per hop.
- **Other UDP is dropped.** SOCKS5 UDP ASSOCIATE cannot follow a chain, and sending UDP direct
  would leak the phone's address. QUIC falls back to TCP on its own.
- **This app is outside its own VPN**, so its socket to hop 1 goes to the real network and
  "Test chain" measures the chain rather than the VPN.

## Tor

Tor is built in: Guardian Project's `tor-android`, the same build Orbot ships, running in
this app's process. That placement matters twice over — the app is outside its own VPN, so
Tor's connections cannot loop back into the tunnel, and Android exempts a VPN app from its
own lockdown, so Tor keeps working with *Block connections without VPN* on.

A Tor hop can go **anywhere** in a chain, once:

- **Hops after Tor** are reached through Tor, the usual *Tor → proxy* shape: sites see the
  proxy, the proxy sees a Tor exit.
- **Hops before Tor** carry Tor's own connections. The engine serves them to Tor as a local
  SOCKS5 upstream (`Socks5Proxy`), which helps where Tor itself is blocked.

The engine starts as soon as Tor's SOCKS port is open; Tor holds connections until it has a
circuit, and the engine now waits 30 s for a dial rather than tun2socks' 5 s. "Test chain"
waits for a circuit first. Bridges and pluggable transports are not wired up yet.

tor-android requires compileSdk 37.1, which is why the build is on AGP 9.4 and Gradle 9.

## Free proxies

The globe on the Home screen scrapes public free-proxy lists (monosans, TheSpeedX, proxifly,
jetkai, proxyscrape — editable), then tests candidates in parallel until it has as many as
asked for. A candidate passes only if a **TLS handshake through it to Cloudflare verifies**:
a proxy that intercepts TLS fails. The same request returns the address and country it
leaves from, which is not always its own (backconnect pools). Passing proxies can be added to
the end of any chain, or start a new one.

By default the lists are fetched and every candidate dialled **through Tor**, so neither the
list hosts nor the proxies — some of them run to harvest exactly this — see the phone.

Free proxies are strangers' machines: some log, some are honeypots, some are hijacked. The
TLS check means they cannot read or alter HTTPS, but they see where you connect. Put them
after Tor. `go test -tags live -v -run Live ./scrape` runs the real thing from a desktop.

## Network view

The hub icon on Home. While the VPN runs it shows every connection through it — which app
opened it (Android tells the active VPN app, API 29+), the name behind the address (learned
from the DNS answers the engine relays), bytes each way, open, closed, failed or blocked —
plus live throughput, every DNS lookup with its answers, and an app → destination graph.

**Blocking** takes domains (a domain covers every name under it), IP addresses and CIDR
ranges, from the Blocklist tab or by tapping a connection, lookup or graph node. Blocked
names get NXDOMAIN without leaving the phone; connections to blocked addresses, or to
addresses DNS tied to a blocked name, are refused. Apps with their own encrypted DNS slip
past name blocks, not address blocks.

The record is held in memory only, bounded (last 300 connections and lookups), and dropped
when the VPN stops: it is a browsing history, and nothing writes it anywhere.

There is no "inbound" in the sense of connections arriving: behind the chain nothing on the
internet can reach the phone. Down is the traffic coming back on connections apps opened.

## On at boot, and nothing outside the VPN

These are Android's settings, not the app's — only a device-owner app can set them for
another — so the Home screen shows their state and links to them:

> Settings → Network → VPN → ⚙ next to SocksChain → **Always-on VPN** and
> **Block connections without VPN**

With both on, Android starts the chain at boot (after first unlock), and until it is up —
or with no data connection at all — nothing leaves the phone. SocksChain adds its own
fail-closed on top: when always-on starts it and the chain cannot come up (a bad profile,
Orbot missing), it holds a VPN that drops everything and says why, instead of stopping and
letting traffic out. With always-on but without the block setting, that is the only thing
standing between a broken chain and a leak.

Lockdown has costs, which the Home screen names for the selected chain: apps left out of the
chain, and possibly the bypassed LAN, get no network at all. SocksChain itself — hop 1, the
built-in Tor, the scraper — keeps working: Android exempts a VPN app from its own lockdown.

## Known limits

- Destinations reach the chain as **IP addresses**, since the TUN sees packets, not names.
  So `.onion` addresses do not resolve, even through Tor, and the exit cannot pick its own nearest server for
  a name. Fake-IP DNS would fix both; it is the next piece of design.
- Proxy passwords are stored unencrypted in app-private storage (excluded from backup).
- Hops are reordered with arrows, not drag and drop.
- Nothing here changes what apps say about the device from inside their own encrypted
  connections — user agent, screen size, model. See `docs/design.md`, *Fingerprinting*.
