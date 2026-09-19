@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(okhttp3.internal.OkHttpInternalApi::class)

package dev.okhttpcronet.bridge

import dev.okhttpcronet.bridge.mapping.OkHttpBridgeCallback
import dev.okhttpcronet.bridge.mapping.RequestConverter
import dev.okhttpcronet.bridge.mapping.ResponseConverter
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.RealInterceptorChain
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.chromium.net.UrlRequest

/**
 * Trampoline target for the rewritten ConnectInterceptor (descriptor
 * `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;`).
 *
 * Routing: re-evaluates [PolicyEngine] on the effective chain configuration (defensive — the
 * trampoline does not call [shouldHandle] first). Allow -> Cronet path; deny -> exact-stock
 * fallback (`initExchange` + `copy` + `proceed`, never the rewritten ConnectInterceptor method
 * itself, which would recurse into this bridge).
 *
 * Bounded differences on the Cronet path (compatibility contract):
 * - No Exchange is ever created, so OkHttp emits no connect/DNS/request-header/response-header
 *   or body events. [okhttp3.EventListener.callEnd] still fires at header return: when the
 *   response unwinds, RealCall.getResponseWithInterceptorChain's finally runs
 *   noMoreExchanges -> callDone (bytecode-verified on 5.5.0), and callDone fires callEnd since
 *   no stream flags were ever opened. callFailed fires only if the chain fails before that.
 * - callTimeout is evaluated inside callDone, so it bounds the pre-return (header) phase only;
 *   body streaming happens after callDone and is bounded by readTimeout (header wait and every
 *   body read).
 * - Network interceptors never run (policy denies clients that have them).
 * - No fabricated metadata: handshake, networkResponse and sentRequestAtMillis stay unset.
 * - Redirects surface as 3xx with an empty body for OkHttp's follow-up logic to follow.
 * - 407 is rejected: RetryAndFollowUp would dereference a null Exchange route (Metis B2).
 *
 * Cancellation: 5-step ordered protocol (Metis B3) — pre-start checks plus a per-call
 * EventListener (public Call.addEventListener) that delivers exactly one engine cancel;
 * post-terminal the listener is a no-op and drops its references.
 */
object CronetBridge {

    private val logger = Logger.getLogger(CronetBridge::class.java.name)
    private val loggedOnce = AtomicBoolean(false)

    private const val CANCELED_MESSAGE = "Canceled"
    private const val PROXY_AUTH_MESSAGE =
        "Proxy authentication is not supported over the Cronet path"

    /** Never throws: any policy failure fails closed to stock OkHttp. */
    @JvmStatic
    fun shouldHandle(chain: Interceptor.Chain): Boolean = try {
        PolicyEngine.shouldHandle(PolicyInput.fromChain(chain), CronetRuntime.snapshot()).allow
    } catch (t: Throwable) {
        logOnce(t)
        false
    }

    @JvmStatic
    @Throws(IOException::class)
    fun intercept(chain: Interceptor.Chain): Response {
        val realChain = chain as RealInterceptorChain
        val snapshot = CronetRuntime.snapshot()
        val decision = try {
            PolicyEngine.shouldHandle(PolicyInput.fromChain(chain), snapshot)
        } catch (t: Throwable) {
            logOnce(t)
            Decision(false, null) // fail closed to stock
        }
        return if (decision.allow) {
            Metrics.record(Metrics.Path.CRONET, null)
            cronetPath(realChain, snapshot!!)
        } else {
            Metrics.record(Metrics.Path.FALLBACK, decision.reason)
            stockFallback(realChain)
        }
    }

    /**
     * Literal stock ConnectInterceptor body. `index` is private (no getter), so the full-arg
     * `copy$okhttp` form is unreachable without reflection; `copy(exchange = exchange)` is what
     * stock itself compiles to (`copy$okhttp$default`) and preserves chain-effective settings.
     */
    private fun stockFallback(realChain: RealInterceptorChain): Response {
        val exchange = realChain.call.initExchange(realChain)
        val connectedChain = realChain.copy(exchange = exchange)
        return connectedChain.proceed(realChain.request)
    }

    private fun cronetPath(realChain: RealInterceptorChain, snapshot: RuntimeSnapshot): Response {
        val call = realChain.call
        val request = realChain.request
        val readTimeoutMillis = realChain.readTimeoutMillis().toLong()

        val converted = RequestConverter(
            cronetEngine = snapshot.engine,
            // Distinct executors: Cronet posts UploadDataProvider callbacks onto the upload
            // executor while the provider submits its body work to the reader executor -
            // one shared single thread would self-deadlock until the write timeout.
            uploadDataProviderExecutor = CronetUploadExecutor,
            bodyReaderExecutor = CronetExecutor,
            responseConverter = ResponseConverter(),
        ).convert(request, readTimeoutMillis, realChain.writeTimeoutMillis().toLong())
        val urlRequest = converted.urlRequest

        // 5-step ordered cancellation protocol (Metis B3).
        if (call.isCanceled()) throw IOException(CANCELED_MESSAGE) // (i)
        val handle = CallRegistry.register(call, AtomicReference(urlRequest)) // (ii)
        try {
            if (call.isCanceled()) { // (iii)
                handle.cancelUrlRequestOnce()
                throw IOException(CANCELED_MESSAGE)
            }
            urlRequest.start() // (iv)
            if (call.isCanceled()) { // (v)
                handle.cancelUrlRequestOnce()
                throw IOException(CANCELED_MESSAGE)
            }

            awaitHeaders(converted.callback, handle, readTimeoutMillis)
            val response = converted.getResponse()
            if (response.code == 407) {
                response.body?.closeQuietly()
                throw IOException(PROXY_AUTH_MESSAGE)
            }
            val body = response.body ?: run { CallRegistry.unregister(call); return response }
            return response.newBuilder().body(UnregisteringResponseBody(body, call)).build()
        } catch (e: Throwable) {
            CallRegistry.unregister(call)
            throw e
        }
    }

    /** Waits for headers within the read-timeout budget; on stall the request is canceled. */
    private fun awaitHeaders(
        callback: OkHttpBridgeCallback,
        handle: CallRegistry.ListenerHandle,
        readTimeoutMillis: Long,
    ) {
        try {
            if (readTimeoutMillis == 0L) callback.headersFuture.get()
            else callback.headersFuture.get(readTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            handle.cancelUrlRequestOnce()
            throw SocketTimeoutException("Timed out waiting for response headers")
        } catch (e: ExecutionException) {
            // Unwrap so "Canceled"/CronetException surfaces with its own message.
            throw (e.cause as? IOException) ?: IOException(e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            handle.cancelUrlRequestOnce()
            throw IOException(e)
        }
    }

    /** Belt over the terminal unregister paths: closing the body or its source tears the
     * registration down (ResponseBody.close() routes through source() as well). */
    private class UnregisteringResponseBody(
        private val delegate: ResponseBody,
        private val call: RealCall,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = object : ForwardingSource(delegate.source()) {
            override fun close() {
                CallRegistry.unregister(call)
                super.close()
            }
        }.buffer()
    }

    private fun logOnce(t: Throwable) {
        if (loggedOnce.compareAndSet(false, true)) {
            logger.log(Level.SEVERE, "policy evaluation failed; failing closed to stock OkHttp", t)
        }
    }
}
