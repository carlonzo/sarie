package sarie.bridge.mapping

import sarie.bridge.CronetPolicy
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.nio.ByteBuffer
import java.util.AbstractMap
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo

/**
 * Hand-written cronet-api doubles for JVM tests (no Android, no device). Subclasses cover every
 * abstract member of cronet-api 143.7445.0:
 *  - CronetEngine: 8 abstract methods
 *  - UrlRequest: 6 (start, followRedirect, read, cancel, isDone, getStatus)
 *  - UrlRequest.Builder: 7 (setHttpMethod, addHeader, disableCache, setPriority,
 *    setUploadDataProvider, allowDirectExecutor, build)
 *  - UrlRequest.Callback: 5 abstract (onRedirectReceived, onResponseStarted, onReadCompleted,
 *    onSucceeded, onFailed); onCanceled has a default impl
 *  - UrlResponseInfo: 10 abstract methods
 *  - UploadDataProvider: getLength, read, rewind
 *  - UploadDataSink: onReadSucceeded(boolean), onReadError, onRewindSucceeded() (no args in this
 *    version), onRewindError
 */

/** Daemon executor service used for background body reads in tests. */
class DaemonExecutorService : AbstractExecutorService() {
    private val shutdown = AtomicBoolean(false)

    override fun execute(command: Runnable) {
        if (shutdown.get()) throw IllegalStateException("executor shut down")
        Thread(command).apply { isDaemon = true }.start()
    }

    override fun shutdown() {
        shutdown.set(true)
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown.set(true)
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = shutdown.get()
    override fun isTerminated(): Boolean = shutdown.get()
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown.get()
}

class FakeCronetException(message: String = "fake network error") : CronetException(message, null)

class FakeCronetEngine : CronetEngine() {
    val builders = mutableListOf<FakeUrlRequestBuilder>()

    val builtRequests: List<FakeUrlRequest>
        get() = builders.mapNotNull { it.builtRequest }

    override fun newUrlRequestBuilder(
        url: String,
        callback: UrlRequest.Callback,
        executor: Executor,
    ): UrlRequest.Builder = FakeUrlRequestBuilder(url, callback, executor).also { builders.add(it) }

    override fun getVersionString(): String = "fake"
    @Suppress("OVERRIDE_DEPRECATION")
    override fun shutdown() = Unit
    @Suppress("OVERRIDE_DEPRECATION")
    override fun startNetLogToFile(fileName: String, logAll: Boolean) = Unit
    override fun stopNetLog() = Unit
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getGlobalMetricsDeltas(): ByteArray = ByteArray(0)
    override fun openConnection(url: URL): URLConnection =
        throw UnsupportedOperationException("fake engine")

    override fun createURLStreamHandlerFactory(): URLStreamHandlerFactory =
        throw UnsupportedOperationException("fake engine")
}

class FakeUrlRequest(private val builder: FakeUrlRequestBuilder) : UrlRequest() {
    var startCalls = 0
        private set
    var cancelCalls = 0
        private set
    var followRedirectCalls = 0
        private set
    var readCalls = 0
        private set

    /** Scripted reaction to read(); invoked synchronously on the caller's thread. */
    var readHandler: ((ByteBuffer) -> Unit)? = null

    val callback: UrlRequest.Callback get() = builder.callback

    override fun start() {
        startCalls++
    }

    override fun cancel() {
        cancelCalls++
    }

    override fun followRedirect() {
        followRedirectCalls++
    }

    override fun isDone(): Boolean = false

    override fun getStatus(listener: UrlRequest.StatusListener) = Unit

    override fun read(buffer: ByteBuffer) {
        readCalls++
        readHandler?.invoke(buffer)
    }
}

class FakeUrlRequestBuilder(
    val url: String,
    val callback: UrlRequest.Callback,
    val callbackExecutor: Executor,
) : UrlRequest.Builder() {
    var method: String? = null
        private set
    val headers = mutableListOf<Pair<String, String>>()
    var uploadDataProvider: UploadDataProvider? = null
        private set
    var uploadExecutor: Executor? = null
        private set
    var directExecutorAllowed = false
        private set
    var builtRequest: FakeUrlRequest? = null
        private set

    override fun setHttpMethod(method: String): UrlRequest.Builder {
        this.method = method
        return this
    }

    override fun addHeader(name: String, value: String): UrlRequest.Builder {
        headers.add(name to value)
        return this
    }

    var cacheDisabled = false
        private set

    override fun disableCache(): UrlRequest.Builder {
        cacheDisabled = true
        return this
    }

    override fun setPriority(priority: Int): UrlRequest.Builder = this

    override fun setUploadDataProvider(
        provider: UploadDataProvider,
        executor: Executor,
    ): UrlRequest.Builder {
        uploadDataProvider = provider
        uploadExecutor = executor
        return this
    }

    override fun allowDirectExecutor(): UrlRequest.Builder {
        directExecutorAllowed = true
        return this
    }

    override fun build(): FakeUrlRequest = FakeUrlRequest(this).also { builtRequest = it }
}

class FakeUrlResponseInfo(
    private val url: String = "https://example.com/",
    private val urlChain: List<String> = listOf(url),
    private val statusCode: Int = 200,
    private val statusText: String = "OK",
    private val headersAsList: List<Map.Entry<String, String>> = emptyList(),
    private val negotiatedProtocol: String = "http/1.1",
    private val cached: Boolean = false,
) : UrlResponseInfo() {
    override fun getUrl(): String = url
    override fun getUrlChain(): List<String> = urlChain
    override fun getHttpStatusCode(): Int = statusCode
    override fun getHttpStatusText(): String = statusText
    override fun getAllHeadersAsList(): List<Map.Entry<String, String>> = headersAsList
    override fun getAllHeaders(): Map<String, List<String>> =
        headersAsList.groupBy({ it.key }, { it.value })

    override fun wasCached(): Boolean = cached
    override fun getNegotiatedProtocol(): String = negotiatedProtocol
    override fun getProxyServer(): String? = null
    override fun getReceivedByteCount(): Long = 0

    companion object {
        fun headerEntry(name: String, value: String): Map.Entry<String, String> =
            AbstractMap.SimpleEntry(name, value)
    }
}

class FakeUploadDataSink : UploadDataSink() {
    val readSucceeded = mutableListOf<Boolean>()
    val readErrors = mutableListOf<Exception>()
    var rewindSucceededCalls = 0
        private set
    val rewindErrors = mutableListOf<Exception>()

    override fun onReadSucceeded(finalChunk: Boolean) {
        readSucceeded.add(finalChunk)
    }

    override fun onReadError(e: Exception) {
        readErrors.add(e)
    }

    override fun onRewindSucceeded() {
        rewindSucceededCalls++
    }

    override fun onRewindError(e: Exception) {
        rewindErrors.add(e)
    }
}

class FakePolicy(override val allowedOrigins: Set<String> = emptySet()) : CronetPolicy
