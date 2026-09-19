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
package dev.okhttpcronet.bridge.mapping

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okio.Buffer
import okio.Source
import okio.Timeout
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo

/**
 * An implementation of Cronet's callback. This is the heart of the bridge and deals with most of
 * the async-sync paradigm translation.
 *
 * Translating the [UrlResponseInfo] is straightforward since the entire object is available
 * immediately. Translating the body is trickier: the [Source] returned by [bodySourceFuture]
 * invokes Cronet's read and waits for the result with a bounded callback-result queue. The
 * implementation assumes at most one read() in flight, which is safe to assume.
 *
 * Deviation from upstream: redirects are NEVER followed inside Cronet (the bridge never calls
 * [UrlRequest.followRedirect]); a redirect response surfaces to OkHttp's follow-up logic with an
 * empty body, mirroring [RedirectStrategy.withoutRedirects] upstream.
 */
class OkHttpBridgeCallback(readTimeoutMillis: Long) : UrlRequest.Callback() {

    /** The byte buffer capacity for reading Cronet response bodies. */
    private companion object {
        const val CRONET_BYTE_BUFFER_CAPACITY = 32 * 1024
        const val CANCELED_MESSAGE = "Canceled"
    }

    /**
     * Wall-clock time the request was handed to Cronet: surfaced as OkHttp's
     * sentRequestAtMillis (we own this clock - no fabricated engine data).
     */
    val sentAtMillis: Long = System.currentTimeMillis()

    /**
     * Wall-clock time response headers arrived (onResponseStarted / onRedirectReceived):
     * surfaced as OkHttp's receivedResponseAtMillis.
     */
    @Volatile
    var receivedHeadersAtMillis: Long = 0L
        private set

    /** The read timeout as specified by OkHttp. */
    private val readTimeoutMillis: Long =
        // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all
        // practical use cases.
        if (readTimeoutMillis == 0L) Int.MAX_VALUE.toLong() else readTimeoutMillis

    /** The response headers. */
    val headersFuture: CompletableFuture<UrlResponseInfo> = CompletableFuture()

    /**
     * The streaming OkHttp [Source] for the request associated with this callback.
     *
     * Retrieving data from the Source instance might block further as the body streams in.
     */
    val bodySourceFuture: CompletableFuture<Source> = CompletableFuture()

    /** Signal whether the request is finished and the response has been fully read. */
    private val finished = AtomicBoolean(false)

    /** Signal whether the request was canceled. */
    private val canceled = AtomicBoolean(false)

    /**
     * Thread-safe way of passing data between the callback methods and the [Source].
     *
     * Capacity 2 - at most one slot for a read result and at most 1 slot for a cancellation
     * signal - guarantees that all inserts are non-blocking.
     */
    private val callbackResults: BlockingQueue<CallbackResult> = ArrayBlockingQueue(2)

    /** The request being processed. Set when the request is first seen by the callback. */
    @Volatile
    private var request: UrlRequest? = null

    override fun onRedirectReceived(
        urlRequest: UrlRequest,
        urlResponseInfo: UrlResponseInfo,
        nextUrl: String,
    ) {
        // We never follow redirects inside Cronet: pass the 3xx upstream to OkHttp's follow-up
        // logic. There is no way to retrieve a redirect response's body with Cronet's APIs, so
        // provide an empty one.
        receivedHeadersAtMillis = System.currentTimeMillis()
        check(headersFuture.complete(urlResponseInfo))
        check(bodySourceFuture.complete(Buffer()))
        urlRequest.cancel()
    }

    override fun onResponseStarted(urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo) {
        request = urlRequest
        receivedHeadersAtMillis = System.currentTimeMillis()
        check(headersFuture.complete(urlResponseInfo))
        check(bodySourceFuture.complete(CronetBodySource()))
    }

    override fun onReadCompleted(
        urlRequest: UrlRequest,
        urlResponseInfo: UrlResponseInfo,
        byteBuffer: ByteBuffer,
    ) {
        callbackResults.add(CallbackResult(CallbackStep.ON_READ_COMPLETED, null))
    }

    override fun onSucceeded(urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo) {
        callbackResults.add(CallbackResult(CallbackStep.ON_SUCCESS, null))
    }

    override fun onFailed(
        urlRequest: UrlRequest,
        urlResponseInfo: UrlResponseInfo,
        e: CronetException,
    ) {
        // If this was called before we start reading the body, the exception will propagate in
        // the futures providing headers and the body wrapper.
        if (headersFuture.completeExceptionally(e) && bodySourceFuture.completeExceptionally(e)) {
            return
        }

        // If this was called as a reaction to a read() call, the read result will propagate the
        // exception.
        callbackResults.add(CallbackResult(CallbackStep.ON_FAILED, e))
    }

