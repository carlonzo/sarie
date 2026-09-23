package sarie.bridge.mapping

import java.util.concurrent.Executor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseConverterTest {

    private val converter = ResponseConverter()

    private fun request(method: String = "GET"): Request =
        Request.Builder().url("https://example.com/a").method(method, null).build()

    private fun callback(
        info: FakeUrlResponseInfo,
        body: String = "",
    ): OkHttpBridgeCallback {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        cb.headersFuture.complete(info)
        cb.bodySourceFuture.complete(Buffer().writeUtf8(body))
        return cb
    }

    private fun convert(
        info: FakeUrlResponseInfo,
        body: String = "",
        method: String = "GET",
    ): Response = converter.toResponse(request(method), callback(info, body))

    @Test
    fun `maps code, message and headers`() {
        val response = convert(
            FakeUrlResponseInfo(
                statusCode = 404,
                statusText = "Not Found",
                headersAsList = listOf(
                    FakeUrlResponseInfo.headerEntry("Content-Type", "text/plain"),
                    FakeUrlResponseInfo.headerEntry("X-A", "1"),
                ),
            ),
        )

        assertEquals(404, response.code)
        assertEquals("Not Found", response.message)
        assertEquals("text/plain", response.header("Content-Type"))
        assertEquals("1", response.header("X-A"))
    }

    @Test
    fun `negotiated protocol mapping`() {
        val cases = mapOf(
            "quic" to Protocol.HTTP_3,
            "h3" to Protocol.HTTP_3,
            "h3-29" to Protocol.HTTP_3,
            "h2" to Protocol.HTTP_2,
            "h2-14" to Protocol.HTTP_2,
            "spdy/3.1" to Protocol.HTTP_2,
            "http/1.1" to Protocol.HTTP_1_1,
            "unknown" to Protocol.HTTP_1_0,
        )
        for ((negotiated, expected) in cases) {
            val response = convert(FakeUrlResponseInfo(negotiatedProtocol = negotiated))
            assertEquals("negotiated=$negotiated", expected, response.protocol)
        }
    }

    @Test
    fun `duplicate response headers are all kept`() {
        val response = convert(
            FakeUrlResponseInfo(
                headersAsList = listOf(
                    FakeUrlResponseInfo.headerEntry("Set-Cookie", "a=1"),
                    FakeUrlResponseInfo.headerEntry("Set-Cookie", "b=2"),
                ),
            ),
        )

        assertEquals(listOf("a=1", "b=2"), response.headers.values("Set-Cookie"))
    }

    @Test
    fun `all-Cronet-handled Content-Encoding strips encoding and length headers`() {
        val handled = listOf("gzip", "br", "deflate", "x-gzip", "zstd", "gzip, x-gzip")
        for (encoding in handled) {
            val response = convert(
                FakeUrlResponseInfo(
                    headersAsList = listOf(
                        FakeUrlResponseInfo.headerEntry("Content-Encoding", encoding),
                        FakeUrlResponseInfo.headerEntry("Content-Length", "100"),
                        FakeUrlResponseInfo.headerEntry("X-Keep", "yes"),
                    ),
                ),
                body = "decoded",
            )

            assertEquals("encoding=$encoding", null, response.header("Content-Encoding"))
            assertEquals("encoding=$encoding", null, response.header("Content-Length"))
            assertEquals("encoding=$encoding", "yes", response.header("X-Keep"))
            assertEquals("encoding=$encoding", -1L, response.body!!.contentLength())
            assertEquals("encoding=$encoding", "decoded", response.body!!.string())
        }
    }

    @Test
    fun `identity passthrough keeps Content-Length`() {
        val response = convert(
            FakeUrlResponseInfo(
                headersAsList = listOf(
                    FakeUrlResponseInfo.headerEntry("Content-Length", "42"),
                ),
            ),
        )

        assertEquals("42", response.header("Content-Length"))
        assertEquals(42L, response.body!!.contentLength())
    }

    @Test
    fun `unknown Content-Encoding keeps headers and passes body verbatim`() {
        val response = convert(
            FakeUrlResponseInfo(
                headersAsList = listOf(
                    FakeUrlResponseInfo.headerEntry("Content-Encoding", "custom"),
                    FakeUrlResponseInfo.headerEntry("Content-Length", "5"),
                ),
            ),
            body = "raw!!",
        )

        assertEquals("custom", response.header("Content-Encoding"))
        assertEquals(5L, response.body!!.contentLength())
        assertEquals("raw!!", response.body!!.string())
    }

    @Test
    fun `mixed known and unknown encodings keep headers`() {
        val response = convert(
            FakeUrlResponseInfo(
                headersAsList = listOf(
                    FakeUrlResponseInfo.headerEntry("Content-Encoding", "gzip, custom"),
                    FakeUrlResponseInfo.headerEntry("Content-Length", "7"),
                ),
            ),
        )

        assertEquals("gzip, custom", response.header("Content-Encoding"))
        assertEquals("7", response.header("Content-Length"))
    }

    @Test
    fun `204 has empty body without exception`() {
        val response = convert(FakeUrlResponseInfo(statusCode = 204, statusText = "No Content"))

        assertEquals(204, response.code)
        assertTrue(response.body!!.source().exhausted())
    }

    @Test
    fun `204 with non-zero Content-Length throws ProtocolException`() {
        assertThrows(java.net.ProtocolException::class.java) {
            convert(
                FakeUrlResponseInfo(
                    statusCode = 204,
                    headersAsList = listOf(
                        FakeUrlResponseInfo.headerEntry("Content-Length", "5"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `205 with non-zero Content-Length throws ProtocolException`() {
        assertThrows(java.net.ProtocolException::class.java) {
            convert(
                FakeUrlResponseInfo(
                    statusCode = 205,
                    headersAsList = listOf(
                        FakeUrlResponseInfo.headerEntry("Content-Length", "5"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `HEAD forces contentLength 0`() {
        val response = convert(
            FakeUrlResponseInfo(
                headersAsList = listOf(
                    FakeUrlResponseInfo.headerEntry("Content-Length", "123"),
                ),
            ),
            method = "HEAD",
        )

        assertEquals(0L, response.body!!.contentLength())
    }

    @Test
    fun `onFailed before headers surfaces IOException with Cronet cause`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        cb.headersFuture.completeExceptionally(FakeCronetException("boom"))
        cb.bodySourceFuture.completeExceptionally(FakeCronetException("boom"))

        val error = assertThrows(java.io.IOException::class.java) {
            converter.toResponse(request(), cb)
        }
        assertTrue(error.cause is FakeCronetException)
        assertEquals("boom", error.cause!!.message)
    }

    @Test
    fun `unparsable Content-Length falls back to -1`() {
        val response = convert(
            FakeUrlResponseInfo(
                headersAsList = listOf(
                    FakeUrlResponseInfo.headerEntry("Content-Length", "not-a-number"),
                ),
            ),
        )

        assertEquals(-1L, response.body!!.contentLength())
    }

    @Test
    fun `response request field is the original request`() {
        val req = request()
        val response = converter.toResponse(req, callback(FakeUrlResponseInfo()))

        assertEquals(req, response.request)
    }

    @Test
    fun `timestamps populated through the real callback and ordered`() {
        val before = System.currentTimeMillis()
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        val info = FakeUrlResponseInfo(
            headersAsList = listOf(
                FakeUrlResponseInfo.headerEntry("Content-Type", "text/plain"),
                FakeUrlResponseInfo.headerEntry("Content-Length", "3"),
            ),
        )
        cb.onResponseStarted(newRequest(cb), info)

        val response = converter.toResponse(request(), cb)

        assertTrue("sentRequestAtMillis must be set", response.sentRequestAtMillis >= before)
        assertTrue(
            "receivedResponseAtMillis must be set",
            response.receivedResponseAtMillis >= before,
        )
        assertTrue(
            "sent must not be after received (${response.sentRequestAtMillis} > " +
                "${response.receivedResponseAtMillis})",
            response.sentRequestAtMillis <= response.receivedResponseAtMillis,
        )
    }

    private fun newRequest(callback: OkHttpBridgeCallback): FakeUrlRequest =
        FakeUrlRequest(
            FakeUrlRequestBuilder("https://example.com/a", callback, Executor { it.run() }),
        )
}
