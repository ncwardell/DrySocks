package com.sockschain.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A public proxy list; mirrors engine/scrape.Source. */
@Serializable
data class ScrapeSource(
    val url: String,
    /** Scheme for lines that are bare ip:port: socks5, socks4a or http. */
    val kind: String = "socks5",
    val enabled: Boolean = true,
)

/** A rotating hop's pool as the engine reports it; mirrors engine/chain.PoolStatus. */
@Serializable
data class PoolState(val size: Int = 0, val healthy: Int = 0, val current: String? = null, val nextInMs: Long = 0) {
    companion object {
        fun parse(s: String): PoolState = json.decodeFromString(serializer(), s)
    }
}

/** What engine/mobile.SetPool takes for one rotating hop. */
@Serializable
data class PoolConfig(val hops: List<String>, val mode: String, val intervalSec: Int, val paused: Boolean = false) {
    fun encode(): String = json.encodeToString(serializer(), this)
}

/** One proxy that passed; mirrors engine/scrape.Result. */
@Serializable
data class ScrapeResult(
    val url: String,
    val ok: Boolean = false,
    val ms: Long = 0,
    val exitIp: String? = null,
    val country: String? = null,
    val error: String? = null,
) {
    val hop: Hop? get() = Hop.fromUrl(url)

    /** The address the proxy was dialled at, when it differs from where it exits. */
    val exitsElsewhere: Boolean
        get() = exitIp != null && hop?.host != exitIp

    companion object {
        fun parse(s: String): ScrapeResult = json.decodeFromString(serializer(), s)
    }
}

@Serializable
private data class EngineScrapeSource(val url: String, val kind: String)

@Serializable
private data class EngineScrapeConfig(
    val sources: List<EngineScrapeSource>,
    val via: List<String>,
    val want: Int,
    val workers: Int,
    val timeoutMs: Int,
    val shuffle: Boolean,
    val protocols: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
    val reportAll: Boolean = false,
)

/** The JSON engine/mobile.ScrapeStart takes, to scrape lists for new proxies. */
fun scrapeConfig(
    sources: List<ScrapeSource>,
    via: List<String>,
    want: Int,
    workers: Int = 64,
    timeoutMs: Int = 10_000,
    protocols: Set<String> = emptySet(),
    countries: Set<String> = emptySet(),
): String = json.encodeToString(
    EngineScrapeConfig.serializer(),
    EngineScrapeConfig(
        sources.filter { it.enabled }.map { EngineScrapeSource(it.url, it.kind) },
        via, want, workers, timeoutMs, shuffle = true,
        protocols = protocols.toList(), countries = countries.toList(),
    ),
)

/** The same, to check proxies already in hand; every result, pass or fail, comes back. */
fun checkConfig(urls: List<String>, via: List<String>, countries: Set<String> = emptySet(), workers: Int = 32, timeoutMs: Int = 10_000): String =
    json.encodeToString(
        EngineScrapeConfig.serializer(),
        EngineScrapeConfig(
            emptyList(), via, want = 0, workers = workers, timeoutMs = timeoutMs, shuffle = false,
            countries = countries.toList(), candidates = urls, reportAll = true,
        ),
    )

/**
 * Reads a pasted proxy list: one per line, as scheme://[user:pass@]host:port or bare
 * host:port (taken as SOCKS5). Comments and junk are skipped, duplicates dropped.
 */
fun parseProxyList(text: String): List<String> {
    val full = Regex("""^(socks5h?|socks4a?|http)://([^\s/@]+@)?(\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):(\d{1,5})$""")
    val bare = Regex("""^(\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):(\d{1,5})$""")
    return text.lineSequence()
        .map { it.substringBefore('#').trim().split(Regex("""\s+""")).first() }
        .mapNotNull { line ->
            val url = when {
                full.matches(line) -> line.replaceFirst("socks5h://", "socks5://").replaceFirst("socks4://", "socks4a://")
                bare.matches(line) -> "socks5://$line"
                else -> null
            }
            url?.takeIf { (it.substringAfterLast(':').toIntOrNull() ?: 0) in 1..65535 }
        }
        .distinct()
        .toList()
}

fun parseSources(s: String): List<ScrapeSource> =
    json.decodeFromString(ListSerializer(ScrapeSource.serializer()), s)

fun encodeSources(l: List<ScrapeSource>): String =
    json.encodeToString(ListSerializer(ScrapeSource.serializer()), l)
