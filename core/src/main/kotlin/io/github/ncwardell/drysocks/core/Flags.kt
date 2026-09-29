package io.github.ncwardell.drysocks.core

/**
 * A two-letter country code as its flag emoji: each letter becomes a regional indicator
 * symbol, and the pair renders as the flag. Anything that is not a real two-letter code
 * (DB-IP's ZZ for unknown, EU, AP) comes back as a globe, not a broken flag.
 */
fun flag(cc: String?): String {
    val c = cc?.uppercase() ?: return GLOBE
    if (c.length != 2 || !c.all { it in 'A'..'Z' } || c in NOT_COUNTRIES) return GLOBE
    return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

private const val GLOBE = "🌐"
private val NOT_COUNTRIES = setOf("ZZ", "EU", "AP", "XX")
