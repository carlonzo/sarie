package dev.okhttpcronet.bridge

import org.chromium.net.CronetEngine

/**
 * Holds the installed [RuntimeSnapshot]. The engine is host-owned and borrowed:
 * [install]/[uninstall] only swap or drop the reference and never stop any engine.
 */
object CronetRuntime {
    private const val KILL_SWITCH_PROPERTY = "okhttp.cronet.enabled"

    @Volatile
    private var current: RuntimeSnapshot? = null

    /** Atomically replaces any existing snapshot with a new one. */
    fun install(policy: CronetPolicy, engine: CronetEngine, mapper: RequestToUrlRequestMapper) {
        requireNotNull(engine) { "engine must be a ready, host-owned CronetEngine" }
        current = RuntimeSnapshot(engine, policy, mapper, System.currentTimeMillis())
    }

    /** Drops the snapshot reference; the borrowed engine stays running. */
    fun uninstall() {
        current = null
    }

    fun snapshot(): RuntimeSnapshot? = current

    /** Kill switch via system property (default true) plus snapshot presence. */
    fun isEnabled(): Boolean =
        System.getProperty(KILL_SWITCH_PROPERTY, "true").toBoolean() && current != null
}
