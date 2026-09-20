package dev.okhttpcronet.bridge

/**
 * Straightforward [CronetPolicy] for the common host case.
 *
 * [allowedOrigins] defaults to empty, which admits every origin that passes
 * [PolicyEngine]'s other rules (HTTPS, default trust, no cache, no network interceptors,
 * …). Pass a non-empty set of `host` or `host:port` entries to send only those hosts
 * over Cronet — the Cronet path is not OkHttp-parity (see `COMPATIBILITY.md`), so an
 * allowlist is how you limit it to hosts you have tested. `"*"` is an explicit
 * allow-all token.
 */
class DefaultPolicy(
    override val allowedOrigins: Set<String> = emptySet(),
    override val allowLoopbackHttps: Boolean = false,
    override val sdkPins: Set<String> = emptySet(),
    enabled: () -> Boolean = { true },
) : CronetPolicy {
    private val gate = enabled

    override fun enabled(): Boolean = gate()
}
