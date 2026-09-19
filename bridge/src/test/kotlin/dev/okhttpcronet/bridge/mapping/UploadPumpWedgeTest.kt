package dev.okhttpcronet.bridge.mapping

import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.RequestBody
import okio.BufferedSink
import org.chromium.net.UploadDataProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression for the F2 blocking finding: when a streaming upload is abandoned mid-flight
 * (call cancel / redirect / header-wait timeout - Cronet never invokes the provider again, the
 * cronet-api UploadDataProvider has no cancellation notification), the pump task must fail its
 * transfer and RETURN instead of blocking forever in the broker's pending-read wait, wedging
 * the process-wide single-thread [dev.okhttpcronet.bridge.CronetExecutor].
 */
class UploadPumpWedgeTest {

    @Test(timeout = 10_000L)
    fun `abandoned streaming upload releases the executor and fails the transfer`() {
        // Stand-in for the process-wide single-thread CronetExecutor that runs the pump.
        val cronetExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fake-cronet-executor").apply { isDaemon = true }
        }
        val provider = UploadDataProviders.create(
            body(contentLength = -1, payload = "x".repeat(65_536)),
            cronetExecutor,
            writeTimeoutMillis = 200,
        )
        val sink = FakeUploadDataSink()

        // Cronet reads twice, then abandons the request: no further provider callbacks ever
        // arrive, but the pump still has bytes left to hand over.
        provider.read(sink, ByteBuffer.allocate(64))
        provider.read(sink, ByteBuffer.allocate(64))

        // (a) The pump task must complete: the executor must stay reusable for later uploads.
        try {
            cronetExecutor.submit { }.get(2, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            fail("Executor still wedged by the abandoned upload pump: $e")
        }

        // (b) A provider-side read after abandonment unwinds with the timeout/abort error
        // instead of hanging.
        provider.read(sink, ByteBuffer.allocate(64))
        assertTrue("expected a read error after abandonment", sink.readErrors.isNotEmpty())
        val errorChain = generateSequence<Throwable>(sink.readErrors[0]) { it.cause }
        assertTrue(
            "expected the write-timeout error in the failure chain, got: $errorChain",
            errorChain.any { it is TimeoutException },
        )
    }

    private fun body(contentLength: Long, payload: String): RequestBody = object : RequestBody() {
        override fun contentType() = null
        override fun contentLength(): Long = contentLength
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(payload)
        }
    }
}
