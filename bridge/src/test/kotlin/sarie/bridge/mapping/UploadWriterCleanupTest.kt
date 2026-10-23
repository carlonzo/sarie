package sarie.bridge.mapping

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for upload writer cleanup on cancellation, terminal events, and close.
 *
 * Reproduces writer starvation and wedging when writeTimeoutMillis=0 and Cronet requests
 * are abandoned, canceled, redirected, or completed without prompt provider cleanup.
 */
class UploadWriterCleanupTest {

    private fun daemonSingleThreadExecutor(name: String) =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    private class ControlledExecutorService(
        private val delegate: ExecutorService,
        private val onBeforeExecute: ((Runnable) -> Unit)? = null,
        private val onAfterExecute: ((Runnable) -> Unit)? = null,
    ) : AbstractExecutorService() {
        override fun execute(command: Runnable) {
            onBeforeExecute?.invoke(command)
            delegate.execute(command)
            onAfterExecute?.invoke(command)
        }

        override fun shutdown() = delegate.shutdown()
        override fun shutdownNow(): List<Runnable> = delegate.shutdownNow()
        override fun isShutdown(): Boolean = delegate.isShutdown
        override fun isTerminated(): Boolean = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
            delegate.awaitTermination(timeout, unit)
    }

    // --- Streaming upload with writeTimeoutMillis=0 ---

    @Test(timeout = 10_000L)
    fun `streaming upload with writeTimeout 0 frees writer promptly on provider close after partial read`() {
        val writerExecutor = daemonSingleThreadExecutor("test-writer")
        try {
            val writeCount = AtomicInteger(0)
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun writeTo(sink: BufferedSink) {
                    writeCount.incrementAndGet()
                    sink.writeUtf8("chunk-data-".repeat(50))
                    sink.flush()
                }
            }
            val provider = UploadDataProviders.create(
                body,
                writerExecutor,
                writeTimeoutMillis = 0L,
                contentLength = -1L,
            )
            val sink = FakeUploadDataSink()
            provider.read(sink, ByteBuffer.allocate(64))
            assertEquals(listOf(false), sink.readSucceeded)

            provider.close()
            provider.close()

            val writerMarker = CountDownLatch(1)
            writerExecutor.execute { writerMarker.countDown() }
            assertTrue(
                "writer executor must be freed promptly after provider close",
                writerMarker.await(2, TimeUnit.SECONDS),
            )

            val lateSink = FakeUploadDataSink()
            provider.read(lateSink, ByteBuffer.allocate(64))
            assertTrue("late read after close must fail promptly", lateSink.readErrors.isNotEmpty())
            assertEquals("body must not be restarted", 1, writeCount.get())
        } finally {
            writerExecutor.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun `streaming upload closed before first read never starts writeTo`() {
        val writerExecutor = daemonSingleThreadExecutor("test-writer")
        try {
            val writeCount = AtomicInteger(0)
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun writeTo(sink: BufferedSink) {
                    writeCount.incrementAndGet()
                    sink.writeUtf8("data")
                }
            }
            val provider = UploadDataProviders.create(
                body,
                writerExecutor,
                writeTimeoutMillis = 0L,
                contentLength = -1L,
            )

            provider.close()
            provider.close()

            val writerMarker = CountDownLatch(1)
            writerExecutor.execute { writerMarker.countDown() }
            assertTrue(
                "writer executor must remain free",
                writerMarker.await(2, TimeUnit.SECONDS),
            )

            val sink = FakeUploadDataSink()
            provider.read(sink, ByteBuffer.allocate(64))

            assertEquals("body.writeTo must never start after close", 0, writeCount.get())
            assertTrue("late read after close must report error", sink.readErrors.isNotEmpty())
        } finally {
            writerExecutor.shutdownNow()
        }
    }

    // --- Blocked in writeTo unwinds promptly on provider close with writeTimeout 0 ---

    @Test(timeout = 10_000L)
    fun `buffered upload blocked in writeTo unwinds promptly on provider close with writeTimeout 0`() {
        assertBlockedWriteToUnwindsOnClose(isStreaming = false)
    }

    @Test(timeout = 10_000L)
    fun `streaming upload blocked in writeTo unwinds promptly on provider close with writeTimeout 0`() {
        assertBlockedWriteToUnwindsOnClose(isStreaming = true)
    }

