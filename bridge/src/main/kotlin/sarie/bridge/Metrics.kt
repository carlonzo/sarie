package sarie.bridge

/**
 * Pre-send deny reasons. [SarieListener.onRouted] receives one of these, or null when the
 * call is going to Cronet.
 */
object Metrics {
    enum class Reason {
        disabled,
        engine_missing,
        tag_opt_out,
        allowlist,
        cleartext,
        websocket,
        h2_prior_knowledge,
        proxy,
        socket_factory,
        hostname_verifier,
        pins,
        trust,
        dns,
        content_encoding,
    }
}
