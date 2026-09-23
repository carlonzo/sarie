package sarie.bridge

import okhttp3.CertificatePinner
import org.chromium.net.CronetEngine

class SarieConfig private constructor(builder: Builder) {
    val certificatePinner: CertificatePinner? = builder.certificatePinner
    val policy: CronetPolicy = builder.policy
    val mapper: RequestToUrlRequestMapper = builder.mapper
    val listener: SarieListener? = builder.listener
    val logger: SarieLogger? = builder.logger
    val configure: (CronetEngine.Builder) -> Unit = builder.configure

    internal val isConfigureSet: Boolean = builder.isConfigureSet

    fun newBuilder(): Builder = Builder(this)

    class Builder {
        internal var certificatePinner: CertificatePinner? = null
        internal var policy: CronetPolicy = DefaultPolicy()
        internal var mapper: RequestToUrlRequestMapper = RequestToUrlRequestMapper.NOOP
        internal var listener: SarieListener? = null
        internal var logger: SarieLogger? = null
        internal var configure: (CronetEngine.Builder) -> Unit = NOOP_CONFIGURE
        internal var isConfigureSet: Boolean = false

        constructor()

        internal constructor(config: SarieConfig) {
            this.certificatePinner = config.certificatePinner
            this.policy = config.policy
            this.mapper = config.mapper
            this.listener = config.listener
            this.logger = config.logger
            this.configure = config.configure
            this.isConfigureSet = config.isConfigureSet
        }

        fun certificatePinner(certificatePinner: CertificatePinner?): Builder = apply {
            this.certificatePinner = certificatePinner
        }

        fun policy(policy: CronetPolicy): Builder = apply {
            this.policy = policy
        }

        fun mapper(mapper: RequestToUrlRequestMapper): Builder = apply {
            this.mapper = mapper
        }

        fun listener(listener: SarieListener?): Builder = apply {
            this.listener = listener
        }

        fun logger(logger: SarieLogger?): Builder = apply {
            this.logger = logger
        }

        fun configure(configure: (CronetEngine.Builder) -> Unit): Builder = apply {
            this.configure = configure
            this.isConfigureSet = true
        }

        fun build(): SarieConfig = SarieConfig(this)
    }

    companion object {
        private val NOOP_CONFIGURE: (CronetEngine.Builder) -> Unit = {}

        @JvmField
        val DEFAULT: SarieConfig = Builder().build()
    }
}

inline fun SarieConfig(block: SarieConfig.Builder.() -> Unit): SarieConfig =
    SarieConfig.Builder().apply(block).build()
