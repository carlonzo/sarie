package sarie.bridge

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

private val DAEMON_THREAD_FACTORY = ThreadFactory { runnable ->
    Thread(runnable, "sarie-callbacks").apply { isDaemon = true }
}

/**
 * Bridge-owned cached daemon executor for the mapping layer's upload-provider and body-reader
 * work. Never the calling OkHttp thread (Metis B5): upload pumps must not run on the thread
 * blocked in intercept(). Blocking body writers need one worker per active upload so a pump
 * waiting for Cronet's next callback cannot starve another writer. Idle workers expire.
 * Distinct callback executor remains necessary.
 */
internal object CronetExecutor : ExecutorService by Executors.newCachedThreadPool(DAEMON_THREAD_FACTORY)

/**
 * Separate single-thread daemon executor driving Cronet's UploadDataProvider callbacks.
 * Must stay distinct from [CronetExecutor]: a real CronetEngine posts provider.read() onto
 * the upload executor, and the provider's own work (materialize/pump) is submitted to the
 * body-reader executor - sharing one thread would self-deadlock until the write timeout.
 */
internal object CronetUploadExecutor : ExecutorService by Executors.newSingleThreadExecutor(DAEMON_THREAD_FACTORY)
