package sarie.sample.minified

import sarie.bridge.CronetRuntime
import sarie.bridge.Metrics
import sarie.sample.SampleAppRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.chromium.net.CronetEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Critical subset executed against the R8-minified (minifiedRelease) APK: the trampoline's
 * INVOKESTATIC root must keep the whole bridge call graph through shrinking, and the
 * suppressed-internal fallback linkage (initExchange$okhttp / copy$okhttp) must survive
 * R8's renaming. Proves: Cronet path serves (h2/h3), public-origin h3 through the
 * trampoline, native WebSocket upgrade, cleartext bypass, kill-switch restore.
 */
class MinifiedSuite {

    companion object {
        const val HOST = "10.0.2.2"
        const val PORT = 8443
        const val ORIGIN = "https://10.0.2.2:8443"
    }

    private lateinit var server: MockWebServer
    private var installedEngine: CronetEngine? = null

    @Before
    fun setUp() {
        Metrics.resetForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        CronetRuntime.uninstall()
        installedEngine?.let { engine ->
            @Suppress("DEPRECATION") // suite owns these engines; stop them to keep the emulator healthy
            engine.shutdown()
        }
        installedEngine = null
        Metrics.resetForTest()
    }

    /** Installs the cronet-mode runtime with an isolated engine (fresh storage dir). */
    private fun installCronet(quicHintHost: String? = HOST, quicHintPort: Int = PORT) {
        SampleAppRuntime.install(
            mode = SampleAppRuntime.MODE_CRONET,
            quicHintHost = quicHintHost,
            quicHintPort = quicHintPort,
            freshStorage = true,
        )
        installedEngine = SampleAppRuntime.lastEngine
    }

    private fun assertCronetServed(minCount: Long = 1) {
        assertTrue("expected the cronet path, cronet=${Metrics.cronet.get()}", Metrics.cronet.get() >= minCount)
        assertNull(
            "cronet-path request recorded a fallback reason: ${Metrics.lastReason}",
            Metrics.lastReason,
        )
    }

    private fun assertFallbackOnly(expectedReason: Metrics.Reason) {
        assertEquals(0, Metrics.cronet.get())
        assertTrue(
            "expected at least one fallback, got ${Metrics.okhttpFallback.get()}",
            Metrics.okhttpFallback.get() >= 1,
        )
        assertEquals(expectedReason, Metrics.lastReason)
    }

    @Test
    fun cronetPathStillServes() {
        installCronet()

        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
            assertTrue(
                "expected the Cronet transport to serve h2 or h3 (client saw ${response.protocol})",
                response.protocol == Protocol.HTTP_2 || response.protocol == Protocol.HTTP_3,
            )
        }
        assertCronetServed()
    }

    @Test
    fun publicOriginH3ThroughTrampoline() {
        // cloudflare-quic.com serves HTTP/3 ONLY (no TCP listener), so no h2 downgrade can
        // fake the result; its cert chains to a known public root, which the embedded
        // engine's QUIC proof verifier requires. Strongest R8 proof: suppressed-internal
        // calls + trampoline + mapping all survive shrinking on a real h3 response.
        installCronet(quicHintHost = "cloudflare-quic.com", quicHintPort = 443)

        OkHttpClient().newCall(Request.Builder().url("https://cloudflare-quic.com/").build())
            .execute()
            .use { response ->
                assertEquals(
                    "expected real HTTP/3 through the Cronet path (client saw ${response.protocol})",
                    Protocol.HTTP_3,
                    response.protocol,
                )
                assertEquals(200, response.code)
            }
        assertCronetServed()
    }

    @Test
    fun webSocketUpgradeStillNative() {
        installCronet()

        val echoed = CountDownLatch(1)
        val received = AtomicReference<String>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("echo:$text")
                }
            }).build(),
        )
        val ws = OkHttpClient().newWebSocket(
            Request.Builder().url(server.url("/ws")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("hello")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.set(text)
                    echoed.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    echoed.countDown()
                }
            },
        )
        assertTrue("websocket echo did not complete", echoed.await(15, TimeUnit.SECONDS))
        assertEquals("echo:hello", received.get())
        // cancel() (not close()): the close handshake is async and would leave the server
        // socket open for MockWebServer.close() in tearDown.
        ws.cancel()
        // The upgrade is cleartext, so the stock fallback served it natively (the cleartext
        // rule precedes the websocket rule in the policy order).
        assertFallbackOnly(Metrics.Reason.cleartext)
    }

    @Test
    fun cleartextBypass() {
        installCronet()
        server.enqueue(MockResponse.Builder().code(200).body("cleartext-ok").build())

        OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("cleartext-ok", response.body.string())
        }
        assertFallbackOnly(Metrics.Reason.cleartext)
    }

    @Test
    fun killSwitchRestoresStock() {
        // Policy enabled=false (MODE_FALLBACK): kill switch off -> everything served stock,
        // including HTTPS to an allowlisted origin (the disabled rule precedes allowlist).
        SampleAppRuntime.install(SampleAppRuntime.MODE_FALLBACK, freshStorage = true)
        installedEngine = SampleAppRuntime.lastEngine

        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
        }
        assertFallbackOnly(Metrics.Reason.disabled)
    }
}
