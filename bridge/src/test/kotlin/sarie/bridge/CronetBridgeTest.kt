@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import sarie.bridge.mapping.FakeCronetException
import sarie.bridge.mapping.FakeUrlResponseInfo
import sarie.bridge.mapping.OkHttpBridgeCallback
import java.io.IOException
import java.net.ProtocolException
import java.net.ServerSocket
import javax.net.ssl.SSLPeerUnverifiedException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.CallServerInterceptor
import okhttp3.internal.http.RealInterceptorChain
import okio.Buffer
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.NetworkException
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sarie.bridge.mapping.FakeRequestFinishedInfo

/**
 * Bridge-glue tests for [CronetBridge]: bytecode shape of the exact-stock fallback, the Cronet
 * path over scripted cronet-api doubles, the three release-blocker cancel interleavings, and the
 * 407 guard.
 */
class CronetBridgeTest {

    // --- scripted cronet-api doubles (pattern reused from mapping/Fakes.kt) ---

    /** Two-latch gate: the scripted hook blocks in [block] until the test releases it. */
    private class Gate {
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun block() {
            entered.countDown()
            release.await()
        }

        fun awaitEntered(): Boolean = entered.await(5, TimeUnit.SECONDS)

        fun releaseNow() {
            release.countDown()
        }
    }

    private class ScriptedCronetEngine : CronetEngine() {
        val builders = mutableListOf<ScriptedBuilder>()
        val builtRequests: List<ScriptedUrlRequest>
            get() = builders.mapNotNull { it.builtRequest }

        var builderGate: Gate? = null
        var startGate: Gate? = null
        var responseInfo: UrlResponseInfo = FakeUrlResponseInfo()
        var isRedirect = false
        var rejectFinishedListener = false

        /**
         * Number of requests (in build order) whose start() fails pre-headers with a
         * transport-level CronetException, mirroring onFailed before onResponseStarted.
         */
        var preHeaderFailures = 0
        var preHeaderFailure: CronetException = FakeCronetException("net err")

        override fun newUrlRequestBuilder(
            url: String,
            callback: UrlRequest.Callback,
            executor: Executor,
        ): UrlRequest.Builder {
            builderGate?.block()
            return ScriptedBuilder(url, callback, this).also {
                it.rejectFinishedListener = rejectFinishedListener
                builders.add(it)
            }
        }

        override fun getVersionString(): String = "scripted"
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shutdown() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun startNetLogToFile(fileName: String, logAll: Boolean) = Unit
        override fun stopNetLog() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getGlobalMetricsDeltas(): ByteArray = ByteArray(0)
        override fun openConnection(url: URL): URLConnection =
            throw UnsupportedOperationException("scripted engine")

        override fun createURLStreamHandlerFactory(): URLStreamHandlerFactory =
            throw UnsupportedOperationException("scripted engine")
    }

    private class ScriptedBuilder(
        val url: String,
        val callback: UrlRequest.Callback,
        private val engine: ScriptedCronetEngine,
    ) : UrlRequest.Builder() {
        var builtRequest: ScriptedUrlRequest? = null
            private set
        var finishedListener: RequestFinishedInfo.Listener? = null
            private set
        var rejectFinishedListener = false

        override fun setHttpMethod(method: String): UrlRequest.Builder = this
        override fun addHeader(name: String, value: String): UrlRequest.Builder = this
        override fun disableCache(): UrlRequest.Builder = this
        override fun setPriority(priority: Int): UrlRequest.Builder = this
        override fun setUploadDataProvider(provider: UploadDataProvider, executor: Executor): UrlRequest.Builder = this
        override fun allowDirectExecutor(): UrlRequest.Builder = this
        override fun setRequestFinishedListener(
            listener: RequestFinishedInfo.Listener,
        ): UrlRequest.Builder {
            if (rejectFinishedListener) throw UnsupportedOperationException("no finished listener")
            finishedListener = listener
            return this
        }
        override fun build(): UrlRequest =
            ScriptedUrlRequest(callback, engine, engine.builders.count { it.builtRequest != null })
                .also { builtRequest = it }
    }

