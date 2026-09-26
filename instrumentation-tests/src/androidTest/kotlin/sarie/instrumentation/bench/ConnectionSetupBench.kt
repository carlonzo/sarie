package sarie.instrumentation.bench

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.QuicOptions
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.Assume.assumeTrue
import org.junit.Test
import sarie.bridge.CronetOptOut
import sarie.bridge.DefaultPolicy
import sarie.bridge.FallbackReason
import sarie.bridge.SarieBridge
import sarie.bridge.SarieConfig
import sarie.bridge.SarieListener
import sarie.bridge.SarieTimings

/**
 * Connection-setup benchmark: stock OkHttp vs Sarie vs a plain CronetEngine, cold then warm, one
 * request each per host. One `am instrument` run is one app launch; `scripts/bench-connection-setup.sh`
 * runs it N times (with and without `pm clear`) to separate first-launch from persisted state.
 * Skipped unless the instrumentation arg `bench=true` is set. Emits `SarieBench` logcat CSV rows.
 */
class ConnectionSetupBench {

    private val hosts = listOf(
        "images.unsplash.com" to "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=100&q=60",
        "cloudflare-quic.com" to "https://cloudflare-quic.com/",
        "www.google.com" to "https://www.google.com/",
        "cdn.jsdelivr.net" to "https://cdn.jsdelivr.net/npm/jquery@3.7.1/package.json",
    )

    private val args = InstrumentationRegistry.getArguments()
    private val launch = args.getString("launch") ?: "0"
    private val label = args.getString("label") ?: "run"

    @Test
    fun connectionSetup() {
        assumeTrue("set -e bench true", args.getString("bench") == "true")
        val context: Context = ApplicationProvider.getApplicationContext()

        val sarieTimings = ConcurrentHashMap<Call, SarieTimings>()
        SarieBridge.install(
            context,
            SarieConfig {
                policy(DefaultPolicy { allowedOrigins(hosts.map { it.first }.toSet()) })
                listener(object : SarieListener {
                    override fun onRouted(call: Call, reason: FallbackReason?) = Unit
                    override fun onFinished(call: Call, timings: SarieTimings) {
                        sarieTimings[call] = timings
                    }
                })
                configure { b ->
                    hosts.forEach { b.addQuicHint(it.first, 443, 443) }
                    b.setQuicOptions(shortIdle())
                }
            },
        )
        val cronet = CronetEngine.Builder(context)
            .enableQuic(true)
            .enableHttp2(true)
            .setStoragePath(File(context.cacheDir, "bench-cronet").apply { mkdirs() }.absolutePath)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 0L)
            .apply { hosts.forEach { addQuicHint(it.first, 443, 443) } }
            .setQuicOptions(shortIdle())
            .build()

        val stockEvents = StockEvents()
        val stock = OkHttpClient.Builder()
            .eventListener(stockEvents)
            .addInterceptor { it.proceed(it.request().newBuilder().tag(CronetOptOut::class.java, CronetOptOut).build()) }
            .build()
        val sarie = OkHttpClient()

