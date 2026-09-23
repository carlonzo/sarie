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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.CallServerInterceptor
import okhttp3.internal.http.RealInterceptorChain
import okio.Buffer
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.NetworkException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bridge-glue tests for [CronetBridge]: bytecode shape of the exact-stock fallback, the Cronet
 * path over scripted cronet-api doubles, the three release-blocker cancel interleavings, the
 * 407 guard, and the never-throwing [CronetBridge.shouldHandle].
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
            return ScriptedBuilder(url, callback, this).also { builders.add(it) }
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

        override fun setHttpMethod(method: String): UrlRequest.Builder = this
        override fun addHeader(name: String, value: String): UrlRequest.Builder = this
        override fun disableCache(): UrlRequest.Builder = this
        override fun setPriority(priority: Int): UrlRequest.Builder = this
        override fun setUploadDataProvider(provider: UploadDataProvider, executor: Executor): UrlRequest.Builder = this
        override fun allowDirectExecutor(): UrlRequest.Builder = this
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
                } else {
                    callback.onResponseStarted(this, engine.responseInfo)
                }
            }
        }

        override fun cancel() {
            cancelCalls++
            // Real engines deliver onCanceled for started requests; the bridge must cope.
            if (startEntered) callback.onCanceled(this, engine.responseInfo)
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

    @Before
    fun setUp() {
        System.clearProperty("okhttp.cronet.enabled")
        Metrics.resetForTest()
        CallRegistry.clearForTest()
    }

    @After
    fun tearDown() {
        System.clearProperty("okhttp.cronet.enabled")
        SarieBridge.uninstall()
        Metrics.resetForTest()
        CallRegistry.clearForTest()
    }

    private fun install(engine: CronetEngine, vararg origins: String) {
        SarieBridge.install(
            engine,
            object : CronetPolicy {
                override val allowedOrigins: Set<String> = origins.toSet()
            },
            mapper,
        )
    }

    /** Chain shape used by the Cronet path (no proceed happens on that path). */
    private fun cronetChain(
        client: OkHttpClient,
        url: String,
        method: String = "GET",
        body: RequestBody? = null,
    ): Pair<RealCall, RealInterceptorChain> {
        val request: Request = Request.Builder().url(url).method(method, body).build()
        val call = client.newCall(request) as RealCall
        return call to RealInterceptorChain(call, emptyList(), 0, null, request, client)
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
        val fallbacksBefore = Metrics.okhttpFallback.get()

        assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(Metrics.Reason.allowlist, Metrics.lastReason)
        assertEquals(fallbacksBefore + 1, Metrics.okhttpFallback.get())
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
        val cronetBefore = Metrics.cronet.get()

        val response = CronetBridge.intercept(chain)

        assertEquals(Protocol.HTTP_3, response.protocol)
        assertEquals(200, response.code)
        assertEquals(cronetBefore + 1, Metrics.cronet.get())
        assertEquals(1, CallRegistry.activeCount())

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
        assertEquals(0, CallRegistry.activeCount())
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
        assertEquals(0, CallRegistry.activeCount())
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
        assertEquals(0, CallRegistry.activeCount())
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
        assertEquals(0, CallRegistry.activeCount())
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
        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")

        val thrown = assertThrows(ProtocolException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(
            "Received HTTP_PROXY_AUTH (407) code while not using proxy",
            thrown.message,
        )
        // Not retryable: one attempt, no transport-failure retry.
        assertEquals(1, engine.builtRequests.size)
        assertEquals(0, Metrics.retries.get())
        // Closing the body quietly cancels the still-unfinished engine request.
        assertEquals(1, engine.builtRequests.single().cancelCalls)
        assertEquals(0, CallRegistry.activeCount())
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
        val retriesBefore = Metrics.retries.get()

        val response = CronetBridge.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(2, engine.builtRequests.size)
        engine.builtRequests.forEach { assertEquals(1, it.startCalls) }
        // Closing the unread body cancels attempt 2 and unregisters.
        response.body.close()
        assertEquals(retriesBefore + 1, Metrics.retries.get())
        assertEquals(0, CallRegistry.activeCount())
    }

    @Test
    fun `pre-headers transport failure twice for GET throws after exactly two attempts`() {
        val engine = ScriptedCronetEngine()
        engine.preHeaderFailures = 2
        install(engine, "example.com")
        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        val retriesBefore = Metrics.retries.get()

        val thrown = assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        // awaitHeaders unwraps the ExecutionException: the CronetException itself surfaces
        // (it is an IOException), which is exactly what the retry predicate matches on.
        assertTrue("expected the CronetException to surface", thrown is FakeCronetException)
        assertEquals(2, engine.builtRequests.size)
        assertEquals(retriesBefore + 1, Metrics.retries.get())
        assertEquals(0, CallRegistry.activeCount())
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
        assertEquals(0, Metrics.retries.get())
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
            body = "x".toRequestBody(null),
        )

        assertThrows(IOException::class.java) { CronetBridge.intercept(chain) }

        assertEquals(1, engine.builtRequests.size)
        assertEquals(0, Metrics.retries.get())
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
        assertEquals(0, Metrics.retries.get())
        assertEquals(0, CallRegistry.activeCount())
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
        assertEquals(0, Metrics.retries.get())
    }

    // --- shouldHandle never throws ---

    @Test
    fun `shouldHandle returns false without throwing when no snapshot is installed`() {
        SarieBridge.uninstall()
        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        assertFalse(CronetBridge.shouldHandle(chain))
    }

    @Test
    fun `shouldHandle allows allowlisted https origin`() {
        install(ScriptedCronetEngine(), "example.com")
        val (_, chain) = cronetChain(OkHttpClient(), "https://example.com/")
        assertTrue(CronetBridge.shouldHandle(chain))
    }
}
