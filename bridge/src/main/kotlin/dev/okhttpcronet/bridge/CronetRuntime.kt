package dev.okhttpcronet.bridge

import java.util.logging.Logger
import okhttp3.OkHttp
import org.chromium.net.CronetEngine

private val runtimeLogger: Logger = Logger.getLogger("dev.okhttpcronet.bridge")

/**
 * Process-wide handle to the host-owned Cronet engine.
 *
 * The host creates the [CronetEngine] (choosing the Maven artifact, QUIC hints, cache path,
 * and so on) and calls [install] once at startup, before the first OkHttp call. The bridge
 * borrows that engine: [install] / [uninstall] only swap or drop the reference and never
 * call [CronetEngine.shutdown].
 *
 * Without an install, every request falls back to stock OkHttp (`reason=engine_missing`).
 * The runtime kill switch `okhttp.cronet.enabled=false` does the same without uninstalling.
 */
object CronetRuntime {
    private const val KILL_SWITCH_PROPERTY = "okhttp.cronet.enabled"

    @Volatile
    private var current: RuntimeSnapshot? = null

    /**
     * Publishes [engine] so the rewritten `ConnectInterceptor` can route matching requests
     * through Cronet. A second call replaces the snapshot; the previous engine is not shut
     * down.
     *
     * @param engine Host-built engine. The host owns its lifecycle and the artifact it came
     *   from (`cronet-embedded`, `cronet-bundled`, Play Services, …).
     * @param policy Which requests may use Cronet. [DefaultPolicy] with an empty
     *   [CronetPolicy.allowedOrigins] (the default) lets every origin that passes the other
     *   fail-closed checks through; pass a non-empty set to restrict to those hosts.
     * @param mapper Optional hook applied to each Cronet `UrlRequest.Builder` after the
     *   OkHttp request is copied. Used for Cronet-only knobs (priority, traffic-stats tag,
     *   annotations). Most hosts leave the default no-op.
     */
    @JvmOverloads
    fun install(
        engine: CronetEngine,
        policy: CronetPolicy = DefaultPolicy(),
        mapper: RequestToUrlRequestMapper = RequestToUrlRequestMapper.NOOP,
    ) {
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
