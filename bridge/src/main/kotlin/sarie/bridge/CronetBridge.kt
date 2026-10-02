@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import sarie.bridge.mapping.OkHttpBridgeCallback
import sarie.bridge.mapping.RequestBodyEvents
import sarie.bridge.mapping.SarieTimingsMapper
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLPeerUnverifiedException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.RealInterceptorChain
import org.chromium.net.CronetException
import org.chromium.net.NetworkException
import org.chromium.net.UrlRequest

/**
 * Trampoline target for the rewritten ConnectInterceptor (descriptor
 * `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;`).
 *
 * Routing: re-evaluates [PolicyEngine] on the effective chain configuration. Allow -> Cronet
 * path; deny -> exact-stock
 * fallback (`initExchange` + `copy` + `proceed`, never the rewritten ConnectInterceptor method
 * itself, which would recurse into this bridge).
 *
 * Bounded differences on the Cronet path (compatibility contract):
 * - No Exchange is ever created. The allow branch proceeds the real chain with no exchange, so
 *   the app's network interceptors run, then the rewritten CallServerInterceptor calls
 *   [callServer]. [okhttp3.EventListener.callEnd] still fires at header return: when the
 *   response unwinds, RealCall.getResponseWithInterceptorChain's finally runs
 *   noMoreExchanges -> callDone (bytecode-verified on 5.5.0), and callDone fires callEnd since
 *   no stream flags were ever opened. callFailed fires only if the chain fails before that.
 * - Header events are emitted from the terminal hop (handoff/delivery by Cronet, not on-wire).
 *   Connect, DNS, secure-connect and response-body events are not emitted. Response-body events
 *   would land after callEnd.
 * - callTimeout is read from the call and bounds the whole hop - the header wait and, through
 *   the body source, every body read - matching stock, where it spans the entire call. It fires
 *   with OkHttp's own shape (InterruptedIOException("timeout")) so it stays distinguishable
 *   from the read timeout. A network interceptor's withReadTimeout is the chain read timeout
 *   the hop uses; whichever of the two bounds is tighter wins.
 * - chain.connection() stays null. No fabricated metadata: handshake and networkResponse stay
 *   unset; the sent/received timestamps come from bridge-owned clocks
 *   (RequestConverter start / onResponseStarted).
 * - Redirects surface as 3xx with an empty body for OkHttp's follow-up logic to follow.
 * - 401 is returned so RetryAndFollowUp calls authenticator.authenticate(route = null, response).
 * - 407 is rejected with ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy"),
 *   never a Response. It is not retryable.
 *
 * Transport-failure retry (compensates for the missing Exchange-based route retry stock runs
 * inside ConnectInterceptor/RetryAndFollowUp): a Cronet request that fails at the transport
 * level BEFORE any response bytes arrived (onFailed -> headersFuture completed exceptionally
 * with a CronetException; no onResponseStarted, no body consumed) is retried EXACTLY ONCE with
 * a fresh UrlRequest, and only when the method is idempotent (GET/HEAD/OPTIONS per RFC), the
 * call was not canceled and `client.retryOnConnectionFailure` is true. Never after
 * onResponseStarted (the server may have acted on the request), never for non-idempotent
 * methods, and no retry for header-wait timeouts (stock treats InterruptedIOException the same
 * way). A pinning failure (`NetworkException.cronetInternalErrorCode == -150`) is mapped to
 * [SSLPeerUnverifiedException] before that decision and is not retried; the call is not handed
 * to stock after Cronet has started. Bodyless methods have no request body, so re-running the
 * converter has no replay hazard.
 *
 * Cancellation: 5-step ordered protocol (Metis B3) — pre-start checks plus [notifyCanceled],
 * which the bytecode rewrite appends to `RealCall.cancel()` and which delivers exactly one
 * engine cancel per registration. OkHttp's EventListener is not used for this: the per-call
 * `Call.addEventListener` it would take rebuilds an aggregate listener inside a CAS retry
 * loop. Post-terminal the registry entry is gone, so [notifyCanceled] is a no-op.
 */
@SarieInternalApi
public object CronetBridge {

    private val loggedOnce = AtomicBoolean(false)
    private val listenerLoggedOnce = AtomicBoolean(false)
    private val responseStartedLoggedOnce = AtomicBoolean(false)

    private const val CANCELED_MESSAGE = "Canceled"
    private const val PROXY_AUTH_MESSAGE =
        "Received HTTP_PROXY_AUTH (407) code while not using proxy"

    /** OkHttp's `AsyncTimeout` message for an expired call timeout. */
    private const val CALL_TIMEOUT_MESSAGE = "timeout"

