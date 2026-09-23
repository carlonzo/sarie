package sarie.bridge

/**
 * Host-supplied routing policy evaluated by [PolicyEngine] before any Cronet I/O.
 */
public interface CronetPolicy {
    /**
     * Origins that may use the Cronet path, as `host` (port 443) or `host:port`.
     * Empty, or containing `"*"`, means every origin that passed the other policy
     * checks. A non-empty set without `"*"` is exact-match default-deny.
     */
    public val allowedOrigins: Set<String>

    /** Whether loopback HTTPS origins (local test servers) may go over Cronet. */
    public val allowLoopbackHttps: Boolean get() = false

    /** Pin patterns supplied by the SDK itself; host pins outside this set force fallback. */
    public val sdkPins: Set<String> get() = emptySet()

    /** Secondary per-request enable gate. */
    public fun enabled(): Boolean = true
}
