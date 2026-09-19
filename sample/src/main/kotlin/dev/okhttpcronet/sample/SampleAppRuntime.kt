package dev.okhttpcronet.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.okhttpcronet.bridge.CronetRuntime
import dev.okhttpcronet.bridge.DefaultPolicy
import dev.okhttpcronet.bridge.RequestToUrlRequestMapper
import java.io.File
import org.chromium.net.CronetEngine

/**
 * Test-host installer. Only ever invoked from androidTest code: [ApplicationProvider] lives on
 * the classpath solely during instrumentation, and cronet-embedded supplies the engine at that
 * point (main sources compile against the cronet-api stubs only).
 *
 * Modes (instrumentation arg `mode`, default "stock"):
 * - "cronet": real embedded engine + baseline policy (allowlist + loopback HTTPS on).
 * - "fallback": same engine, policy permanently disabled -> every request falls back (disabled).
 * - "stock": no snapshot; OkHttp runs entirely stock.
 *
 * CronetSuite passes [quicHintHost]/[quicHintPort] to force first-connection HTTP/3 and
 * [freshStorage] for an isolated storage dir (no cross-test QUIC server-config caching).
 * [lastEngine] exposes the borrowed engine so the owning suite can stop it in @After.
 */
object SampleAppRuntime {

    const val MODE_CRONET = "cronet"
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
    ) {
        if (mode == MODE_STOCK) return
        val context: Context = ApplicationProvider.getApplicationContext()
        val builder = CronetEngine.Builder(context)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)
            // The Caddy origin chains to the NSC raw-resource CA (a local trust anchor).
            // Upstream default (true): chains anchored by local (NSC) CAs bypass pinning.
            // Note: this flag does NOT enable local-root QUIC - that needed the origin to
            // serve the full chain (scripts/gen-certs.sh fullchain.pem), netlog-verified.
            .enablePublicKeyPinningBypassForLocalTrustAnchors(true)
        if (freshStorage) {
            val dir = File(context.cacheDir, "cronet-fresh-" + System.nanoTime())
            dir.mkdirs()
            builder.setStoragePath(dir.absolutePath)
        } else {
            builder.setStoragePath(context.cacheDir.absolutePath)
        }
        if (quicHintHost != null) {
            builder.addQuicHint(quicHintHost, quicHintPort, quicHintPort)
        }
        val engine = builder.build()
        if (netLog) {
            // Unique per engine: several suite engines may capture netlogs in one run.
            @Suppress("DEPRECATION")
            engine.startNetLogToFile(
                File(context.cacheDir, "cronet-netlog-" + System.nanoTime() + ".json").absolutePath,
                false,
            )
        }
        lastEngine = engine
        val policy = DefaultPolicy(
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
                MODE_CRONET -> ({ System.getProperty("okhttp.cronet.enabled", "true").toBoolean() })
                else -> throw IllegalArgumentException("unknown mode: $mode")
            },
        )
        CronetRuntime.install(policy, engine, RequestToUrlRequestMapper { _, _ -> })
    }

    /** True when instrumentation args request Cronet NetLog capture (h3 diagnostics). */
    val netLogRequested: Boolean =
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("netlog") == "true"
}