    override fun onCanceled(urlRequest: UrlRequest, responseInfo: UrlResponseInfo?) {
        canceled.set(true)
        callbackResults.add(CallbackResult(CallbackStep.ON_CANCELED, null))

        // If there's nobody listening it's possible that the cancellation happened before we even
        // received anything from the server. In that case inform the thread that's awaiting the
        // server response as well. This is a no-op if the futures were already set.
        val e = IOException(CANCELED_MESSAGE)
        headersFuture.completeExceptionally(e)
        bodySourceFuture.completeExceptionally(e)
    }

    private fun canceledError(): IOException = IOException(CANCELED_MESSAGE)

    /** A bridge between Cronet's asynchronous callbacks and OkHttp's blocking stream-like reads. */
    private inner class CronetBodySource : Source {

        /** Used for reading data from the network and for writing it downstream. */
        private val buffer = ByteBuffer.allocateDirect(CRONET_BYTE_BUFFER_CAPACITY)

        /** Whether the close() method has been called. */
        @Volatile
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (canceled.get()) {
                throw canceledError()
            }

            require(byteCount >= 0) { "byteCount < 0: $byteCount" }
            check(!closed) { "closed" }

            if (finished.get()) {
                return -1
            }

            // A caller requesting 0 bytes doesn't need a network read.
            if (byteCount == 0L) {
                return 0
            }

            // When entering read() with buffer.position() == 0 the buffer is definitely empty
            // (we always drain it fully downstream before clearing), so a network read is needed.
            if (buffer.position() == 0) {
                if (fillBuffer()) {
                    // Cronet leaves the bytes between position 0 and position; flip to read mode.
                    buffer.flip()
                    check(buffer.hasRemaining()) { "Buffer should have remaining bytes after flip" }
                } else {
                    return -1 // End of stream
                }
            }

            val bytesWritten = copyByteBufferToOkioBuffer(buffer, sink, byteCount)
            check(bytesWritten > 0) { "Bytes written should be positive" }

            // Clear the buffer if it became empty again so it can be refilled on the next read.
            if (!buffer.hasRemaining()) {
                buffer.clear()
            }

            return bytesWritten.toLong()
        }

        /**
         * Reads data from the network to fill the buffer. Always requests up to the entire buffer
         * capacity - larger reads amortize Cronet's per-read overhead (upstream issue #47).
         *
         * @return whether any bytes were read; false for onCanceled/onFailed/onSucceeded.
         */
        private fun fillBuffer(): Boolean {
            check(buffer.position() == 0) { "Buffer position is not 0" }
            check(buffer.limit() == buffer.capacity()) { "Buffer limit is not capacity" }

            val currentRequest = requireNotNull(request) { "read before onResponseStarted" }
            currentRequest.read(buffer)

            val result = try {
                callbackResults.poll(readTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }

            if (result == null) {
                // Either poll was interrupted or it timed out.
                currentRequest.cancel()
                throw IOException("Timed out reading the response body")
            }

            return when (result.callbackStep) {
                CallbackStep.ON_FAILED -> {
                    finished.set(true)
                    throw IOException(result.exception)
                }
                CallbackStep.ON_SUCCESS -> {
                    finished.set(true)
                    false
                }
                CallbackStep.ON_CANCELED -> throw canceledError()
                CallbackStep.ON_READ_COMPLETED -> true
            }
        }

        /** Copies data from the ByteBuffer to the okio Buffer, up to byteCount bytes. */
        private fun copyByteBufferToOkioBuffer(from: ByteBuffer, to: Buffer, byteCount: Long): Int {
            return if (from.remaining() <= byteCount) {
                to.write(from)
            } else {
                val originalLimit = from.limit()
                try {
                    // Buffer#write(ByteBuffer) has no byteCount overload; clamp via the limit.
                    from.limit(from.position() + byteCount.toInt())
                    to.write(from)
                } finally {
                    from.limit(originalLimit)
                }
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            if (closed) {
                return
            }
            closed = true
            if (!finished.get()) {
                request?.cancel()
            }
        }
    }

    private class CallbackResult(
        val callbackStep: CallbackStep,
        val exception: CronetException?,
    )

    private enum class CallbackStep {
        ON_READ_COMPLETED,
        ON_SUCCESS,
        ON_FAILED,
        ON_CANCELED,
    }
}
