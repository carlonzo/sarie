package dev.okhttpcronet.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.okhttpcronet.bridge.CronetRuntime
import dev.okhttpcronet.bridge.DefaultPolicy
import dev.okhttpcronet.bridge.RequestToUrlRequestMapper
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
 */
object SampleAppRuntime {

    const val MODE_CRONET = "cronet"
    const val MODE_FALLBACK = "fallback"
    const val MODE_STOCK = "stock"

    fun install(mode: String) {
        if (mode == MODE_STOCK) return
        val context: Context = ApplicationProvider.getApplicationContext()
        val engine = CronetEngine.Builder(context)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)
            .setStoragePath(context.cacheDir.absolutePath)
            .build()
        val policy = DefaultPolicy(
            allowedOrigins = setOf("10.0.2.2:8443", "localhost", "127.0.0.1"),
            allowLoopbackHttps = true,
            enabled = when (mode) {
                MODE_FALLBACK -> ({ false })
                MODE_CRONET -> ({ System.getProperty("okhttp.cronet.enabled", "true").toBoolean() })
                else -> throw IllegalArgumentException("unknown mode: $mode")
            },
        )
        CronetRuntime.install(policy, engine, RequestToUrlRequestMapper { _, _ -> })
    }
}