    /** Chromium `ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN`. */
    private const val ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN = -150

    /** Methods safe to retry once on a pre-headers transport failure (RFC idempotent). */
    private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS")

    /**
     * Intercepts OkHttp's connection phase, evaluated by the rewritten `ConnectInterceptor`.
     *
     * Evaluates pre-send routing policy via [PolicyEngine]. If allowed, proceeds without an
     * exchange so network interceptors run before reaching [callServer]. If denied, executes
     * the stock connection fallback.
     *
     * @param chain The active OkHttp interceptor chain.
     * @return The response returned by either Cronet or stock OkHttp.
     * @throws IOException on network or protocol errors.
     */
    @JvmStatic
    @Throws(IOException::class)
    public fun intercept(chain: Interceptor.Chain): Response {
        val realChain = chain as RealInterceptorChain
        val snapshot = SarieBridge.snapshot()
        val reason = try {
            PolicyEngine.shouldHandle(PolicyInput.fromChain(chain), snapshot)
        } catch (t: Throwable) {
            logOnce(t)
            notifyRouted(realChain.call, FallbackReason.policy_error)
            return stockFallback(realChain)
        }
        if (reason != null) {
            notifyRouted(realChain.call, reason)
            return stockFallback(realChain)
        }
        val call = realChain.call
        notifyRouted(call, null)
        // No exchange: OkHttp runs network interceptors and lands in callServer.
        val cycle = RoutedCycle.open(call, realChain.request.url)
        return try {
            val response = realChain.proceed(realChain.request)
            if (!cycle.reachedTerminal()) {
                throw IllegalStateException(exactlyOnceMessage(call))
            }
            response
        } finally {
            RoutedCycle.close(call)
        }
    }

    /**
     * Terminal hop for a Sarie-routed call. Descriptor
     * `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;`.
     *
     * A non-null exchange means stock OkHttp reached CallServerInterceptor; return null and the
     * stock body runs. Stock never arrives here with a null exchange (it would NPE), so a null
     * exchange means this bridge proceeded without one.
     *
     * The checks are the ones [okhttp3.internal.http.RealInterceptorChain.proceed] skips when
     * `exchange == null` (OkHttp 5.5.0, RealInterceptorChain.kt:317-339).
     *
     * @param chain The active OkHttp interceptor chain.
     * @return The Cronet response if routed through Cronet, or null if stock OkHttp should execute.
     * @throws IOException on network or protocol errors.
     */
    @JvmStatic
    @Throws(IOException::class)
    public fun callServer(chain: Interceptor.Chain): Response? {
        val realChain = chain as RealInterceptorChain
        if (realChain.exchange != null) return null
        val call = realChain.call
        val cycle = RoutedCycle.current(call)
            ?: throw IllegalStateException("Sarie terminal hop without a routed cycle")
        val url = realChain.request.url
        if (url.scheme != cycle.scheme || url.host != cycle.host || url.port != cycle.port) {
            throw IllegalStateException(sameAddressMessage(call))
        }
        if (!cycle.claimTerminal()) {
            throw IllegalStateException(exactlyOnceMessage(call))
        }
        val snapshot = SarieBridge.snapshot()
            ?: throw IOException("Cronet engine is not installed")
        return cronetPath(realChain, snapshot)
    }

    /**
     * RealInterceptorChain.kt:319 — stock's message. Scheme is checked too (the plan requires
     * it); stock's sameHostAndPort does not mention scheme, and neither does this message.
     */
    private fun sameAddressMessage(call: RealCall): String =
        "network interceptor ${blamedNetworkInterceptor(call)} must retain the same host and port"

    /** RealInterceptorChain.kt:322 and :338. */
    private fun exactlyOnceMessage(call: RealCall): String =
        "network interceptor ${blamedNetworkInterceptor(call)} must call proceed() exactly once"

    private fun blamedNetworkInterceptor(call: RealCall): Any =
        call.client.networkInterceptors.lastOrNull() ?: "unknown"

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
        val writeTimeoutMillis = realChain.writeTimeoutMillis().toLong()
        // OkHttp's own callTimeout is still running during the header phase and, now that cancel()
        // reaches Cronet, it already aborts the engine request. It only stops covering the call
        // once the response is returned, which is why the body side needs this value and the
        // header phase does not.
        val callTimeoutMillis = call.timeout().timeoutNanos() / 1_000_000L
        // Distinct executors live inside the snapshot converter: Cronet posts
        // UploadDataProvider callbacks onto the upload executor while the provider submits
        // its body work to the reader executor. One shared single thread would self-deadlock
        // until the write timeout.
        val converter = snapshot.requestConverter

