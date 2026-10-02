@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import android.util.Log
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.RealInterceptorChain
import okio.RealBufferedSource
import org.chromium.net.CronetEngine
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sarie.bridge.mapping.FakeUrlResponseInfo
import sarie.bridge.mapping.OkHttpBridgeCallback

/**
 * Terminal-path behaviour of the Cronet bridge: the response body is handed back exactly as
 * `ResponseConverter` built it, the registration is torn down on every terminal path (including
 * the ones the rewritten `cancel()` makes routine), `callTimeout` bounds the header wait, and the
 * per-hop routing log is only built when the host logger wants it.
 *
 * The JVM test classpath carries an ordinary, unrewritten OkHttp, so `RealCall.cancel()` does not
 * run the appended `CronetBridge.notifyCanceled` call. These tests drive that hook explicitly,
 * which is precisely what the bytecode rewrite does at the end of `cancel()`.
 */
class BridgeTerminalPathTest {

    // --- scripted cronet-api doubles ---

    /** Engine whose `start()` can be told to deliver nothing, stalling the header wait. */
    private class ScriptedCronetEngine : CronetEngine() {
        val callbacks = mutableListOf<UrlRequest.Callback>()
        val requests = mutableListOf<ScriptedUrlRequest>()

        /** When true, `start()` returns without any callback: the headers never arrive. */
        var stallHeaders = false
        var responseInfo: UrlResponseInfo = FakeUrlResponseInfo()

        /** When true, `start()` delivers a 3xx the bridge surfaces instead of following. */
        var isRedirect = false

        override fun newUrlRequestBuilder(
            url: String,
            callback: UrlRequest.Callback,
            executor: Executor,
        ): UrlRequest.Builder {
            callbacks += callback
            return ScriptedBuilder(this)
        }

        override fun getVersionString(): String = "scripted"
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shutdown() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun startNetLogToFile(fileName: String, logAll: Boolean) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun stopNetLog() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getGlobalMetricsDeltas(): ByteArray = ByteArray(0)
        override fun openConnection(url: URL): URLConnection =
            throw UnsupportedOperationException("scripted engine")
        override fun createURLStreamHandlerFactory(): URLStreamHandlerFactory =
            throw UnsupportedOperationException("scripted engine")

        fun build(): ScriptedUrlRequest =
            ScriptedUrlRequest(this, callbacks.last()).also { requests += it }
    }

    private class ScriptedBuilder(private val engine: ScriptedCronetEngine) : UrlRequest.Builder() {
        override fun setHttpMethod(method: String): UrlRequest.Builder = this
        override fun addHeader(name: String, value: String): UrlRequest.Builder = this
        override fun disableCache(): UrlRequest.Builder = this
        override fun setPriority(priority: Int): UrlRequest.Builder = this
        override fun setUploadDataProvider(
            provider: UploadDataProvider,
            executor: Executor,
        ): UrlRequest.Builder = this
        override fun allowDirectExecutor(): UrlRequest.Builder = this
        override fun setRequestFinishedListener(
            listener: RequestFinishedInfo.Listener,
        ): UrlRequest.Builder = this
        override fun build(): UrlRequest = engine.build()
    }

    private class ScriptedUrlRequest(
        private val engine: ScriptedCronetEngine,
        private val callback: UrlRequest.Callback,
    ) : UrlRequest() {
        var startCalls = 0
            private set
        var cancelCalls = 0
            private set

        override fun start() {
            startCalls++
            if (engine.stallHeaders) return
            if (engine.isRedirect) {
                callback.onRedirectReceived(this, engine.responseInfo, "https://example.com/moved")
            } else {
                callback.onResponseStarted(this, engine.responseInfo)
            }
        }

        override fun cancel() {
            cancelCalls++
            callback.onCanceled(this, engine.responseInfo)
        }

        override fun followRedirect() = Unit
        override fun isDone(): Boolean = false
        override fun getStatus(listener: UrlRequest.StatusListener) = Unit
        override fun read(buffer: ByteBuffer) = Unit
    }

