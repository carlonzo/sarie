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
    private const val STORAGE_DIR_NAME = "cronet-cache"

    private val missingProviderLogged = AtomicBoolean(false)
    private val storageDirLogged = AtomicBoolean(false)

    @Volatile
    private var current: RuntimeSnapshot? = null

    /** The last engine this object built. Survives [uninstall] so a reinstall can reuse it. */
    @Volatile
    private var lastBuilt: RuntimeSnapshot? = null

    /**
     * Builds a Cronet engine and publishes it. [configure] runs after the overridable defaults
     * (connection migration and stale DNS) and before bridge-owned settings, which overwrite
     * brotli, the HTTP cache, the storage path, pins, and local-trust pin bypass. Stale DNS
     * stays at the default unless [configure] replaces it.
     *
     * When no enabled app-packaged, HttpEngine, or Play Services provider exists, this does not
     * publish a snapshot (logged once). Requests keep falling back with `reason=engine_missing`.
     * The Java fallback provider is never selected.
     *
     * Call it once per process. A later call reuses the engine already built (its storage path
     * stays locked while it runs) and only swaps [policy] and [mapper]; its [client] pins and
     * [configure] are ignored, with a warning.
     *
     * @param client Source of certificate pins. Null installs no pins.
     * @param configure Tuning such as QUIC hints. Do not add pins here: Cronet enforces them but
     *   the routing policy cannot see them, so OkHttp clients without those pins would still be
     *   routed to Cronet. Put pins on the [client]'s `CertificatePinner`.
     */
    @JvmOverloads
    fun install(
        context: Context,
        client: OkHttpClient? = null,
        policy: CronetPolicy = DefaultPolicy(),
        mapper: RequestToUrlRequestMapper = RequestToUrlRequestMapper.NOOP,
        listener: SarieListener? = null,
        configure: (CronetEngine.Builder) -> Unit = {},
    ) {
        // Before the provider lookup: a failed install still reports engine_missing.
        this.listener = listener
        warmTrustBaseline()
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
        val storageDir = File(context.cacheDir, STORAGE_DIR_NAME)
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
        val engine = try {
            cronetBuilder.build()
        } catch (e: IllegalStateException) {
            // Cronet refuses a storage path a live engine holds ("Disk cache storage path already
            // in use"). Sarie never shuts its engine down, so a second install in this process
            // lands here: keep the engine that owns the path, with the pins it actually enforces.
            val previous = lastBuilt ?: throw e
            runtimeLogger.warning(
                "Sarie already built a Cronet engine in this process; reusing it. Pins and " +
                    "configure from this install are ignored (${e.message}).",
            )
            current = RuntimeSnapshot(
                engine = previous.engine,
                policy = policy,
                mapper = mapper,
                installedAtMillis = System.currentTimeMillis(),
                sarieBuilt = true,
                installedPins = previous.installedPins,
                providerName = previous.providerName,
                providerVersion = previous.providerVersion,
            )
            return
        }
        val snapshot = RuntimeSnapshot(
            engine = engine,
            policy = policy,
            mapper = mapper,
            installedAtMillis = System.currentTimeMillis(),
            sarieBuilt = true,
            installedPins = translation.installedPins,
            providerName = chosen.name,
            providerVersion = chosen.version,
        )
        lastBuilt = snapshot
        current = snapshot
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
        listener: SarieListener? = null,
    ) {
        this.listener = listener
        warmTrustBaseline()
        warnIfUnverified(OkHttp.VERSION)
        current = RuntimeSnapshot(
            engine,
            policy,
            mapper,
            System.currentTimeMillis(),
        )
    }

    /**
     * The listener from the last [install], kept apart from the snapshot so it also hears
     * `engine_missing`: an install that found no provider, and calls after [uninstall].
     */
    @Volatile
    internal var listener: SarieListener? = null
        private set

    /**
     * Drops the snapshot reference. The engine keeps running; this does not call shutdown.
     * The listener stays, so the fallbacks that follow are still reported.
     */
    fun uninstall() {
        current = null
    }

    internal fun snapshot(): RuntimeSnapshot? = current

    /** The engine requests are routed to, or null when nothing is installed. Never shut it down. */
    val engine: CronetEngine? get() = current?.engine

    /** Kill switch via system property (default true) plus snapshot presence. */
    fun isEnabled(): Boolean =
        System.getProperty(KILL_SWITCH_PROPERTY, "true").toBoolean() && current != null
}

/**
 * Pays the platform-CA hash on the install thread instead of the first request. A failure here
 * is left for the policy, which fails closed to stock OkHttp; install itself never throws for it.
 */
private fun warmTrustBaseline() {
    runCatching { TrustBaseline.baseline }
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
