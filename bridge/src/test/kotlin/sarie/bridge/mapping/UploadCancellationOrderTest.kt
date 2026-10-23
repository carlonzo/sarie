package sarie.bridge.mapping

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.connection.RealCall
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sarie.bridge.CallRegistry

class UploadCancellationOrderTest {

    private class TestFixture {
        val call = OkHttpClient()
            .newCall(Request.Builder().url("https://example.com/upload").build()) as RealCall
        val callback = OkHttpBridgeCallback(readTimeoutMillis = 5_000L, call = call)
        val request = FakeUrlRequestBuilder("https://example.com/upload", callback, Executor { it.run() })
            .build()
        var providerCloseCalls = 0
        var uploadClosedBeforeCancel = false

        val testProvider = object : UploadDataProvider() {
            override fun getLength(): Long = -1L
            override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) = Unit
            override fun rewind(uploadDataSink: UploadDataSink) = Unit
            override fun close() {
                providerCloseCalls++
                if (request.cancelCalls == 0) {
                    uploadClosedBeforeCancel = true
                    callback.onFailed(request, null, FakeCronetException("upload abort"))
                }
            }
        }

        val registration: CallRegistry.Registration

        init {
            request.cancelHandler = {
                callback.onCanceled(request, null)
            }
            callback.attachUploadDataProvider(testProvider)
            registration = CallRegistry.register(
                call,
                AtomicReference<UrlRequest?>(request),
                onCancel = callback::closeUpload,
            )
            callback.attach(registration)
        }
    }

    @Test
    fun `cancelUrlRequestOnce cancels engine before closing upload`() {
        val fixture = TestFixture()
        try {
            fixture.registration.cancelUrlRequestOnce()

            val failure = assertThrows(ExecutionException::class.java) {
                fixture.callback.headersFuture.get(1, TimeUnit.SECONDS)
            }
            val cause = failure.cause
            assertTrue(
                "expected IOException('Canceled'), got: $cause",
                cause is IOException && cause.message == "Canceled",
            )
            assertFalse(fixture.uploadClosedBeforeCancel)
            assertEquals(1, fixture.request.cancelCalls)
            assertEquals(1, fixture.providerCloseCalls)

            // Repeated cancel is safe and idempotent
            fixture.registration.cancelUrlRequestOnce()
            assertEquals(1, fixture.request.cancelCalls)
            assertEquals(1, fixture.providerCloseCalls)
        } finally {
            fixture.registration.unregister()
        }
    }

    @Test
    fun `body source close cancels engine before closing upload`() {
        val fixture = TestFixture()
        try {
            val info = FakeUrlResponseInfo(
                url = "https://example.com/upload",
                statusCode = 200,
            )
            fixture.callback.onResponseStarted(fixture.request, info)
            val source = fixture.callback.bodySourceFuture.get(1, TimeUnit.SECONDS)

            source.close()

            assertFalse(fixture.uploadClosedBeforeCancel)
            assertEquals(1, fixture.request.cancelCalls)
            assertEquals(1, fixture.providerCloseCalls)

            // Repeated close is safe and idempotent
            source.close()
            assertEquals(1, fixture.request.cancelCalls)
            assertEquals(1, fixture.providerCloseCalls)
        } finally {
            fixture.registration.unregister()
        }
    }
}
