package dev.okhttpcronet.bridge.mapping

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okio.Buffer
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpBridgeCallbackTest {

    private val info: UrlResponseInfo = FakeUrlResponseInfo()

    private fun newRequest(callback: UrlRequest.Callback): FakeUrlRequest {
        val builder = FakeUrlRequestBuilder("https://example.com/", callback, Executor { it.run() })
        return builder.build()
    }

    @Test
    fun `redirect surfaces 3xx with empty body and cancels request`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        val request = newRequest(cb)
        val redirectInfo = FakeUrlResponseInfo(
            statusCode = 302,
            statusText = "Found",
            headersAsList = listOf(FakeUrlResponseInfo.headerEntry("Location", "/b")),
        )

        cb.onRedirectReceived(request, redirectInfo, "https://example.com/b")

        assertEquals(redirectInfo, cb.headersFuture.get(1, TimeUnit.SECONDS))
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
        val sink = Buffer()
        assertEquals(-1L, source.read(sink, 1024))
        assertEquals(0L, sink.size)
        assertEquals(1, request.cancelCalls)
        assertEquals(0, request.followRedirectCalls)
    }

    @Test
    fun `responseStarted completes headers and streaming source futures`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        val request = newRequest(cb)

        cb.onResponseStarted(request, info)

        assertEquals(info, cb.headersFuture.get(1, TimeUnit.SECONDS))
        assertTrue(cb.bodySourceFuture.get(1, TimeUnit.SECONDS) is okio.Source)
    }

    @Test
    fun `readTimeout stall cancels the request and throws timeout IOException`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 100)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)

        val error = CompletableFuture<Throwable>()
        Thread {
            try {
                source.read(Buffer(), 1024)
            } catch (t: Throwable) {
                error.complete(t)
            }
        }.start()

        val thrown = error.get(5, TimeUnit.SECONDS)
        assertTrue("expected IOException but was $thrown", thrown is java.io.IOException)
        assertEquals(1, request.cancelCalls)
    }

    @Test
    fun `onFailed before response started fails both futures`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        val request = newRequest(cb)
        val cause = FakeCronetException("network down")

        cb.onFailed(request, info, cause)

        try {
            cb.headersFuture.get(1, TimeUnit.SECONDS)
            throw AssertionError("headersFuture should have failed")
        } catch (expected: java.util.concurrent.ExecutionException) {
            assertEquals(cause, expected.cause)
        }
        try {
            cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
            throw AssertionError("bodySourceFuture should have failed")
        } catch (expected: java.util.concurrent.ExecutionException) {
            assertEquals(cause, expected.cause)
        }
    }

    @Test
    fun `onFailed during read propagates into the pending read`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
        request.readHandler = { cb.onFailed(request, info, FakeCronetException("mid-body")) }

        val error = CompletableFuture<Throwable>()
        Thread {
            try {
                source.read(Buffer(), 1024)
            } catch (t: Throwable) {
                error.complete(t)
            }
        }.start()

        val thrown = error.get(5, TimeUnit.SECONDS)
        assertTrue(thrown is java.io.IOException)
        assertTrue(thrown.cause is FakeCronetException)
        assertEquals("mid-body", thrown.cause!!.message)
    }

    @Test
    fun `onCanceled before response started fails futures with Canceled`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        val request = newRequest(cb)

        cb.onCanceled(request, info)

        try {
            cb.headersFuture.get(1, TimeUnit.SECONDS)
            throw AssertionError("headersFuture should have failed")
        } catch (expected: java.util.concurrent.ExecutionException) {
            assertTrue(expected.cause is java.io.IOException)
            assertEquals("Canceled", expected.cause!!.message)
        }
    }

    @Test
    fun `onCanceled during read throws IOException Canceled`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
        request.readHandler = { cb.onCanceled(request, info) }

        val error = CompletableFuture<Throwable>()
        Thread {
            try {
                source.read(Buffer(), 1024)
            } catch (t: Throwable) {
                error.complete(t)
            }
        }.start()

        val thrown = error.get(5, TimeUnit.SECONDS)
        assertTrue(thrown is java.io.IOException)
        assertEquals("Canceled", thrown.message)
    }

    @Test
    fun `read after cancellation throws Canceled immediately`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
        cb.onCanceled(request, info)

        val thrown = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            source.read(Buffer(), 1024)
        }
        assertEquals("Canceled", thrown.message)
    }

    @Test
    fun `streaming reads deliver chunks in order to completion`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 0)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
        var reads = 0
        request.readHandler = { buffer ->
            reads++
            if (reads == 1) {
                buffer.put("hello".toByteArray())
                cb.onReadCompleted(request, info, buffer)
            } else if (reads == 2) {
                buffer.put(" world".toByteArray())
                cb.onReadCompleted(request, info, buffer)
            } else {
                cb.onSucceeded(request, info)
            }
        }

        val sink = Buffer()
        assertEquals(5L, source.read(sink, 1024))
        assertEquals(6L, source.read(sink, 1024))
        assertEquals(-1L, source.read(sink, 1024))
        assertEquals("hello world", sink.readUtf8())
        assertEquals(0, request.cancelCalls)
    }

    @Test
    fun `read honors the caller byteCount limit`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 0)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
        var reads = 0
        request.readHandler = { buffer ->
            reads++
            buffer.put("hello".toByteArray())
            cb.onReadCompleted(request, info, buffer)
        }

        val sink = Buffer()
        assertEquals(3L, source.read(sink, 3))
        assertEquals("hel", sink.readUtf8())
        // Remaining 2 bytes are still in the internal buffer: no new Cronet read yet.
        assertEquals(1, request.readCalls)
        assertEquals(2L, source.read(sink, 1024))
        assertEquals("lo", sink.readUtf8())
        assertEquals(1, request.readCalls)
        // Buffer fully drained: the next read goes back to Cronet.
        assertEquals(5L, source.read(sink, 1024))
        assertEquals(2, request.readCalls)
    }

    @Test
    fun `zero-byte read neither touches network nor marks finished`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 0)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)

        assertEquals(0L, source.read(Buffer(), 0))
        assertEquals(0, request.readCalls)
    }

    @Test
    fun `close cancels an unfinished request exactly once`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 0)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)

        source.close()
        source.close()

        assertEquals(1, request.cancelCalls)
    }

    @Test
    fun `close after full read does not cancel`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 0)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
        request.readHandler = { buffer -> cb.onSucceeded(request, info) }

        assertEquals(-1L, source.read(Buffer(), 1024))
        source.close()

        assertEquals(0, request.cancelCalls)
    }

    @Test
    fun `readTimeoutMillis zero means effectively infinite`() {
        // Must not throw; 0 is OkHttp's "no timeout".
        OkHttpBridgeCallback(readTimeoutMillis = 0)
    }
}
