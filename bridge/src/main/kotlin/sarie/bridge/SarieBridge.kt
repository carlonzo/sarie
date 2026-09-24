package sarie.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
public object SarieBridge {
    private const val KILL_SWITCH_PROPERTY = "okhttp.cronet.enabled"


    private val generations = InstallGeneration()

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
     * Builds a Cronet engine with the default configuration ([SarieConfig.DEFAULT]) and publishes it.
     *
     * Should be called early during application startup off the main thread.
     *
     * @param context Android application or activity context.
     */
    public fun install(context: Context) {
        install(context, SarieConfig.DEFAULT)
    }

    /**
     * Builds a Cronet engine with the configuration constructed by [block] and publishes it.
     *
     * Should be called early during application startup off the main thread.
     *
     * @param context Android application or activity context.
     * @param block Configuration lambda executed on [SarieConfig.Builder].
     */
    public inline fun install(
        context: Context,
        block: SarieConfig.Builder.() -> Unit,
    ): Unit {
        install(context, SarieConfig(block))
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
     *
     * @param context Android application or activity context.
     * @param config Configuration for policy, mapper, listener, logger, pins, and builder hooks.
     */
    public fun install(
        context: Context,
        config: SarieConfig,
    ) {
        val generation = generations.next()
        // Before the provider lookup: a failed install still reports engine_missing.
        this.logger = config.debugLogger?.let(::SwallowingLogger)
        this.listener = config.listener
        warmTrustBaseline()
        warnIfUnverified(OkHttp.VERSION)
        val providers = CronetProvider.getAllProviders(context).map { LiveCronetProvider(it) }
        when (decideProviderPlan(providers, isPlayServicesInstallerAvailable())) {
            ProviderDecision.BUILD_NOW -> {
                buildAndPublishEngine(context, config, providers, generation)
            }
            ProviderDecision.RUN_INSTALLER -> {
                initPlayServicesAndBuild(context, config, providers, generation)
            }
            ProviderDecision.GIVE_UP -> warnNoProvider()
        }
    }

    /**
     * Runs the Play Services installer, then re-runs provider selection: Play Services on
     * success, otherwise the next enabled provider (HttpEngine), otherwise engine_missing.
     */
    private fun initPlayServicesAndBuild(
        context: Context,
        config: SarieConfig,
        providers: List<LiveCronetProvider>,
        generation: Int,
    ) {
        val task = try {
            com.google.android.gms.net.CronetProviderInstaller.installProvider(context)
        } catch (t: Throwable) {
            logger?.log(Log.WARN, "Play Services CronetProviderInstaller failed to start.", t)
            buildAndPublishEngine(context, config, providers, generation)
            return
        }
        task.addOnCompleteListener(installerExecutor) { done ->
            if (!generations.isCurrent(generation)) return@addOnCompleteListener
            if (!done.isSuccessful) {
                logger?.log(Log.WARN, "Play Services CronetProviderInstaller failed.", done.exception)
            }
            // Not the caller's thread: a throw here would crash the process.
            try {
                val fresh = CronetProvider.getAllProviders(context).map { LiveCronetProvider(it) }
                buildAndPublishEngine(context, config, fresh, generation)
            } catch (t: Throwable) {
                logger?.log(
                    Log.WARN,
                    "Building the Cronet engine failed; leaving requests on stock OkHttp " +
                        "(engine_missing).",
                    t,
                )
            }
        }
    }

    private fun warnNoProvider() {
        logger?.log(
            Log.WARN,
            "No enabled Cronet provider (app-packaged, Play Services, or HttpEngine); " +
                "leaving requests on stock OkHttp (engine_missing). " +
                "Fallback-Cronet-Provider is not used.",
            null,
        )
    }

    private fun buildAndPublishEngine(
        context: Context,
        config: SarieConfig,
        providers: List<LiveCronetProvider>,
        generation: Int,
    ) {
        val chosen = selectCronetProvider(providers)
        if (chosen == null) {
            warnNoProvider()
            return
        }
        val dirName = cronetStorageDirName(currentProcessName(), context.packageName)
        val storageDir = File(context.cacheDir, dirName)
        if (!storageDir.isDirectory && !storageDir.mkdirs()) {
            logger?.log(
                Log.WARN,
                "Could not create ${storageDir.absolutePath}; leaving requests on stock OkHttp " +
                    "(engine_missing).",
                null,
            )
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
            logger?.log(
                Log.WARN,
                "Sarie already built a Cronet engine in this process; reusing it. Pins and " +
                    "configure from this install are ignored (${e.message}).",
                e,
            )
            val reused = RuntimeSnapshot(
                engine = previous.engine,
                policy = config.policy,
                mapper = config.mapper,
                installedAtMillis = System.currentTimeMillis(),
                sarieBuilt = true,
                installedPins = previous.installedPins,
                providerName = previous.providerName,
                providerVersion = previous.providerVersion,
                bypassableDns = config.bypassableDns,
            )
            publishIfCurrentGeneration(generation, reused)
            logger?.log(
                Log.INFO,
                "Cronet installed (built, reused): provider=${previous.providerName} ${previous.providerVersion}, pins=${previous.installedPins.size}, storage=${storageDir.absolutePath}",
                null,
            )
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
            bypassableDns = config.bypassableDns,
        )
        lastBuilt = snapshot
        publishIfCurrentGeneration(generation, snapshot)
        logger?.log(
            Log.INFO,
            "Cronet installed (built): provider=${chosen.name} ${chosen.version}, pins=${translation.installedPins.size}, storage=${storageDir.absolutePath}",
            null,
        )
    }

    /**
     * Publishes a borrowed [engine] with the default configuration ([SarieConfig.DEFAULT]).
     *
     * @param engine Host-built Cronet engine. The host owns its lifecycle.
     */
    public fun install(engine: CronetEngine) {
        install(engine, SarieConfig.DEFAULT)
    }

    /**
     * Publishes a borrowed [engine] with the configuration constructed by [block].
     *
     * @param engine Host-built Cronet engine. The host owns its lifecycle.
     * @param block Configuration lambda executed on [SarieConfig.Builder].
     * @throws IllegalArgumentException if `certificatePinner` or `configure` is set in [block].
     */
    public inline fun install(
        engine: CronetEngine,
        block: SarieConfig.Builder.() -> Unit,
    ): Unit {
        install(engine, SarieConfig(block))
    }

    /**
     * Publishes a borrowed [engine]. Not Sarie-built: no installed pins and no provider record.
     * A second call replaces the snapshot; the previous engine is not shut down.
     *
     * @param engine Host-built engine. The host owns its lifecycle. Compression and HTTP cache
     *   are whatever the host set; Sarie still disables the cache on each request.
     * @param config Configuration for routing policy, mapper, listener, logger, and bypassable DNS.
     *   Setting `certificatePinner` or `configure` throws [IllegalArgumentException].
     * @throws IllegalArgumentException if `config.certificatePinner` or `config.configure` is set.
     */
    public fun install(
        engine: CronetEngine,
        config: SarieConfig,
    ) {
        require(config.certificatePinner == null) {
            "certificatePinner cannot be used with a borrowed CronetEngine"
        }
        require(!config.isConfigureSet) {
            "configure cannot be used with a borrowed CronetEngine"
        }
        val generation = generations.next()
        this.logger = config.debugLogger?.let(::SwallowingLogger)
        this.listener = config.listener
        warmTrustBaseline()
        warnIfUnverified(OkHttp.VERSION)
        publishIfCurrentGeneration(
            generation,
            RuntimeSnapshot(
                engine = engine,
                policy = config.policy,
                mapper = config.mapper,
                installedAtMillis = System.currentTimeMillis(),
                bypassableDns = config.bypassableDns,
            ),
        )
        logger?.log(
            Log.INFO,
            "Cronet installed (borrowed): version=${engine.versionString}, pins=0",
            null,
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
    public fun uninstall() {
        synchronized(generations) {
            generations.next()
            current = null
        }
    }

    /** Locked with [uninstall] so a late Play Services callback cannot publish after it. */
    private fun publishIfCurrentGeneration(generation: Int, snapshot: RuntimeSnapshot) {
        synchronized(generations) {
            if (generations.isCurrent(generation)) current = snapshot
        }
    }

    internal fun snapshot(): RuntimeSnapshot? = current

    /**
     * The active [CronetEngine] requests are routed to, or `null` when no engine is installed.
     *
     * Never call [CronetEngine.shutdown] on this instance. Sarie manages engine lifecycle and
     * retains engines across reinstalls.
     */
    public val engine: CronetEngine? get() = current?.engine

    /**
     * Returns whether the bridge is actively routing eligible calls to Cronet.
     *
     * Evaluates to `true` when an engine snapshot is installed and the system property
     * `okhttp.cronet.enabled` is not set to `"false"`.
     */
    public fun isEnabled(): Boolean =
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
 * Runtime compatibility tripwire: the build-time registry pins verified okhttp versions, but a
 * host app may ship a different one. Warns instead of failing - routing is unaffected. Called
 * once per [SarieBridge.install]; `OkHttp.VERSION` has no ConstantValue attribute (javap on
 * the pinned artifact), so the reference is a real GETSTATIC read of the runtime version.
 */
internal fun warnIfUnverified(runtimeVersion: String) {
    if (runtimeVersion in VerifiedOkHttpVersions) return
    SarieBridge.logger?.log(
        Log.WARN,
        "okhttp-cronet is running against okhttp $runtimeVersion, which was not verified with this " +
            "build (verified: ${VerifiedOkHttpVersions.sorted().joinToString()}). The build-time " +
            "structural guard covered ConnectInterceptor only; run the verification suites for this version.",
        null,
    )
}

/**
 * Install counter. The Play Services path builds the engine later, on another thread; it
 * publishes only if no newer [SarieBridge.install] or [SarieBridge.uninstall] happened since.
 */
internal class InstallGeneration {
    private val counter = AtomicInteger()

    fun next(): Int = counter.incrementAndGet()

    fun isCurrent(generation: Int): Boolean = counter.get() == generation
}

/**
 * Host loggers run inside network calls and on Cronet and executor threads; a throw from one
 * must never fail a request or crash a background thread.
 */
private class SwallowingLogger(private val delegate: SarieLogger) : SarieLogger {
    override fun log(priority: Int, message: String, throwable: Throwable?) {
        try {
            delegate.log(priority, message, throwable)
        } catch (_: Throwable) {
        }
    }
}
