/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Ported from google/cronet-transport-for-okhttp@eda650fbc9b5279b6219160c2a0b210b28303fd7
package sarie.bridge.mapping

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Callable
import okhttp3.RequestBody
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink

/**
 * Creates [UploadDataProvider]s bridging OkHttp [RequestBody]s to Cronet.
 *
 * Decision rule: buffered (fully materialized in memory, rewindable) for replayable
 * fixed-length bodies; streaming for one-shot or unknown-length bodies. Both honor the OkHttp
 * writeTimeoutMillis while the body is being handed to Cronet.
 *
 * Deviation from upstream: no 1 MiB in-memory threshold - the task simplifies to one-shot or
 * unknown length => streaming, otherwise buffered.
 */
object UploadDataProviders {

    fun create(
        body: RequestBody,
        bodyReaderExecutor: ExecutorService,
        writeTimeoutMillis: Long,
    ): UploadDataProvider = if (body.isOneShot() || body.contentLength() == -1L) {
        StreamingUploadDataProvider(body, bodyReaderExecutor, writeTimeoutMillis)
    } else {
        BufferedUploadDataProvider(body, bodyReaderExecutor, writeTimeoutMillis)
    }

    /**
     * Materializes a fixed-length body fully in memory on first read, honoring the write
     * timeout while the body writes. Supports rewind (the materialized bytes are replayable).
     */
    private class BufferedUploadDataProvider(
        private val body: RequestBody,
        private val bodyReaderExecutor: ExecutorService,
        writeTimeoutMillis: Long,
    ) : UploadDataProvider() {

        private val writeTimeoutMillis: Long =
            if (writeTimeoutMillis == 0L) Long.MAX_VALUE else writeTimeoutMillis

        private var materialized: ByteArray? = null
        private var offset = 0

        override fun getLength(): Long = body.contentLength()

        @Synchronized
        override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
            val data = materialized ?: materialize().also { materialized = it }
            if (offset == data.size) {
                // Known-length bodies shouldn't be over-read by Cronet; this is a safeguard.
                throw IllegalStateException("The source has been exhausted but we expected more!")
            }
            val toCopy = minOf(buffer.remaining(), data.size - offset)
            buffer.put(data, offset, toCopy)
            offset += toCopy
            sink.onReadSucceeded(false)
        }

        @Synchronized
        override fun rewind(sink: UploadDataSink) {
            offset = 0
            sink.onRewindSucceeded()
        }

