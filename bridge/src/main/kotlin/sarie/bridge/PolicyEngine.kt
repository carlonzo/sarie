@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import java.io.IOException
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.tls.OkHostnameVerifier

/** One allowlist entry, parsed once when the snapshot is built. */
internal class ParsedOrigin(val host: String, val port: Int)

/**
 * Empty set or `"*"` admits every origin (`null`). Other entries are `"host"` (port 443)
 * or `"host:port"` (exact port; an unparseable port is 443).
 */
internal fun parseAllowedOrigins(allowed: Set<String>): List<ParsedOrigin>? {
    if (allowed.isEmpty() || "*" in allowed) return null
    val rules = ArrayList<ParsedOrigin>(allowed.size)
    for (entry in allowed) {
        val colon = entry.lastIndexOf(':')
        val host: String
        val port: Int
        if (colon > 0) {
            host = entry.substring(0, colon)
            port = entry.substring(colon + 1).toIntOrNull() ?: 443
        } else {
            host = entry
            port = 443
        }
        rules.add(ParsedOrigin(host, port))
    }
    return rules
}

/**
 * Pure pre-send routing decision over [PolicyInput]. Never performs I/O and never records
 * metrics. Returns null to allow; otherwise the fallback [FallbackReason].
 *
 * Rule order (first hit wins):
 * 1. snapshot missing -> engine_missing
 * 2. kill switch off -> disabled
 * 3. policy disabled -> disabled
 * 4. call canceled -> engine_missing (canceled pre-flight is not a routing concern)
 * 5. CronetOptOut tag -> tag_opt_out
 * 6. non-https scheme -> cleartext
 * 7. forWebSocket -> websocket
 * 8. H2_PRIOR_KNOWLEDGE -> h2_prior_knowledge
 * 9. explicit proxy or non-baseline proxySelector -> proxy
 * 10. socketFactory class != default class -> socket_factory (class check, never instance — Metis B1)
 * 11. hostnameVerifier != OkHostnameVerifier -> hostname_verifier
 * 12. dns !== Dns.SYSTEM and not in bypassableDns -> dns
 * 13. certificate pins the Sarie-built engine did not install, or any `*.` pin -> pins
 * 14. TLS/trust fingerprint mismatch -> trust (Metis B1)
 * 15. Accept-Encoding the app owns, or a swap Accept-Encoding that does not list gzip
 *     -> content_encoding
 * 16. nonzero or unknown length body missing Content-Type -> content_type
 * 17. loopback https without allowLoopbackHttps -> cleartext (cleartext reason reused: loopback
 *     is a local-test-server concern, not a distinct transport incompatibility)
 * 18. origin not allowlisted -> allowlist (empty set or "*" admits every origin)
 * 19. else allow
 *
 * Authenticators are not a routing rule. A 401 is returned so OkHttp calls
 * authenticator.authenticate(route = null, response).
 * OkHttp's cache is not a deny. Hits and 304 revalidation stay on OkHttp's chain.
 * Network interceptors are not a deny. They run on OkHttp's own chain before the Cronet hop.
 */
internal object PolicyEngine {

    /** Cached: evaluating `::class` allocates a [kotlin.reflect.KClass] on every use. */
    private val optOutClass = CronetOptOut::class

