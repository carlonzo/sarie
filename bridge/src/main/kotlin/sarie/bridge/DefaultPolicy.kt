package sarie.bridge


/**
 * Standard implementation of [CronetPolicy] for the common host case.
 *
 * [allowedOrigins] defaults to empty, which admits every origin that passes
 * earlier safety rules (HTTPS, default trust, etc.). Pass a non-empty set of
 * `host` or `host:port` entries to send only those origins over Cronet.
 * `"*"` is an explicit allow-all token.
 *
 * Construct instances using [Builder] in Java or the [DefaultPolicy] trailing-lambda DSL in Kotlin:
 * ```kotlin
 * val policy = DefaultPolicy {
 *     allowedOrigins(setOf("api.example.com", "cdn.example.com:443"))
 *     allowLoopbackHttps(true)
 * }
 * ```
 */
public class DefaultPolicy private constructor(builder: Builder) : CronetPolicy {
    /**
     * Creates a [DefaultPolicy] with default values (all HTTPS origins allowed, loopback HTTPS disabled).
     */
    public constructor() : this(Builder())

    /**
     * Allowed origins as `host` or `host:port`. Empty or containing `"*"` admits every origin.
     * Default is empty.
     */
    public override val allowedOrigins: Set<String> = builder.allowedOrigins

    /**
     * Whether loopback HTTPS origins (`127.0.0.1`, `localhost`, `::1`) may route over Cronet.
     * Default is `false`.
     */
    public override val allowLoopbackHttps: Boolean = builder.allowLoopbackHttps

    /**
     * Pin patterns supplied by the SDK itself. Default is empty.
     */
    public override val sdkPins: Set<String> = builder.sdkPins

    private val gate: () -> Boolean = builder.enabled

    /**
     * Secondary per-request gate. Returns `false` to force requests to stock OkHttp with [FallbackReason.disabled].
     * Defaults to returning `true`.
     */
    public override fun enabled(): Boolean = gate()

    /**
     * Creates a new [Builder] initialized with this policy's current settings.
     */
    public fun newBuilder(): Builder = Builder(this)

    /**
     * Builder for [DefaultPolicy].
     */
    public class Builder {
        internal var allowedOrigins: Set<String> = emptySet()
        internal var allowLoopbackHttps: Boolean = false
        internal var sdkPins: Set<String> = emptySet()
        internal var enabled: () -> Boolean = { true }

        /**
         * Creates an empty [Builder] with default values.
         */
        public constructor()

        internal constructor(policy: DefaultPolicy) {
            this.allowedOrigins = policy.allowedOrigins
            this.allowLoopbackHttps = policy.allowLoopbackHttps
            this.sdkPins = policy.sdkPins
            this.enabled = policy.gate
        }

        /**
         * Sets the allowed origins.
         *
         * Entries can be bare hostnames (assuming default HTTPS port 443, e.g. `"api.example.com"`)
         * or explicit host and port (e.g. `"api.example.com:8443"`).
         *
         * An empty set or a set containing `"*"` permits all HTTPS origins.
         *
         * @param allowedOrigins Origins permitted on the Cronet path.
         */
        public fun allowedOrigins(allowedOrigins: Set<String>): Builder = apply {
            this.allowedOrigins = allowedOrigins.toSet()
        }

        /**
         * Controls whether loopback HTTPS origins (`127.0.0.1`, `localhost`, `::1`) are allowed on Cronet.
         *
         * Useful for local mock servers and test environments.
         *
         * @param allowLoopbackHttps `true` to allow loopback HTTPS on Cronet; `false` to route to stock OkHttp.
         */
        public fun allowLoopbackHttps(allowLoopbackHttps: Boolean): Builder = apply {
            this.allowLoopbackHttps = allowLoopbackHttps
        }

        /**
         * Sets SDK-supplied certificate pin patterns.
         *
         * @param sdkPins Set of pin patterns.
         */
        public fun sdkPins(sdkPins: Set<String>): Builder = apply {
            this.sdkPins = sdkPins.toSet()
        }

        /**
         * Sets a runtime gate function evaluated before each request.
         *
         * Return `false` to dynamically route requests to stock OkHttp with [FallbackReason.disabled].
         *
         * @param enabled Gate function returning `true` to allow Cronet routing, or `false` to disable.
         */
        public fun enabled(enabled: () -> Boolean): Builder = apply {
            this.enabled = enabled
        }

        /**
         * Builds an immutable [DefaultPolicy] instance.
         */
        public fun build(): DefaultPolicy = DefaultPolicy(this)
    }
}

/**
 * Constructs a [DefaultPolicy] using a Kotlin DSL block.
 *
 * Example:
 * ```kotlin
 * val policy = DefaultPolicy {
 *     allowedOrigins(setOf("api.example.com"))
 *     allowLoopbackHttps(true)
 * }
 * ```
 *
 * @param block Builder configuration lambda.
 */
public inline fun DefaultPolicy(block: DefaultPolicy.Builder.() -> Unit): DefaultPolicy =
    DefaultPolicy.Builder().apply(block).build()

