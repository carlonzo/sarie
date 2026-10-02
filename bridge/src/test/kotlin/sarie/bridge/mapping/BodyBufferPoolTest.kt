package sarie.bridge.mapping

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import okio.Buffer
import okio.Source
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sarie.bridge.SarieBridge

/**
 * The direct buffer behind [OkHttpBridgeCallback]'s body source is pooled: a direct buffer is
 * only reclaimed by its Cleaner, so one per response is off-heap GC pressure.
 *
 * Two things have to hold, and they pull in opposite directions. Reuse is only safe once Cronet
 * has said the request is over - `UrlRequest.cancel()` is asynchronous, so a cancelled request can
 * still land a pending `read(buffer)` into a buffer another response is now holding. And reuse is
 * only worth having if it actually happens. Both are asserted here by buffer identity, which is
 * what the pool's callers can observe.
 */
class BodyBufferPoolTest {

    @After
    fun tearDown() {
        SarieBridge.uninstall()
    }

    private val info: UrlResponseInfo = FakeUrlResponseInfo()

    private fun newRequest(callback: UrlRequest.Callback): FakeUrlRequest =
        FakeUrlRequestBuilder("https://example.com/", callback, Executor { it.run() }).build()

    /** A body source with headers already delivered, so reads go straight to the buffer. */
    private fun newBodySource(
        readTimeoutMillis: Long = 5_000,
    ): Triple<OkHttpBridgeCallback, FakeUrlRequest, Source> {
        val callback = OkHttpBridgeCallback(readTimeoutMillis = readTimeoutMillis)
        val request = newRequest(callback)
        callback.onResponseStarted(request, info)
        val source = callback.bodySourceFuture.get(5, TimeUnit.SECONDS)
        return Triple(callback, request, source)
    }

    /**
     * Everything currently idle in the pool, returned to it afterwards by the caller.
     *
     * The pool is a process-wide singleton, so "is our buffer back?" cannot be answered by
     * renting one buffer: a previous test may have left idle buffers ahead of ours.
     */
    private fun drainIdlePool(): List<ByteBuffer> =
        List(BodyBufferPool.MAX_IDLE) { BodyBufferPool.rent() }

    @Test(timeout = 20_000)
    fun `a buffer is reused once the response finishes normally`() {
        val (callback, request, source) = newBodySource()
        var handed: ByteBuffer? = null
        request.readHandler = { buffer ->
            handed = buffer
            buffer.put("hello".toByteArray())
            callback.onReadCompleted(request, info, buffer)
            // One completion only; the next read must be served by onSucceeded.
            request.readHandler = null
        }

        val sink = Buffer()
        assertEquals(5L, source.read(sink, 1024))
        callback.onSucceeded(request, info)
        assertEquals(-1L, source.read(sink, 1024))
        source.close()

        // The pool is process-wide and other tests leave buffers idle, so look for ours among the
        // whole idle set rather than assuming we get the very first one back.
        val batch = drainIdlePool()
        assertTrue("buffer should be reusable once the request is over", batch.any { it === handed })
        batch.forEach { BodyBufferPool.release(it) }
    }

    @Test(timeout = 20_000)
    fun `a buffer is NOT returned when a read timeout cancels the request`() {
        val (callback, request, source) = newBodySource(readTimeoutMillis = 150)
        var handed: ByteBuffer? = null
        // Never completes: the read times out, the bridge cancels, and Cronet's cancel is async,
        // so a pending read can still land in this buffer after we let go of it.
        request.readHandler = { buffer -> handed = buffer }

        val error = assertThrows(java.io.IOException::class.java) { source.read(Buffer(), 1024) }
        assertTrue("was: ${error.message}", error.message!!.contains("Timed out"))
        assertEquals(1, request.cancelCalls)

        source.close()

        val rented = BodyBufferPool.rent()
        assertNotSame(
            "a buffer with a pending Cronet read behind it must not be handed to the next response",
            handed,
            rented,
        )
        BodyBufferPool.release(rented)
    }

    @Test(timeout = 20_000)
    fun `a buffer is NOT returned when another thread closes the source mid-read`() {
        val (callback, request, source) = newBodySource(readTimeoutMillis = 2_000)
        var handed: ByteBuffer? = null
        val readStarted = CountDownLatch(1)
        request.readHandler = { buffer ->
            handed = buffer
            readStarted.countDown()
        }

        val reader = Thread { runCatching { source.read(Buffer(), 1024) } }
        reader.start()
        assertTrue("reader never reached the buffer", readStarted.await(5, TimeUnit.SECONDS))

        source.close()
        reader.join(10_000)

        // The blocked reader also cancels when its own read timeout expires, so the count is not 1.
        assertTrue(request.cancelCalls >= 1)
        val batch = drainIdlePool()
        assertTrue(
            "closing while a read is in flight must not return the buffer to the pool",
            batch.none { it === handed },
        )
        batch.forEach { BodyBufferPool.release(it) }
    }

    @Test
    fun `every live response gets its own buffer`() {
        val rented = List(BodyBufferPool.MAX_IDLE + 4) { BodyBufferPool.rent() }
        assertEquals(
            "two live responses must never share a buffer",
            rented.size,
            rented.distinctByIdentity(),
        )
        rented.forEach { BodyBufferPool.release(it) }
    }

    @Test
    fun `buffers stay distinct under repeated rent and release churn`() {
        repeat(BodyBufferPool.MAX_IDLE * 2) {
            val batch = List(BodyBufferPool.MAX_IDLE) { BodyBufferPool.rent() }
            assertEquals(batch.size, batch.distinctByIdentity())
            batch.forEach { buffer ->
                buffer.put(1)
                BodyBufferPool.release(buffer)
            }
        }
    }
}

/**
 * [ByteBuffer.equals] compares *contents*, and every pooled buffer is empty and identically sized,
 * so a set of them collapses to a single element. Identity is the only thing that tells two
 * rented buffers apart, and it is exactly what these tests assert.
 */
private fun List<ByteBuffer>.distinctByIdentity(): Int {
    val seen = java.util.IdentityHashMap<ByteBuffer, Unit>()
    forEach { seen[it] = Unit }
    return seen.size
}