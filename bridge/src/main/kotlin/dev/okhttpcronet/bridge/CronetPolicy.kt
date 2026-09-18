package dev.okhttpcronet.bridge

/**
 * Host-supplied routing policy. Defined here (todo 2) and implemented by the host;
 * todo 4 provides the default implementation and the per-request evaluation engine.
 */
interface CronetPolicy {
    /** Exact origins (host[:port]) allowed on the Cronet path; default-deny for everything else. */
    val allowedOrigins: Set<String>

    /** Whether loopback HTTPS origins (local test servers) may go over Cronet. */
    val allowLoopbackHttps: Boolean get() = false

    /** Pin patterns supplied by the SDK itself; host pins outside this set force fallback. */
    val sdkPins: Set<String> get() = emptySet()

    /** Secondary per-request enable gate. */
    fun enabled(): Boolean = true
}
