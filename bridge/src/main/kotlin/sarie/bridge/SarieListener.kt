package sarie.bridge

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import okhttp3.Call

/**
 * Optional observer for request routing decisions and Cronet transport metrics.
 *
 * Configured via [SarieConfig.Builder.listener]. Applies to both built and borrowed installs.
 * Routing does not depend on listener execution: any exception thrown from any callback is
 * logged as a warning and swallowed, and will not crash the thread or affect request outcome.
 */
public interface SarieListener {
    /**
     * Invoked on the OkHttp caller thread before any network I/O begins.
     *
     * Fires once per network hop: follow-ups from redirects and 401 authenticator retries report again.
     *
     * @param call The OkHttp [Call] being dispatched. Request tags can be read from `call.request()`.
     * @param reason `null` when the call was routed to Cronet; a [FallbackReason] when falling back
     *   to stock OkHttp.
     */
    public fun onRouted(call: Call, reason: FallbackReason?): Unit {}

    /**
     * Cronet path only. Invoked on the OkHttp caller thread inside the terminal hop, after
     * headers arrived and BEFORE the Response is returned up the chain — i.e. before network
     * interceptors, application interceptors (e.g. Sentry's) and EventListener.callEnd see it.
     * Once per UrlRequest attempt (redirect hops and transport retries report again).
     *
     * Implementations should perform minimal work or hand off processing to their own executor.
     *
     * @param call The OkHttp [Call] that received response headers.
     * @param info Response metadata including protocol, status, cache status, and bridge timestamps.
     */
    public fun onResponseStarted(call: Call, info: SarieResponseInfo): Unit {}

    /**
     * Cronet path only. Once per UrlRequest attempt.
     *
     * Unless the provider rejects finished listeners, runs before the caller's body read returns
     * EOF / throws, before `close()` returns, and before a pre-header failure is thrown or retried.
     * Otherwise it runs later on Sarie's listener thread with [SarieTimings.deliveredLate] = true.
     *
     * Because this callback usually runs synchronously on the OkHttp caller / reader thread,
     * implementations should perform minimal work or hand off processing to their own executor.
     *
     * @param call The OkHttp [Call] that finished.
     * @param timings Detailed transport metrics, phase durations, and wire byte counts.
     */
    public fun onFinished(call: Call, timings: SarieTimings): Unit {}
}

/**
 * Single daemon thread for [SarieListener.onFinished] fallback delivery.
 * Not [CronetExecutor]: a slow host listener must not stall body reads.
 * The thread starts on the first finished request that uses the fallback path.
 */
internal object RequestFinishedExecutor {
    val executor: Executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sarie-request-finished").apply { isDaemon = true }
        }
    }
}
