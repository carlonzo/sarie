package sarie.bridge.mapping

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import org.chromium.net.UploadDataProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sarie.bridge.CronetExecutor
import sarie.bridge.CronetUploadExecutor

/**
 * Concurrency regression tests for issue #52:
 *
 * Verifies that concurrent uploads do not starve or deadlock when streaming upload pumps wait
 * between reads on the body-reader executor while Cronet's upload executor is servicing
 * other upload callbacks.
 */
class ConcurrentGzipUploadStarvationTest {

    @After
    fun ensureExecutorsResponsive() {
        CronetExecutor.submit { }.get(3, TimeUnit.SECONDS)
        CronetUploadExecutor.submit { }.get(3, TimeUnit.SECONDS)
    }

    @Test(timeout = 10_000L)
    fun `concurrent unknown-length gzip and buffered upload succeeds without starvation`() {
        val rawPayloadA = (1..100).joinToString("") { "gzip-payload-chunk-$it-" }.toByteArray()
        val providerA = UploadDataProviders.create(streamingBody(rawPayloadA, gzip = true), CronetExecutor, 1_000L)
        val sinkA = FakeUploadDataSink()
        val collectedA = Buffer()

        // Step 1: Read first chunk of streaming A on upload executor
        CronetUploadExecutor.submit { readChunk(providerA, sinkA, collectedA) }.get(5, TimeUnit.SECONDS)
        assertEquals(listOf(false), sinkA.readSucceeded)
        assertTrue(sinkA.readErrors.isEmpty())

        // Step 2: Enqueue buffered B then A continuation on upload executor
        val rawPayloadB = "buffered-fixed-length-payload-B".toByteArray()
        val providerB = UploadDataProviders.create(rawPayloadB.toRequestBody(null), CronetExecutor, 1_000L)
        val sinkB = FakeUploadDataSink()
        val bufferB = ByteBuffer.allocate(rawPayloadB.size)

        val futureB = CronetUploadExecutor.submit { providerB.read(sinkB, bufferB) }
        val futureAContinuation = CronetUploadExecutor.submit { drainStream(providerA, sinkA, collectedA) }

        try {
            futureB.get(5, TimeUnit.SECONDS)
        } finally {
            runCatching { futureAContinuation.get(5, TimeUnit.SECONDS) }
        }
        futureAContinuation.get(5, TimeUnit.SECONDS)

        assertBufferedSucceeded(sinkB, bufferB, rawPayloadB)
        assertStreamSucceeded(sinkA, collectedA, rawPayloadA, isGzip = true)
        verifyBufferedRecovery()
    }

    @Test(timeout = 10_000L)
    fun `concurrent multi-chunk streaming uploads succeed without starvation`() {
        val rawPayloadA = "streaming-payload-chunk-A-".repeat(30).toByteArray()
        val providerA = UploadDataProviders.create(streamingBody(rawPayloadA), CronetExecutor, 1_000L)
        val sinkA = FakeUploadDataSink()
        val collectedA = Buffer()

        // Step 1: Read first chunk of streaming A on upload executor
        CronetUploadExecutor.submit { readChunk(providerA, sinkA, collectedA) }.get(5, TimeUnit.SECONDS)
        assertEquals(listOf(false), sinkA.readSucceeded)
        assertTrue(sinkA.readErrors.isEmpty())

        // Step 2: Enqueue streaming B then A continuation on upload executor
        val rawPayloadB = "streaming-payload-chunk-B-".repeat(30).toByteArray()
        val providerB = UploadDataProviders.create(streamingBody(rawPayloadB), CronetExecutor, 1_000L)
        val sinkB = FakeUploadDataSink()
        val collectedB = Buffer()

        val futureB1 = CronetUploadExecutor.submit { readChunk(providerB, sinkB, collectedB) }
        val futureAContinuation = CronetUploadExecutor.submit { drainStream(providerA, sinkA, collectedA) }
        val futureBContinuation = CronetUploadExecutor.submit { drainStream(providerB, sinkB, collectedB) }

        try {
            futureB1.get(5, TimeUnit.SECONDS)
        } finally {
            runCatching { futureAContinuation.get(5, TimeUnit.SECONDS) }
            runCatching { futureBContinuation.get(5, TimeUnit.SECONDS) }
        }
        futureAContinuation.get(5, TimeUnit.SECONDS)
        futureBContinuation.get(5, TimeUnit.SECONDS)

        assertStreamSucceeded(sinkA, collectedA, rawPayloadA)
        assertStreamSucceeded(sinkB, collectedB, rawPayloadB)
        verifyBufferedRecovery()
    }

    private fun decompressGzip(compressed: ByteArray): ByteArray =
        GzipSource(Buffer().write(compressed)).buffer().use { it.readByteArray() }

    private fun streamingBody(payload: ByteArray, gzip: Boolean = false): RequestBody =
        object : RequestBody() {
            override fun contentType() = null
            override fun contentLength(): Long = -1L
            override fun writeTo(sink: BufferedSink) {
                if (gzip) {
                    GzipSink(sink).buffer().use { it.write(payload) }
                } else {
                    sink.write(payload)
                }
            }
        }

    private fun readChunk(provider: UploadDataProvider, sink: FakeUploadDataSink, out: Buffer, chunkSize: Int = 64) {
        val buf = ByteBuffer.allocate(chunkSize)
        provider.read(sink, buf)
        buf.flip()
        val chunk = ByteArray(buf.remaining())
        buf.get(chunk)
        out.write(chunk)
    }

    private fun drainStream(provider: UploadDataProvider, sink: FakeUploadDataSink, out: Buffer, chunkSize: Int = 64) {
        var reads = 0
        while (sink.readSucceeded.lastOrNull() != true && sink.readErrors.isEmpty() && reads++ < 100) {
            readChunk(provider, sink, out, chunkSize)
        }
    }

    private fun assertStreamSucceeded(
        sink: FakeUploadDataSink,
        collected: Buffer,
        expected: ByteArray,
        isGzip: Boolean = false,
    ) {
        assertTrue("stream had read errors: ${sink.readErrors}", sink.readErrors.isEmpty())
        assertTrue("expected at least 3 reads (2 data + EOF)", sink.readSucceeded.size >= 3)
        assertEquals(true, sink.readSucceeded.last())
        assertTrue(sink.readSucceeded.dropLast(1).all { !it })
        val actual = if (isGzip) decompressGzip(collected.readByteArray()) else collected.readByteArray()
        assertArrayEquals(expected, actual)
    }

    private fun assertBufferedSucceeded(sink: FakeUploadDataSink, buffer: ByteBuffer, expected: ByteArray) {
        assertTrue("buffered upload had read errors: ${sink.readErrors}", sink.readErrors.isEmpty())
        assertEquals(listOf(false), sink.readSucceeded)
        buffer.flip()
        val readBytes = ByteArray(buffer.remaining())
        buffer.get(readBytes)
        assertArrayEquals(expected, readBytes)
    }

    private fun verifyBufferedRecovery() {
        val payload = "recovered-buffered-C".toByteArray()
        val provider = UploadDataProviders.create(payload.toRequestBody(null), CronetExecutor, 1_000L)
        val sink = FakeUploadDataSink()
        val buffer = ByteBuffer.allocate(payload.size)
        CronetUploadExecutor.submit { provider.read(sink, buffer) }.get(5, TimeUnit.SECONDS)
        assertBufferedSucceeded(sink, buffer, payload)
    }
}
