package io.github.ncwardell.drysocks

import android.content.Context
import java.io.DataInputStream
import java.net.InetAddress

/** A country as the map uses it: where to put its dot, and what to call it. */
data class Country(val code: String, val lon: Float, val lat: Float, val name: String)

/**
 * Offline IP → country, from DB-IP's country lite database packed by tools/geo/build.py.
 *
 * Nothing here touches the network: looking up where each address you connect to lives,
 * online, would hand your browsing to whoever answers. Loaded once, on first use, off the
 * main thread; about 5 MB in memory.
 */
object Geo {
    @Volatile private var data: Data? = null

    private class Data(
        val countries: List<Country>,
        val v4Start: IntArray, val v4Country: ByteArray,
        val v6Start: LongArray, val v6Country: ByteArray,
        val land: List<FloatArray>,
    )

    /** The land outlines as polylines of (lon, lat) pairs, for drawing the globe. */
    fun land(context: Context): List<FloatArray> = load(context).land

    fun country(context: Context, code: String): Country? = load(context).countries.firstOrNull { it.code == code }

    /**
     * The country an address is in, or null for names, private and reserved addresses. A
     * host name is never resolved here: resolving it would itself be a lookup that leaks.
     */
    fun locate(context: Context, address: String): Country? {
        val literal = address.removePrefix("[").removeSuffix("]")
        if (!looksLikeIp(literal)) return null
        val ip = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return null
        if (ip.isSiteLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress) return null
        val d = load(context)
        val b = ip.address
        val i = if (b.size == 4) {
            val v = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
                ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
            // Starts are unsigned; compare them as such.
            floor(d.v4Start.size) { Integer.compareUnsigned(d.v4Start[it], v) <= 0 }?.let { d.v4Country[it].toInt() and 0xff }
        } else {
            var top = 0L
            for (k in 0 until 8) top = (top shl 8) or (b[k].toLong() and 0xff)
            floor(d.v6Start.size) { java.lang.Long.compareUnsigned(d.v6Start[it], top) <= 0 }?.let { d.v6Country[it].toInt() and 0xff }
        } ?: return null
        return d.countries.getOrNull(i)?.takeIf { it.code != "ZZ" }
    }

    /** The last index for which [atOrBelow] holds, in a sorted table; null if none. */
    private inline fun floor(size: Int, atOrBelow: (Int) -> Boolean): Int? {
        var lo = 0
        var hi = size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (atOrBelow(mid)) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best.takeIf { it >= 0 }
    }

    private fun looksLikeIp(s: String) = s.isNotEmpty() && (s.all { it.isDigit() || it == '.' } || ':' in s)

    private fun load(context: Context): Data = data ?: synchronized(this) {
        data ?: read(context.applicationContext).also { data = it }
    }

    private fun read(context: Context): Data {
        val assets = context.assets
        val countries = assets.open("geo/countries.txt").bufferedReader().readLines().map {
            val (cc, lon, lat, name) = it.split('\t')
            Country(cc, lon.toFloat(), lat.toFloat(), name)
        }
        val v4 = assets.open("geo/ipv4.bin").readBytes()
        val n4 = v4.size / 5
        val v4Start = IntArray(n4)
        val v4Country = ByteArray(n4)
        for (k in 0 until n4) {
            val o = k * 5
            v4Start[k] = ((v4[o].toInt() and 0xff) shl 24) or ((v4[o + 1].toInt() and 0xff) shl 16) or
                ((v4[o + 2].toInt() and 0xff) shl 8) or (v4[o + 3].toInt() and 0xff)
            v4Country[k] = v4[o + 4]
        }
        val v6 = assets.open("geo/ipv6.bin").readBytes()
        val n6 = v6.size / 9
        val v6Start = LongArray(n6)
        val v6Country = ByteArray(n6)
        for (k in 0 until n6) {
            val o = k * 9
            var x = 0L
            for (j in 0 until 8) x = (x shl 8) or (v6[o + j].toLong() and 0xff)
            v6Start[k] = x
            v6Country[k] = v6[o + 8]
        }
        val land = mutableListOf<FloatArray>()
        DataInputStream(assets.open("geo/land.bin").buffered()).use { inp ->
            while (inp.available() > 0) {
                val count = inp.readUnsignedShort()
                land += FloatArray(count * 2) { inp.readFloat() }
            }
        }
        return Data(countries, v4Start, v4Country, v6Start, v6Country, land)
    }
}
