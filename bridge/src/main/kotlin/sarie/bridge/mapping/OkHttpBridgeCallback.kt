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
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

// Ported from google/cronet-transport-for-okhttp@eda650fbc9b5279b6219160c2a0b210b28303fd7
package sarie.bridge.mapping

import android.util.Log
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import sarie.bridge.CallRegistry
import sarie.bridge.RequestFinishedExecutor
import sarie.bridge.SarieBridge
import okhttp3.Call
import okhttp3.internal.connection.RealCall
import okio.Buffer
import okio.Source
import okio.Timeout
import org.chromium.net.CronetException
import org.chromium.net.RequestFinishedInfo
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
 * The body source surfaces bytes as soon as Cronet hands them over, never waiting to fill its
 * buffer: a partially filled chunk (what a live SSE event stream produces) is returned to the
 * caller immediately. It also owns the call's terminal teardown - [CronetBodySource.close]
 * unregisters the call from [CallRegistry] and returns the pooled buffer - so nothing has to
 * wrap the response body.
 *
 * Deviation from upstream: redirects are NEVER followed inside Cronet (the bridge never calls
 * [UrlRequest.followRedirect]); a redirect response surfaces to OkHttp's follow-up logic with an
 * empty body, mirroring [RedirectStrategy.withoutRedirects] upstream.
 */
