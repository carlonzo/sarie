package sarie.sample.baseline

import androidx.test.platform.app.InstrumentationRegistry
import sarie.bridge.CronetRuntime
import sarie.bridge.Metrics
import sarie.sample.SampleAppRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stock/fallback baseline on a real device: the real embedded engine is installed, but every
 * request here is denied by policy (cleartext / websocket / disabled), so the suite proves the
 * exact-stock fallback path end-to-end on device — behavior AND the recorded path/reason.
 */
class BaselineSuite {

    private lateinit var server: MockWebServer
    private var installedMode: String = SampleAppRuntime.MODE_STOCK

    private val mode: String
        get() = InstrumentationRegistry.getArguments().getString("mode") ?: SampleAppRuntime.MODE_STOCK

    @Before
    fun setUp() {
        Metrics.resetForTest()
        server = MockWebServer()
        server.start()
        when (mode) {
            SampleAppRuntime.MODE_CRONET -> installCronet()
            SampleAppRuntime.MODE_FALLBACK -> installDisabled()
        }
    }

    @After
    fun tearDown() {
        server.close()
        CronetRuntime.uninstall()
        Metrics.resetForTest()
        installedMode = SampleAppRuntime.MODE_STOCK
    }

    private fun installCronet() {
        if (installedMode == SampleAppRuntime.MODE_CRONET) return
        SampleAppRuntime.install(SampleAppRuntime.MODE_CRONET)
        installedMode = SampleAppRuntime.MODE_CRONET
    }

    private fun installDisabled() {
        if (installedMode == SampleAppRuntime.MODE_FALLBACK) return
        SampleAppRuntime.install(SampleAppRuntime.MODE_FALLBACK)
        installedMode = SampleAppRuntime.MODE_FALLBACK
    }

    private fun getRequest() = Request.Builder().url(server.url("/")).build()

    private fun enqueueCleartext200(body: String) {
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
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
    fun cleartextGoesToStock() {
        installCronet()
        enqueueCleartext200("baseline-ok")

        OkHttpClient().newCall(getRequest()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("baseline-ok", response.body.string())
        }
        assertFallbackOnly(Metrics.Reason.cleartext)
    }

    @Test
    fun applicationInterceptorsStillRun() {
        installCronet()
        enqueueCleartext200("intercepted-ok")

        val ran = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                ran.set(true)
                chain.proceed(chain.request().newBuilder().header("X-App-Interceptor", "ran").build())
            }
            .build()

        client.newCall(getRequest()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("intercepted-ok", response.body.string())
        }
        assertTrue("application interceptor did not run", ran.get())
        assertEquals(
            "application interceptor ran after the transport (header missing)",
            "ran",
            server.takeRequest().headers["X-App-Interceptor"],
        )
        assertFallbackOnly(Metrics.Reason.cleartext)
    }

    @Test
    fun newBuilderInterceptorsRun() {
        installCronet()
        enqueueCleartext200("cloned-ok")

        val ran = AtomicBoolean(false)
        val child = OkHttpClient()
            .newBuilder()
            .addInterceptor { chain ->
                ran.set(true)
                chain.proceed(chain.request())
            }
            .build()

        child.newCall(getRequest()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("cloned-ok", response.body.string())
        }
        assertTrue("interceptor added via newBuilder did not run", ran.get())
        assertFallbackOnly(Metrics.Reason.cleartext)
    }

    @Test
    fun interceptorsClearCannotDisableBridge() {
        installCronet()
        enqueueCleartext200("cleared-ok")

        val stripped = OkHttpClient()
            .newBuilder()
            .also(InterceptorClear::clear)
            .build()

        stripped.newCall(getRequest()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("cleared-ok", response.body.string())
        }
        // The fallback metric is recorded by the trampoline, not by any interceptor: clearing
        // them cannot disable the bridge.
        assertFallbackOnly(Metrics.Reason.cleartext)
    }

    @Test
    fun killSwitchRestoresStock() {
        installDisabled()
        enqueueCleartext200("killswitch-ok")

        assertNull(CronetRuntime.snapshot()?.policy?.takeIf { it.enabled() })
        OkHttpClient().newCall(getRequest()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("killswitch-ok", response.body.string())
        }
        assertFallbackOnly(Metrics.Reason.disabled)
    }

    @Test
    fun webSocketStaysNative() {
        installCronet()

        // (a) Real cleartext WebSocket upgrade: served natively over the stock fallback path.
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
        assertEquals(0, Metrics.cronet.get())
        assertTrue(Metrics.okhttpFallback.get() >= 1)
        // The handshake is cleartext, and the cleartext rule precedes the websocket rule.
        assertEquals(Metrics.Reason.cleartext, Metrics.lastReason)

        // (b) HTTPS forWebSocket call: policy denies with reason=websocket before any I/O.
        val dead = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        OkHttpClient().newWebSocket(
            Request.Builder().url("https://127.0.0.1:1/ws").build(),
            object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failed.set(true)
                    dead.countDown()
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    dead.countDown()
                }
            },
        )
        assertTrue("dead-port websocket did not settle", dead.await(15, TimeUnit.SECONDS))
        assertTrue("expected stock-path connection failure", failed.get())
        assertEquals(0, Metrics.cronet.get())
        assertEquals(Metrics.Reason.websocket, Metrics.lastReason)
    }
}
