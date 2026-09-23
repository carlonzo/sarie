package sarie.bridge

/**
 * Why a call went to stock OkHttp instead of Cronet. [SarieListener.onRouted] receives one of
 * these, or null when the call is going to Cronet.
 */
public enum class FallbackReason {
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

    /** The policy itself threw; the call failed closed to stock OkHttp. */
    policy_error,
}
