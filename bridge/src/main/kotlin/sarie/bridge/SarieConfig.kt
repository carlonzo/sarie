package sarie.bridge

import java.util.Collections
import java.util.IdentityHashMap
import okhttp3.CertificatePinner
import okhttp3.Dns
import org.chromium.net.CronetEngine

/**
 * Configuration for [SarieBridge].
 *
 * Construct instances using [Builder] in Java or the [SarieConfig] trailing-lambda DSL in Kotlin:
 * ```kotlin
 * val config = SarieConfig {
 *     certificatePinner(client.certificatePinner)
 *     debugLogger(SarieLogger.Logcat)
 * }
 * ```
 *
 * Settings specify which install path they apply to:
 * - **Built install** ([SarieBridge.install] with `Context`): supports all settings.
 * - **Borrowed install** ([SarieBridge.install] with `CronetEngine`): supports [policy],
 *   [mapper], [listener], [debugLogger], and [bypassableDns]. Setting [certificatePinner] or
 *   [configure] on a borrowed install throws [IllegalArgumentException].
 */
public class SarieConfig private constructor(builder: Builder) {
    /**
     * Certificate pins translated from OkHttp and installed into the Cronet engine at build time.
     *
     * Applies to the built [SarieBridge.install] path only. Null by default. Setting this on a
     * borrowed install throws [IllegalArgumentException].
     */
    public val certificatePinner: CertificatePinner? = builder.certificatePinner

    /**
     * Routing policy governing origin allowlists and runtime gates.
     *
     * Applies to both built and borrowed installs. Defaults to [DefaultPolicy].
     */
    public val policy: CronetPolicy = builder.policy

    /**
     * Request customizer invoked before each Cronet request is built, allowing modification of
     * priority or other Cronet-specific settings.
     *
     * Applies to both built and borrowed installs. Defaults to [RequestToUrlRequestMapper.NOOP].
     */
    public val mapper: RequestToUrlRequestMapper = builder.mapper

    /**
     * Listener receiving per-call routing decisions ([SarieListener.onRouted]) and Cronet
     * request completion metrics ([SarieListener.onFinished]).
     *
     * Applies to both built and borrowed installs. Null by default.
     */
    public val listener: SarieListener? = builder.listener

    /**
     * Logger receiving internal diagnostic messages and routing summaries.
     *
     * Applies to both built and borrowed installs. Null by default.
     */
    public val debugLogger: SarieLogger? = builder.debugLogger

    /**
     * Customization hook invoked on Cronet's [CronetEngine.Builder] before the engine is built.
     *
     * Runs after overridable defaults (connection migration and stale DNS) and before bridge-owned
     * settings (HTTP/3 on, cache off, storage path, and pins).
     *
     * Applies to the built [SarieBridge.install] path only. Defaults to a no-op action. Setting this
     * on a borrowed install throws [IllegalArgumentException].
     */
    public val configure: (CronetEngine.Builder) -> Unit = builder.configure

    /**
     * Set of [Dns] instances whose callers are permitted to route to Cronet instead of falling
     * back to stock OkHttp with [FallbackReason.dns].
     *
     * Matched by object identity. Applies to both built and borrowed installs. Empty by default.
     */
    public val bypassableDns: Set<Dns> = builder.bypassableDns.toIdentitySet()

    internal val isConfigureSet: Boolean get() = configure !== NOOP_CONFIGURE

    /**
     * Creates a new [Builder] initialized with this configuration's current values.
     */
    public fun newBuilder(): Builder = Builder(this)

    /**
     * Builder for [SarieConfig].
     */
    public class Builder {
        internal var certificatePinner: CertificatePinner? = null
        internal var policy: CronetPolicy = DefaultPolicy()
        internal var mapper: RequestToUrlRequestMapper = RequestToUrlRequestMapper.NOOP
        internal var listener: SarieListener? = null
        internal var debugLogger: SarieLogger? = null
        internal var configure: (CronetEngine.Builder) -> Unit = NOOP_CONFIGURE
        internal val bypassableDns: MutableList<Dns> = ArrayList()

        /**
         * Creates an empty [Builder] with default values.
         */
        public constructor()

        internal constructor(config: SarieConfig) {
            this.certificatePinner = config.certificatePinner
            this.policy = config.policy
            this.mapper = config.mapper
            this.listener = config.listener
            this.debugLogger = config.debugLogger
            this.configure = config.configure
            this.bypassableDns.addAll(config.bypassableDns)
        }

