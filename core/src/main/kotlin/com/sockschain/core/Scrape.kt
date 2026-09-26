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
)

/** The JSON engine/mobile.ScrapeStart takes. */
fun scrapeConfig(
    sources: List<ScrapeSource>,
    via: List<String>,
    want: Int,
    workers: Int = 64,
    timeoutMs: Int = 10_000,
): String = json.encodeToString(
    EngineScrapeConfig.serializer(),
    EngineScrapeConfig(
        sources.filter { it.enabled }.map { EngineScrapeSource(it.url, it.kind) },
        via, want, workers, timeoutMs, shuffle = true,
    ),
)

fun parseSources(s: String): List<ScrapeSource> =
    json.decodeFromString(ListSerializer(ScrapeSource.serializer()), s)

fun encodeSources(l: List<ScrapeSource>): String =
    json.encodeToString(ListSerializer(ScrapeSource.serializer()), l)
