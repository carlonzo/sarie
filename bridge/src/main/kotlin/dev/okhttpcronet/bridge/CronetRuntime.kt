package dev.okhttpcronet.bridge

import java.util.logging.Logger
import okhttp3.OkHttp
import org.chromium.net.CronetEngine

private val runtimeLogger: Logger = Logger.getLogger("dev.okhttpcronet.bridge")

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
        warnIfUnverified(OkHttp.VERSION)
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

/**
 * Runtime compatibility tripwire: the build-time registry pins verified okhttp versions, but a
 * host app may ship a different one. Warns instead of failing - routing is unaffected. Called
 * once per [CronetRuntime.install]; `OkHttp.VERSION` has no ConstantValue attribute (javap on
 * the pinned artifact), so the reference is a real GETSTATIC read of the runtime version.
 */
internal fun warnIfUnverified(runtimeVersion: String) {
    if (runtimeVersion in VerifiedOkHttpVersions) return
    runtimeLogger.warning(
        "okhttp-cronet is running against okhttp $runtimeVersion, which was not verified with this " +
            "build (verified: ${VerifiedOkHttpVersions.sorted().joinToString()}). The build-time " +
            "structural guard covered ConnectInterceptor only; run the verification suites for this version.",
    )
}
