package sarie.bridge

import okhttp3.CertificatePinner
import org.chromium.net.CronetEngine

public class SarieConfig private constructor(builder: Builder) {
    public val certificatePinner: CertificatePinner? = builder.certificatePinner
    public val policy: CronetPolicy = builder.policy
    public val mapper: RequestToUrlRequestMapper = builder.mapper
    public val listener: SarieListener? = builder.listener
    public val logger: SarieLogger? = builder.logger
    public val configure: (CronetEngine.Builder) -> Unit = builder.configure

    internal val isConfigureSet: Boolean = builder.isConfigureSet

    public fun newBuilder(): Builder = Builder(this)

    public class Builder {
        internal var certificatePinner: CertificatePinner? = null
        internal var policy: CronetPolicy = DefaultPolicy()
        internal var mapper: RequestToUrlRequestMapper = RequestToUrlRequestMapper.NOOP
        internal var listener: SarieListener? = null
        internal var logger: SarieLogger? = null
        internal var configure: (CronetEngine.Builder) -> Unit = NOOP_CONFIGURE
        internal var isConfigureSet: Boolean = false

        public constructor()

        internal constructor(config: SarieConfig) {
            this.certificatePinner = config.certificatePinner
            this.policy = config.policy
            this.mapper = config.mapper
            this.listener = config.listener
            this.logger = config.logger
            this.configure = config.configure
            this.isConfigureSet = config.isConfigureSet
        }

        public fun certificatePinner(certificatePinner: CertificatePinner?): Builder = apply {
            this.certificatePinner = certificatePinner
        }

        public fun policy(policy: CronetPolicy): Builder = apply {
            this.policy = policy
        }

        public fun mapper(mapper: RequestToUrlRequestMapper): Builder = apply {
            this.mapper = mapper
        }

        public fun listener(listener: SarieListener?): Builder = apply {
            this.listener = listener
        }

        public fun logger(logger: SarieLogger?): Builder = apply {
            this.logger = logger
        }

        public fun configure(configure: (CronetEngine.Builder) -> Unit): Builder = apply {
            this.configure = configure
            this.isConfigureSet = true
        }

        public fun build(): SarieConfig = SarieConfig(this)
    }

    public companion object {
        private val NOOP_CONFIGURE: (CronetEngine.Builder) -> Unit = {}

        @JvmField
        public val DEFAULT: SarieConfig = Builder().build()
    }
}

public inline fun SarieConfig(block: SarieConfig.Builder.() -> Unit): SarieConfig =
    SarieConfig.Builder().apply(block).build()