    private fun assertBlockedWriteToUnwindsOnClose(isStreaming: Boolean) {
        val writerExecutor = daemonSingleThreadExecutor("test-writer")
        val uploadExecutor = daemonSingleThreadExecutor("test-upload")
        try {
            val writerEntered = CountDownLatch(1)
            val blockLatch = CountDownLatch(1)
            val contentLength = if (isStreaming) -1L else 100L
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = contentLength
                override fun isOneShot() = isStreaming
                override fun writeTo(sink: BufferedSink) {
                    writerEntered.countDown()
                    blockLatch.await()
                }
            }
            val provider = UploadDataProviders.create(
                body,
                writerExecutor,
                writeTimeoutMillis = 0L,
                contentLength = contentLength,
            )
            val sink = FakeUploadDataSink()
            val readFuture = uploadExecutor.submit {
                provider.read(sink, ByteBuffer.allocate(64))
            }

            assertTrue("writer must enter writeTo", writerEntered.await(5, TimeUnit.SECONDS))

            val closeStart = System.nanoTime()
            provider.close()
            val closeDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStart)
            assertTrue("close() must return promptly without hanging: ${closeDurationMs}ms", closeDurationMs < 1_000L)

            val writerMarker = CountDownLatch(1)
            writerExecutor.execute { writerMarker.countDown() }
            assertTrue(
                "writer executor must be freed and reusable after provider close",
                writerMarker.await(2, TimeUnit.SECONDS),
            )

