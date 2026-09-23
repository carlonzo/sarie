package sarie.bridge

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import okhttp3.Call
import org.chromium.net.RequestFinishedInfo

/**
 * Optional host observer. Routing does not depend on it: a throw from either method is logged
 * once and swallowed.
 */
public interface SarieListener {
    /**
     * Caller thread, before any I/O. [reason] is null when the call is going to Cronet. Fires
     * once per network hop: each redirect and each authenticator retry reports again.
     */
    public fun onRouted(call: Call, reason: FallbackReason?): Unit {}

    /**
     * Sarie listener thread, once per Cronet UrlRequest. A retry reports twice. The thread is
     * shared and its queue is unbounded: keep this cheap, or hand off to your own executor.
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
