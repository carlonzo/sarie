package sarie.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class RoundTripResult(
    val stockColdMs: Long,
    val stockWarmP50Ms: Long,
    val stockWarmP95Ms: Long,
    val stockProtocol: String,
    val sarieColdMs: Long?,
    val sarieWarmP50Ms: Long,
    val sarieWarmP95Ms: Long,
    val sarieProtocol: String,
)

data class ParallelImagesResult(
    val stockWallTotalMs: Long,
    val stockFirstImageMs: Long,
    val stockP50Ms: Long,
    val stockP95Ms: Long,
    val stockP99Ms: Long,
    val stockTotalBytes: Long,
    val stockProtocolCounts: Map<String, Int>,
    val stockFailedCount: Int,

    val sarieWallTotalMs: Long,
    val sarieFirstImageMs: Long,
    val sarieP50Ms: Long,
    val sarieP95Ms: Long,
    val sarieP99Ms: Long,
    val sarieTotalBytes: Long,
    val sarieProtocolCounts: Map<String, Int>,
    val sarieFailedCount: Int,
)

data class HostSetupMetrics(
    val host: String,
    val stack: String,
    val dnsMs: Long?,
    val connMs: Long?,
    val tlsMs: Long?,
    val ttfbMs: Long?,
    val socketReused: Boolean = false,
)

data class ConnectionSetupResult(
    val rows: List<HostSetupMetrics>,
)

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val isComplete: Boolean,
    val error: String?,
)

object Scenarios {

    enum class Stack { STOCK, SARIE }

    data class RoundTripRun(
        val coldMs: Long,
        val warmP50Ms: Long,
        val warmP95Ms: Long,
        val protocol: String,
        val firstCall: Call? = null,
    )

