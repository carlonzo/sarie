package sarie.bridge

import java.util.Collections
import java.util.IdentityHashMap
import okhttp3.CertificatePinner
import okhttp3.Dns
import org.chromium.net.CronetEngine

public class SarieConfig private constructor(builder: Builder) {
    public val certificatePinner: CertificatePinner? = builder.certificatePinner
    public val policy: CronetPolicy = builder.policy
    public val mapper: RequestToUrlRequestMapper = builder.mapper
    public val listener: SarieListener? = builder.listener
    public val debugLogger: SarieLogger? = builder.debugLogger
    public val configure: (CronetEngine.Builder) -> Unit = builder.configure
    public val bypassableDns: Set<Dns> = builder.bypassableDns.toIdentitySet()

    internal val isConfigureSet: Boolean get() = configure !== NOOP_CONFIGURE

    public fun newBuilder(): Builder = Builder(this)

    public class Builder {
        internal var certificatePinner: CertificatePinner? = null
        internal var policy: CronetPolicy = DefaultPolicy()
        internal var mapper: RequestToUrlRequestMapper = RequestToUrlRequestMapper.NOOP
        internal var listener: SarieListener? = null
        internal var debugLogger: SarieLogger? = null
        internal var configure: (CronetEngine.Builder) -> Unit = NOOP_CONFIGURE
        internal val bypassableDns: MutableList<Dns> = ArrayList()

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

        public fun debugLogger(debugLogger: SarieLogger?): Builder = apply {
            this.debugLogger = debugLogger
        }

        public fun configure(configure: (CronetEngine.Builder) -> Unit): Builder = apply {
            this.configure = configure
        }

        public fun bypassableDns(dns: Dns): Builder = apply {
            this.bypassableDns.add(dns)
        }

        public fun build(): SarieConfig = SarieConfig(this)
    }

    public companion object {
        private val NOOP_CONFIGURE: (CronetEngine.Builder) -> Unit = {}

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

public inline fun SarieConfig(block: SarieConfig.Builder.() -> Unit): SarieConfig =
    SarieConfig.Builder().apply(block).build()
