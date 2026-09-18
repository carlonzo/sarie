package dev.okhttpcronet.bridge.mapping

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import okhttp3.RequestBody
import okio.BufferedSink
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadDataProvidersTest {

    private val executor = DaemonExecutorService()

    private fun sink() = FakeUploadDataSink()

    private fun buffer(capacity: Int = 64): ByteBuffer = ByteBuffer.allocate(capacity)

    /** Drives one provider.read() and returns the bytes handed over. */
    private fun readOnce(provider: UploadDataProvider, sink: UploadDataSink): ByteArray {
        val buffer = buffer()
        provider.read(sink, buffer)
        buffer.flip()
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun body(
        contentLength: Long = -1L,
        oneShot: Boolean = false,
        payload: String = "hello",
        emitter: (BufferedSink) -> Unit = { it.writeUtf8(payload) },
    ): RequestBody = object : RequestBody() {
        override fun contentType() = null
        override fun contentLength(): Long = contentLength
        override fun isOneShot(): Boolean = oneShot
        override fun writeTo(sink: BufferedSink) = emitter(sink)
    }

    private fun bytes(s: String): ByteArray = s.toByteArray()

    @Test
    fun `buffered provider returns full body and getLength`() {
        val provider = UploadDataProviders.create(
            body(contentLength = 5),
            executor,
            writeTimeoutMillis = 5_000,
        )
        val sink = sink()

        assertEquals(5L, provider.getLength())
        assertTrue(bytes("hello").contentEquals(readOnce(provider, sink)))
        assertEquals(listOf(false), sink.readSucceeded)
    }

    @Test
    fun `buffered provider supports rewind`() {
        val provider = UploadDataProviders.create(
            body(contentLength = 5),
            executor,
            writeTimeoutMillis = 5_000,
        )
        val sink = sink()

        assertTrue(bytes("hello").contentEquals(readOnce(provider, sink)))
        provider.rewind(sink)
        assertEquals(1, sink.rewindSucceededCalls)
        assertTrue(bytes("hello").contentEquals(readOnce(provider, sink)))
    }

    @Test
    fun `buffered provider honors writeTimeoutMillis on a stalled body`() {
        val latch = CountDownLatch(1)
        val provider = UploadDataProviders.create(
            body(contentLength = 5, emitter = { latch.await() }),
            executor,
            writeTimeoutMillis = 50,
        )

        val thrown = assertThrows(java.io.IOException::class.java) {
            readOnce(provider, sink())
        }
        assertTrue(thrown.cause is java.util.concurrent.TimeoutException)
        latch.countDown()
    }

    @Test
    fun `one-shot body with known length uses streaming provider`() {
        val provider = UploadDataProviders.create(
            body(contentLength = 5, oneShot = true),
            executor,
            writeTimeoutMillis = 5_000,
        )
        val sink = sink()

        // Streaming providers refuse rewind: one-shot bodies cannot replay.
        provider.rewind(sink)
        assertEquals(1, sink.rewindErrors.size)

        assertTrue(bytes("hello").contentEquals(readOnce(provider, sink)))
        assertEquals(listOf(false), sink.readSucceeded)
    }

    @Test
    fun `unknown length body streams to end-of-body`() {
        val provider = UploadDataProviders.create(
            body(contentLength = -1),
            executor,
            writeTimeoutMillis = 5_000,
        )
        val sink = sink()

        assertTrue(bytes("hello").contentEquals(readOnce(provider, sink)))
        assertEquals(listOf(false), sink.readSucceeded)

        // Second read signals the end of the body.
        provider.read(sink, buffer())
        assertEquals(listOf(false, true), sink.readSucceeded)
    }

    @Test
    fun `streaming known-length body signals read success with the last chunk`() {
        val provider = UploadDataProviders.create(
            body(contentLength = 5),
            executor,
            writeTimeoutMillis = 5_000,
        )
        val sink = sink()

        assertTrue(bytes("hello").contentEquals(readOnce(provider, sink)))
        // Single read drained the whole known-length body: exactly one onReadSucceeded(false).
        assertEquals(listOf(false), sink.readSucceeded)
    }

    @Test
    fun `streaming provider honors writeTimeoutMillis via onReadError`() {
        val latch = CountDownLatch(1)
        val provider = UploadDataProviders.create(
            body(contentLength = -1, emitter = { latch.await() }),
            executor,
            writeTimeoutMillis = 50,
        )
        val sink = sink()

        provider.read(sink, buffer())

        assertEquals(1, sink.readErrors.size)
        assertTrue(sink.readErrors[0].cause is java.util.concurrent.TimeoutException)
        latch.countDown()
    }

    @Test
    fun `streaming provider larger than one buffer drains in multiple reads`() {
        val provider = UploadDataProviders.create(
            body(contentLength = 200, payload = "x".repeat(200)),
            executor,
            writeTimeoutMillis = 5_000,
        )
        val sink = sink()

        val total = StringBuilder()
        var reads = 0
        while (reads < 10) {
            val buffer = buffer(capacity = 64)
            provider.read(sink, buffer)
            buffer.flip()
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            total.append(String(chunk))
            reads++
            if (total.length >= 200) break
        }

        assertEquals(200, total.length)
        assertTrue(sink.readSucceeded.all { !it })
    }
}
