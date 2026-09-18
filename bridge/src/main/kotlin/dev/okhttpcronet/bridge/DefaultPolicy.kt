package dev.okhttpcronet.bridge

/**
 * Straightforward [CronetPolicy] implementation for the common host case: exact-origin
 * allowlist, optional loopback HTTPS, SDK pins, and a secondary enable gate (the kill
 * switch supplies its own `enabled = { false }`).
 */
class DefaultPolicy(
    override val allowedOrigins: Set<String>,
    override val allowLoopbackHttps: Boolean = false,
    override val sdkPins: Set<String> = emptySet(),
    enabled: () -> Boolean = { true },
) : CronetPolicy {
    private val gate = enabled

    override fun enabled(): Boolean = gate()
}
