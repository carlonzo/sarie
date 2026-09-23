package sarie.bridge

/**
 * Host-supplied routing policy evaluated before any network I/O begins.
 *
 * Configured via [SarieConfig.Builder.policy]. Applies to both built and borrowed installs.
 * Methods are invoked on the OkHttp caller thread for each request.
 *
 * If any method throws an uncaught exception, the call fails closed to stock OkHttp
 * with [FallbackReason.policy_error].
 */
public interface CronetPolicy {
    /**
     * Origins that may use the Cronet path, as `host` (port 443) or `host:port`.
     * Empty, or containing `"*"`, means every origin that passed earlier policy
     * checks. A non-empty set without `"*"` is exact-match default-deny.
     */
    public val allowedOrigins: Set<String>

    /**
     * Whether loopback HTTPS origins (such as local test servers) may go over Cronet.
     * Defaults to `false`.
     */
    public val allowLoopbackHttps: Boolean get() = false

    /**
     * Pin patterns supplied by the SDK itself; host pins outside this set force fallback.
     * Defaults to an empty set.
     */
    public val sdkPins: Set<String> get() = emptySet()

    /**
     * Secondary per-request enable gate. Return `false` to route the request to stock OkHttp
     * with [FallbackReason.disabled]. Defaults to `true`.
     */
    public fun enabled(): Boolean = true
}
