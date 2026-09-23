package sarie.bridge

/**
 * Straightforward [CronetPolicy] for the common host case.
 *
 * [allowedOrigins] defaults to empty, which admits every origin that passes
 * [PolicyEngine]'s other rules (HTTPS, default trust, …). Pass a non-empty set of
 * `host` or `host:port` entries to send only those hosts
 * over Cronet — the Cronet path is not OkHttp-parity (see `COMPATIBILITY.md`), so an
 * allowlist is how you limit it to hosts you have tested. `"*"` is an explicit
 * allow-all token.
 */
public class DefaultPolicy private constructor(builder: Builder) : CronetPolicy {
    public constructor() : this(Builder())

    public override val allowedOrigins: Set<String> = builder.allowedOrigins
    public override val allowLoopbackHttps: Boolean = builder.allowLoopbackHttps
    public override val sdkPins: Set<String> = builder.sdkPins
    private val gate: () -> Boolean = builder.enabled

    public override fun enabled(): Boolean = gate()

    public fun newBuilder(): Builder = Builder(this)

    public class Builder {
        internal var allowedOrigins: Set<String> = emptySet()
        internal var allowLoopbackHttps: Boolean = false
        internal var sdkPins: Set<String> = emptySet()
        internal var enabled: () -> Boolean = { true }

        public constructor()

        internal constructor(policy: DefaultPolicy) {
            this.allowedOrigins = policy.allowedOrigins
            this.allowLoopbackHttps = policy.allowLoopbackHttps
            this.sdkPins = policy.sdkPins
            this.enabled = policy.gate
        }

        public fun allowedOrigins(allowedOrigins: Set<String>): Builder = apply {
            this.allowedOrigins = allowedOrigins.toSet()
        }

        public fun allowLoopbackHttps(allowLoopbackHttps: Boolean): Builder = apply {
            this.allowLoopbackHttps = allowLoopbackHttps
        }

        public fun sdkPins(sdkPins: Set<String>): Builder = apply {
            this.sdkPins = sdkPins.toSet()
        }

        public fun enabled(enabled: () -> Boolean): Builder = apply {
            this.enabled = enabled
        }

        public fun build(): DefaultPolicy = DefaultPolicy(this)
    }
}
