package sarie.bridge.mapping

import sarie.bridge.RequestToUrlRequestMapper
import sarie.bridge.SarieBridge
import java.util.concurrent.Executor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RequestConverterTest {

    private val engine = FakeCronetEngine()
    private val bodyReaderExecutor = DaemonExecutorService()

    @Before
    fun setUp() {
        SarieBridge.uninstall()
    }

    @After
    fun tearDown() {
        SarieBridge.uninstall()
    }

    private fun converter(): RequestConverter =
        RequestConverter(
            cronetEngine = engine,
            uploadDataProviderExecutor = Executor { it.run() },
            bodyReaderExecutor = bodyReaderExecutor,
            responseConverter = ResponseConverter(),
        )

    private fun body(
        contentType: String? = null,
        contentLength: Long = -1L,
        payload: String = "hello",
        oneShot: Boolean = false,
    ): RequestBody = object : RequestBody() {
        override fun contentType() = contentType?.toMediaType()
        override fun contentLength(): Long = contentLength
        override fun isOneShot(): Boolean = oneShot
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(payload)
        }
    }

    private fun get(vararg headers: Pair<String, String>): Request {
        val builder = Request.Builder().url("https://example.com/a")
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.build()
    }

    @Test
    fun `convert disables the Cronet cache before build`() {
        converter().convert(get(), readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)
        assertTrue(engine.builders.single().cacheDisabled)
    }

    @Test
    fun `GET maps method, url, headers and multi-values`() {
        val request = get("X-Dup" to "one", "X-Dup" to "two", "X-Single" to "s")

        converter().convert(request, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)

        val builder = engine.builders.single()
        assertEquals("https://example.com/a", builder.url)
        assertEquals("GET", builder.method)
        assertTrue(builder.directExecutorAllowed)
        assertEquals(
            listOf<Pair<String, String>>(
                "X-Dup" to "one, two",
                "X-Single" to "s",
            ),
            builder.headers,
        )
        assertNull(builder.uploadDataProvider)
        // The callback handed to the engine is the same one the response futures live on.
        assertNotNull(builder.callback)
    }

    @Test
    fun `repeated headers join with comma except Cookie with semicolon`() {
        val request = get(
            "X-Dup" to "one",
            "X-Dup" to "two",
            "Cookie" to "a=1",
            "cookie" to "b=2",
        )

        converter().convert(request, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)

        assertEquals(
            listOf(
                "X-Dup" to "one, two",
                "Cookie" to "a=1; b=2",
            ),
            engine.builders.single().headers,
        )
    }

    @Test
    fun `converter Content-Type and Content-Length fold into one header each`() {
        val same = Request.Builder()
            .url("https://example.com/a")
            .header("Content-Type", "text/plain")
            .header("Content-Length", "5")
            .post(body(contentType = "text/plain", contentLength = 5))
            .build()
        converter().convert(same, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)
        assertEquals(
            listOf("Content-Type" to "text/plain", "Content-Length" to "5"),
            engine.builders.single().headers,
        )

        val different = Request.Builder()
            .url("https://example.com/a")
            .header("Content-Type", "application/json")
            .post(body(contentType = "text/plain", contentLength = 5))
            .build()
        converter().convert(different, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)
        val folded = engine.builders.last().headers
            .filter { it.first.equals("Content-Type", ignoreCase = true) }
        assertEquals(listOf("Content-Type" to "application/json, text/plain"), folded)
    }

    @Test
    fun `POST with body maps Content-Type and Content-Length and sets provider`() {
        val request = Request.Builder()
            .url("https://example.com/a")
            .post(body(contentType = "text/plain", contentLength = 5))
            .build()

        converter().convert(request, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)

        val builder = engine.builders.single()
        assertEquals("POST", builder.method)
        assertTrue("Content-Type" to "text/plain" in builder.headers)
        assertTrue("Content-Length" to "5" in builder.headers)
        assertNotNull(builder.uploadDataProvider)
        assertNotNull(builder.uploadExecutor)
    }

    @Test
    fun `explicit Content-Length header is not overwritten`() {
        val request = get("Content-Length" to "7")
            .newBuilder()
            .method("POST", body(contentType = "text/plain", contentLength = 5))
            .build()

        converter().convert(request, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)

        val contentLengths = engine.builders.single().headers
            .filter { it.first.equals("Content-Length", ignoreCase = true) }
        assertEquals(listOf("Content-Length" to "7"), contentLengths)
    }

    @Test
    fun `missing or empty Content-Type with non-zero body overrides to octet-stream`() {
        val missingHeader = Request.Builder()
            .url("https://example.com/a")
            .method("POST", body(contentType = null, contentLength = 5))
            .build()
        val emptyHeader = get("Content-Type" to "   ")
            .newBuilder()
            .method("POST", body(contentType = null, contentLength = 5))
            .build()

        for (request in listOf(missingHeader, emptyHeader)) {
            converter().convert(request, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)
            val builder = engine.builders.last()
            val contentTypes = builder.headers
                .filter { it.first.equals("Content-Type", ignoreCase = true) }
            assertEquals(
                listOf("Content-Type" to "application/octet-stream"),
                contentTypes,
            )
            assertNotNull(builder.uploadDataProvider)
        }
    }

    @Test
    fun `zero-length body sets no provider and no octet-stream`() {
        val request = Request.Builder()
            .url("https://example.com/a")
            .method("POST", body(contentType = null, contentLength = 0, payload = ""))
            .build()

        converter().convert(request, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)

        val builder = engine.builders.single()
        assertNull(builder.uploadDataProvider)
        assertFalse(builder.headers.any { it.first.equals("Content-Type", ignoreCase = true) })
    }

    @Test
    fun `runtime mapper is applied to the builder before build`() {
        val request = get()
        val seen = mutableListOf<Request>()
        val mapper = RequestToUrlRequestMapper { seenRequest, builder ->
            seen.add(seenRequest)
            builder.addHeader("X-Mapped", "1")
        }
        SarieBridge.install(engine, FakePolicy(), mapper)

        converter().convert(request, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)

        val builder = engine.builders.single()
        assertSame(request, seen.single())
        assertTrue("X-Mapped" to "1" in builder.headers)
        assertSame(engine, SarieBridge.snapshot()!!.engine)
    }

    @Test
    fun `no installed snapshot still converts without mapper`() {
        converter().convert(get(), readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)

        assertEquals("GET", engine.builders.single().method)
    }

    @Test
    fun `converted request exposes urlRequest and blocking getResponse`() {
        val responseConverter = ResponseConverter()
        val converted = converter()
            .convert(get(), readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)

        val fakeRequest = engine.builders.single().builtRequest!!
        assertSame(fakeRequest, converted.urlRequest)

        fakeRequest.callback.onResponseStarted(
            fakeRequest,
            FakeUrlResponseInfo(statusCode = 200, statusText = "OK"),
        )
        val response = converted.getResponse()
        assertEquals(200, response.code)
        assertEquals("OK", response.message)
    }
}
