package sarie.bridge.mapping

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okio.Buffer
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sarie.bridge.SarieBridge

/**
 * The overall call deadline OkHttp enforces with `RealCall.timeout()`. It composes with the read
 * timeout instead of replacing it: every blocking wait is bounded by whichever budget runs out
 * first, and the two are distinguishable by the exception the caller sees - OkHttp's own
 * `InterruptedIOException("timeout")` for the call, the bridge's read-timeout `IOException`
 * otherwise.
 */
class CallTimeoutTest {

    @After
    fun tearDown() {
        SarieBridge.uninstall()
    }

    private val info: UrlResponseInfo = FakeUrlResponseInfo()

    private fun newRequest(callback: UrlRequest.Callback): FakeUrlRequest =
        FakeUrlRequestBuilder("https://example.com/", callback, Executor { it.run() }).build()

    private fun newBody(
        readTimeoutMillis: Long,
        callTimeoutMillis: Long,
    ): Triple<OkHttpBridgeCallback, FakeUrlRequest, okio.Source> {
        val cb = OkHttpBridgeCallback(
            readTimeoutMillis = readTimeoutMillis,
            callTimeoutMillis = callTimeoutMillis,
        )
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        return Triple(cb, request, cb.bodySourceFuture.get(1, TimeUnit.SECONDS))
    }

    @Test(timeout = 20_000)
    fun `an expired call deadline fails the read the way OkHttp does and cancels the request`() {
        val (_, request, source) = newBody(readTimeoutMillis = 30_000, callTimeoutMillis = 150)
        request.readHandler = { /* the body never arrives */ }

        val thrown = assertThrows(InterruptedIOException::class.java) {
            source.read(Buffer(), 1024)
        }

        assertEquals("timeout", thrown.message)
        assertEquals(1, request.cancelCalls)
    }

    @Test(timeout = 20_000)
    fun `the default call timeout leaves the read timeout in charge`() {
        val (_, request, source) = newBody(readTimeoutMillis = 150, callTimeoutMillis = 0)
        request.readHandler = { /* the body never arrives */ }

        val thrown = assertThrows(IOException::class.java) { source.read(Buffer(), 1024) }

        assertEquals("Timed out reading the response body", thrown.message)
        assertFalse(
            "a read timeout must not look like a call timeout",
            thrown is InterruptedIOException,
        )
        assertEquals(1, request.cancelCalls)
    }

    @Test(timeout = 20_000)
    fun `a read timeout shorter than the call budget still wins`() {
        val (_, request, source) = newBody(readTimeoutMillis = 120, callTimeoutMillis = 30_000)
        request.readHandler = { /* the body never arrives */ }

        val thrown = assertThrows(IOException::class.java) { source.read(Buffer(), 1024) }

        assertEquals("Timed out reading the response body", thrown.message)
        assertFalse(thrown is InterruptedIOException)
    }

    @Test(timeout = 30_000)
    fun `the deadline spans the whole body, not each individual read`() {
        val (cb, request, source) = newBody(readTimeoutMillis = 30_000, callTimeoutMillis = 300)
        request.readHandler = { buffer ->
            // Each read succeeds well inside the budget; only their total does not.
            Thread.sleep(50)
            buffer.put("x".toByteArray())
            cb.onReadCompleted(request, info, buffer)
        }

        val sink = Buffer()
        var reads = 0
        val thrown = runCatching {
            while (true) {
                assertEquals(1L, source.read(sink, 1024))
                reads++
            }
        }.exceptionOrNull()

        assertTrue(
            "expected the call deadline to expire, got $thrown",
            thrown is InterruptedIOException,
        )
        assertEquals("timeout", thrown!!.message)
        assertTrue("reads must succeed before the deadline, got $reads", reads >= 3)
        assertEquals(reads.toLong(), sink.size)
    }

    @Test(timeout = 20_000)
    fun `a body read inside the budget never times out`() {
        val (cb, request, source) = newBody(readTimeoutMillis = 0, callTimeoutMillis = 10_000)
        val chunks = ArrayDeque(listOf("one", "two", "three"))
        request.readHandler = { buffer ->
            val next = chunks.removeFirstOrNull()
            if (next == null) {
                cb.onSucceeded(request, info)
            } else {
                buffer.put(next.toByteArray())
                cb.onReadCompleted(request, info, buffer)
            }
        }

        val sink = Buffer()
        while (source.read(sink, 1024) != -1L) {
            // drain
        }

        assertEquals("onetwothree", sink.readUtf8())
        assertEquals(0, request.cancelCalls)
    }

    @Test(timeout = 30_000)
    fun `the call budget runs from the callback, so pre-read time is charged to it`() {
        val (_, request, source) = newBody(readTimeoutMillis = 30_000, callTimeoutMillis = 400)
        // A slow header phase. The budget is anchored when the callback is built - okio's Timeout
        // offers no remaining-budget accessor at our compile floor - so this time is charged to
        // the body rather than granted on top of it. See OkHttpBridgeCallback.callTimeoutMillis.
        Thread.sleep(300)
        request.readHandler = { /* the body never arrives */ }

        val start = System.nanoTime()
        val thrown = assertThrows(InterruptedIOException::class.java) {
            source.read(Buffer(), 1024)
        }
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertEquals("timeout", thrown.message)
        assertTrue(
            "the read waited ${elapsedMillis}ms; only ~100ms of the 400ms budget was left, so a " +
                "budget that restarted at the read would have taken noticeably longer",
            elapsedMillis < 300,
        )
    }
}
