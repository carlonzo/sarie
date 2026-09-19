package dev.okhttpcronet.bridge

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

private val DAEMON_THREAD_FACTORY = ThreadFactory { runnable ->
    Thread(runnable, "okhttp-cronet-callbacks").apply { isDaemon = true }
}

/**
 * Bridge-owned single-thread daemon executor for the mapping layer's upload-provider and
 * body-reader work. Never the calling OkHttp thread (Metis B5): upload pumps must not run on
 * the thread blocked in intercept().
 */
internal object CronetExecutor : ExecutorService by Executors.newSingleThreadExecutor(DAEMON_THREAD_FACTORY)

/**
 * Separate single-thread daemon executor driving Cronet's UploadDataProvider callbacks.
 * Must stay distinct from [CronetExecutor]: a real CronetEngine posts provider.read() onto
 * the upload executor, and the provider's own work (materialize/pump) is submitted to the
 * body-reader executor - sharing one thread would self-deadlock until the write timeout.
 */
internal object CronetUploadExecutor : ExecutorService by Executors.newSingleThreadExecutor(DAEMON_THREAD_FACTORY)
