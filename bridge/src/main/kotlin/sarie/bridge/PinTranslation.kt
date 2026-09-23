package sarie.bridge

import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import okhttp3.CertificatePinner

/** How an OkHttp pin pattern maps onto Cronet `addPublicKeyPins`. */
internal enum class PinPatternKind {
    EXACT,
    SUBDOMAINS,
    SINGLE_LABEL,
    UNSUPPORTED,
}

/**
 * One `addPublicKeyPins` call. [hashes] are raw SHA-256 bytes. SHA-1 pins are omitted: Cronet's
 * API is SHA-256 only, so a host whose matching pins include SHA-1 does not compare equal and
 * falls back.
 */
internal class PinGroup(
    val host: String,
    val includeSubdomains: Boolean,
    val hashes: List<ByteArray>,
    val pins: Set<CertificatePinner.Pin>,
)

internal class PinTranslation(
    val groups: List<PinGroup>,
    val installedPins: Set<CertificatePinner.Pin>,
)

/** Cronet rejects digit-and-dot hostnames (`^[0-9.]*$` in cronet 500.0.2). Those pins are not installed. */
private val DIGIT_DOT_HOST = Regex("^[0-9.]*$")

/**
 * 2100-01-01 UTC. Chromium converts the expiry from milliseconds to microseconds; [Long.MAX_VALUE]
 * overflows that conversion.
 */
internal fun pinExpiryDate(): Date {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    calendar.clear()
    calendar.set(2100, Calendar.JANUARY, 1, 0, 0, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return Date(calendar.timeInMillis)
}

internal fun classifyPinPattern(pattern: String): PinPatternKind = when {
    pattern.startsWith("**.") && pattern.indexOf('*', startIndex = 2) == -1 -> PinPatternKind.SUBDOMAINS
    pattern.startsWith("*.") && pattern.indexOf('*', startIndex = 1) == -1 -> PinPatternKind.SINGLE_LABEL
    '*' !in pattern -> PinPatternKind.EXACT
    else -> PinPatternKind.UNSUPPORTED
}

internal fun isSingleLabelWildcard(pattern: String): Boolean =
    classifyPinPattern(pattern) == PinPatternKind.SINGLE_LABEL

/**
 * Groups [pins] by pattern. Exact hosts are installed with `includeSubdomains = false`.
 * `**.host` is installed on `host` with `includeSubdomains = true`. `*.host` (one label) has no
 * Cronet equivalent and is left out.
 */
internal fun translatePins(pins: Set<CertificatePinner.Pin>): PinTranslation {
    val groups = mutableListOf<PinGroup>()
    val installed = mutableSetOf<CertificatePinner.Pin>()
    for ((pattern, group) in pins.groupBy { it.pattern }.toSortedMap()) {
        val kind = classifyPinPattern(pattern)
        if (kind != PinPatternKind.EXACT && kind != PinPatternKind.SUBDOMAINS) continue
        val host = if (kind == PinPatternKind.SUBDOMAINS) pattern.removePrefix("**.") else pattern
        if (host.isEmpty() || DIGIT_DOT_HOST.matches(host)) continue
        val sha256 = group.filter { it.hashAlgorithm == "sha256" }.distinctBy { it.hash }
        if (sha256.isEmpty()) continue
        val pinSet = sha256.toSet()
        groups += PinGroup(
            host = host,
            includeSubdomains = kind == PinPatternKind.SUBDOMAINS,
            hashes = pinSet.sortedBy { it.hash.base64() }.map { it.hash.toByteArray() },
            pins = pinSet,
        )
        installed += pinSet
    }
    return PinTranslation(groups, installed)
}

/**
 * Allow a pinned host only when this Sarie-built engine installed exactly those pins and none of
 * them came from a `*.` pattern. A borrowed engine has no installed pins, so any match denies.
 *
 * A client with no pins for the host is denied when the engine pins it: Cronet would enforce pins
 * that client never configured. Pins from more than one pattern (`api.example.com` plus
 * `**.example.com`) are denied too: OkHttp accepts a match from any of them, Chromium checks only
 * the most specific entry.
 */
internal fun pinsSatisfied(
    pinner: CertificatePinner,
    host: String,
    sarieBuilt: Boolean,
    installedPins: Set<CertificatePinner.Pin>,
): Boolean {
    if (pinner.pins.isEmpty() && installedPins.isEmpty()) return true
    val matching = pinner.findMatchingPins(host)
    val installedForHost = installedPins.filter { it.matchesHostname(host) }.toSet()
    if (matching.isEmpty()) return installedForHost.isEmpty()
    if (!sarieBuilt) return false
    if (matching.any { isSingleLabelWildcard(it.pattern) }) return false
    if (matching.distinctBy { it.pattern }.size > 1) return false
    return matching.toSet() == installedForHost
}