        val stacks = listOf("stock", "sarie", "cronet")
        // cold: first connection in this process. warm: immediate second request (reuse).
        // resume: after every connection is gone (QUIC idle timeout, OkHttp evictAll) -> new
        // connection with in-process resumption state (QUIC 0-RTT / TLS session ticket).
        for (phase in listOf("cold", "resume")) {
            if (phase == "resume") {
                Thread.sleep(IDLE_SECONDS * 1_000 + 1_000)
                stock.connectionPool.evictAll()
            }
            for ((hostIndex, target) in hosts.withIndex()) {
                val (host, url) = target
                // Rotate order so no stack always benefits from the OS (netd) DNS cache warmed by another.
                val shift = (launch.toIntOrNull() ?: 0) + hostIndex
                val order = stacks.indices.map { stacks[(it + shift) % stacks.size] }
                for ((position, stack) in order.withIndex()) {
                    // warm follows cold immediately, before the QUIC session idles out.
                    val phases = if (phase == "cold") listOf("cold", "warm") else listOf(phase)
                    for (p in phases) {
                        val row = when (stack) {
                            "stock" -> runStock(stock, stockEvents, url)
                            "sarie" -> runSarie(sarie, sarieTimings, url)
                            else -> runCronet(cronet, url)
                        }
                        Log.i(TAG, "BENCH,$label,$launch,$host,$stack,$p,$position,$row")
                    }
                }
            }
        }
        // Let Cronet flush HttpServerProperties to storage before the process dies.
        Thread.sleep(2_000)
        @Suppress("DEPRECATION")
        cronet.shutdown()
    }

    private fun runStock(client: OkHttpClient, events: StockEvents, url: String): String {
        val call = client.newCall(Request.Builder().url(url).build())
        val start = System.nanoTime()
        val protocol = try {
            call.execute().use { it.protocol.toString() }
        } catch (e: Exception) {
            "error:${e.javaClass.simpleName}"
        }
        val wall = ms(System.nanoTime() - start)
        val m = events.byCall.remove(call) ?: StockCall()
        val reused = m.connectStart == null
        return row(protocol, reused, m.dur(m.dnsStart, m.dnsEnd), m.dur(m.connectStart, m.connectEnd),
            m.dur(m.tlsStart, m.tlsEnd), m.dur(m.callStart, m.headersStart), wall)
    }

    private fun runSarie(client: OkHttpClient, timings: ConcurrentHashMap<Call, SarieTimings>, url: String): String {
        val call = client.newCall(Request.Builder().url(url).build())
        val start = System.nanoTime()
        val protocol = try {
            call.execute().use { it.protocol.toString() }
        } catch (e: Exception) {
            "error:${e.javaClass.simpleName}"
        }
        val wall = ms(System.nanoTime() - start)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (timings[call] == null && System.nanoTime() < deadline) Thread.sleep(5)
        val t = timings.remove(call) ?: return row(protocol, false, null, null, null, null, wall)
        val ttfb = if (t.responseStartAtMillis != null && t.requestStartAtMillis != null) {
            t.responseStartAtMillis!! - t.requestStartAtMillis!!
        } else null
        return row(t.negotiatedProtocol ?: protocol, t.socketReused, t.dnsMs, t.connectMs, t.tlsMs, ttfb, wall)
    }

    private fun runCronet(engine: CronetEngine, url: String): String {
        val done = CountDownLatch(1)
        var info: RequestFinishedInfo? = null
        var responseAt = 0L
        val direct = Executor { it.run() }
        val start = System.nanoTime()
        engine.newUrlRequestBuilder(url, object : UrlRequest.Callback() {
            override fun onRedirectReceived(r: UrlRequest, i: UrlResponseInfo, u: String) = r.followRedirect()
            override fun onResponseStarted(r: UrlRequest, i: UrlResponseInfo) {
                responseAt = System.nanoTime()
                r.cancel() // Same as closing the OkHttp response without reading the body.
            }
            override fun onReadCompleted(r: UrlRequest, i: UrlResponseInfo, b: ByteBuffer) = Unit
            override fun onSucceeded(r: UrlRequest, i: UrlResponseInfo) = Unit
            override fun onFailed(r: UrlRequest, i: UrlResponseInfo?, e: CronetException) = Unit
        }, direct)
            .allowDirectExecutor()
            .setRequestFinishedListener(object : RequestFinishedInfo.Listener(direct) {
                override fun onRequestFinished(requestInfo: RequestFinishedInfo) {
                    info = requestInfo
                    done.countDown()
                }
            })
            .build()
            .start()
        done.await(30, TimeUnit.SECONDS)
        val wall = if (responseAt != 0L) ms(responseAt - start) else null
        val m = info?.metrics
        fun d(a: Date?, b: Date?) = if (a != null && b != null) b.time - a.time else null
        val ttfb = d(m?.requestStart, m?.responseStart)
        return row(info?.responseInfo?.negotiatedProtocol ?: "error", m?.socketReused ?: false,
            d(m?.dnsStart, m?.dnsEnd), d(m?.connectStart, m?.connectEnd), d(m?.sslStart, m?.sslEnd), ttfb, wall)
    }

    private fun row(proto: String, reused: Boolean, dns: Long?, conn: Long?, tls: Long?, ttfb: Long?, wall: Long?) =
        "$proto,$reused,${dns ?: ""},${conn ?: ""},${tls ?: ""},${ttfb ?: ""},${wall ?: ""}"

    private fun ms(ns: Long) = ns / 1_000_000

    class StockCall {
        var callStart: Long? = null
        var dnsStart: Long? = null
        var dnsEnd: Long? = null
        var connectStart: Long? = null
        var connectEnd: Long? = null
        var tlsStart: Long? = null
        var tlsEnd: Long? = null
        var headersStart: Long? = null
        fun dur(a: Long?, b: Long?) = if (a != null && b != null) (b - a) / 1_000_000 else null
    }

    class StockEvents : EventListener() {
        val byCall = ConcurrentHashMap<Call, StockCall>()
        private fun m(call: Call) = byCall.getOrPut(call) { StockCall() }
        private fun now() = System.nanoTime()
        override fun callStart(call: Call) { m(call).callStart = now() }
        override fun dnsStart(call: Call, domainName: String) { m(call).dnsStart = now() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { m(call).dnsEnd = now() }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { m(call).connectStart = now() }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { m(call).connectEnd = now() }
        override fun secureConnectStart(call: Call) { m(call).tlsStart = now() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) { m(call).tlsEnd = now() }
        override fun responseHeadersStart(call: Call) { m(call).headersStart = now() }
    }

    // Short QUIC idle timeout (both Cronet engines) so the resume phase gets a fresh connection.
    private fun shortIdle() = QuicOptions.builder().setIdleConnectionTimeoutSeconds(IDLE_SECONDS).build()

    private companion object {
        const val TAG = "SarieBench"
        const val IDLE_SECONDS = 2L
    }
}
