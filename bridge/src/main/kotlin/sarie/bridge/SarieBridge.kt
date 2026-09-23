package sarie.bridge

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider

private val runtimeLogger: Logger = Logger.getLogger("sarie.bridge")

/**
 * Process-wide handle to the Cronet engine.
 *
 * [install] with a [Context] builds the engine (provider, pins, cache off). [install] with an
 * existing [CronetEngine] borrows a host-built engine and does not enforce pins. Either way the
 * bridge only swaps or drops the reference and never calls [CronetEngine.shutdown], including
 * on the engine it built.
 *
 * Without an install, every request falls back to stock OkHttp (`reason=engine_missing`).
 * The runtime kill switch `okhttp.cronet.enabled=false` does the same without uninstalling.
 */
object SarieBridge {
    private const val KILL_SWITCH_PROPERTY = "okhttp.cronet.enabled"
    private const val STORAGE_DIR_NAME = "sarie-cronet"

    private val missingProviderLogged = AtomicBoolean(false)
    private val storageDirLogged = AtomicBoolean(false)

    @Volatile
    private var current: RuntimeSnapshot? = null

    /**
     * Builds a Cronet engine and publishes it. [configure] runs after the overridable defaults
     * (connection migration) and before bridge-owned settings, which overwrite brotli, the HTTP
     * cache, the storage path, pins, and local-trust pin bypass.
     *
     * When no enabled app-packaged, HttpEngine, or Play Services provider exists, this does not
     * publish a snapshot (logged once). Requests keep falling back with `reason=engine_missing`.
     * The Java fallback provider is never selected.
     *
     * @param client Source of certificate pins. Null installs no pins.
     */
    @JvmOverloads
    fun install(
        context: Context,
        client: OkHttpClient? = null,
        policy: CronetPolicy = DefaultPolicy(),
        mapper: RequestToUrlRequestMapper = RequestToUrlRequestMapper.NOOP,
        configure: (CronetEngine.Builder) -> Unit = {},
    ) {
        warnIfUnverified(OkHttp.VERSION)
        val chosen = selectCronetProvider(
            CronetProvider.getAllProviders(context).map { LiveCronetProvider(it) },
        )
        if (chosen == null) {
            if (missingProviderLogged.compareAndSet(false, true)) {
                runtimeLogger.warning(
                    "No enabled Cronet provider (app-packaged, HttpEngine, or Play Services); " +
                        "leaving requests on stock OkHttp (engine_missing). " +
                        "Fallback-Cronet-Provider is not used.",
                )
            }
            return
        }
        val storageDir = File(context.noBackupFilesDir, STORAGE_DIR_NAME)
        if (!storageDir.isDirectory && !storageDir.mkdirs()) {
            if (storageDirLogged.compareAndSet(false, true)) {
                runtimeLogger.warning(
                    "Could not create ${storageDir.absolutePath}; leaving requests on stock OkHttp " +
                        "(engine_missing).",
                )
            }
            return
        }
        val translation = translatePins(client?.certificatePinner?.pins ?: emptySet())
        val cronetBuilder = chosen.source.createBuilder()
        val seam = CronetEngineBuilderAdapter(cronetBuilder)
        applyEngineConfiguration(seam, storageDir.absolutePath, translation.groups) {
            configure(cronetBuilder)
        }
        val engine = cronetBuilder.build()
        current = RuntimeSnapshot(
            engine = engine,
            policy = policy,
            mapper = mapper,
            installedAtMillis = System.currentTimeMillis(),
            sarieBuilt = true,
            installedPins = translation.installedPins,
            providerName = chosen.name,
            providerVersion = chosen.version,
        )
    }

    /**
     * Publishes a borrowed [engine]. Not Sarie-built: no installed pins and no provider record.
     * A second call replaces the snapshot; the previous engine is not shut down.
     *
     * @param engine Host-built engine. The host owns its lifecycle. Compression and HTTP cache
     *   are whatever the host set; Sarie still disables the cache on each request.
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

    /** Drops the snapshot reference. The engine keeps running; this does not call shutdown. */
    fun uninstall() {
        current = null
    }

    fun snapshot(): RuntimeSnapshot? = current

    /** Kill switch via system property (default true) plus snapshot presence. */
    fun isEnabled(): Boolean =
        System.getProperty(KILL_SWITCH_PROPERTY, "true").toBoolean() && current != null
}

/**
 * Backward-compatibility alias for [SarieBridge].
 */
@Deprecated(
    message = "Renamed to SarieBridge to match the library name.",
    replaceWith = ReplaceWith("SarieBridge", "sarie.bridge.SarieBridge"),
)
typealias CronetRuntime = SarieBridge

/**
 * Runtime compatibility tripwire: the build-time registry pins verified okhttp versions, but a
 * host app may ship a different one. Warns instead of failing - routing is unaffected. Called
 * once per [SarieBridge.install]; `OkHttp.VERSION` has no ConstantValue attribute (javap on
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