    // --- harness ---

    private val routedReasons = mutableListOf<FallbackReason?>()

    private val routingListener = object : SarieListener {
        override fun onRouted(call: Call, reason: FallbackReason?) {
            routedReasons += reason
        }
    }

    @Before
    fun setUp() {
        routedReasons.clear()
    }

    @After
    fun tearDown() {
        SarieBridge.uninstall()
        routedReasons.clear()
    }

    private fun install(engine: CronetEngine, logger: SarieLogger? = null) {
        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = setOf("example.com")
                    },
                )
                mapper(RequestToUrlRequestMapper { _, _ -> })
                listener(routingListener)
                if (logger != null) debugLogger(logger)
            },
        )
    }

    private fun cronetChain(client: OkHttpClient, url: String): RealInterceptorChain {
        val request: Request = Request.Builder().url(url).build()
        val call = client.newCall(request) as RealCall
        return RealInterceptorChain(
            call,
            listOf(Interceptor { CronetBridge.callServer(it)!! }),
            0,
            null,
            request,
            client,
        )
    }

    /**
     * Cancels the way the instrumented OkHttp does: `RealCall.cancel()` followed by the appended
     * `CronetBridge.notifyCanceled` static call.
     */
    private fun cancelAsRewritten(call: RealCall) {
        call.cancel()
        CronetBridge.notifyCanceled(call)
    }

    // --- the body is ResponseConverter's, unwrapped ---

    @Test
    fun `cronet response body is the converter's own buffered source, not a wrapper`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(
            url = "https://example.com/api",
            statusCode = 200,
            headersAsList = listOf(
                FakeUrlResponseInfo.headerEntry("Content-Type", "text/plain"),
                FakeUrlResponseInfo.headerEntry("X-Trace", "abc"),
            ),
            negotiatedProtocol = "h3",
        )
        install(engine)
        val chain = cronetChain(OkHttpClient(), "https://example.com/api")

        val response = CronetBridge.intercept(chain)

        val source = response.body!!.source()
        // Identity, not type: the buffered source OkHttp hands out must BE the one
        // ResponseConverter built over the callback's body source, so its delegate is that
        // source itself rather than a ForwardingSource re-wrapping it.
        assertTrue(
            "expected okio.RealBufferedSource but was ${source.javaClass.name}",
            source is RealBufferedSource,
        )
        val callback = engine.callbacks.single() as OkHttpBridgeCallback
        assertSame(callback.bodySourceFuture.get(), (source as RealBufferedSource).source)
        response.close()
    }

    @Test
    fun `cronet response still carries request code headers and bridge timestamps`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(
            statusCode = 201,
            headersAsList = listOf(
                FakeUrlResponseInfo.headerEntry("Content-Type", "application/json"),
                FakeUrlResponseInfo.headerEntry("X-Trace", "abc"),
            ),
            negotiatedProtocol = "h2",
        )
        install(engine)
        val chain = cronetChain(OkHttpClient(), "https://example.com/thing")

        val response = CronetBridge.intercept(chain)

        assertEquals(201, response.code)
        assertEquals("GET", response.request.method)
        assertEquals(chain.request.url, response.request.url)
        assertEquals("abc", response.header("X-Trace"))
        assertEquals("application/json", response.body!!.contentType().toString())
        assertEquals(Protocol.HTTP_2, response.protocol)
        // Bridge-owned clocks, never fabricated engine data.
        assertTrue("sentRequestAtMillis must be set", response.sentRequestAtMillis > 0L)
        assertTrue(
            "receivedResponseAtMillis must be at or after sentRequestAtMillis",
            response.receivedResponseAtMillis >= response.sentRequestAtMillis,
        )
        response.close()
    }

    // --- the registration is gone on every terminal path ---

    @Test
    fun `closing the body unregisters so a later cancel never reaches the engine`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        install(engine)
        val chain = cronetChain(OkHttpClient(), "https://example.com/")

        val response = CronetBridge.intercept(chain)
        response.close()
        // Closing an unfinished body cancels the engine request itself; that is the close path,
        // not a cancel delivery, and it must not repeat.
        val afterClose = engine.requests.single().cancelCalls

        cancelAsRewritten(chain.call)
        cancelAsRewritten(chain.call)
        assertEquals(
            "a cancel after body close must not reach the engine again",
            afterClose,
            engine.requests.single().cancelCalls,
        )
    }

    @Test
    fun `a redirect body unregisters so a later cancel does not re-cancel the engine`() {
        val engine = ScriptedCronetEngine()
        engine.isRedirect = true
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 302, negotiatedProtocol = "h2")
        install(engine)
        val chain = cronetChain(OkHttpClient(), "https://example.com/")

        val response = CronetBridge.intercept(chain)
        assertEquals(302, response.code)
        // A redirect body is an empty Buffer, not the callback's body source, so closing it
        // cannot reach the teardown. Cronet already canceled the request at header time.
        response.close()
        assertEquals(1, engine.requests.single().cancelCalls)

        cancelAsRewritten(chain.call)
        assertEquals(
            "the redirect registration must already be gone",
            1,
            engine.requests.single().cancelCalls,
        )
    }

    @Test
    fun `407 unregisters exactly once so a later cancel never reaches the engine`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 407)
        install(engine)
        val chain = cronetChain(OkHttpClient(), "https://example.com/")

        val thrown = assertThrows(ProtocolException::class.java) { CronetBridge.intercept(chain) }
        assertEquals(
            "Received HTTP_PROXY_AUTH (407) code while not using proxy",
            thrown.message,
        )
        // Closing the body quietly cancels the still-unfinished engine request...
        assertEquals(1, engine.requests.single().cancelCalls)
        // ...and the entry is gone, so later cancels are inert rather than second cancels.
        cancelAsRewritten(chain.call)
        cancelAsRewritten(chain.call)
        assertEquals(1, engine.requests.single().cancelCalls)
    }

    @Test
    fun `header-phase failure unregisters so a later cancel never reaches the engine`() {
        val engine = ScriptedCronetEngine()
        engine.stallHeaders = true
        install(engine)
        val client = OkHttpClient.Builder().readTimeout(150, TimeUnit.MILLISECONDS).build()
        val chain = cronetChain(client, "https://example.com/")

        assertThrows(SocketTimeoutException::class.java) { CronetBridge.intercept(chain) }
        assertEquals(1, engine.requests.single().cancelCalls)

        cancelAsRewritten(chain.call)
        assertEquals(1, engine.requests.single().cancelCalls)
    }

    @Test
    fun `in-flight cancel delivers exactly one engine cancel and unregisters`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        install(engine)
        val chain = cronetChain(OkHttpClient(), "https://example.com/")

        val response = CronetBridge.intercept(chain)
        cancelAsRewritten(chain.call)
        cancelAsRewritten(chain.call)

        assertEquals(1, engine.requests.single().cancelCalls)
        response.close()
    }

    @Test
    fun `notifyCanceled for a call Sarie never routed is a no-op`() {
        val engine = ScriptedCronetEngine()
        install(engine)
        val chain = cronetChain(OkHttpClient(), "https://example.com/")

        // Never intercepted: no registration exists, so the routine bytecode hook must be inert
        // and must not throw.
        CronetBridge.notifyCanceled(chain.call)
        CronetBridge.notifyCanceled(chain.call)

        assertTrue("no engine request may exist", engine.requests.isEmpty())
    }

    // --- callTimeout on the header wait is OkHttp's job, not the bridge's ---

    /**
     * The bridge deliberately does not re-implement `callTimeout` on the header wait. OkHttp
     * enters its own `AsyncTimeout` before the chain runs, so that timer already covers this
     * phase - and now that `RealCall.cancel()` reaches Cronet, it aborts the engine request too.
     * A second implementation here could only ever fire later than OkHttp's own.
     *
     * This harness calls `CronetBridge.intercept` directly, which bypasses that entry, so the
     * claim it can prove here is the narrow one: with no OkHttp timer running, a callTimeout
     * shorter than the read timeout does not win the header wait. The end-to-end shape is proven
     * on device by `CronetSuite.callTimeoutDuringHeaderWaitIsOkHttpShapesOwnTimeout`.
     */
    @Test
    fun `the header wait is bounded by the read timeout only, never by callTimeout`() {
        val engine = ScriptedCronetEngine()
        engine.stallHeaders = true
        install(engine)
        val client = OkHttpClient.Builder()
            .callTimeout(200, TimeUnit.MILLISECONDS)
            .readTimeout(400, TimeUnit.MILLISECONDS)
            .build()
        val chain = cronetChain(client, "https://example.com/")

        val thrown = assertThrows(SocketTimeoutException::class.java) {
            CronetBridge.intercept(chain)
        }

        // The read timeout's shape, not the call timeout's: callTimeout did not win.
        assertTrue(thrown.message!!.contains("Timed out waiting for response headers"))
        assertEquals(SocketTimeoutException::class.java, thrown.javaClass)
        // The stalled engine request is canceled exactly once.
        assertEquals(1, engine.requests.single().cancelCalls)
        cancelAsRewritten(chain.call)
        assertEquals(1, engine.requests.single().cancelCalls)
    }

    @Test
    fun `no callTimeout leaves the read timeout as the only bound on the header wait`() {
        val engine = ScriptedCronetEngine()
        engine.stallHeaders = true
        install(engine)
        val client = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(150, TimeUnit.MILLISECONDS)
            .build()
        val chain = cronetChain(client, "https://example.com/")

        val thrown = assertThrows(SocketTimeoutException::class.java) {
            CronetBridge.intercept(chain)
        }

        assertEquals("Timed out waiting for response headers", thrown.message)
        assertEquals(1, engine.requests.single().cancelCalls)
    }

    @Test
    fun `a read timeout shorter than callTimeout still reports the read timeout`() {
        val engine = ScriptedCronetEngine()
        engine.stallHeaders = true
        install(engine)
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.MILLISECONDS)
            .build()
        val chain = cronetChain(client, "https://example.com/")

        val thrown = assertThrows(SocketTimeoutException::class.java) {
            CronetBridge.intercept(chain)
        }

        assertEquals("Timed out waiting for response headers", thrown.message)
        assertEquals(1, engine.requests.single().cancelCalls)
    }

    // --- the routing log is gated ---


    private class CountingLogger(private val debugLoggable: Boolean) : SarieLogger {
        /** Only the DEBUG stream matters here: install notices are logged at other priorities. */
        val debugMessages = mutableListOf<String>()

        override fun isLoggable(priority: Int): Boolean =
            priority != Log.DEBUG || debugLoggable

        override fun log(priority: Int, message: String, throwable: Throwable?) {
            if (priority == Log.DEBUG) debugMessages += message
        }
    }

    @Test
    fun `routing message is not built when the logger rejects DEBUG`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        val logger = CountingLogger(debugLoggable = false)
        install(engine, logger)
        val chain = cronetChain(OkHttpClient(), "https://example.com/")

        val response = CronetBridge.intercept(chain)
        response.close()

        assertEquals("onRouted must still fire exactly once", 1, routedReasons.size)
        assertNull(routedReasons.single())
        // The callback's own header line is gated by the same logger; neither may be built.
        assertEquals(emptyList<String>(), logger.debugMessages)
    }

    @Test
    fun `routing message is built and logged when the logger accepts DEBUG`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        val logger = CountingLogger(debugLoggable = true)
        install(engine, logger)
        val chain = cronetChain(OkHttpClient(), "https://example.com/")

        val response = CronetBridge.intercept(chain)
        response.close()

        assertEquals(1, routedReasons.size)
        assertTrue(logger.debugMessages.contains("GET https://example.com/ -> cronet"))
    }
}
