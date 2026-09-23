package sarie.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import sarie.bridge.DefaultPolicy
import sarie.bridge.SarieBridge

/**
 * Test-host installer. Only ever invoked from androidTest code: [ApplicationProvider] lives on
 * the classpath solely during instrumentation, and cronet-embedded supplies the engine at that
 * point (main sources compile against the cronet-api stubs only).
 *
 * Modes (instrumentation arg `mode`, default "stock"):
 * - "cronet": Sarie builds the engine (`SarieBridge.install(context, client, configure)`).
 *   QUIC hints go through [configure]. Brotli stays off. The bridge never shuts the engine down.
 * - "borrowed": host-built engine passed to `SarieBridge.install(engine)`. Used by the HTTP
 *   cache bypass test and the brotli decode test. No pins.
 * - "fallback": Sarie-built engine, policy permanently disabled -> every request falls back.
 * - "stock": no snapshot; OkHttp runs entirely stock.
 *
 * [freshStorage] applies only to the borrowed engine. The Sarie-built engine always uses
 * `<noBackupFilesDir>/sarie-cronet`, which [configure] cannot replace.
 * [lastEngine] is the engine from the snapshot so the owning suite can stop it in @After.
 * This object does not call [CronetEngine.shutdown].
 */
object SampleAppRuntime {

    const val MODE_CRONET = "cronet"
    const val MODE_BORROWED = "borrowed"
    const val MODE_FALLBACK = "fallback"
    const val MODE_STOCK = "stock"

    var lastEngine: CronetEngine? = null
        private set

    fun install(
        mode: String,
        quicHintHost: String? = null,
        quicHintPort: Int = 443,
        freshStorage: Boolean = false,
        netLog: Boolean = false,
        client: OkHttpClient? = null,
        brotli: Boolean = false,
        diskCache: Boolean = false,
    ) {
        if (mode == MODE_STOCK) return
        val context: Context = ApplicationProvider.getApplicationContext()
        val policy = samplePolicy(mode)
        if (mode == MODE_BORROWED) {
            installBorrowed(context, policy, quicHintHost, quicHintPort, freshStorage, brotli, diskCache)
        } else {
            // Sarie-built. Do not call enableBrotli(true) and do not shut the engine down.
            SarieBridge.install(context, client, policy) { builder ->
                if (quicHintHost != null) {
                    builder.addQuicHint(quicHintHost, quicHintPort, quicHintPort)
                }
            }
        }
        val engine = SarieBridge.snapshot()?.engine
            ?: error("SarieBridge.install did not publish an engine")
        if (netLog) {
            // Unique per engine: several suite engines may capture netlogs in one run.
            @Suppress("DEPRECATION")
            engine.startNetLogToFile(
                File(context.cacheDir, "cronet-netlog-" + System.nanoTime() + ".json").absolutePath,
                false,
            )
        }
        lastEngine = engine
    }

    /**
     * Host-built engine. [brotli] is the brotli-decode test; [diskCache] is the cache-bypass
     * test (`HTTP_CACHE_DISK`). Pin bypass stays on so chains anchored by the NSC test CA are
     * not treated as pin failures — the Sarie-built engine forces that flag off instead.
     */
    private fun installBorrowed(
        context: Context,
        policy: DefaultPolicy,
        quicHintHost: String?,
        quicHintPort: Int,
        freshStorage: Boolean,
        brotli: Boolean,
        diskCache: Boolean,
    ) {
        val builder = CronetEngine.Builder(context)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(brotli)
            .enablePublicKeyPinningBypassForLocalTrustAnchors(true)
        val storage = if (freshStorage || diskCache) {
            File(context.cacheDir, "cronet-borrowed-" + System.nanoTime()).apply { mkdirs() }
        } else {
            context.cacheDir
        }
        builder.setStoragePath(storage.absolutePath)
        if (diskCache) {
            builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 10L * 1024 * 1024)
        }
        if (quicHintHost != null) {
            builder.addQuicHint(quicHintHost, quicHintPort, quicHintPort)
        }
        SarieBridge.install(builder.build(), policy)
    }

    /**
     * Test isolation: uninstalls, stops the last engine, and deletes the Sarie storage dir.
     * Chromium persists per-host state there (alt-svc, QUIC marked broken after a failed
     * handshake), which would otherwise leak from one test into the next.
     */
    fun reset() {
        SarieBridge.uninstall()
        @Suppress("DEPRECATION")
        lastEngine?.shutdown()
        lastEngine = null
        val context: Context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, "sarie-cronet").deleteRecursively()
    }

    private fun samplePolicy(mode: String): DefaultPolicy = DefaultPolicy(
        allowedOrigins = setOf(
            "10.0.2.2:8443",
            "localhost",
            "127.0.0.1",
            // H3-only public origin (known-public-root cert): the embedded engine's QUIC
            // proof verifier rejects locally-anchored chains outright, so the on-device
            // h3 proof needs one allowlisted public host (see CronetSuite KDoc).
            "cloudflare-quic.com",
        ),
        allowLoopbackHttps = true,
        enabled = when (mode) {
            MODE_FALLBACK -> ({ false })
            MODE_CRONET, MODE_BORROWED -> ({ System.getProperty("okhttp.cronet.enabled", "true").toBoolean() })
            else -> throw IllegalArgumentException("unknown mode: $mode")
        },
    )

    /** True when instrumentation args request Cronet NetLog capture (h3 diagnostics). */
    val netLogRequested: Boolean =
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("netlog") == "true"
}