        /**
         * Sets the [CertificatePinner] whose pins will be translated and installed on the Cronet
         * engine when built.
         *
         * Only exact hostnames and double-wildcards (`**.example.com`) are supported by Cronet;
         * single-label wildcards (`*.example.com`) cannot be installed and fall back to stock OkHttp.
         *
         * Applies to the built [SarieBridge.install] path only. If set on a borrowed install,
         * [SarieBridge.install] throws [IllegalArgumentException].
         *
         * @param certificatePinner The OkHttp certificate pinner, or null for none.
         */
        public fun certificatePinner(certificatePinner: CertificatePinner?): Builder = apply {
            this.certificatePinner = certificatePinner
        }

        /**
         * Sets the routing policy.
         *
         * Applies to both built and borrowed installs. Defaults to [DefaultPolicy].
         *
         * @param policy The policy to enforce.
         */
        public fun policy(policy: CronetPolicy): Builder = apply {
            this.policy = policy
        }

        /**
         * Sets the mapper for customizing Cronet's [org.chromium.net.UrlRequest.Builder].
         *
         * Applies to both built and borrowed installs. Defaults to [RequestToUrlRequestMapper.NOOP].
         *
         * @param mapper The mapper to run on each request.
         */
        public fun mapper(mapper: RequestToUrlRequestMapper): Builder = apply {
            this.mapper = mapper
        }

        /**
         * Sets the listener for routing decisions and request metrics.
         *
         * Applies to both built and borrowed installs. Defaults to null.
         *
         * @param listener The listener to receive routing and finished events, or null.
         */
        public fun listener(listener: SarieListener?): Builder = apply {
            this.listener = listener
        }

        /**
         * Sets the debug logger for diagnostic messages and per-call routing reports.
         *
         * Applies to both built and borrowed installs. Defaults to null. Recommended to set
         * [SarieLogger.Logcat] in debug builds.
         *
         * @param debugLogger The logger to receive messages, or null to disable logging.
         */
        public fun debugLogger(debugLogger: SarieLogger?): Builder = apply {
            this.debugLogger = debugLogger
        }

        /**
         * Sets an engine configuration action invoked before building the Cronet engine.
         *
         * Use this to set options such as QUIC hints (`builder.addQuicHint(...)`). Must not call
         * `addPublicKeyPins`.
         *
         * Applies to the built [SarieBridge.install] path only. If set on a borrowed install,
         * [SarieBridge.install] throws [IllegalArgumentException].
         *
         * @param configure The action to configure the Cronet engine builder.
         */
        public fun configure(configure: (CronetEngine.Builder) -> Unit): Builder = apply {
            this.configure = configure
        }

        /**
         * Registers a custom [Dns] instance whose calls are allowed to route to Cronet.
         *
         * OkHttp clients using a custom [Dns] normally fall back to stock OkHttp ([FallbackReason.dns]).
         * If the custom DNS can be safely bypassed because Cronet's independent resolution is acceptable,
         * register it here. Repeatable; matched by object identity.
         *
         * Applies to both built and borrowed installs.
         *
         * @param dns The custom [Dns] instance to allow on Cronet.
         */
        public fun bypassableDns(dns: Dns): Builder = apply {
            this.bypassableDns.add(dns)
        }

        /**
         * Builds a new immutable [SarieConfig].
         */
        public fun build(): SarieConfig = SarieConfig(this)
    }

    public companion object {
        private val NOOP_CONFIGURE: (CronetEngine.Builder) -> Unit = {}

        /**
         * Default configuration with [DefaultPolicy], no pins, no listener, and no logger.
         */
        @JvmField
        public val DEFAULT: SarieConfig = Builder().build()

        private fun <T : Any> Collection<T>.toIdentitySet(): Set<T> {
            if (isEmpty()) return emptySet()
            val set = Collections.newSetFromMap(IdentityHashMap<T, Boolean>(size))
            set.addAll(this)
            return Collections.unmodifiableSet(set)
        }
    }
}

/**
 * Constructs a [SarieConfig] using a Kotlin DSL block.
 *
 * Example:
 * ```kotlin
 * val config = SarieConfig {
 *     certificatePinner(client.certificatePinner)
 *     debugLogger(SarieLogger.Logcat)
 * }
 * ```
 *
 * @param block Builder configuration lambda.
 */
public inline fun SarieConfig(block: SarieConfig.Builder.() -> Unit): SarieConfig =
    SarieConfig.Builder().apply(block).build()