    fun runRoundTrip(clients: Clients): RoundTripResult {
        val runSequence = listOf(
            Stack.STOCK,
            Stack.SARIE,
            Stack.SARIE,
            Stack.STOCK,
            Stack.STOCK,
            Stack.SARIE,
        )

        val stockRuns = mutableListOf<RoundTripRun>()
        val sarieRuns = mutableListOf<RoundTripRun>()

        for (stack in runSequence) {
            when (stack) {
                Stack.STOCK -> {
                    clients.stockClient.connectionPool.evictAll()
                    val run = executeRoundTripRun(clients.stockClient)
                    stockRuns.add(run)
                }
                Stack.SARIE -> {
                    val run = executeRoundTripRun(clients.sarieClient)
                    sarieRuns.add(run)
                }
            }
        }

        val stockColds = stockRuns.map { it.coldMs }.toLongArray()
        val stockP50s = stockRuns.map { it.warmP50Ms }.toLongArray()
        val stockP95s = stockRuns.map { it.warmP95Ms }.toLongArray()
        val stockProtocol = stockRuns.lastOrNull()?.protocol ?: "h2"

        val firstSarieCall = sarieRuns.firstOrNull()?.firstCall
        val sarieColdMs: Long? = if (firstSarieCall != null) {
            val future = DemoLog.finishedCalls.computeIfAbsent(firstSarieCall) { java.util.concurrent.CompletableFuture() }
            val info = try {
                future.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
            if (info?.metrics?.socketReused == false) {
                sarieRuns.first().coldMs
            } else {
                null
            }
        } else {
            null
        }

        val sarieP50s = sarieRuns.map { it.warmP50Ms }.toLongArray()
        val sarieP95s = sarieRuns.map { it.warmP95Ms }.toLongArray()
        val sarieProtocol = sarieRuns.lastOrNull()?.protocol ?: "h3"

        return RoundTripResult(
            stockColdMs = Stats.median(stockColds),
            stockWarmP50Ms = Stats.median(stockP50s),
            stockWarmP95Ms = Stats.median(stockP95s),
            stockProtocol = stockProtocol,
            sarieColdMs = sarieColdMs,
            sarieWarmP50Ms = Stats.median(sarieP50s),
            sarieWarmP95Ms = Stats.median(sarieP95s),
            sarieProtocol = sarieProtocol,
        )
    }

    private fun executeRoundTripRun(client: OkHttpClient): RoundTripRun {
        val request = Request.Builder()
            .url("https://cloudflare-quic.com/")
            .head()
            .build()

        val durations = LongArray(20)
        var lastProtocol = ""
        var firstCall: Call? = null

        for (i in 0 until 20) {
            val startNs = System.nanoTime()
            val call = client.newCall(request)
            if (i == 0) {
                firstCall = call
            }
            val response = call.execute()
            response.use {
                lastProtocol = it.protocol.toString()
            }
            durations[i] = (System.nanoTime() - startNs) / 1_000_000
        }

        val coldMs = durations[0]
        val warmDurations = durations.copyOfRange(1, 20)
        val warmP50 = Stats.percentile(warmDurations, 50.0)
        val warmP95 = Stats.percentile(warmDurations, 95.0)

        return RoundTripRun(
            coldMs = coldMs,
            warmP50Ms = warmP50,
            warmP95Ms = warmP95,
            protocol = lastProtocol,
            firstCall = firstCall,
        )
    }

    data class ParallelRun(
        val wallTotalMs: Long,
        val firstImageMs: Long,
        val p50Ms: Long,
        val p95Ms: Long,
        val p99Ms: Long,
        val totalBytes: Long,
        val protocolCounts: Map<String, Int>,
        val failedCount: Int,
    )

    fun runParallelImages(
        clients: Clients,
        onImageReceived: ((index: Int, bitmap: Bitmap?, stack: Stack) -> Unit)? = null,
    ): ParallelImagesResult {
        val warmupRequest = Request.Builder()
            .url("https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=100&q=60")
            .build()
        try {
            clients.stockClient.newCall(warmupRequest).execute().close()
        } catch (_: Exception) {}
        try {
            clients.sarieClient.newCall(warmupRequest).execute().close()
        } catch (_: Exception) {}

        val runSequence = listOf(
            Stack.STOCK,
            Stack.SARIE,
            Stack.SARIE,
            Stack.STOCK,
            Stack.STOCK,
            Stack.SARIE,
        )

        val stockRuns = mutableListOf<ParallelRun>()
        val sarieRuns = mutableListOf<ParallelRun>()

        for (stack in runSequence) {
            when (stack) {
                Stack.STOCK -> {
                    val run = executeParallelRun(clients.stockClient, stack, onImageReceived)
                    stockRuns.add(run)
                }
                Stack.SARIE -> {
                    val run = executeParallelRun(clients.sarieClient, stack, onImageReceived)
                    sarieRuns.add(run)
                }
            }
        }

        val stockWallTotal = Stats.median(stockRuns.map { it.wallTotalMs }.toLongArray())
        val stockFirstImage = Stats.median(stockRuns.map { it.firstImageMs }.toLongArray())
        val stockP50 = Stats.median(stockRuns.map { it.p50Ms }.toLongArray())
        val stockP95 = Stats.median(stockRuns.map { it.p95Ms }.toLongArray())
        val stockP99 = Stats.median(stockRuns.map { it.p99Ms }.toLongArray())
        val stockBytes = Stats.median(stockRuns.map { it.totalBytes }.toLongArray())
        val stockCounts = stockRuns.lastOrNull()?.protocolCounts ?: emptyMap()
        val stockFailed = stockRuns.lastOrNull()?.failedCount ?: 0

        val sarieWallTotal = Stats.median(sarieRuns.map { it.wallTotalMs }.toLongArray())
        val sarieFirstImage = Stats.median(sarieRuns.map { it.firstImageMs }.toLongArray())
        val sarieP50 = Stats.median(sarieRuns.map { it.p50Ms }.toLongArray())
        val sarieP95 = Stats.median(sarieRuns.map { it.p95Ms }.toLongArray())
        val sarieP99 = Stats.median(sarieRuns.map { it.p99Ms }.toLongArray())
        val sarieBytes = Stats.median(sarieRuns.map { it.totalBytes }.toLongArray())
        val sarieCounts = sarieRuns.lastOrNull()?.protocolCounts ?: emptyMap()
        val sarieFailed = sarieRuns.lastOrNull()?.failedCount ?: 0

        return ParallelImagesResult(
            stockWallTotalMs = stockWallTotal,
            stockFirstImageMs = stockFirstImage,
            stockP50Ms = stockP50,
            stockP95Ms = stockP95,
            stockP99Ms = stockP99,
            stockTotalBytes = stockBytes,
            stockProtocolCounts = stockCounts,
            stockFailedCount = stockFailed,

            sarieWallTotalMs = sarieWallTotal,
            sarieFirstImageMs = sarieFirstImage,
            sarieP50Ms = sarieP50,
            sarieP95Ms = sarieP95,
            sarieP99Ms = sarieP99,
            sarieTotalBytes = sarieBytes,
            sarieProtocolCounts = sarieCounts,
            sarieFailedCount = sarieFailed,
        )
    }

    private fun executeParallelRun(
        client: OkHttpClient,
        stack: Stack,
        onImageReceived: ((index: Int, bitmap: Bitmap?, stack: Stack) -> Unit)?,
    ): ParallelRun {
        val latch = CountDownLatch(100)
        val wallStartNs = System.nanoTime()
        val lock = Any()
        var firstImageNs: Long? = null
        val durations = mutableListOf<Long>()
        val totalBytes = AtomicLong(0)
        val protocolCounts = ConcurrentHashMap<String, AtomicInteger>()
        val failedCount = AtomicInteger(0)

        for (i in 0 until 100) {
            val width = 100 + i
            val url = "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=$width&q=60"
            val request = Request.Builder().url(url).build()
            val reqStartNs = System.nanoTime()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failedCount.incrementAndGet()
                    latch.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    var success = false
                    try {
                        response.use { resp ->
                            if (!resp.isSuccessful) {
                                return
                            }
                            val bytes = resp.body?.bytes() ?: return
                            val tookMs = (System.nanoTime() - reqStartNs) / 1_000_000
                            synchronized(lock) {
                                durations.add(tookMs)
                                if (firstImageNs == null) {
                                    firstImageNs = System.nanoTime() - wallStartNs
                                }
                            }
                            totalBytes.addAndGet(bytes.size.toLong())
                            protocolCounts.computeIfAbsent(resp.protocol.toString()) { AtomicInteger() }.incrementAndGet()
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            onImageReceived?.invoke(i, bitmap, stack)
                            success = true
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (!success) {
                            failedCount.incrementAndGet()
                        }
                        latch.countDown()
                    }
                }
            })
        }

