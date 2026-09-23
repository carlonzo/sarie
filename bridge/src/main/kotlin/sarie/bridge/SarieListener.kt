package sarie.bridge

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import okhttp3.Call
import org.chromium.net.RequestFinishedInfo

/**
 * Optional host observer. Routing does not depend on it: a throw from [onRouted] is logged
 * once and the call continues.
 */
interface SarieListener {
    /** Caller thread, before any I/O. [reason] is null when the call is going to Cronet. */
    fun onRouted(call: Call, reason: Metrics.Reason?) {}

    /** Sarie listener thread, once per Cronet UrlRequest. A retry reports twice. */
    fun onFinished(call: Call, info: RequestFinishedInfo) {}
}

/**
 * Single daemon thread for [SarieListener.onFinished]. Not [CronetExecutor]: a slow host
 * listener must not stall body reads. Created on the first install that passes a listener.
 */
internal object RequestFinishedExecutor {
    val executor: Executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sarie-request-finished").apply { isDaemon = true }
        }
    }
}