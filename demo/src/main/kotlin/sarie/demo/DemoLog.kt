package sarie.demo

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import sarie.bridge.CronetOptOut
import sarie.bridge.FallbackReason
import sarie.bridge.SarieListener
import sarie.bridge.SarieLogger
import sarie.bridge.SarieResponseInfo
import sarie.bridge.SarieTimings

object DemoLog : SarieLogger, SarieListener, Interceptor {

    private const val BUFFER_CAPACITY = 500

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sarieBuffer = ArrayDeque<String>(BUFFER_CAPACITY)
    private val networkBuffer = ArrayDeque<String>(BUFFER_CAPACITY)
    private val cronetBuffer = ArrayDeque<String>(BUFFER_CAPACITY)

    val cronetCount = AtomicInteger(0)
    val stockCount = AtomicInteger(0)

    @Volatile
    var lastLine: String = "ready"
        private set

    var version by mutableIntStateOf(0)
        private set

    val finishedCalls = ConcurrentHashMap<Call, CompletableFuture<SarieTimings>>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun now(): String = synchronized(timeFormat) {
        timeFormat.format(Date())
    }

    private fun addLine(buffer: ArrayDeque<String>, line: String, updateLastLine: Boolean = true) {
        synchronized(lock) {
            if (buffer.size >= BUFFER_CAPACITY) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
            if (updateLastLine) {
                lastLine = line
            }
        }
        notifyChange()
    }

    private fun notifyChange() {
        mainHandler.post {
            version++
        }
    }

    fun getLines(tab: Int): List<String> = synchronized(lock) {
        when (tab) {
            0 -> sarieBuffer.toList()
            1 -> networkBuffer.toList()
            2 -> cronetBuffer.toList()
            else -> emptyList()
        }
    }

    fun clear(tab: Int) {
        synchronized(lock) {
            when (tab) {
                0 -> sarieBuffer.clear()
                1 -> networkBuffer.clear()
                2 -> cronetBuffer.clear()
            }
        }
        notifyChange()
    }

    // SarieLogger
    override fun log(priority: Int, message: String, throwable: Throwable?) {
        val letter = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        val text = if (throwable != null) {
            "$message: ${throwable.message ?: throwable.javaClass.simpleName}"
        } else {
            message
        }
        val line = "${now()} [$letter] $text"
        addLine(sarieBuffer, line, updateLastLine = false)
    }

    // SarieListener.onRouted
    override fun onRouted(call: Call, reason: FallbackReason?) {
        val path = call.request().url.encodedPath
        val method = call.request().method
        val line: String
        if (reason == null) {
            cronetCount.incrementAndGet()
            line = "${now()} → cronet $method $path"
        } else {
            stockCount.incrementAndGet()
            line = "${now()} → stock: $reason $method $path"
        }
        addLine(sarieBuffer, line, updateLastLine = true)
    }

    // SarieListener.onResponseStarted
    override fun onResponseStarted(call: Call, info: SarieResponseInfo) {
        val path = call.request().url.encodedPath
        val line = "${now()} [headers] ${info.protocol} ${info.httpStatusCode} $path (attempt=${info.attempt})"
        addLine(cronetBuffer, line, updateLastLine = false)
    }

    // SarieListener.onFinished
    override fun onFinished(call: Call, timings: SarieTimings) {
        finishedCalls.computeIfAbsent(call) { CompletableFuture() }.complete(timings)

        val path = call.request().url.encodedPath
        val totalMs = timings.totalMs ?: -1
        val dnsStr = timings.dnsMs?.let { "${it}ms" } ?: "—"
        val connStr = timings.connectMs?.let { "${it}ms" } ?: "—"
        val sslStr = timings.tlsMs?.let { "${it}ms" } ?: "—"
        val ttfbMs = timings.ttfbMs ?: -1
        val reused = timings.socketReused
        val bytes = timings.receivedBytes ?: -1
        val reasonStr = timings.result.name

        val line = "${now()} $path total=${totalMs}ms dns=$dnsStr conn=$connStr ssl=$sslStr ttfb=${ttfbMs}ms reused=$reused bytes=$bytes $reasonStr"
        addLine(cronetBuffer, line, updateLastLine = true)
    }

    // Network Interceptor for both clients
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isStock = request.tag(CronetOptOut::class.java) != null
        val stack = if (isStock) "stock" else "sarie"
        val path = "${request.url.host}${request.url.encodedPath}"
        val start = System.currentTimeMillis()
        return try {
            val response = chain.proceed(request)
            val took = System.currentTimeMillis() - start
            val line = "${now()} [$stack] ${request.method} $path → ${response.code} ${response.protocol} (${took}ms)"
            addLine(networkBuffer, line, updateLastLine = true)
            response
        } catch (e: Exception) {
            val took = System.currentTimeMillis() - start
            val line = "${now()} [$stack] ${request.method} $path → ERROR: ${e.message} (${took}ms)"
            addLine(networkBuffer, line, updateLastLine = true)
            throw e
        }
    }
}
