package sarie.bridge.mapping

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okio.Buffer
import okio.Source
import okio.buffer
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
 * How a partially filled body chunk reaches the caller: promptly, byte for byte, and without a
 * Cronet-side failure. okhttp-sse's `ServerSentEventReader` reads with `select()` and
 * `readUtf8LineStrict()`, so a chunk held back until the 32 KiB buffer filled would stall the
 * stream; these tests pin the opposite.
 */
class BodySourceStreamingTest {

    @After
    fun tearDown() {
        SarieBridge.uninstall()
    }

    private val info: UrlResponseInfo = FakeUrlResponseInfo()

    private fun newRequest(callback: UrlRequest.Callback): FakeUrlRequest =
        FakeUrlRequestBuilder("https://example.com/", callback, Executor { it.run() }).build()

    /** A live body source whose Cronet reads are driven by a [ScriptedNetwork]. */
    private fun newBodySource(
        readTimeoutMillis: Long = 5_000,
    ): Pair<ScriptedNetwork, Source> {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = readTimeoutMillis)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)
        return ScriptedNetwork(cb, request, info) to source
    }

    @Test(timeout = 20_000)
    fun `uneven short chunks reach the caller as they arrive, byte for byte`() {
        val (network, source) = newBodySource()
        val longChunk = ByteArray(200) { 'x'.code.toByte() }

        val cronetThread = Thread {
            network.complete("seven!!".toByteArray()) // 7 bytes
            network.complete("abc".toByteArray()) // 3 bytes
            network.complete(ByteArray(0)) // a completion that delivered nothing
            network.complete(longChunk)
            network.complete("tail!".toByteArray()) // 5 bytes
            network.succeed()
        }
        cronetThread.start()

        val sink = Buffer()
        // One read per non-empty chunk: nothing is withheld waiting for a full 32 KiB buffer.
        assertEquals(7L, source.read(sink, 1024))
        assertEquals(3L, source.read(sink, 1024))
        assertEquals(200L, source.read(sink, 1024))
        assertEquals(5L, source.read(sink, 1024))
        assertEquals(-1L, source.read(sink, 1024))

        assertEquals("seven!!abc" + "x".repeat(200) + "tail!", sink.readUtf8())
        cronetThread.join(10_000)
        // Five completions - one of them empty - plus the read that observed EOF: the empty
        // completion cost exactly one re-issued read and nothing else.
        assertEquals(6, network.readCalls.get())
    }

    @Test(timeout = 20_000)
    fun `a completion with no bytes does not fail the read and the next chunk still arrives`() {
        val (network, source) = newBodySource()

        val cronetThread = Thread {
            network.complete(ByteArray(0))
            network.complete(ByteArray(0))
            network.complete(ByteArray(0))
            network.complete("payload".toByteArray())
            network.succeed()
        }
        cronetThread.start()

        val sink = Buffer()
        assertEquals(7L, source.read(sink, 1024))
        assertEquals("payload", sink.readUtf8())
        assertEquals(4, network.readCalls.get())
        assertEquals(-1L, source.read(sink, 1024))
        cronetThread.join(10_000)
    }

    @Test(timeout = 20_000)
    fun `SSE event lines are readable the moment their chunk arrives`() {
        val (network, source) = newBodySource()
        val buffered = source.buffer()

        val firstEventRead = CountDownLatch(1)
        val secondEventRead = CountDownLatch(1)

        val cronetThread = Thread {
            // One completion per event, and the next chunk is only produced once the test has
            // confirmed the previous event was delivered. A body that withheld a partial chunk
            // would never release the first line, and this thread would stall.
            network.complete("data: one\n\n".toByteArray())
            check(firstEventRead.await(10, TimeUnit.SECONDS)) { "first event never surfaced" }
            network.complete("data: two\r\n\r\n".toByteArray())
            check(secondEventRead.await(10, TimeUnit.SECONDS)) { "second event never surfaced" }
            network.succeed()
        }
        cronetThread.start()

        // Exactly what ServerSentEventReader does: block until the line is complete, take it, and
        // do not ask Cronet for anything else.
        assertEquals("data: one", buffered.readUtf8LineStrict())
        assertEquals("", buffered.readUtf8LineStrict())
        firstEventRead.countDown()

        assertEquals("data: two", buffered.readUtf8LineStrict())
        assertEquals("", buffered.readUtf8LineStrict())
        secondEventRead.countDown()

        assertTrue(buffered.exhausted())
        cronetThread.join(10_000)
        assertFalse(cronetThread.isAlive)
        // One Cronet read per event, one for EOF: no read-ahead, no extra completions needed.
        assertEquals(3, network.readCalls.get())
    }

    @Test
    fun `a byteCount past the Int range neither truncates nor corrupts the stream`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 5_000)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)

        val chunks = ArrayDeque(listOf("hello", " world"))
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
        // Larger than Int.MAX_VALUE: the copy count must be clamped to what the buffer holds
        // instead of being truncated to a negative limit.
        assertEquals(5L, source.read(sink, 3_000_000_000L))
        assertEquals(6L, source.read(sink, Long.MAX_VALUE))
        assertEquals(-1L, source.read(sink, Long.MAX_VALUE))
        assertEquals("hello world", sink.readUtf8())
    }

    @Test
    fun `a huge byteCount after a partial drain keeps the buffered remainder readable`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 5_000)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)

        var completions = 0
        request.readHandler = { buffer ->
            completions++
            if (completions == 1) {
                buffer.put("abcdef".toByteArray())
                cb.onReadCompleted(request, info, buffer)
            } else {
                cb.onSucceeded(request, info)
            }
        }

        val sink = Buffer()
        assertEquals(2L, source.read(sink, 2))
        assertEquals(4L, source.read(sink, 3_000_000_000L))
        assertEquals("abcdef", sink.readUtf8())
        // The whole chunk came out of the single Cronet read that filled it.
        assertEquals(1, request.readCalls)
    }

    @Test
    fun `closed source still refuses reads`() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 5_000)
        val request = newRequest(cb)
        cb.onResponseStarted(request, info)
        val source = cb.bodySourceFuture.get(1, TimeUnit.SECONDS)

        source.close()

        val thrown = assertThrows(IllegalStateException::class.java) {
            source.read(Buffer(), 1024)
        }
        assertEquals("closed", thrown.message)
    }
}