internal class OkHttpBridgeCallback(
    readTimeoutMillis: Long,
    private val onResponseHeadersStart: (() -> Unit)? = null,
    private val call: Call? = null,
    private val attempt: Int = 1,

    /**
     * OkHttp's overall call deadline in milliseconds, as wired from `RealCall.timeout()`. [0L]
     * (the default, matching OkHttp's `Timeout.NONE`) means no overall deadline: only the read
     * timeout bounds a blocking body read. A non-zero value bounds every wait inside the body
     * source by the *remaining* budget and expires with OkHttp's own call-timeout shape -
     * [java.io.InterruptedIOException] with message `timeout`.
     *
     * Approximation, deliberately: the deadline is anchored when this callback is built, not when
     * OkHttp started the call, because okio's `Timeout` exposes no remaining-budget accessor at
     * this module's compile floor. Time before the request is converted is therefore not charged
     * to the body, and a follow-up hop restarts the budget instead of inheriting the call's
     * remaining one. Everything from conversion onwards - including the response-header phase -
     * counts against the body, so the body is never cut short of the budget it was handed.
     */
    callTimeoutMillis: Long = 0L,
) : UrlRequest.Callback() {

    internal companion object {
        private const val CANCELED_MESSAGE = "Canceled"
        private const val READ_TIMEOUT_MESSAGE = "Timed out reading the response body"

        /** OkHttp's AsyncTimeout throws exactly this for an expired call timeout. */
        private const val CALL_TIMEOUT_MESSAGE = "timeout"

        /**
         * How long a reader waits for Cronet's finished info after the terminal callback, and how
         * long the late path waits before delivering. Cronet reports right after the terminal
         * callback on the same network thread, so the real gap is far below this.
         */
        const val FINISHED_INFO_WAIT_MS = 100L
        private val finishedThrewLogged = AtomicBoolean(false)
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

    /**
     * The overall call deadline as a [System.nanoTime] instant, or [Long.MAX_VALUE] when the call
     * carries no deadline (OkHttp's `Timeout.NONE`, the default, and the only case in which
     * nothing here is enforced). It is anchored at construction: OkHttp's own call clock starts
     * earlier, so the body is bounded from the moment the request was converted rather than from
     * the call's start. The budget is therefore never shorter than the one the caller asked for.
     */
    private val callDeadlineNanos: Long = deadlineFromNow(callTimeoutMillis)

    /** Single-terminal gate for [CallRegistry.unregister], per attempt. */
    private val callUnregistered = AtomicBoolean(false)

    /** Milliseconds left before [callDeadlineNanos], rounded up; unbounded when there is none. */
    private fun remainingCallMillis(): Long {
        val deadline = callDeadlineNanos
        if (deadline == Long.MAX_VALUE) return Long.MAX_VALUE
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0L) return 0L
        val millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
        // Round up: a sub-millisecond remainder still gets one full millisecond of wait.
        return millis + 1
    }

    /**
     * This attempt's [CallRegistry] registration, attached by [attach] right after the bridge
     * registers and before `start()`. Every teardown path here runs strictly later, so by the time
     * one fires the registration is always present - which is what lets the teardown be scoped to
     * this attempt instead of to the call.
     */
    @Volatile
    private var registration: CallRegistry.Registration? = null

    internal fun attach(registration: CallRegistry.Registration) {
        this.registration = registration
    }

    /**
     * Tears the [CallRegistry] entry down for this attempt, at most once. The body source owns
     * this: closing it (or reaching a terminal callback) is what tells the bridge the call is over,
     * so no wrapper around the response body is needed.
     *
     * Scoped to [registration] rather than the call: a late close from an earlier attempt of the
     * same call must not remove a newer attempt's entry.
     */
    private fun unregisterCallOnce() {
        if (call == null) return
        if (!callUnregistered.compareAndSet(false, true)) return
        registration?.unregister()
    }

    private fun deliverResponseHeaders(urlResponseInfo: UrlResponseInfo) {
        responseHeadersDelivered = true
        val logger = SarieBridge.logger
        // The message string calls getUrl() and runs on a Cronet thread: build it only when the
        // installed logger says DEBUG is wanted.
        if (logger != null && logger.isLoggable(Log.DEBUG)) {
            logger.log(
                Log.DEBUG,
                "${urlResponseInfo.url} -> ${urlResponseInfo.negotiatedProtocol}",
                null,
            )
        }
        // Direct executor: this runs on a Cronet thread. The listener call itself does no I/O.
        onResponseHeadersStart?.invoke()
    }

    private fun deadlineFromNow(millis: Long): Long {
        if (millis <= 0L) return Long.MAX_VALUE
        val span = TimeUnit.MILLISECONDS.toNanos(millis)
        val now = System.nanoTime()
        if (span <= 0L || now > Long.MAX_VALUE - span) return Long.MAX_VALUE
        return now + span
    }

    /** The response headers. */
    val headersFuture: CompletableFuture<UrlResponseInfo> = CompletableFuture()

    /** Set once Cronet has delivered response headers (a normal response or a redirect). */
    @Volatile
    var responseHeadersDelivered: Boolean = false
        private set

    /** Completed by the RequestFinishedInfo listener; see [onFinishedInfoReceived]. */
    private val finishedInfoFuture: CompletableFuture<RequestFinishedInfo> = CompletableFuture()

    /**
     * Set by RequestConverter when the provider accepted the finished listener. When false no
     * info will ever arrive, so nothing waits for it.
     */
    val finishedListenerActive: AtomicBoolean = AtomicBoolean(false)

    /** Single-delivery gate for [sarie.bridge.SarieListener.onFinished], per attempt. */
    private val delivered = AtomicBoolean(false)

    /** Whether this attempt ended in a redirect (never followed inside Cronet). */
    @Volatile
    var isRedirect: Boolean = false
        private set

    /**
     * The OkHttp [Source] for this attempt's response body.
     *
     * A normal response streams ([CronetBodySource]); a redirect, which is never followed inside
     * Cronet, gets an empty [Buffer] because Cronet's API cannot retrieve a 3xx body. A streamed
     * body owns the attempt's terminal teardown - closing it unregisters the call - so closing a
     * response body is never optional. A redirect hop does not: Cronet has already canceled the
     * engine request, and `CronetBridge` unregisters the call as soon as the 3xx headers arrive.
     *
     * Retrieving data from a streaming Source instance might block as the body streams in.
     */
    val bodySourceFuture: CompletableFuture<Source> = CompletableFuture()

    /** Signal whether the request is finished and the response has been fully read. */
    private val finished = AtomicBoolean(false)

    /** Signal whether the request was canceled. */
    private val canceled = AtomicBoolean(false)

    /**
     * Set by [onSucceeded], [onFailed] and [onCanceled] - the three callbacks after which Cronet
     * will never write into the buffer we handed it again.
     *
     * A buffer may only go back to [BodyBufferPool] once this is true. `UrlRequest.cancel()` is
     * asynchronous: after we cancel (a read timeout, a call timeout, or an early close) a pending
     * `read(buffer)` can still complete on Cronet's network thread. Releasing the buffer before
     * the terminal callback lands would let another response rent it and take a write that belongs
     * to this one - silent cross-response corruption. With no terminal callback the buffer is
     * simply dropped and its Cleaner reclaims it, exactly as when there was no pool.
     */
    @Volatile
    private var terminalCallbackSeen = false

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
        isRedirect = true
        receivedHeadersAtMillis = System.currentTimeMillis()
        deliverResponseHeaders(urlResponseInfo)
        check(headersFuture.complete(urlResponseInfo))
        check(bodySourceFuture.complete(Buffer()))
        urlRequest.cancel()
    }

    override fun onResponseStarted(urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo) {
        request = urlRequest
        receivedHeadersAtMillis = System.currentTimeMillis()
        deliverResponseHeaders(urlResponseInfo)
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
        terminalCallbackSeen = true
        callbackResults.add(CallbackResult(CallbackStep.ON_SUCCESS, null))
    }

    // urlResponseInfo is null when the failure precedes any response (DNS, connect, TLS, pins).
    // A non-null Kotlin parameter would throw inside Cronet's callback, the futures would never
    // complete, and the call would hang until the read timeout.
    override fun onFailed(
        urlRequest: UrlRequest,
        urlResponseInfo: UrlResponseInfo?,
        e: CronetException,
    ) {
        terminalCallbackSeen = true
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
        terminalCallbackSeen = true
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

    /**
     * Runs on the Cronet network thread (direct executor): CPU-only. Completes the future a
     * waiting reader blocks on, and schedules the late delivery for when no reader claims it:
     * immediately for a redirect (nobody reads its body), otherwise after the reader's window.
     */
    fun onFinishedInfoReceived(info: RequestFinishedInfo) {
        finishedInfoFuture.complete(info)
        val delayMs = if (isRedirect) 0L else FINISHED_INFO_WAIT_MS
        RequestFinishedExecutor.executor.schedule(
            { deliver(info, deliveredLate = true) },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Reader/caller thread: waits at most [FINISHED_INFO_WAIT_MS] for the finished info and
     * delivers it on this thread. No wait when the provider rejected the finished listener.
     */
    fun awaitAndDeliver() {
        if (!finishedListenerActive.get() || delivered.get()) return
        val info = try {
            finishedInfoFuture.get(FINISHED_INFO_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return
        } catch (_: ExecutionException) {
            return
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        deliver(info, deliveredLate = false)
    }

    private fun deliver(info: RequestFinishedInfo, deliveredLate: Boolean) {
        if (!delivered.compareAndSet(false, true)) return
        val call = call ?: return
        val listener = SarieBridge.listener ?: return
        val timings = SarieTimingsMapper.map(info, attempt, isRedirect, deliveredLate)
        // Host code: an escaped throw would reach the reader, or the executor thread's
        // uncaught-exception handler and kill the process.
        try {
            listener.onFinished(call, timings)
        } catch (t: Throwable) {
            if (finishedThrewLogged.compareAndSet(false, true)) {
                SarieBridge.logger?.log(Log.WARN, "SarieListener.onFinished threw", t)
            }
        }
    }

    /** A bridge between Cronet's asynchronous callbacks and OkHttp's blocking stream-like reads. */
    private inner class CronetBodySource : Source {

        /**
         * Used for reading data from the network and for writing it downstream. Rented from the
         * bounded [BodyBufferPool]: a direct buffer is only reclaimed by its Cleaner, so one per
         * response is real off-heap GC pressure.
         */
        private val buffer: ByteBuffer = BodyBufferPool.rent()

        /** Single-return gate: the buffer goes back to the pool at most once. */
        private val bufferReturned = AtomicBoolean(false)

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
                if (!fillBuffer()) {
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
         * Hands the direct buffer back to [BodyBufferPool], but only once Cronet has said this
         * request is over (see [terminalCallbackSeen]).
         *
         * Otherwise the buffer is dropped and its Cleaner reclaims it. That happens whenever we
         * cancel first: a read timeout, a call timeout, or a close from another thread while the
         * reader is blocked. Cronet's cancel is asynchronous, so a pending `read(buffer)` may
         * still land afterwards; releasing early would hand that write to whoever rents the
         * buffer next.
         */
        private fun returnBuffer() {
            if (!bufferReturned.compareAndSet(false, true)) {
                return
            }
            if (terminalCallbackSeen) {
                BodyBufferPool.release(buffer)
            }
        }

        /**
         * Cancels the request and fails the read the way OkHttp's AsyncTimeout does for an
         * expired call timeout. Only called once the call deadline - not the read timeout - is
         * what ran out, so the two remain distinguishable to the caller.
         */
        private fun failCallTimeout(currentRequest: UrlRequest): Nothing {
            currentRequest.cancel()
            throw InterruptedIOException(CALL_TIMEOUT_MESSAGE)
        }

        /**
         * Reads data from the network into the buffer until it holds at least one byte, the
         * request ends, or a bound expires. Always requests up to the entire buffer capacity -
         * larger reads amortize Cronet's per-read overhead (upstream issue #47).
         *
         * Each wait is bounded by `min(readTimeoutMillis, remaining call budget)`, so the read
         * timeout and the overall call timeout compose instead of replacing one another. A
         * zero-byte completion is not a failure: Cronet may complete a read with nothing
         * buffered, so the read is simply re-issued - each attempt is bounded afresh, so even a
         * pathological stream of empty completions cannot hang the reader forever.
         *
         * @return whether any bytes were read; false for onCanceled/onFailed/onSucceeded.
         */
        private fun fillBuffer(): Boolean {
            check(buffer.position() == 0) { "Buffer position is not 0" }
            check(buffer.limit() == buffer.capacity()) { "Buffer limit is not capacity" }

            val currentRequest = requireNotNull(request) { "read before onResponseStarted" }

            while (true) {
                // The deadline spans the whole call, not one read: an already-expired budget
                // fails before another network read is issued.
                if (remainingCallMillis() == 0L) failCallTimeout(currentRequest)

                currentRequest.read(buffer)

                val callBudgetMillis = remainingCallMillis()
                val waitMillis = minOf(readTimeoutMillis, callBudgetMillis)
                val result = try {
                    callbackResults.poll(waitMillis, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                }

                if (result == null) {
                    currentRequest.cancel()
                    if (callBudgetMillis == 0L || remainingCallMillis() == 0L) {
                        // The overall call deadline is what ran out, not the read timeout.
                        throw InterruptedIOException(CALL_TIMEOUT_MESSAGE)
                    }
                    // Either poll was interrupted or the read timeout ran out.
                    throw IOException(READ_TIMEOUT_MESSAGE)
                }

                when (result.callbackStep) {
                    CallbackStep.ON_FAILED -> {
                        finished.set(true)
                        awaitAndDeliver()
                        unregisterCallOnce()
                        returnBuffer()
                        throw IOException(result.exception)
                    }
                    CallbackStep.ON_SUCCESS -> {
                        finished.set(true)
                        awaitAndDeliver()
                        unregisterCallOnce()
                        returnBuffer()
                        return false
                    }
                    CallbackStep.ON_CANCELED -> {
                        finished.set(true)
                        awaitAndDeliver()
                        unregisterCallOnce()
                        returnBuffer()
                        throw canceledError()
                    }
                    CallbackStep.ON_READ_COMPLETED -> {
                        // Cronet leaves the bytes between position 0 and position; flip to
                        // read mode.
                        buffer.flip()
                        if (buffer.hasRemaining()) return true
                        // Nothing was delivered: re-issue instead of failing the read.
                        buffer.clear()
                    }
                }
            }
        }

        /**
         * Copies data from the ByteBuffer to the okio Buffer, up to byteCount bytes. The copy is
         * clamped to what the buffer actually holds, so a caller asking for more than an Int can
         * hold (or more than the buffer capacity) can never truncate the limit or corrupt the
         * stream.
         */
        private fun copyByteBufferToOkioBuffer(from: ByteBuffer, to: Buffer, byteCount: Long): Int {
            if (from.remaining() <= byteCount) {
                return to.write(from)
            }
            // Clamp through the limit: Buffer#write(ByteBuffer) has no byteCount overload. The
            // count is bounded by remaining(), so it always fits an Int.
            val copyCount = minOf(byteCount, from.remaining().toLong()).toInt()
            val originalLimit = from.limit()
            try {
                from.limit(from.position() + copyCount)
                return to.write(from)
            } finally {
                from.limit(originalLimit)
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            if (closed) {
                return
            }
            closed = true
            try {
                if (!finished.get()) {
                    request?.cancel()
                    awaitAndDeliver()
                }
            } finally {
                // The body source is the last owner of this call: tearing the registry entry down
                // here (and on the terminal callbacks above) is what makes an extra wrapping
                // ResponseBody unnecessary.
                unregisterCallOnce()
                returnBuffer()
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

/**
 * A small, bounded, thread-safe pool of the direct [ByteBuffer]s that carry Cronet response
 * bodies.
 *
 * A direct buffer lives off the Java heap and is only reclaimed by its Cleaner, so allocating one
 * per response makes every response pay a native allocation plus a Cleaner registration. Sequential
 * responses overwhelmingly reuse the same few buffers, so a capped pool removes that cost while
 * keeping the worst case (a burst of concurrent responses) bounded: [MAX_IDLE] buffers are ever
 * retained, and any excess is simply dropped for the GC to reclaim exactly as before.
 *
 * A rented buffer is owned by exactly one body source until it is released, so two live readers
 * can never share one.
 */
internal object BodyBufferPool {

    /** The capacity of every pooled buffer, in bytes. */
    const val BUFFER_CAPACITY: Int = 32 * 1024

    /** Upper bound on retained (idle) buffers; the pool never grows past this. */
    const val MAX_IDLE: Int = 8

    private val idle = ArrayBlockingQueue<ByteBuffer>(MAX_IDLE)

    /** Takes an empty buffer with position 0 and limit [BUFFER_CAPACITY], reusing an idle one. */
    fun rent(): ByteBuffer {
        val pooled = idle.poll() ?: ByteBuffer.allocateDirect(BUFFER_CAPACITY)
        pooled.clear()
        return pooled
    }

    /**
     * Returns a buffer rented by [rent]. Excess past [MAX_IDLE] is dropped, not retained.
     *
     * Only ever called once the request is over - see
     * [OkHttpBridgeCallback.terminalCallbackSeen] - because a buffer with a pending Cronet read
     * behind it must not be handed to the next response.
     */
    fun release(buffer: ByteBuffer) {
        buffer.clear()
        idle.offer(buffer)
    }
}