    private class ScriptedUrlRequest(
        val callback: UrlRequest.Callback,
        private val engine: ScriptedCronetEngine,
        private val buildIndex: Int,
    ) : UrlRequest() {
        var startCalls = 0
            private set
        var cancelCalls = 0
            private set
        var followRedirectCalls = 0
            private set
        var readCalls = 0
            private set

        /** Scripted reaction to read(); invoked synchronously on the reader's thread. */
        var readHandler: ((ByteBuffer) -> Unit)? = null

        private var startEntered = false

        override fun start() {
            startCalls++
            startEntered = true
            engine.startGate?.block()
            // A cancel that landed while start() was parked replaces the header delivery,
            // mirroring a real engine (onCanceled is delivered from cancel() below).
            if (cancelCalls == 0) {
                if (buildIndex < engine.preHeaderFailures) {
                    callback.onFailed(this, engine.responseInfo, engine.preHeaderFailure)
                    val finishedListener = engine.builders.getOrNull(buildIndex)?.finishedListener
                    finishedListener?.onRequestFinished(
                        FakeRequestFinishedInfo(
                            url = engine.responseInfo.url,
                            finishedReason = RequestFinishedInfo.FAILED,
                            responseInfo = engine.responseInfo,
                            exception = engine.preHeaderFailure,
                        ),
                    )
                } else {
                    if (engine.isRedirect) {
                        callback.onRedirectReceived(this, engine.responseInfo, "https://example.com/redirected")
                    } else {
                        callback.onResponseStarted(this, engine.responseInfo)
                    }
                }
            }
        }

        override fun cancel() {
            cancelCalls++
            // Real engines deliver onCanceled for started requests; the bridge must cope.
            if (startEntered) {
                if (!engine.isRedirect) {
                    callback.onCanceled(this, engine.responseInfo)
                }
                val finishedListener = engine.builders.getOrNull(buildIndex)?.finishedListener
                finishedListener?.onRequestFinished(
                    FakeRequestFinishedInfo(
                        url = engine.responseInfo.url,
                        finishedReason = RequestFinishedInfo.CANCELED,
                        responseInfo = engine.responseInfo,
                    ),
                )
            }
        }

        override fun followRedirect() {
            followRedirectCalls++
        }

        override fun isDone(): Boolean = false

        override fun getStatus(listener: UrlRequest.StatusListener) = Unit

        override fun read(buffer: ByteBuffer) {
            readCalls++
            readHandler?.invoke(buffer)
        }
    }

    // --- harness ---

    private val mapper = RequestToUrlRequestMapper { _, _ -> }

    private val routes = object : SarieListener {
        val reasons = mutableListOf<FallbackReason?>()
        override fun onRouted(call: Call, reason: FallbackReason?) {
            reasons += reason
        }
        fun clear() = reasons.clear()
        fun cronetCount() = reasons.count { it == null }
        fun fallbackCount() = reasons.count { it != null }
        fun lastReason(): FallbackReason? = reasons.lastOrNull()
    }

    @Before
    fun setUp() {
        System.clearProperty("okhttp.cronet.enabled")
        routes.clear()
    }

    @After
    fun tearDown() {
        System.clearProperty("okhttp.cronet.enabled")
        SarieBridge.uninstall()
        routes.clear()
    }

