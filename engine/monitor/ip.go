package monitor

import "net/netip"

// Addresses are keyed as netip prints them, which is how tun2socks' metadata
// prints destinations, so the two always agree.
func netipFrom4(b [4]byte) string   { return netip.AddrFrom4(b).String() }
func netipFrom16(b [16]byte) string { return netip.AddrFrom16(b).Unmap().String() }