            try {
                readFuture.get(2, TimeUnit.SECONDS)
                assertTrue("completed streaming read must report read error", sink.readErrors.isNotEmpty())
            } catch (e: ExecutionException) {
                assertTrue("ExecutionException cause must be IOException, was ${e.cause}", e.cause is IOException)
            }
        } finally {
            writerExecutor.shutdownNow()
            uploadExecutor.shutdownNow()
        }
    }

    // --- Queued task / publication race regressions ---

    @Test(timeout = 10_000L)
    fun `buffered upload closed during submit before execute never starts writeTo`() {
        assertQueuedTaskRaceNeverStartsWriteTo(isStreaming = false)
    }

    @Test(timeout = 10_000L)
    fun `streaming upload closed during submit before execute never starts writeTo`() {
        assertQueuedTaskRaceNeverStartsWriteTo(isStreaming = true)
    }

    private fun assertQueuedTaskRaceNeverStartsWriteTo(isStreaming: Boolean) {
        val delegate = daemonSingleThreadExecutor("test-writer")
        val uploadExecutor = daemonSingleThreadExecutor("test-upload")
        val releaseExecute = CountDownLatch(1)
        try {
            val executeEntered = CountDownLatch(1)
            val writeCount = AtomicInteger(0)
            val controlledExecutor = ControlledExecutorService(
                delegate,
                onBeforeExecute = {
                    executeEntered.countDown()
                    if (!releaseExecute.await(5, TimeUnit.SECONDS)) {
                        throw AssertionError("execute release latch timed out")
                    }
                },
            )
            val contentLength = if (isStreaming) -1L else 100L
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = contentLength
                override fun isOneShot() = isStreaming
                override fun writeTo(sink: BufferedSink) {
                    writeCount.incrementAndGet()
                    sink.writeUtf8("data")
                }
            }
            val provider = UploadDataProviders.create(
                body,
                controlledExecutor,
                writeTimeoutMillis = 0L,
                contentLength = contentLength,
            )
            val sink = FakeUploadDataSink()
            val readFuture = uploadExecutor.submit {
                provider.read(sink, ByteBuffer.allocate(64))
            }

            assertTrue("executor must enter execute()", executeEntered.await(5, TimeUnit.SECONDS))
            provider.close()
            releaseExecute.countDown()

            try {
                readFuture.get(2, TimeUnit.SECONDS)
                if (isStreaming) {
                    assertTrue("streaming read should report error to sink", sink.readErrors.isNotEmpty())
                }
            } catch (e: ExecutionException) {
                assertTrue("ExecutionException cause must be IOException, was ${e.cause}", e.cause is IOException)
            }

            assertEquals("app writeTo must never start after close", 0, writeCount.get())

            val marker = CountDownLatch(1)
            delegate.execute { marker.countDown() }
            assertTrue(
                "writer executor must remain free",
                marker.await(2, TimeUnit.SECONDS),
            )
        } finally {
            releaseExecute.countDown()
            delegate.shutdownNow()
            uploadExecutor.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun `buffered upload closed while execute paused after dispatch interrupts writer before execute returns`() {
        assertActiveWriterPublicationRace(isStreaming = false)
    }

    @Test(timeout = 10_000L)
    fun `streaming upload closed while execute paused after dispatch interrupts writer before execute returns`() {
        assertActiveWriterPublicationRace(isStreaming = true)
    }

    private fun assertActiveWriterPublicationRace(isStreaming: Boolean) {
        val delegate = daemonSingleThreadExecutor("test-writer")
        val uploadExecutor = daemonSingleThreadExecutor("test-upload")
        val executePaused = CountDownLatch(1)
        val releaseExecute = CountDownLatch(1)
        try {
            val writerEntered = CountDownLatch(1)
            val writerExited = CountDownLatch(1)
            val controlledExecutor = ControlledExecutorService(
                delegate,
                onAfterExecute = {
                    executePaused.countDown()
                    if (!releaseExecute.await(5, TimeUnit.SECONDS)) {
                        throw AssertionError("releaseExecute latch timed out")
                    }
                },
            )
            val contentLength = if (isStreaming) -1L else 100L
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = contentLength
                override fun isOneShot() = isStreaming
                override fun writeTo(sink: BufferedSink) {
                    writerEntered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } finally {
                        writerExited.countDown()
                    }
                }
            }
            val provider = UploadDataProviders.create(
                body,
                controlledExecutor,
                writeTimeoutMillis = 0L,
                contentLength = contentLength,
            )
            val sink = FakeUploadDataSink()
            val readFuture = uploadExecutor.submit {
                provider.read(sink, ByteBuffer.allocate(64))
            }

            assertTrue("writer must enter writeTo", writerEntered.await(5, TimeUnit.SECONDS))
            assertTrue("execute must pause before returning", executePaused.await(5, TimeUnit.SECONDS))

            provider.close()

            assertTrue(
                "writer must exit promptly on close before execute returns",
                writerExited.await(5, TimeUnit.SECONDS),
            )

            releaseExecute.countDown()

            try {
                readFuture.get(2, TimeUnit.SECONDS)
                if (isStreaming) {
                    assertTrue("streaming read should report error to sink", sink.readErrors.isNotEmpty())
                }
            } catch (e: ExecutionException) {
                assertTrue("ExecutionException cause must be IOException, was ${e.cause}", e.cause is IOException)
            }

            val marker = CountDownLatch(1)
            delegate.execute { marker.countDown() }
            assertTrue(
                "writer executor must remain free",
                marker.await(2, TimeUnit.SECONDS),
            )
        } finally {
            releaseExecute.countDown()
            delegate.shutdownNow()
            uploadExecutor.shutdownNow()
        }
    }

    // --- Finite write timeout releases writer executor ---

    @Test(timeout = 10_000L)
    fun `finite write timeout releases writer executor on buffered upload without latch countdown`() {
        val writerExecutor = daemonSingleThreadExecutor("test-writer")
        try {
            val blockLatch = CountDownLatch(1)
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = 100L
                override fun isOneShot() = false
                override fun writeTo(sink: BufferedSink) {
                    blockLatch.await()
                }
            }
            val provider = UploadDataProviders.create(
                body,
                writerExecutor,
                writeTimeoutMillis = 100L,
                contentLength = 100L,
            )
            val sink = FakeUploadDataSink()
            val thrown = assertThrows(IOException::class.java) {
                provider.read(sink, ByteBuffer.allocate(64))
            }
            assertTrue(thrown.cause is java.util.concurrent.TimeoutException)

            val marker = CountDownLatch(1)
            writerExecutor.execute { marker.countDown() }
            assertTrue(
                "writer executor must be freed on finite timeout without latch countdown",
                marker.await(2, TimeUnit.SECONDS),
            )
        } finally {
            writerExecutor.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun `finite write timeout releases writer executor on streaming upload without latch countdown`() {
        val writerExecutor = daemonSingleThreadExecutor("test-writer")
        try {
            val blockLatch = CountDownLatch(1)
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun writeTo(sink: BufferedSink) {
                    blockLatch.await()
                }
            }
            val provider = UploadDataProviders.create(
                body,
                writerExecutor,
                writeTimeoutMillis = 100L,
                contentLength = -1L,
            )
            val sink = FakeUploadDataSink()
            provider.read(sink, ByteBuffer.allocate(64))
            assertEquals(1, sink.readErrors.size)
            assertTrue(sink.readErrors[0].cause is java.util.concurrent.TimeoutException)

            val marker = CountDownLatch(1)
            writerExecutor.execute { marker.countDown() }
            assertTrue(
                "writer executor must be freed on finite timeout without latch countdown",
                marker.await(2, TimeUnit.SECONDS),
            )
        } finally {
            writerExecutor.shutdownNow()
        }
    }

    // --- Converter or bridge integration reproduction ---

    @Test(timeout = 10_000L)
    fun `cancellation stops in flight upload writer when writeTimeout is zero`() {
        assertTerminalEventStopsWriter { converted, urlRequest ->
            urlRequest.cancel()
            converted.callback.onCanceled(urlRequest, null)
        }
    }

    @Test(timeout = 10_000L)
    fun `terminal failure stops in flight upload writer when writeTimeout is zero`() {
        assertTerminalEventStopsWriter { converted, urlRequest ->
            converted.callback.onFailed(urlRequest, null, FakeCronetException("network failure"))
        }
    }

    @Test(timeout = 10_000L)
    fun `redirect stops in flight upload writer when writeTimeout is zero`() {
        assertTerminalEventStopsWriter { converted, urlRequest ->
            converted.callback.onRedirectReceived(
                urlRequest,
                FakeUrlResponseInfo(statusCode = 302),
                "https://example.com/redirect",
            )
        }
    }

    @Test(timeout = 10_000L)
    fun `success stops in flight upload writer when writeTimeout is zero`() {
        assertTerminalEventStopsWriter { converted, urlRequest ->
            val info = FakeUrlResponseInfo(statusCode = 200)
            converted.callback.onResponseStarted(urlRequest, info)
            converted.callback.onSucceeded(urlRequest, info)
        }
    }

    private fun assertTerminalEventStopsWriter(
        triggerTerminalEvent: (RequestConverter.ConvertedRequest, FakeUrlRequest) -> Unit,
    ) {
        val writerExecutor = daemonSingleThreadExecutor("test-writer")
        val uploadExecutor = daemonSingleThreadExecutor("test-upload")
        try {
            val writerEntered = CountDownLatch(1)
            val blockLatch = CountDownLatch(1)
            val body = object : RequestBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun contentLength() = -1L
                override fun writeTo(sink: BufferedSink) {
                    writerEntered.countDown()
                    blockLatch.await()
                }
            }
            val request = Request.Builder().url("https://example.com/upload").post(body).build()
            val engine = FakeCronetEngine()
            val converter = RequestConverter(
                cronetEngine = engine,
                uploadDataProviderExecutor = uploadExecutor,
                bodyReaderExecutor = writerExecutor,
                responseConverter = ResponseConverter(),
            )
            val converted = converter.convert(
                request,
                readTimeoutMillis = 5_000L,
                writeTimeoutMillis = 0L,
            )
            val builder = engine.builders.single()
            val provider = builder.uploadDataProvider!!
            val urlRequest = builder.builtRequest!!
            val sink = FakeUploadDataSink()

            val readFuture = uploadExecutor.submit {
                provider.read(sink, ByteBuffer.allocate(64))
            }

            assertTrue("writer must enter writeTo", writerEntered.await(5, TimeUnit.SECONDS))

            // Fake Cronet never calls provider.close()
            triggerTerminalEvent(converted, urlRequest)

            val marker = CountDownLatch(1)
            writerExecutor.execute { marker.countDown() }
            assertTrue(
                "in-flight upload writer must be stopped and executor freed after terminal event",
                marker.await(2, TimeUnit.SECONDS),
            )

            try {
                readFuture.get(2, TimeUnit.SECONDS)
                assertTrue("completed streaming read must report read error", sink.readErrors.isNotEmpty())
            } catch (e: ExecutionException) {
                assertTrue("ExecutionException cause must be IOException, was ${e.cause}", e.cause is IOException)
            }
        } finally {
            writerExecutor.shutdownNow()
            uploadExecutor.shutdownNow()
        }
    }

    // --- Interrupted pending provider read cleanup ---

    @Test(timeout = 10_000L)
    fun `interrupted pending provider read with writeTimeout 0 exits worker and leaves executors reusable`() {
        val writerExecutor = daemonSingleThreadExecutor("test-writer")
        val uploadExecutor = daemonSingleThreadExecutor("test-upload")
        val blockLatch = CountDownLatch(1)
        try {
            val writerEntered = CountDownLatch(1)
            val writerExited = CountDownLatch(1)
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun writeTo(sink: BufferedSink) {
                    writerEntered.countDown()
                    try {
                        blockLatch.await()
                    } finally {
                        writerExited.countDown()
                    }
                }
            }
            val provider = UploadDataProviders.create(
                body,
                writerExecutor,
                writeTimeoutMillis = 0L,
                contentLength = -1L,
            )
            val sink = FakeUploadDataSink()
            val uploadThreadRef = AtomicReference<Thread>()
            val readFuture = uploadExecutor.submit {
                uploadThreadRef.set(Thread.currentThread())
                provider.read(sink, ByteBuffer.allocate(64))
            }

            assertTrue("writer must enter writeTo", writerEntered.await(5, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (uploadThreadRef.get() == null || uploadThreadRef.get()!!.state == Thread.State.RUNNABLE) {
                if (System.nanoTime() > deadline) {
                    throw AssertionError("upload thread failed to reach waiting state")
                }
                Thread.yield()
            }

            uploadThreadRef.get()!!.interrupt()

            try {
                readFuture.get(5, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                // Expected when future wraps error
            }
            assertTrue("read must report error to sink", sink.readErrors.isNotEmpty())
            val error = sink.readErrors[0]
            assertTrue(
                "error cause must be InterruptedException, was: ${error.cause}",
                error.cause is InterruptedException,
            )

            assertTrue(
                "actual writer worker must exit promptly after reader interrupted",
                writerExited.await(5, TimeUnit.SECONDS),
            )

            val writerMarker = CountDownLatch(1)
            writerExecutor.execute { writerMarker.countDown() }
            assertTrue(
                "single-thread writer executor must be reusable",
                writerMarker.await(2, TimeUnit.SECONDS),
            )

            val uploadMarker = CountDownLatch(1)
            uploadExecutor.execute { uploadMarker.countDown() }
            assertTrue(
                "single-thread upload executor must be reusable",
                uploadMarker.await(2, TimeUnit.SECONDS),
            )
        } finally {
            blockLatch.countDown()
            writerExecutor.shutdownNow()
            uploadExecutor.shutdownNow()
        }
    }

    // --- Broker owned-read cancellation race regressions ---

    private fun getBrokerTransferLock(broker: Any): Any {
        val field = broker.javaClass.getDeclaredField("transferLock")
        field.isAccessible = true
        return field.get(broker)!!
    }

    @Test(timeout = 10_000L)
    fun `broker dequeued read in write completes exceptionally promptly when aborted before lock`() {
        val broker = UploadDataProviders.UploadBodyDataBroker(writeTimeoutMillis = 0L)
        val transferLock = getBrokerTransferLock(broker)
        val buffer = ByteBuffer.allocate(64)
        val readFuture = broker.enqueueBodyRead(buffer)

        val writeThrown = AtomicReference<Throwable?>()
        val writeDone = CountDownLatch(1)

        val writerThread = synchronized(transferLock) {
            val thread = Thread({
                try {
                    val source = Buffer().apply { writeUtf8("test data that should not be copied") }
                    broker.write(source, source.size)
                } catch (t: Throwable) {
                    writeThrown.set(t)
                } finally {
                    writeDone.countDown()
                }
            }, "test-broker-writer").apply {
                isDaemon = true
                start()
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.state != Thread.State.BLOCKED) {
                if (System.nanoTime() > deadline) {
                    throw AssertionError("Writer thread failed to block on transferLock (state=${thread.state})")
                }
                Thread.yield()
            }

            broker.abort(IOException("aborted while write waiting on transferLock"))
            thread
        }

        assertTrue("writer must finish promptly", writeDone.await(5, TimeUnit.SECONDS))
        val thrown = writeThrown.get()
        assertTrue("write must throw IOException, was: $thrown", thrown is IOException)

        val ex = assertThrows(ExecutionException::class.java) {
            readFuture.get(1, TimeUnit.SECONDS)
        }
        assertTrue("future exception cause must be IOException, was: ${ex.cause}", ex.cause is IOException)
        assertEquals("buffer must not have been written to after abort", 0, buffer.position())
    }

    @Test(timeout = 10_000L)
    fun `broker dequeued read in close completes exceptionally promptly when aborted before lock`() {
        val broker = UploadDataProviders.UploadBodyDataBroker(writeTimeoutMillis = 0L)
        val transferLock = getBrokerTransferLock(broker)
        val buffer = ByteBuffer.allocate(64)
        val readFuture = broker.enqueueBodyRead(buffer)

        val closeDone = CountDownLatch(1)

        val closerThread = synchronized(transferLock) {
            val thread = Thread({
                try {
                    broker.close()
                } finally {
                    closeDone.countDown()
                }
            }, "test-broker-closer").apply {
                isDaemon = true
                start()
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.state != Thread.State.BLOCKED) {
                if (System.nanoTime() > deadline) {
                    throw AssertionError("Closer thread failed to block on transferLock (state=${thread.state})")
                }
                Thread.yield()
            }

            broker.abort(IOException("aborted while close waiting on transferLock"))
            thread
        }

        assertTrue("closer must finish promptly", closeDone.await(5, TimeUnit.SECONDS))

        val ex = assertThrows(ExecutionException::class.java) {
            readFuture.get(1, TimeUnit.SECONDS)
        }
        assertTrue("future exception cause must be IOException, was: ${ex.cause}", ex.cause is IOException)
        assertEquals("buffer must not have been modified", 0, buffer.position())
    }
}