    private fun install(engine: CronetEngine, vararg origins: String) {
        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = origins.toSet()
                    },
                )
                mapper(mapper)
                listener(routes)
            },
        )
    }

    /**
     * Chain at the ConnectInterceptor position: index points at a stand-in for the rewritten
     * CallServerInterceptor, which is what [CronetBridge.intercept] proceeds into.
     */
    private fun cronetChain(
        client: OkHttpClient,
        url: String,
        method: String = "GET",
        body: RequestBody? = null,
        network: List<Interceptor> = emptyList(),
    ): Pair<RealCall, RealInterceptorChain> {
        val request: Request = Request.Builder().url(url).method(method, body).build()
        val call = client.newCall(request) as RealCall
        val terminal = Interceptor { inner ->
            CronetBridge.callServer(inner)
                ?: throw AssertionError("exchange should be null on the cronet test chain")
        }
        val chain = RealInterceptorChain(
            call,
            network + terminal,
            0,
            null,
            request,
            client,
        )
        return call to chain
    }

    /**
     * Chain shape mirroring production at the ConnectInterceptor position: index points at the
     * terminal interceptor and the route planner is prepared, so the exact-stock fallback can
     * really attempt the connection.
     */
    private fun fallbackChain(client: OkHttpClient, url: String): RealInterceptorChain {
        val request: Request = Request.Builder().url(url).build()
        val call = client.newCall(request) as RealCall
        val chain = RealInterceptorChain(
            call,
            listOf(Interceptor { it.proceed(it.request()) }, CallServerInterceptor),
            1,
            null,
            request,
            client,
        )
        call.enterNetworkInterceptorExchange(request, true, chain)
        return chain
    }

    /** Runs intercept on a worker thread; completes with the thrown Throwable. */
    private fun interceptAsync(chain: RealInterceptorChain): CompletableFuture<Throwable> {
        val error = CompletableFuture<Throwable>()
        Thread {
            try {
                CronetBridge.intercept(chain)
            } catch (t: Throwable) {
                error.complete(t)
            }
        }.start()
        return error
    }

    // --- bytecode shape of the exact-stock fallback ---

    /**
     * ASM is not on the bridge test classpath (build files are locked), so this scans the raw
     * constant pool of the compiled CronetBridge.class instead: every invoked reference must
     * appear as a UTF-8 constant, and the negative check is stricter than an invoked-ref scan
     * (any mention anywhere in the class fails).
     */
    @Test
    fun `fallback bytecode shape - initExchange and copy referenced and ConnectInterceptor absent`() {
        val bytes = CronetBridge::class.java.getResourceAsStream("CronetBridge.class")!!.readBytes()
        val pool = String(bytes, Charsets.ISO_8859_1)
        assertTrue("fallback must call initExchange\$okhttp", pool.contains("initExchange\$okhttp"))
        assertTrue("fallback must call copy\$okhttp", pool.contains("copy\$okhttp"))
        assertTrue("fallback must call proceed", pool.contains("proceed"))
        assertFalse(
            "fallback must never reference the rewritten ConnectInterceptor",
            pool.contains("ConnectInterceptor"),
        )
    }

    // --- exact-stock fallback ---

    @Test
    fun `fallback executes stock path when policy denies`() {
        val engine = ScriptedCronetEngine()
        install(engine, "example.com")
        val closedPort = ServerSocket(0).use { it.localPort }
        val chain = fallbackChain(OkHttpClient(), "https://127.0.0.2:$closedPort/")
        val fallbacksBefore = routes.fallbackCount()

        assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(FallbackReason.allowlist, routes.lastReason())
        assertEquals(fallbacksBefore + 1, routes.fallbackCount())
        assertTrue(
            "fallback must never touch the Cronet engine",
            engine.builders.isEmpty(),
        )
    }

    // --- Cronet path happy ---

    @Test
    fun `cronet path happy - h3 200 streams fully and registry empties after body close`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(
            statusCode = 200,
            headersAsList = listOf(
                FakeUrlResponseInfo.headerEntry("Content-Type", "text/plain"),
                FakeUrlResponseInfo.headerEntry("Content-Length", "5"),
            ),
            negotiatedProtocol = "h3",
        )
        install(engine, "example.com")
        val (call, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        val cronetBefore = routes.cronetCount()

        val response = CronetBridge.intercept(chain)

        assertEquals(Protocol.HTTP_3, response.protocol)
        assertEquals(200, response.code)
        assertEquals(cronetBefore + 1, routes.cronetCount())
        assertNull(routes.lastReason())

        val fake = engine.builtRequests.single()
        assertEquals(1, fake.startCalls)
        assertEquals(0, fake.followRedirectCalls)
        var reads = 0
        fake.readHandler = { buffer ->
            reads++
            if (reads == 1) {
                buffer.put("hello".toByteArray())
                fake.callback.onReadCompleted(fake, engine.responseInfo, buffer)
            } else {
                fake.callback.onSucceeded(fake, engine.responseInfo)
            }
        }
        val source = response.body!!.source()
        val sink = Buffer()
        assertEquals(5L, source.read(sink, 1024))
        assertEquals(-1L, source.read(sink, 1024))
        assertEquals("hello", sink.readUtf8())
        assertEquals(0, fake.cancelCalls)

        source.close()
        call.cancel()
        assertEquals(0, fake.cancelCalls)
    }

    // --- the three release-blocker cancel interleavings ---

    @Test
    fun `cancel before attach aborts before any engine start`() {
        val engine = ScriptedCronetEngine()
        val gate = Gate()
        engine.builderGate = gate
        install(engine, "example.com")
        val (call, chain) = cronetChain(OkHttpClient(), "https://example.com/")

        val error = interceptAsync(chain)
        assertTrue(gate.awaitEntered())
        call.cancel()
        gate.releaseNow()

        val thrown = error.get(5, TimeUnit.SECONDS)
        assertTrue("expected IOException but was $thrown", thrown is IOException)
        assertEquals("Canceled", thrown.message)
        val fake = engine.builtRequests.single()
        assertEquals(0, fake.startCalls)
        assertEquals(0, fake.cancelCalls)
    }

    @Test
    fun `cancel between attach and start delivers exactly one engine cancel`() {
        val engine = ScriptedCronetEngine()
        val gate = Gate()
        engine.startGate = gate
        install(engine, "example.com")
        val (call, chain) = cronetChain(OkHttpClient(), "https://example.com/")

        val error = interceptAsync(chain)
        assertTrue(gate.awaitEntered()) // start() parked: register already happened
        call.cancel() // delivered by the attached EventListener, exactly once
        gate.releaseNow()

        val thrown = error.get(5, TimeUnit.SECONDS)
        assertTrue("expected IOException but was $thrown", thrown is IOException)
        assertEquals("Canceled", thrown.message)
        val fake = engine.builtRequests.single()
        assertEquals(1, fake.startCalls)
        assertEquals(1, fake.cancelCalls)
        val callback = engine.builders.single().callback as OkHttpBridgeCallback
        assertTrue("headersFuture must be settled", callback.headersFuture.isDone)
        assertTrue("bodySourceFuture must be settled", callback.bodySourceFuture.isDone)
    }

    @Test
    fun `cancel after headers mid-body aborts the read with Canceled`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(
            statusCode = 200,
            headersAsList = listOf(FakeUrlResponseInfo.headerEntry("Content-Type", "text/plain")),
            negotiatedProtocol = "h3",
        )
        install(engine, "example.com")
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val (call, chain) = cronetChain(client, "https://example.com/")

        val response = CronetBridge.intercept(chain)
        val fake = engine.builtRequests.single()
        val gate = Gate()
        fake.readHandler = { _ -> gate.block() }

        val readError = CompletableFuture<Throwable>()
        Thread {
            try {
                response.body!!.source().read(Buffer(), 1024)
            } catch (t: Throwable) {
                readError.complete(t)
            }
        }.start()
        assertTrue(gate.awaitEntered())
        call.cancel() // listener delivers the single engine cancel and unregisters
        gate.releaseNow()

        val thrown = readError.get(5, TimeUnit.SECONDS)
        assertTrue("expected IOException but was $thrown", thrown is IOException)
        assertEquals("Canceled", thrown.message)
        assertEquals(1, fake.cancelCalls)
    }

    // --- 407 guard ---

    @Test
    fun `407 response is rejected with ProtocolException`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(
            statusCode = 407,
            statusText = "Proxy Authentication Required",
        )
        install(engine, "example.com")
        val (call, chain) = cronetChain(OkHttpClient(), "https://example.com/")

        val thrown = assertThrows(ProtocolException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(
            "Received HTTP_PROXY_AUTH (407) code while not using proxy",
            thrown.message,
        )
        // Not retryable: one attempt, no transport-failure retry.
        assertEquals(1, engine.builtRequests.size)
        // Closing the body quietly cancels the still-unfinished engine request.
        assertEquals(1, engine.builtRequests.single().cancelCalls)
        // Unregistered: a later cancel does not reach the engine again.
        call.cancel()
        assertEquals(1, engine.builtRequests.single().cancelCalls)
    }

    // --- transport-failure retry (idempotent, pre-headers only, once) ---

    @Test
    fun `pre-headers transport failure retries once for GET and returns response`() {
        val engine = ScriptedCronetEngine()
        engine.preHeaderFailures = 1
        engine.responseInfo = FakeUrlResponseInfo(
            statusCode = 200,
            headersAsList = listOf(
                FakeUrlResponseInfo.headerEntry("Content-Type", "text/plain"),
                FakeUrlResponseInfo.headerEntry("Content-Length", "5"),
            ),
        )
        install(engine, "example.com")
        val (call, chain) = cronetChain(OkHttpClient(), "https://example.com/")

        val response = CronetBridge.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(2, engine.builtRequests.size)
        engine.builtRequests.forEach { assertEquals(1, it.startCalls) }
        // Closing the unread body cancels attempt 2 and unregisters.
        response.body.close()
        call.cancel()
        assertEquals(1, engine.builtRequests[1].cancelCalls)
    }

    @Test
    fun `pre-headers transport failure twice for GET throws after exactly two attempts`() {
        val engine = ScriptedCronetEngine()
        engine.preHeaderFailures = 2
        install(engine, "example.com")
        val (call, chain) = cronetChain(OkHttpClient(), "https://example.com/")

        val thrown = assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        // awaitHeaders unwraps the ExecutionException: the CronetException itself surfaces
        // (it is an IOException), which is exactly what the retry predicate matches on.
        assertTrue("expected the CronetException to surface", thrown is FakeCronetException)
        assertEquals(2, engine.builtRequests.size)
        // Unregistered: a cancel after the failure does not reach either engine request.
        call.cancel()
        engine.builtRequests.forEach { assertEquals(0, it.cancelCalls) }
    }

    @Test
    fun `pin failure -150 becomes SSLPeerUnverifiedException and is not retried`() {
        val engine = ScriptedCronetEngine()
        engine.preHeaderFailures = 2
        engine.preHeaderFailure = object : NetworkException("pin", null) {
            override fun getCronetInternalErrorCode(): Int = -150
            override fun getErrorCode(): Int = ERROR_OTHER
            override fun immediatelyRetryable(): Boolean = true
        }
        install(engine, "example.com")
        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")

        val thrown = assertThrows(SSLPeerUnverifiedException::class.java) {
            CronetBridge.intercept(chain)
        }

        assertEquals("Certificate pinning failure!", thrown.message)
        assertEquals(1, engine.builtRequests.size)
    }

    @Test
    fun `non-idempotent POST pre-headers failure is terminal - no retry`() {
        val engine = ScriptedCronetEngine()
        engine.preHeaderFailures = 1
        install(engine, "example.com")
        val (_, chain) = cronetChain(
            OkHttpClient(),
            "https://example.com/",
            method = "POST",
            body = "x".toRequestBody("text/plain".toMediaType()),
        )

        assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(1, engine.builtRequests.size)
    }

    @Test
    fun `cancel during the first attempt prevents the retry`() {
        val engine = ScriptedCronetEngine()
        val gate = Gate()
        engine.startGate = gate
        install(engine, "example.com")
        val (call, chain) = cronetChain(OkHttpClient(), "https://example.com/")

        val error = interceptAsync(chain)
        assertTrue(gate.awaitEntered()) // start() parked: request attached
        call.cancel() // listener delivers exactly one engine cancel
        gate.releaseNow()

        val thrown = error.get(5, TimeUnit.SECONDS)
        assertTrue("expected IOException but was $thrown", thrown is IOException)
        assertEquals("Canceled", thrown.message)
        assertEquals(1, engine.builtRequests.size)
    }

    @Test
    fun `retryOnConnectionFailure false disables the retry`() {
        val engine = ScriptedCronetEngine()
        engine.preHeaderFailures = 1
        install(engine, "example.com")
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val (_, chain) = cronetChain(client, "https://example.com/")

        assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(1, engine.builtRequests.size)
    }

    // --- network-interceptor checks RealInterceptorChain skips when exchange is null ---

    @Test
    fun `network interceptor host scheme or port change throws stock illegal state`() {
        val engine = ScriptedCronetEngine()
        install(engine, "example.com")
        val host = Interceptor { chain ->
            val url = chain.request().url.newBuilder().host("evil.example").build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }
        assertStockAddress(host, "https://example.com/")

        val scheme = Interceptor { chain ->
            val url = chain.request().url.newBuilder().scheme("http").build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }
        assertStockAddress(scheme, "https://example.com/")

        val port = Interceptor { chain ->
            val url = chain.request().url.newBuilder().port(9).build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }
        assertStockAddress(port, "https://example.com/")
        assertTrue(engine.builders.isEmpty())
    }

    @Test
    fun `network interceptor proceed twice throws stock exactly once`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200)
        install(engine, "example.com")
        val interceptor = Interceptor { chain ->
            chain.proceed(chain.request()).close()
            chain.proceed(chain.request())
        }
        val thrown = assertThrows(IllegalStateException::class.java) {
            CronetBridge.intercept(networkChain(interceptor))
        }
        assertEquals(
            "network interceptor $interceptor must call proceed() exactly once",
            thrown.message,
        )
    }

    @Test
    fun `network interceptor short-circuit throws stock exactly once`() {
        val engine = ScriptedCronetEngine()
        install(engine, "example.com")
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("ok")
                .body("short".toResponseBody(null))
                .build()
        }
        val thrown = assertThrows(IllegalStateException::class.java) {
            CronetBridge.intercept(networkChain(interceptor))
        }
        assertEquals(
            "network interceptor $interceptor must call proceed() exactly once",
            thrown.message,
        )
        assertTrue(engine.builders.isEmpty())
    }

    @Test
    fun `terminal hop emits requestHeadersStart before responseHeadersEnd`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 204)
        install(engine, "example.com")
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { seen += "callStart" }
                override fun requestHeadersStart(call: Call) { seen += "requestHeadersStart" }
                override fun responseHeadersEnd(call: Call, response: Response) { seen += "responseHeadersEnd" }
                override fun callEnd(call: Call) { seen += "callEnd" }
            })
            .build()
        val (_, chain) = cronetChain(client, "https://example.com/")
        CronetBridge.intercept(chain).close()
        val headers = seen.indexOf("requestHeadersStart")
        val response = seen.indexOf("responseHeadersEnd")
        assertTrue("events=$seen", headers >= 0 && response > headers)
    }

    @Test
    fun `requestHeadersEnd precedes a response delivered during start`() {
        val engine = ScriptedCronetEngine() // delivers onResponseStarted inside start()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 204)
        install(engine, "example.com")
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun requestHeadersEnd(call: Call, request: Request) { seen += "requestHeadersEnd" }
                override fun responseHeadersStart(call: Call) { seen += "responseHeadersStart" }
            })
            .build()
        val (_, chain) = cronetChain(client, "https://example.com/")
        CronetBridge.intercept(chain).close()
        assertEquals(listOf("requestHeadersEnd", "responseHeadersStart"), seen)
    }

    private fun assertStockAddress(interceptor: Interceptor, url: String) {
        val thrown = assertThrows(IllegalStateException::class.java) {
            CronetBridge.intercept(networkChain(interceptor, url))
        }
        assertEquals(
            "network interceptor $interceptor must retain the same host and port",
            thrown.message,
        )
    }

    private fun networkChain(interceptor: Interceptor, url: String = "https://example.com/"): RealInterceptorChain {
        val client = OkHttpClient.Builder().addNetworkInterceptor(interceptor).build()
        val (_, chain) = cronetChain(client, url, network = listOf(interceptor))
        return chain
    }

    @Test
    fun `onRouted reports the deny reason and null on the Cronet path`() {
        val engine = ScriptedCronetEngine()
        install(engine, "example.com")
        val denied = fallbackChain(OkHttpClient(), "https://other.example/")
        assertThrows(IOException::class.java) { CronetBridge.intercept(denied) }
        assertEquals(FallbackReason.allowlist, routes.lastReason())

        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        val (_, allowed) = cronetChain(OkHttpClient(), "https://example.com/")
        CronetBridge.intercept(allowed).close()
        assertNull(routes.lastReason())
        assertEquals(1, routes.cronetCount())
    }

    @Test
    fun `after uninstall the listener still hears engine_missing`() {
        install(ScriptedCronetEngine(), "example.com")
        SarieBridge.uninstall()
        val closedPort = ServerSocket(0).use { it.localPort }
        val chain = fallbackChain(OkHttpClient(), "https://127.0.0.2:$closedPort/")

        assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(FallbackReason.engine_missing, routes.lastReason())
    }

    @Test
    fun `a throwing policy fails closed and reports policy_error`() {
        val engine = ScriptedCronetEngine()
        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = emptySet()
                        override fun enabled(): Boolean = throw IllegalStateException("host policy")
                    },
                )
                mapper(mapper)
                listener(routes)
            },
        )
        val closedPort = ServerSocket(0).use { it.localPort }
        val chain = fallbackChain(OkHttpClient(), "https://127.0.0.2:$closedPort/")

        assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(FallbackReason.policy_error, routes.lastReason())
        assertTrue(engine.builders.isEmpty())
    }

    @Test
    fun `throwing onRouted still returns the response`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = setOf("example.com")
                    },
                )
                mapper(mapper)
                listener(
                    object : SarieListener {
                        override fun onRouted(call: Call, reason: FallbackReason?) {
                            throw IllegalStateException("host listener")
                        }
                    },
                )
            },
        )
        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        val response = CronetBridge.intercept(chain)
        assertEquals(200, response.code)
        response.close()
    }

    @Test
    fun `throwing debugLogger still returns the response`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        SarieBridge.install(engine) {
            policy(
                object : CronetPolicy {
                    override val allowedOrigins: Set<String> = setOf("example.com")
                },
            )
            mapper(mapper)
            debugLogger { _, _, _ -> throw IllegalStateException("host logger") }
        }
        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        val response = CronetBridge.intercept(chain)
        assertEquals(200, response.code)
        response.close()
    }

    @Test
    fun `debugLogger receives routing lines and negotiated protocol`() {
        val messages = mutableListOf<String>()
        val testLogger = SarieLogger { _, message, _ ->
            messages += message
        }
        val engine = ScriptedCronetEngine()
        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = setOf("example.com")
                    },
                )
                mapper(mapper)
                debugLogger(testLogger)
            },
        )
        // 1. Deny route
        val denied = fallbackChain(OkHttpClient(), "https://other.example/")
        assertThrows(IOException::class.java) { CronetBridge.intercept(denied) }
        assertTrue(messages.any { it == "GET https://other.example/ -> okhttp (reason=allowlist)" })

        // 2. Allow route and headers arrived
        engine.responseInfo = FakeUrlResponseInfo(
            url = "https://example.com/api",
            statusCode = 200,
            negotiatedProtocol = "h3",
        )
        val (_, allowed) = cronetChain(OkHttpClient(), "https://example.com/api")
        CronetBridge.intercept(allowed).close()

        assertTrue(messages.any { it == "GET https://example.com/api -> cronet" })
        assertTrue(messages.any { it == "https://example.com/api -> h3" })
    }

    @Test
    fun `onResponseStarted fires before application and network interceptors observe the response and before responseHeadersEnd and callEnd`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        val order = mutableListOf<String>()
        var observedInfo: SarieResponseInfo? = null

        val listener = object : SarieListener {
            override fun onResponseStarted(call: Call, info: SarieResponseInfo) {
                order += "onResponseStarted"
                observedInfo = info
            }
        }

        val appInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            order += "applicationInterceptor"
            response
        }

        val netInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            order += "networkInterceptor"
            response
        }

        val eventListener = object : EventListener() {
            override fun responseHeadersStart(call: Call) {
                order += "responseHeadersStart"
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                order += "responseHeadersEnd"
            }
            override fun callEnd(call: Call) {
                order += "callEnd"
            }
        }

        val client = OkHttpClient.Builder()
            .eventListener(eventListener)
            .build()

        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = setOf("example.com")
                    },
                )
                mapper(mapper)
                listener(listener)
            },
        )

        val (call, innerChain) = cronetChain(client, "https://example.com/", network = listOf(netInterceptor))
        val outerChain = RealInterceptorChain(
            innerChain.call,
            listOf(appInterceptor, Interceptor { CronetBridge.intercept(innerChain) }),
            0,
            null,
            innerChain.request,
            client,
        )

        val response = outerChain.proceed(outerChain.request)
        response.close()
        call.eventListener.callEnd(call)

        assertEquals(
            listOf(
                "responseHeadersStart",
                "onResponseStarted",
                "responseHeadersEnd",
                "networkInterceptor",
                "applicationInterceptor",
                "callEnd",
            ),
            order,
        )

        val info = checkNotNull(observedInfo)
        assertEquals(SarieProtocol.HTTP_3, info.protocol)
        assertEquals("h3", info.negotiatedProtocol)
        assertEquals(200, info.httpStatusCode)
        assertFalse(info.wasCached)
        assertEquals(1, info.attempt)
        assertFalse(info.isRedirect)
        assertTrue(info.handoffAtMillis > 0L)
        assertTrue(info.headersAtMillis >= info.handoffAtMillis)
    }

    @Test
    fun `pre-headers transport failure with retry delivers attempt 1 onFinished before attempt 2 onResponseStarted`() {
        val engine = ScriptedCronetEngine()
        engine.preHeaderFailures = 1
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")

        val events = mutableListOf<String>()
        val finishedAttempts = mutableListOf<Int>()
        val startedAttempts = mutableListOf<Int>()

        val listener = object : SarieListener {
            override fun onResponseStarted(call: Call, info: SarieResponseInfo) {
                events += "onResponseStarted:${info.attempt}"
                startedAttempts += info.attempt
            }

            override fun onFinished(call: Call, timings: SarieTimings) {
                events += "onFinished:${timings.attempt}"
                finishedAttempts += timings.attempt
            }
        }

        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = setOf("example.com")
                    },
                )
                mapper(mapper)
                listener(listener)
            },
        )

        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        val response = CronetBridge.intercept(chain)
        assertEquals(200, response.code)

        // Attempt 1 onFinished must precede Attempt 2 onResponseStarted
        assertEquals(listOf("onFinished:1", "onResponseStarted:2"), events)
        assertEquals(listOf(1), finishedAttempts)
        assertEquals(listOf(2), startedAttempts)

        // Closing the response triggers attempt 2 onFinished
        response.close()
        assertEquals(listOf("onFinished:1", "onResponseStarted:2", "onFinished:2"), events)
        assertEquals(listOf(1, 2), finishedAttempts)
    }

    @Test
    fun `redirect emits onResponseStarted and late onFinished with isRedirect true`() {
        val engine = ScriptedCronetEngine()
        engine.isRedirect = true
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 302, negotiatedProtocol = "h2")

        val startedLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(1)
        var startedInfo: SarieResponseInfo? = null
        var finishedTimings: SarieTimings? = null

        val listener = object : SarieListener {
            override fun onResponseStarted(call: Call, info: SarieResponseInfo) {
                startedInfo = info
                startedLatch.countDown()
            }

            override fun onFinished(call: Call, timings: SarieTimings) {
                finishedTimings = timings
                finishedLatch.countDown()
            }
        }

        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = setOf("example.com")
                    },
                )
                mapper(mapper)
                listener(listener)
            },
        )

        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        val response = CronetBridge.intercept(chain)
        assertEquals(302, response.code)
        response.close()

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))
        val info = checkNotNull(startedInfo)
        assertTrue(info.isRedirect)
        assertEquals(302, info.httpStatusCode)
        assertEquals(SarieProtocol.HTTP_2, info.protocol)
        assertEquals(1, info.attempt)

        assertTrue(finishedLatch.await(5, TimeUnit.SECONDS))
        val timings = checkNotNull(finishedTimings)
        assertTrue(timings.isRedirect)
        assertTrue(timings.deliveredLate)
        assertEquals(SarieTimings.Result.CANCELED, timings.result)
        assertEquals(1, timings.attempt)
    }

    @Test
    fun `provider rejecting finished listener does not wait and still fires onResponseStarted`() {
        val engine = ScriptedCronetEngine()
        engine.rejectFinishedListener = true
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h2")

        var startedInfo: SarieResponseInfo? = null
        var finishedCalled = false

        val listener = object : SarieListener {
            override fun onResponseStarted(call: Call, info: SarieResponseInfo) {
                startedInfo = info
            }

            override fun onFinished(call: Call, timings: SarieTimings) {
                finishedCalled = true
            }
        }

        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = setOf("example.com")
                    },
                )
                mapper(mapper)
                listener(listener)
            },
        )

        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        val startNanos = System.nanoTime()
        val response = CronetBridge.intercept(chain)
        response.close()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        assertNotNull(startedInfo)
        assertEquals(200, startedInfo!!.httpStatusCode)
        assertFalse(finishedCalled)
        assertTrue("Expected elapsedMs < 80 but was $elapsedMs", elapsedMs < 80)
    }

    @Test
    fun `throwing onResponseStarted still returns the response`() {
        val engine = ScriptedCronetEngine()
        engine.responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3")
        SarieBridge.install(
            engine,
            SarieConfig {
                policy(
                    object : CronetPolicy {
                        override val allowedOrigins: Set<String> = setOf("example.com")
                    },
                )
                mapper(mapper)
                listener(
                    object : SarieListener {
                        override fun onResponseStarted(call: Call, info: SarieResponseInfo) {
                            throw IllegalStateException("host listener error")
                        }
                    },
                )
            },
        )
        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        val response = CronetBridge.intercept(chain)
        assertEquals(200, response.code)
        response.close()
    }
}