        private fun materialize(): ByteArray {
            val future = bodyReaderExecutor.submit(
                Callable {
                    val sink = Buffer()
                    body.writeTo(sink)
                    sink.readByteArray()
                },
            )
            return try {
                future.get(writeTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw IOException("Timed out writing the request body", e)
            } catch (e: ExecutionException) {
                throw IOException(e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(e)
            }
        }
    }

    /**
     * Streams a one-shot or unknown-length body through an [UploadBodyDataBroker] without
     * holding it in memory. Rewind is not supported.
     */
    private class StreamingUploadDataProvider(
        private val body: RequestBody,
        private val readExecutor: ExecutorService,
        writeTimeoutMillis: Long,
    ) : UploadDataProvider() {

        private val writeTimeoutMillis: Long =
            if (writeTimeoutMillis == 0L) Long.MAX_VALUE else writeTimeoutMillis

        private val broker = UploadBodyDataBroker(writeTimeoutMillis)
        private var readTask: Future<*>? = null
        private var totalBytesReadFromOkHttp = 0L

        override fun getLength(): Long = body.contentLength()

        override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
            ensureReadTaskStarted()

            try {
                if (getLength() == -1L) {
                    sink.onReadSucceeded(readFromOkHttp(buffer) == ReadResult.END_OF_BODY)
                } else {
                    readKnownBodyLength(sink, buffer)
                }
            } catch (e: TimeoutException) {
                readTask?.cancel(true)
                sink.onReadError(IOException(e))
            } catch (e: ExecutionException) {
                readTask?.cancel(true)
                sink.onReadError(IOException(e))
            }
        }

        private fun readKnownBodyLength(sink: UploadDataSink, buffer: ByteBuffer) {
            val readResult = readFromOkHttp(buffer)
            val length = getLength()

            if (totalBytesReadFromOkHttp > length) {
                throw bodyTooLong(length)
            }

            if (totalBytesReadFromOkHttp < length) {
                when (readResult) {
                    ReadResult.SUCCESS -> sink.onReadSucceeded(false)
                    ReadResult.END_OF_BODY ->
                        throw IOException("The source has been exhausted but we expected more data!")
                }
                return
            }

            // We're handling what's supposed to be the last chunk.
            handleLastBodyRead(sink, buffer)
        }

        /**
         * The last body read is special for fixed-length bodies - if Cronet receives exactly the
         * advertised number of bytes it won't ask for more, so verify the stream is exhausted.
         */
        private fun handleLastBodyRead(sink: UploadDataSink, buffer: ByteBuffer) {
            // Reuse the buffer for the end-of-body read (non-destructive when the body ends).
            val bufferPosition = buffer.position()
            buffer.position(0)

            val readResult = readFromOkHttp(buffer)
            if (readResult != ReadResult.END_OF_BODY) {
                throw bodyTooLong(getLength())
            }

            check(buffer.position() == 0) {
                "END_OF_BODY reads shouldn't write anything to the buffer"
            }

            buffer.position(bufferPosition)
            sink.onReadSucceeded(false)
        }

        private fun bodyTooLong(expectedLength: Long): IOException = IOException(
            "Expected $expectedLength bytes but got at least $totalBytesReadFromOkHttp",
        )

        /**
         * Starts the background task writing the OkHttp body to the broker. Idempotent; must be
         * called at least once or Cronet would wait for data that never arrives.
         */
        private fun ensureReadTaskStarted() {
            // No concurrent calls expected, so a simple flag suffices.
            if (readTask == null) {
                readTask = readExecutor.submit(
                    Callable {
                        try {
                            val sink = broker.buffer()
                            body.writeTo(sink)
                            // Close() flushes remaining bytes and signals end-of-body to Cronet;
                            // without it Cronet would time out waiting for more data.
                            sink.close()
                        } catch (t: Throwable) {
                            broker.setBackgroundReadError(t)
                            throw t
                        }
                        null
                    },
                )
            }
        }

        private fun readFromOkHttp(buffer: ByteBuffer): ReadResult {
            val positionBeforeRead = buffer.position()
            val readResult = try {
                broker.enqueueBodyRead(buffer).get(writeTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(e)
            }
            totalBytesReadFromOkHttp += buffer.position() - positionBeforeRead
            return readResult
        }

        override fun rewind(sink: UploadDataSink) {
            // One-shot bodies cannot replay.
            sink.onRewindError(UnsupportedOperationException("Rewind is not supported!"))
        }
    }

    /**
     * An okio Sink handing body bytes written by OkHttp over to Cronet's pending read calls.
     *
     * At most one read is in flight for a single request body provider, hence the capacity-1
     * handoff queue.
     */
    internal class UploadBodyDataBroker(
        writeTimeoutMillis: Long,
    ) : Sink {

        private val writeTimeoutMillis: Long =
            if (writeTimeoutMillis == 0L) Long.MAX_VALUE else writeTimeoutMillis

        private data class PendingRead(
            val buffer: ByteBuffer,
            val future: CompletableFuture<ReadResult>,
        )
        private val pendingRead = ArrayBlockingQueue<PendingRead>(1)
        private val isClosed = AtomicBoolean()
        private val backgroundReadError = AtomicReference<Throwable?>(null)

        /** Called by the upload provider when Cronet is ready for another body part. */
        fun enqueueBodyRead(readBuffer: ByteBuffer): Future<ReadResult> {
            var backgroundThrowable = backgroundReadError.get()
            if (backgroundThrowable != null) {
                return failedFuture(backgroundThrowable)
            }
            val future = CompletableFuture<ReadResult>()
            pendingRead.add(PendingRead(readBuffer, future))

            // Properly handle interleaving setBackgroundReadError / enqueueBodyRead calls.
            backgroundThrowable = backgroundReadError.get()
            if (backgroundThrowable != null) {
                future.completeExceptionally(backgroundThrowable)
            }
            return future
        }

        /** Signals that reading the OkHttp body failed on the background thread. */
        fun setBackgroundReadError(t: Throwable) {
            backgroundReadError.set(t)
            pendingRead.poll()?.let { it.future.completeExceptionally(t) }
        }

        override fun write(source: Buffer, byteCount: Long) {
            // Safeguard: close() is a no-op if the body length contract is honored.
            check(!isClosed.get()) { "closed" }

            var bytesRemaining = byteCount
            while (bytesRemaining != 0L) {
                val (readBuffer, future) = getPendingCronetRead()
                val originalBufferLimit = readBuffer.limit()
                readBuffer.limit(minOf(originalBufferLimit.toLong(), bytesRemaining).toInt())

                try {
                    val bytesRead = source.read(readBuffer)
                    if (bytesRead == -1) {
                        throw IOException("The source has been exhausted but we expected more!")
                    }
                    bytesRemaining -= bytesRead
                    readBuffer.limit(originalBufferLimit)
                    future.complete(ReadResult.SUCCESS)
                } catch (e: IOException) {
                    future.completeExceptionally(e)
                    throw e
                }
            }
        }

        /**
         * Bounded wait for Cronet's next read. Cronet may abandon an upload (cancel, redirect,
         * failure) without ever notifying the provider again, so an unbounded wait here would
         * wedge the shared upload executor forever. On timeout the exception propagates to the
         * pump task, which reports it as the background read error (unwinding any racing
         * provider-side future) and releases the executor thread.
         */
        private fun getPendingCronetRead(): PendingRead = try {
            pendingRead.poll(writeTimeoutMillis, TimeUnit.MILLISECONDS)
                ?: throw IOException(
                    "Timed out writing the request body",
                    TimeoutException("Cronet stopped reading the request body"),
                )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for a read to finish!")
        }

        /**
         * Signals we're done writing the body. Idempotent; crucial to call at least once or
         * Cronet would wait for more data forever (resulting in a timeout).
         */
        override fun close() {
            if (!isClosed.getAndSet(true)) {
                getPendingCronetRead().future.complete(ReadResult.END_OF_BODY)
            }
        }

        override fun flush() {
            // Not necessary - data is handed to Cronet straight away on write().
        }

        override fun timeout(): Timeout = Timeout.NONE

        private fun failedFuture(t: Throwable): Future<ReadResult> =
            CompletableFuture<ReadResult>().apply { completeExceptionally(t) }
    }

    enum class ReadResult {
        SUCCESS,
        END_OF_BODY,
    }
}
