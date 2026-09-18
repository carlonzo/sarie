package dev.okhttpcronet.bridge

import java.util.concurrent.atomic.AtomicLong

/**
 * Path disambiguation counters: every served request records which transport handled it
 * and, for fallbacks, the pre-send [Reason] it was diverted.
 */
object Metrics {
    enum class Path { CRONET, FALLBACK }

    enum class Reason {
        disabled,
        engine_missing,
        tag_opt_out,
        allowlist,
        cleartext,
        websocket,
        cache,
        network_interceptors,
        h2_prior_knowledge,
        authenticator,
        proxy,
        socket_factory,
        hostname_verifier,
        pins,
        trust,
        protocols,
        engine_cold,
    }

    val cronet = AtomicLong()
    val okhttpFallback = AtomicLong()

    @Volatile
    var lastReason: Reason? = null
        private set

    fun record(path: Path, reason: Reason?) {
        when (path) {
            Path.CRONET -> cronet.incrementAndGet()
            Path.FALLBACK -> okhttpFallback.incrementAndGet()
        }
        if (reason != null) lastReason = reason
    }

    fun resetForTest() {
        cronet.set(0)
        okhttpFallback.set(0)
        lastReason = null
    }
}