        val events = BridgeEvents(call)
        var retried = false
        var attempt = 1
        while (true) {
            events.requestHeadersStart()
            var reportedOutcome = false
            val converted = try {
                converter.convert(
                    request,
                    readTimeoutMillis,
                    writeTimeoutMillis,
                    requestBodyEvents = RequestBodyEvents(
                        onStart = events::requestBodyStart,
                        onEnd = events::requestBodyEnd,
                    ),
                    onResponseHeadersStart = { events.responseHeadersStart() },
                    call = call,
                    attempt = attempt,
                    callTimeoutMillis = callTimeoutMillis,
                )
            } catch (e: IOException) {
                events.requestFailed(e)
                throw e
            }
            val urlRequest = converted.urlRequest

            // 5-step ordered cancellation protocol (Metis B3).
            if (call.isCanceled()) {
                val canceled = IOException(CANCELED_MESSAGE)
                events.requestFailed(canceled)
                throw canceled
            } // (i)
            val registration = CallRegistry.register(call, AtomicReference(urlRequest)) // (ii)
            converted.callback.attach(registration)
            try {
                try {
                    if (call.isCanceled()) { // (iii)
                        registration.cancelUrlRequestOnce()
                        throw IOException(CANCELED_MESSAGE)
                    }
                    // Before start(): Cronet may call the upload provider or onResponseStarted on
                    // its own threads before start() returns, and those emit later events.
                    events.requestHeadersEnd(request)
                    urlRequest.start() // (iv)
                    if (call.isCanceled()) { // (v)
                        registration.cancelUrlRequestOnce()
                        throw IOException(CANCELED_MESSAGE)
                    }

                    awaitHeaders(converted.callback, registration, readTimeoutMillis)
                } catch (e: IOException) {
                    // Pre-headers transport failure: the single idempotent retry (see KDoc).
                    if (converted.callback.responseHeadersDelivered) events.responseFailed(e)
                    else events.requestFailed(e)
                    reportedOutcome = true
                    registration.unregister()

                    // Bounded await + deliver attempt before retry or throw
                    converted.callback.awaitAndDeliver()

                    if (!retried && isRetryable(e, call, request)) {
                        retried = true
                        attempt++
                        continue
                    }
                    throw e
                }

                // Headers arrived! Dispatch onResponseStarted BEFORE responseHeadersEnd.
                notifyResponseStarted(call, converted.callback, attempt)

                val response = converted.getResponse()
                events.responseHeadersEnd(response)
                reportedOutcome = true
                if (response.code == 407) {
                    response.body.closeQuietly()
                    throw ProtocolException(PROXY_AUTH_MESSAGE)
                }
                // A redirect carries an empty Buffer rather than the callback's body source, so
                // closing it never reaches the body source's teardown. Cronet has already
                // canceled the engine request at header time, so the registration ends here -
                // otherwise a later cancel would deliver a second, redundant engine cancel.
                if (converted.callback.isRedirect) {
                    registration.unregister()
                }
                // The body is returned as ResponseConverter built it: OkHttpBridgeCallback's
                // body source owns the registration teardown (close, terminal callbacks), so
                // nothing wraps it and no response byte is copied an extra time.
                return response
            } catch (e: Throwable) {
                if (e is IOException && !reportedOutcome) {
                    if (converted.callback.responseHeadersDelivered) events.responseFailed(e)
                    else events.requestFailed(e)
                }
                registration.unregister()
                throw e
            }
        }
    }

    /**
     * The cancel hook the bytecode rewrite appends to `RealCall.cancel()`. Safe for every call
     * in the process: a call Sarie never routed, or one that already unregistered, is a no-op.
     */
    @JvmStatic
    public fun notifyCanceled(call: RealCall) {
        CallRegistry.notifyCanceled(call)
    }

    /** Stock's message prefix. Mapped before [isRetryable], and excluded there if it leaks through. */
    private fun mapPinningFailure(error: IOException): IOException {
        if (error is NetworkException &&
            error.cronetInternalErrorCode == ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN
        ) {
            return SSLPeerUnverifiedException("Certificate pinning failure!")
        }
        return error
    }

    /** Header events only. The calls themselves do no I/O; Cronet-thread emits must not block. */
    private class BridgeEvents(private val call: Call) {
        private val listener: EventListener get() = (call as RealCall).eventListener

        fun requestHeadersStart() = listener.requestHeadersStart(call)
        fun requestHeadersEnd(request: Request) = listener.requestHeadersEnd(call, request)
        fun requestBodyStart() = listener.requestBodyStart(call)
        fun requestBodyEnd(byteCount: Long) = listener.requestBodyEnd(call, byteCount)
        fun responseHeadersStart() = listener.responseHeadersStart(call)
        fun responseHeadersEnd(response: Response) = listener.responseHeadersEnd(call, response)
        fun requestFailed(ioe: IOException) = listener.requestFailed(call, ioe)
        fun responseFailed(ioe: IOException) = listener.responseFailed(call, ioe)
    }

    /**
     * Whether a pre-headers failure qualifies for the single retry: transport-level
     * CronetException only (header-wait timeouts, cancellations, and certificate-pin failures
     * do not qualify), idempotent method, live call, and the stock `retryOnConnectionFailure`
     * setting honored.
     */
    private fun isRetryable(e: IOException, call: RealCall, request: Request): Boolean {
        if (e is SSLPeerUnverifiedException) return false
        if (e is NetworkException && e.cronetInternalErrorCode == ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN) {
            return false
        }
        return e is CronetException &&
            !call.isCanceled() &&
            request.method in IDEMPOTENT_METHODS &&
            call.client.retryOnConnectionFailure
    }

    /**
     * Waits for headers within the read-timeout budget; on stall the request is canceled.
     *
     * The call's own `callTimeout` is deliberately not applied here: OkHttp's `AsyncTimeout` is
     * still running while this blocks, and it cancels the call itself, which now reaches Cronet
     * through [notifyCanceled]. Re-implementing it could only fire later than OkHttp's own.
     */
    private fun awaitHeaders(
        callback: OkHttpBridgeCallback,
        registration: CallRegistry.Registration,
        readTimeoutMillis: Long,
    ) {
        try {
            if (readTimeoutMillis == 0L) callback.headersFuture.get()
            else callback.headersFuture.get(readTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            registration.cancelUrlRequestOnce()
            throw SocketTimeoutException("Timed out waiting for response headers")
        } catch (e: ExecutionException) {
            // Unwrap so "Canceled"/CronetException surfaces with its own message.
            // Pin failures become SSLPeerUnverifiedException before the retry decision.
            val cause = (e.cause as? IOException) ?: IOException(e.cause ?: e)
            throw mapPinningFailure(cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            registration.cancelUrlRequestOnce()
            throw IOException(e)
        }
    }

    private fun notifyRouted(call: Call, reason: FallbackReason?) {
        val logger = SarieBridge.logger
        // The routing line calls HttpUrl.toString() on the caller thread on every hop. A host
        // that filters DEBUG out never wants it, so ask before building it.
        if (logger != null && logger.isLoggable(Log.DEBUG)) {
            val request = call.request()
            logger.log(
                Log.DEBUG,
                if (reason == null) {
                    "${request.method} ${request.url} -> cronet"
                } else {
                    "${request.method} ${request.url} -> okhttp (reason=$reason)"
                },
                null,
            )
        }
        val listener = SarieBridge.listener ?: return
        try {
            listener.onRouted(call, reason)
        } catch (t: Throwable) {
            if (listenerLoggedOnce.compareAndSet(false, true)) {
                SarieBridge.logger?.log(Log.WARN, "SarieListener.onRouted threw; routing continues", t)
            }
        }
    }

    private fun notifyResponseStarted(call: Call, callback: OkHttpBridgeCallback, attempt: Int) {
        val listener = SarieBridge.listener ?: return
        val urlResponseInfo = callback.headersFuture.getNow(null) ?: return
        val negotiatedProtocol = urlResponseInfo.negotiatedProtocol
        val info = SarieResponseInfo(
            protocol = SarieTimingsMapper.parseProtocol(negotiatedProtocol),
            negotiatedProtocol = negotiatedProtocol,
            httpStatusCode = urlResponseInfo.httpStatusCode,
            wasCached = urlResponseInfo.wasCached(),
            handoffAtMillis = callback.sentAtMillis,
            headersAtMillis = callback.receivedHeadersAtMillis,
            attempt = attempt,
            isRedirect = callback.isRedirect,
        )
        try {
            listener.onResponseStarted(call, info)
        } catch (t: Throwable) {
            if (responseStartedLoggedOnce.compareAndSet(false, true)) {
                SarieBridge.logger?.log(
                    Log.WARN,
                    "SarieListener.onResponseStarted threw; response processing continues",
                    t,
                )
            }
        }
    }

    private fun logOnce(t: Throwable) {
        if (loggedOnce.compareAndSet(false, true)) {
            SarieBridge.logger?.log(Log.ERROR, "policy evaluation failed; failing closed to stock OkHttp", t)
        }
    }
}
