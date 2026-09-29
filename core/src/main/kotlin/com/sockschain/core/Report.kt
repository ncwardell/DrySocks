package com.sockschain.core

import kotlinx.serialization.Serializable

/**
 * What a deep inspection measured about one proxy; mirrors engine/scrape.Report.
 * Every field is measured through the pool's route, so it describes the proxy as seen
 * from where the pool will use it.
 */
@Serializable
data class ProxyReport(
    val url: String,
    /** Protocols the port actually answers to, whatever the list said. */
    val protocols: List<String> = emptyList(),
    val needsAuth: Boolean = false,
    val web443: Boolean = false,
    val web80: Boolean = false,
    /** Tunnels to non-web ports: needed to test other proxies, or to carry Tor. */
    val anyPort: Boolean = false,
    /** A TLS handshake with a real Tor relay got through. */
    val tor: Boolean = false,
    /** Headers it added to a plain-HTTP request passing through. */
    val addedHeaders: List<String> = emptyList(),
    val tampers: Boolean = false,
    val exitIp: String? = null,
    val country: String? = null,
    val isp: String? = null,
    val asn: String? = null,
    /** A datacenter address rather than a home or mobile line. */
    val hosting: Boolean = false,
    val mobile: Boolean = false,
    /** Already listed as a proxy or VPN by ip-api, so sites may treat it as one. */
    val flagged: Boolean = false,
    val kbps: Long = 0,
    val errors: Map<String, String> = emptyMap(),
) {
    val network: String
        get() = when {
            mobile -> "mobile"
            hosting -> "datacenter"
            isp != null -> "residential"
            else -> "unknown"
        }

    companion object {
        fun parse(s: String): ProxyReport = json.decodeFromString(serializer(), s)
    }
}

@Serializable
private data class InspectConfig(val urls: List<String>, val via: List<String>, val parallel: Int)

/** The JSON engine/mobile.InspectStart takes. */
fun inspectConfig(urls: List<String>, via: List<String>, parallel: Int = 6): String =
    json.encodeToString(InspectConfig.serializer(), InspectConfig(urls, via, parallel))
