@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import javax.net.SocketFactory
import okhttp3.Authenticator
import okhttp3.Protocol
import okhttp3.internal.tls.OkHostnameVerifier

/** Routing verdict: [allow] with a null [reason] on the Cronet path, else the fallback reason. */
data class Decision(val allow: Boolean, val reason: Metrics.Reason?)

/**
 * Pure pre-send routing decision over [PolicyInput]. Never performs I/O and never records
 * metrics — the bridge glue (todo 6) records the path/reason it acted on.
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
 * 9. custom authenticator/proxyAuthenticator -> authenticator
 * 10. explicit proxy or non-baseline proxySelector -> proxy
 * 11. socketFactory class != default class -> socket_factory (class check, never instance — Metis B1)
 * 12. hostnameVerifier != OkHostnameVerifier -> hostname_verifier
 * 13. certificate pins -> pins
 * 14. TLS/trust fingerprint mismatch -> trust (Metis B1)
 * 15. loopback https without allowLoopbackHttps -> cleartext (cleartext reason reused: loopback
 *     is a local-test-server concern, not a distinct transport incompatibility)
 * 16. origin not allowlisted -> allowlist (empty set or "*" admits every origin)
 * 17. else allow
 *
 * OkHttp's cache is not a deny. Hits and 304 revalidation stay on OkHttp's chain.
 * [Metrics.Reason.cache] stays as a retired constant and is never produced.
 * Network interceptors are not a deny. They run on OkHttp's own chain before the Cronet hop.
 * [Metrics.Reason.network_interceptors] stays as a retired constant and is never produced.
 */
object PolicyEngine {

    fun shouldHandle(input: PolicyInput, snapshot: RuntimeSnapshot?): Decision {
        if (snapshot == null) return Decision(false, Metrics.Reason.engine_missing)
        if (!SarieBridge.isEnabled()) return Decision(false, Metrics.Reason.disabled)
        if (!snapshot.policy.enabled()) return Decision(false, Metrics.Reason.disabled)
        if (input.isCanceled) return Decision(false, Metrics.Reason.engine_missing)
        if (input.request.tag(CronetOptOut::class.java) != null) {
            return Decision(false, Metrics.Reason.tag_opt_out)
        }
        if (!input.request.url.isHttps) return Decision(false, Metrics.Reason.cleartext)
        if (input.forWebSocket) return Decision(false, Metrics.Reason.websocket)
        if (input.protocols.any { it == Protocol.H2_PRIOR_KNOWLEDGE }) {
            return Decision(false, Metrics.Reason.h2_prior_knowledge)
        }
        if (input.authenticator !== Authenticator.NONE || input.proxyAuthenticator !== Authenticator.NONE) {
            return Decision(false, Metrics.Reason.authenticator)
        }
        if (input.proxy != null || input.proxySelector !== TrustBaseline.baseline.proxySelector) {
            return Decision(false, Metrics.Reason.proxy)
        }
        if (input.socketFactory.javaClass != SocketFactory.getDefault().javaClass) {
            return Decision(false, Metrics.Reason.socket_factory)
        }
        if (input.hostnameVerifier !== OkHostnameVerifier) {
            return Decision(false, Metrics.Reason.hostname_verifier)
        }
        if (input.certificatePinner.pins.isNotEmpty()) return Decision(false, Metrics.Reason.pins)
        if (trustMismatched(input)) return Decision(false, Metrics.Reason.trust)
        if (isLoopback(input.request.url.host) && !snapshot.policy.allowLoopbackHttps) {
            return Decision(false, Metrics.Reason.cleartext)
        }
        if (!originAllowed(snapshot.policy.allowedOrigins, input.request.url.host, input.request.url.port)) {
            return Decision(false, Metrics.Reason.allowlist)
        }
        return Decision(true, null)
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

    /**
     * Empty set or `"*"` admits every origin. Other entries are `"host"` (port 443)
     * or `"host:port"` (exact port match).
     */
    private fun originAllowed(allowed: Set<String>, host: String, port: Int): Boolean {
        if (allowed.isEmpty() || "*" in allowed) return true
        return allowed.any { entry ->
            val colon = entry.lastIndexOf(':')
            val entryHost: String
            val entryPort: Int
            if (colon > 0) {
                entryHost = entry.substring(0, colon)
                entryPort = entry.substring(colon + 1).toIntOrNull() ?: 443
            } else {
                entryHost = entry
                entryPort = 443
            }
            entryHost == host && entryPort == port
        }
    }
}
