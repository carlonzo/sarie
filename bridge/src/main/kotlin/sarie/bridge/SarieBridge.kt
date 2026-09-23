package sarie.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttp
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider

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

    private val missingProviderLogged = AtomicBoolean(false)
    private val storageDirLogged = AtomicBoolean(false)
    private val installerFailedLogged = AtomicBoolean(false)

    private val installGeneration = AtomicInteger(0)

    private val installerExecutor: Executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sarie-play-services-init").apply { isDaemon = true }
        }
    }

    @Volatile
    private var current: RuntimeSnapshot? = null

    /** The last engine this object built. Survives [uninstall] so a reinstall can reuse it. */
    @Volatile
    private var lastBuilt: RuntimeSnapshot? = null

    /**
     * Builds a Cronet engine with the default configuration and publishes it.
     */
    fun install(context: Context) {
        install(context, SarieConfig.DEFAULT)
    }

    /**
     * Builds a Cronet engine and publishes it. [SarieConfig.configure] runs after the overridable
     * defaults (connection migration and stale DNS) and before bridge-owned settings, which overwrite
     * brotli, the HTTP cache, the storage path, pins, and local-trust pin bypass. Stale DNS
     * stays at the default unless configure replaces it.
     *
     * When no enabled app-packaged, HttpEngine, or Play Services provider exists, this does not
     * publish a snapshot (logged once). Requests keep falling back with `reason=engine_missing`.
     * The Java fallback provider is never selected.
     *
     * Call it once per process. A later call reuses the engine already built (its storage path
     * stays locked while it runs) and only swaps policy and mapper; its pins and configure
     * are ignored, with a warning.
     */
    fun install(
        context: Context,
        config: SarieConfig,
    ) {
        val generation = installGeneration.incrementAndGet()
        // Before the provider lookup: a failed install still reports engine_missing.
        this.logger = config.logger
        this.listener = config.listener
        warmTrustBaseline()
        warnIfUnverified(OkHttp.VERSION)
        val providers = CronetProvider.getAllProviders(context).map { LiveCronetProvider(it) }
        when (decideProviderPlan(providers, isPlayServicesInstallerAvailable())) {
            ProviderDecision.BUILD_NOW -> {
                buildAndPublishEngine(context, config, providers, generation)
            }
            ProviderDecision.RUN_INSTALLER -> {
                initPlayServicesAndBuild(context, config, generation)
            }
            ProviderDecision.GIVE_UP -> {
                if (missingProviderLogged.compareAndSet(false, true)) {
                    logger?.let {
                        it.log(
                            Log.WARN,
                            "No enabled Cronet provider (app-packaged, HttpEngine, or Play Services); " +
                                "leaving requests on stock OkHttp (engine_missing). " +
                                "Fallback-Cronet-Provider is not used.",
                            null,
                        )
                    }
                }
            }
        }
    }

    private fun initPlayServicesAndBuild(
        context: Context,
        config: SarieConfig,
        generation: Int,
    ) {
        try {
            com.google.android.gms.net.CronetProviderInstaller.installProvider(context)
                .addOnCompleteListener(installerExecutor) { task ->
                    if (installGeneration.get() != generation) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        val fresh = CronetProvider.getAllProviders(context).map { LiveCronetProvider(it) }
                        buildAndPublishEngine(context, config, fresh, generation)
                    } else {
                        if (installerFailedLogged.compareAndSet(false, true)) {
                            logger?.let {
                                it.log(
                                    Log.WARN,
                                    "Play Services CronetProviderInstaller failed; " +
                                        "leaving requests on stock OkHttp (engine_missing).",
                                    task.exception,
                                )
                            }
                        }
                    }
                }
        } catch (t: Throwable) {
            if (installerFailedLogged.compareAndSet(false, true)) {
                logger?.let {
                    it.log(
                        Log.WARN,
                        "Play Services CronetProviderInstaller failed to start; " +
                            "leaving requests on stock OkHttp (engine_missing).",
                        t,
                    )
                }
            }
        }
    }

    private fun buildAndPublishEngine(
        context: Context,
        config: SarieConfig,
        providers: List<LiveCronetProvider>,
        generation: Int,
    ) {
        val chosen = selectCronetProvider(providers)
        if (chosen == null) {
            if (missingProviderLogged.compareAndSet(false, true)) {
                logger?.let {
                    it.log(
                        Log.WARN,
                        "No enabled Cronet provider (app-packaged, HttpEngine, or Play Services); " +
                            "leaving requests on stock OkHttp (engine_missing). " +
                            "Fallback-Cronet-Provider is not used.",
                        null,
                    )
                }
            }
            return
        }
        val dirName = cronetStorageDirName(currentProcessName(), context.packageName)
        val storageDir = File(context.cacheDir, dirName)
        if (!storageDir.isDirectory && !storageDir.mkdirs()) {
            if (storageDirLogged.compareAndSet(false, true)) {
                logger?.let {
                    it.log(
                        Log.WARN,
                        "Could not create ${storageDir.absolutePath}; leaving requests on stock OkHttp " +
                            "(engine_missing).",
                        null,
                    )
                }
            }
            return
        }
        val translation = translatePins(config.certificatePinner?.pins ?: emptySet())
        val cronetBuilder = chosen.source.createBuilder()
        val seam = CronetEngineBuilderAdapter(cronetBuilder)
        applyEngineConfiguration(seam, storageDir.absolutePath, translation.groups) {
            config.configure(cronetBuilder)
        }
        val engine = try {
            cronetBuilder.build()
        } catch (e: IllegalStateException) {
            // Cronet refuses a storage path a live engine holds ("Disk cache storage path already
            // in use"). Sarie never shuts its engine down, so a second install in this process
            // lands here: keep the engine that owns the path, with the pins it actually enforces.
            val previous = lastBuilt ?: throw e
            logger?.let {
                it.log(
                    Log.WARN,
                    "Sarie already built a Cronet engine in this process; reusing it. Pins and " +
                        "configure from this install are ignored (${e.message}).",
                    e,
                )
            }
            val reused = RuntimeSnapshot(
                engine = previous.engine,
                policy = config.policy,
                mapper = config.mapper,
                installedAtMillis = System.currentTimeMillis(),
                sarieBuilt = true,
                installedPins = previous.installedPins,
                providerName = previous.providerName,
                providerVersion = previous.providerVersion,
            )
            publishIfCurrentGeneration(generation, reused)
            return
        }
        val snapshot = RuntimeSnapshot(
            engine = engine,
            policy = config.policy,
            mapper = config.mapper,
            installedAtMillis = System.currentTimeMillis(),
            sarieBuilt = true,
            installedPins = translation.installedPins,
            providerName = chosen.name,
            providerVersion = chosen.version,
        )
        lastBuilt = snapshot
        publishIfCurrentGeneration(generation, snapshot)
    }

    /**
     * Publishes a borrowed [engine] with the default configuration.
     */
    fun install(engine: CronetEngine) {
        install(engine, SarieConfig.DEFAULT)
    }

    /**
     * Publishes a borrowed [engine]. Not Sarie-built: no installed pins and no provider record.
     * A second call replaces the snapshot; the previous engine is not shut down.
     *
     * @param engine Host-built engine. The host owns its lifecycle. Compression and HTTP cache
     *   are whatever the host set; Sarie still disables the cache on each request.
     * @param config Configuration for routing policy, mapper, and listener. Setting
     *   `certificatePinner` or `configure` throws [IllegalArgumentException].
     */
    fun install(
        engine: CronetEngine,
        config: SarieConfig,
    ) {
        val generation = installGeneration.incrementAndGet()
        require(config.certificatePinner == null) {
            "certificatePinner cannot be used with a borrowed CronetEngine"
        }
        require(!config.isConfigureSet) {
            "configure cannot be used with a borrowed CronetEngine"
        }
        this.logger = config.logger
        this.listener = config.listener
        warmTrustBaseline()
        warnIfUnverified(OkHttp.VERSION)
        publishIfCurrentGeneration(
            generation,
            RuntimeSnapshot(
                engine,
                config.policy,
                config.mapper,
                System.currentTimeMillis(),
            ),
        )
    }

    /**
     * The listener from the last [install], kept apart from the snapshot so it also hears
     * `engine_missing`: an install that found no provider, and calls after [uninstall].
     */
    @Volatile
    internal var listener: SarieListener? = null
        private set

    @Volatile
    internal var logger: SarieLogger? = null
        private set

    /**
     * Drops the snapshot reference. The engine keeps running; this does not call shutdown.
     * The listener stays, so the fallbacks that follow are still reported.
     */
    fun uninstall() {
        installGeneration.incrementAndGet()
        current = null
    }

    internal fun nextGeneration(): Int = installGeneration.incrementAndGet()

    internal fun publishIfCurrentGeneration(generation: Int, snapshot: RuntimeSnapshot): Boolean {
        if (installGeneration.get() != generation) return false
        current = snapshot
        return true
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
    SarieBridge.logger?.let {
        it.log(
            Log.WARN,
            "okhttp-cronet is running against okhttp $runtimeVersion, which was not verified with this " +
                "build (verified: ${VerifiedOkHttpVersions.sorted().joinToString()}). The build-time " +
                "structural guard covered ConnectInterceptor only; run the verification suites for this version.",
            null,
        )
    }
}
