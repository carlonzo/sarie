package sarie.bridge

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import okhttp3.Call
import org.chromium.net.RequestFinishedInfo

/**
 * Optional observer for request routing decisions and Cronet transport metrics.
 *
 * Configured via [SarieConfig.Builder.listener]. Applies to both built and borrowed installs.
 * Routing does not depend on listener execution: any exception thrown from either callback is
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
     * Invoked on Sarie's background listener thread when a Cronet request finishes.
     *
     * Emitted once per Cronet `UrlRequest`. If an idempotent request is retried after a pre-headers
     * transport failure, this method will be invoked for each attempt.
     *
     * The background listener thread is shared across the process: implementations should perform
     * minimal work or hand off processing to their own executor.
     *
     * @param call The OkHttp [Call] that finished.
     * @param info Cronet's [RequestFinishedInfo] detailing wire byte counts, protocol timing metrics,
     *   socket reuse, and transport errors.
     */
    public fun onFinished(call: Call, info: RequestFinishedInfo): Unit {}
}

/**
 * Single daemon thread for [SarieListener.onFinished]. Not [CronetExecutor]: a slow host
 * listener must not stall body reads. The thread starts on the first
 * finished request.
 */
internal object RequestFinishedExecutor {
    val executor: Executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sarie-request-finished").apply { isDaemon = true }
        }
    }
}