        latch.await()
        val wallTotalMs = (System.nanoTime() - wallStartNs) / 1_000_000
        val firstMs = (firstImageNs ?: 0L) / 1_000_000
        val durationArray = synchronized(lock) { durations.toLongArray() }
        val p50 = Stats.percentile(durationArray, 50.0)
        val p95 = Stats.percentile(durationArray, 95.0)
        val p99 = Stats.percentile(durationArray, 99.0)
        val countsMap = protocolCounts.mapValues { it.value.get() }

        return ParallelRun(
            wallTotalMs = wallTotalMs,
            firstImageMs = firstMs,
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            totalBytes = totalBytes.get(),
            protocolCounts = countsMap,
            failedCount = failedCount.get(),
        )
    }

    data class HostTarget(
        val shortName: String,
        val url: String,
    )

    private val SETUP_TARGETS = listOf(
        HostTarget("unsplash", "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=100&q=60"),
        HostTarget("cloudflare", "https://cloudflare-quic.com/"),
        HostTarget("google", "https://www.google.com/"),
        HostTarget("jsdelivr", "https://cdn.jsdelivr.net/npm/jquery@3.7.1/package.json"),
    )

    fun runConnectionSetup(clients: Clients): ConnectionSetupResult {
        val rows = mutableListOf<HostSetupMetrics>()

        for ((targetIndex, target) in SETUP_TARGETS.withIndex()) {
            val runSequence = if (targetIndex % 2 == 0) {
                listOf(Stack.STOCK, Stack.SARIE)
            } else {
                listOf(Stack.SARIE, Stack.STOCK)
            }

            var stockMetrics: HostSetupMetrics? = null
            var sarieMetrics: HostSetupMetrics? = null

            for (stack in runSequence) {
                when (stack) {
                    Stack.STOCK -> {
                        clients.stockClient.connectionPool.evictAll()
                        val req = Request.Builder().url(target.url).build()
                        val call = clients.stockClient.newCall(req)
                        try {
                            val resp = call.execute()
                            resp.close()
                        } catch (_: Exception) {}
                        val m = clients.stockEventListener.callMetrics[call]
                        val dns = if (m?.dnsStartMs != null && m.dnsEndMs != null) m.dnsEndMs!! - m.dnsStartMs!! else null
                        val conn = if (m?.connectStartMs != null && m.connectEndMs != null) m.connectEndMs!! - m.connectStartMs!! else null
                        val tls = if (m?.secureConnectStartMs != null && m.secureConnectEndMs != null) m.secureConnectEndMs!! - m.secureConnectStartMs!! else null
                        val ttfb = if (m?.responseHeadersStartMs != null && m.callStartMs != null) {
                            m.responseHeadersStartMs!! - m.callStartMs!!
                        } else null
                        stockMetrics = HostSetupMetrics(
                            host = target.shortName,
                            stack = "stock",
                            dnsMs = dns,
                            connMs = conn,
                            tlsMs = tls,
                            ttfbMs = ttfb,
                            socketReused = false,
                        )
                    }
                    Stack.SARIE -> {
                        val req = Request.Builder().url(target.url).build()
                        val call = clients.sarieClient.newCall(req)
                        try {
                            val resp = call.execute()
                            resp.close()
                        } catch (_: Exception) {}
                        val future = DemoLog.finishedCalls.computeIfAbsent(call) { java.util.concurrent.CompletableFuture() }
                        val info = try {
                            future.get(2, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (_: Exception) {
                            null
                        }
                        val metrics = info?.metrics
                        val reused = metrics?.socketReused ?: false
                        val dnsStart = metrics?.dnsStart
                        val dnsEnd = metrics?.dnsEnd
                        val dns = if (!reused && dnsStart != null && dnsEnd != null) {
                            dnsEnd.time - dnsStart.time
                        } else null

                        val connectStart = metrics?.connectStart
                        val connectEnd = metrics?.connectEnd
                        val conn = if (!reused && connectStart != null && connectEnd != null) {
                            connectEnd.time - connectStart.time
                        } else null

                        val sslStart = metrics?.sslStart
                        val sslEnd = metrics?.sslEnd
                        val tls = if (!reused && sslStart != null && sslEnd != null) {
                            sslEnd.time - sslStart.time
                        } else null

                        val respStart = metrics?.responseStart
                        val reqStart = metrics?.requestStart
                        val ttfb = if (respStart != null && reqStart != null) {
                            respStart.time - reqStart.time
                        } else null

                        sarieMetrics = HostSetupMetrics(
                            host = target.shortName,
                            stack = "Sarie",
                            dnsMs = dns,
                            connMs = conn,
                            tlsMs = tls,
                            ttfbMs = ttfb,
                            socketReused = reused,
                        )
                    }
                }
            }

            stockMetrics?.let { rows.add(it) }
            sarieMetrics?.let { rows.add(it) }
        }

        return ConnectionSetupResult(rows)
    }

    fun runDownloadMigration(
        clients: Clients,
        onStockProgress: (DownloadProgress) -> Unit,
        onSarieProgress: (DownloadProgress) -> Unit,
    ) {
        val url = "https://cdn.jsdelivr.net/npm/typescript@5.6.3/lib/typescript.js"
        val request = Request.Builder().url(url).build()
        val latch = CountDownLatch(2)
        val threadPool = java.util.concurrent.Executors.newFixedThreadPool(2)

        // Stock download
        threadPool.execute {
            var bytesRead = 0L
            var totalBytes = -1L
            try {
                clients.stockClient.connectionPool.evictAll()
                val call = clients.stockClient.newCall(request)
                val response = call.execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onStockProgress(DownloadProgress(0, -1, true, "HTTP ${resp.code}"))
                        return@execute
                    }
                    val body = resp.body ?: run {
                        onStockProgress(DownloadProgress(0, -1, true, "Empty body"))
                        return@execute
                    }
                    totalBytes = body.contentLength()
                    val source = body.source()
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (source.read(buffer).also { read = it } != -1) {
                        bytesRead += read
                        onStockProgress(DownloadProgress(bytesRead, totalBytes, false, null))
                    }
                    onStockProgress(DownloadProgress(bytesRead, totalBytes, true, null))
                }
            } catch (e: Exception) {
                onStockProgress(DownloadProgress(bytesRead, totalBytes, true, e.message ?: e.javaClass.simpleName))
            } finally {
                latch.countDown()
            }
        }

        // Sarie download
        threadPool.execute {
            var bytesRead = 0L
            var totalBytes = -1L
            try {
                val call = clients.sarieClient.newCall(request)
                val response = call.execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onSarieProgress(DownloadProgress(0, -1, true, "HTTP ${resp.code}"))
                        return@execute
                    }
                    val body = resp.body ?: run {
                        onSarieProgress(DownloadProgress(0, -1, true, "Empty body"))
                        return@execute
                    }
                    totalBytes = body.contentLength()
                    val source = body.source()
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (source.read(buffer).also { read = it } != -1) {
                        bytesRead += read
                        onSarieProgress(DownloadProgress(bytesRead, totalBytes, false, null))
                    }
                    onSarieProgress(DownloadProgress(bytesRead, totalBytes, true, null))
                }
            } catch (e: Exception) {
                onSarieProgress(DownloadProgress(bytesRead, totalBytes, true, e.message ?: e.javaClass.simpleName))
            } finally {
                latch.countDown()
            }
        }

        latch.await()
        threadPool.shutdown()
    }
}
