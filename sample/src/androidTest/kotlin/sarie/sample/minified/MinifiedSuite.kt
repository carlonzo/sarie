package sarie.sample.minified

import sarie.bridge.FallbackReason
import sarie.sample.NetworkParity
import sarie.sample.SampleAppRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
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
        SampleAppRuntime.routes.clear()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        // Stops the engine and wipes the Sarie storage dir (persisted QUIC state).
        SampleAppRuntime.reset()
        installedEngine = null
        SampleAppRuntime.routes.clear()
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

    private fun assertCronetServed(minCount: Int = 1) {
        val routes = SampleAppRuntime.routes
        assertTrue("expected the cronet path, cronet=${routes.cronetCount()}", routes.cronetCount() >= minCount)
        assertNull(
            "cronet-path request recorded a fallback reason: ${routes.lastReason()}",
            routes.lastReason(),
        )
    }

    private fun assertFallbackOnly(expectedReason: FallbackReason) {
        val routes = SampleAppRuntime.routes
        assertEquals(0, routes.cronetCount())
        assertTrue(
            "expected at least one fallback, got ${routes.fallbackCount()}",
            routes.fallbackCount() >= 1,
        )
        assertEquals(expectedReason, routes.lastReason())
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
        // cloudflare-quic.com also serves h2 over TCP now (checked 2026-09-23); the
        // HTTP_3 assertion is the proof. Its cert chains to a known public root, which the embedded
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
        assertFallbackOnly(FallbackReason.cleartext)
    }

    @Test
    fun cleartextBypass() {
        installCronet()
        server.enqueue(MockResponse.Builder().code(200).body("cleartext-ok").build())

        OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("cleartext-ok", response.body.string())
        }
        assertFallbackOnly(FallbackReason.cleartext)
    }

    @Test
    fun callServerTrampolineSurvivesR8() {
        // A network interceptor only reaches a Cronet response if the CallServerInterceptor
        // prefix still calls CronetBridge.callServer after R8. Stock CallServer NPEs when
        // the exchange is null, and a stripped invokestatic fails the call.
        installCronet(quicHintHost = "cloudflare-quic.com", quicHintPort = 443)
        val seen = java.util.concurrent.atomic.AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(Interceptor { chain ->
                seen.set(true)
                chain.proceed(chain.request())
            })
            .build()
        client.newCall(Request.Builder().url("https://cloudflare-quic.com/").build()).execute().use { response ->
            assertEquals(Protocol.HTTP_3, response.protocol)
            assertEquals(200, response.code)
        }
        assertTrue(seen.get())
        assertCronetServed()
    }

    @Test
    fun networkInterceptorsSeeRequestResponseAndHttp3() {
        NetworkParity.loggingAndChuckerSeeHttp3 {
            installCronet(quicHintHost = "cloudflare-quic.com", quicHintPort = 443)
        }
    }

    @Test
    fun networkInterceptorHeaderReachesOrigin() {
        NetworkParity.addedHeaderReachesOrigin({ installCronet(quicHintHost = null) }, ORIGIN)
    }

    @Test
    fun networkInterceptorUrlAndProceedGuards() {
        NetworkParity.urlGuardsThrowStockMessages({ installCronet(quicHintHost = null) }, ORIGIN)
    }

    @Test
    fun networkInterceptorReadTimeoutAbortsStall() {
        NetworkParity.readTimeoutFromNetworkInterceptorAborts({ installCronet(quicHintHost = null) }, ORIGIN)
    }

    @Test
    fun eventListenerHeaderOrderAroundCronetHandoff() {
        NetworkParity.eventListenerHeaderOrder({ installCronet(quicHintHost = null) }, ORIGIN)
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
        assertFallbackOnly(FallbackReason.disabled)
    }
}