    fun shouldHandle(input: PolicyInput, snapshot: RuntimeSnapshot?): FallbackReason? {
        if (snapshot == null) return FallbackReason.engine_missing
        if (!SarieBridge.isEnabled()) return FallbackReason.disabled
        if (!snapshot.policy.enabled()) return FallbackReason.disabled
        if (input.isCanceled) return FallbackReason.engine_missing
        if (input.request.tag(optOutClass) != null) {
            return FallbackReason.tag_opt_out
        }
        if (!input.request.url.isHttps) return FallbackReason.cleartext
        if (input.forWebSocket) return FallbackReason.websocket
        val protocols = input.protocols
        var protocolIndex = 0
        while (protocolIndex < protocols.size) {
            if (protocols[protocolIndex] == Protocol.H2_PRIOR_KNOWLEDGE) {
                return FallbackReason.h2_prior_knowledge
            }
            protocolIndex++
        }
        val base = TrustBaseline.baseline
        if (input.proxy != null || input.proxySelector !== base.proxySelector) {
            return FallbackReason.proxy
        }
        if (input.socketFactory.javaClass != base.socketFactoryClass) {
            return FallbackReason.socket_factory
        }
        if (input.hostnameVerifier !== OkHostnameVerifier) {
            return FallbackReason.hostname_verifier
        }
        if (input.dns !== Dns.SYSTEM && input.dns !in snapshot.bypassableDns) {
            return FallbackReason.dns
        }
        if (!pinsSatisfied(
                input.certificatePinner,
                input.request.url.host,
                snapshot.sarieBuilt,
                snapshot.installedPins,
            )
        ) {
            return FallbackReason.pins
        }
        if (trustMismatched(input)) return FallbackReason.trust
        if (contentEncodingDenied(input)) return FallbackReason.content_encoding
        if (contentTypeDenied(input.request)) return FallbackReason.content_type
        if (isLoopback(input.request.url.host) && !snapshot.policy.allowLoopbackHttps) {
            return FallbackReason.cleartext
        }
        if (!originAllowed(snapshot.originRules, input.request.url.host, input.request.url.port)) {
            return FallbackReason.allowlist
        }
        return null
    }

    /**
     * The app set Accept-Encoding on the call: stock would hand it the raw bytes.
     * Or the request at the swap names codings and gzip is not among them
     * (comma-separated tokens, quality parameters stripped, case-insensitive).
     * No Accept-Encoding at all is allowed — the Range case, where Chromium sends identity.
     */
    private fun contentEncodingDenied(input: PolicyInput): Boolean {
        if (input.originalRequest.header(ACCEPT_ENCODING) != null) return true
        return acceptEncodingLacksGzip(input.request)
    }

    private fun acceptEncodingLacksGzip(request: Request): Boolean {
        val first = request.header(ACCEPT_ENCODING)
        // BridgeInterceptor sets this single value. Null means the header is absent.
        if (first == null || first == "gzip") return false
        val values = request.headers.values(ACCEPT_ENCODING)
        return values.asSequence()
            .flatMap { it.split(',') }
            .map { it.substringBefore(';').trim() }
            .filter { it.isNotEmpty() }
            .none { it.equals("gzip", ignoreCase = true) }
    }

    /** Fail-closed: any null/unknown SSL factory, TM class, or issuer fingerprint mismatch denies. */
    private fun trustMismatched(input: PolicyInput): Boolean {
        val base = TrustBaseline.baseline
        val verdict = input.x509TrustManagerOrNull?.let { TrustBaseline.verdictFor(it) }
        return input.sslSocketFactoryOrNull?.javaClass != base.sslFactoryClass ||
            verdict?.managerClass != base.trustManagerClass ||
            verdict?.fingerprint != base.trustFingerprint
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"

    /** [rules] null admits every origin. Otherwise host and port must match one entry. */
    private fun originAllowed(rules: List<ParsedOrigin>?, host: String, port: Int): Boolean {
        if (rules == null) return true
        var i = 0
        while (i < rules.size) {
            val rule = rules[i]
            if (rule.host == host && rule.port == port) return true
            i++
        }
        return false
    }

    /**
     * Cronet requires a Content-Type for any upload data provider; if missing, it injects
     * application/octet-stream. Requests with a nonzero or unknown-length (-1) body must have a
     * Content-Type from either the body or headers.
     */
    private fun contentTypeDenied(request: Request): Boolean {
        val body = request.body ?: return false
        if (body.contentType() != null) return false
        if (!request.header(CONTENT_TYPE).isNullOrBlank()) return false
        // Last: contentLength() can be costly (multipart sums its parts).
        val length = try {
            body.contentLength()
        } catch (_: IOException) {
            -1L
        }
        return length != 0L
    }

    private const val ACCEPT_ENCODING = "Accept-Encoding"
    private const val CONTENT_TYPE = "Content-Type"
}