/**
 * Stands in for Cronet's network thread: [FakeUrlRequest.read] hands the [ByteBuffer] over here,
 * exactly as real Cronet returns from read() before delivering any callback. Data and the matching
 * callback then arrive later, from the other side.
 */
internal class ScriptedNetwork(
    private val callback: OkHttpBridgeCallback,
    private val request: FakeUrlRequest,
    private val info: UrlResponseInfo,
) {
    private val readBuffers = LinkedBlockingQueue<ByteBuffer>(1)

    /** How many times the body source issued a Cronet read. */
    val readCalls = AtomicInteger(0)

    init {
        request.readHandler = { buffer ->
            readCalls.incrementAndGet()
            readBuffers.put(buffer)
        }
    }

    /** Completes the pending Cronet read with [bytes] (possibly none of them). */
    fun complete(bytes: ByteArray) {
        val buffer = readBuffers.poll(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no pending Cronet read after ${readCalls.get()} issued")
        buffer.put(bytes)
        callback.onReadCompleted(request, info, buffer)
    }

    /** Ends the body: the pending Cronet read completes with onSucceeded instead. */
    fun succeed() {
        if (readBuffers.poll(10, TimeUnit.SECONDS) == null) {
            throw AssertionError("no pending Cronet read after ${readCalls.get()} issued")
        }
        callback.onSucceeded(request, info)
    }
}
