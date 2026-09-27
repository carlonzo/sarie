package sarie.demo

import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** One request's connection phases. Null phases were not reported (or the connection was reused). */
data class ConnSample(
    val dnsMs: Long?,
    val connMs: Long?,
    val tlsMs: Long?,
    val ttfbMs: Long?,
    val reused: Boolean,
    val protocol: String,
    val error: String? = null,
)

data class StackBench(
    val first: ConnSample? = null,
    val warmP50Ms: Long? = null,
    val resumed: ConnSample? = null,
)

data class HostBench(
    val host: String,
    val stock: StackBench = StackBench(),
    val sarie: StackBench = StackBench(),
)

/**
 * Stock vs Sarie per URL, in three phases:
 * - first: first request of the run. Stock always opens a new connection (pool evicted); Sarie opens
 *   one unless a QUIC session is still alive from a previous run (then "reused").
 * - warm: [WARM_REQUESTS] back-to-back requests on the open connection; p50 of time to headers.
 * - resumed: after every connection is gone (QUIC idle timeout, OkHttp evictAll), a new connection
 *   with in-process resumption state: TLS session ticket for stock, QUIC 0-RTT for Sarie.
 */
object ConnectionBench {

    const val WARM_REQUESTS = 10

    /** Demo engine's QUIC idle timeout; see [DemoApp]. The resumed phase waits it out. */
    const val QUIC_IDLE_SECONDS = 4

    fun run(
        clients: Clients,
        urls: List<String>,
        onUpdate: (rows: List<HostBench>, status: String) -> Unit,
    ): List<HostBench> {
        DemoLog.finishedCalls.clear()
        clients.stockEventListener.callMetrics.clear()

        val rows = urls.map { HostBench(host = it.toHttpUrl().host) }.toMutableList()
        fun publish(status: String) = onUpdate(rows.toList(), status)

        for ((i, url) in urls.withIndex()) {
            // Both stacks resolve through the same OS resolver (getaddrinfo), so whichever ran
            // first would otherwise absorb the entire cold DNS lookup and make the other look
            // artificially faster. Warm it once, outside either stack's numbers, so "first" is
            // measuring connection setup, not a DNS race.
            warmDns(rows[i].host)
            val order = if (i % 2 == 0) listOf(true, false) else listOf(false, true)
            for (isStock in order) {
                publish("${rows[i].host}: ${if (isStock) "stock" else "Sarie"}")
                val first = if (isStock) {
                    clients.stockClient.connectionPool.evictAll()
                    measureStock(clients, url)
                } else {
                    measureSarie(clients, url)
                }
                val warm = LongArray(WARM_REQUESTS) {
                    timeToHeaders(if (isStock) clients.stockClient else clients.sarieClient, url)
                }
                val bench = StackBench(first = first, warmP50Ms = Stats.percentile(warm, 50.0))
                rows[i] = if (isStock) rows[i].copy(stock = bench) else rows[i].copy(sarie = bench)
                publish("${rows[i].host}: ${if (isStock) "stock" else "Sarie"}")
            }
        }

        publish("waiting ${QUIC_IDLE_SECONDS + 1}s for QUIC sessions to idle out…")
        Thread.sleep((QUIC_IDLE_SECONDS + 1) * 1_000L)
        clients.stockClient.connectionPool.evictAll()

        for ((i, url) in urls.withIndex()) {
            val order = if (i % 2 == 0) listOf(true, false) else listOf(false, true)
            for (isStock in order) {
                publish("${rows[i].host}: ${if (isStock) "stock" else "Sarie"} resumed")
                rows[i] = if (isStock) {
                    rows[i].copy(stock = rows[i].stock.copy(resumed = measureStock(clients, url)))
                } else {
                    rows[i].copy(sarie = rows[i].sarie.copy(resumed = measureSarie(clients, url)))
                }
            }
        }

        publish("done")
        return rows
    }

    private fun warmDns(host: String) {
        try {
            InetAddress.getAllByName(host)
        } catch (_: Exception) {
            // Left to the real request: it'll surface as a normal error row instead.
        }
    }

    private fun execute(call: Call): Pair<String, String?> = try {
        call.execute().use { it.protocol.toString() to null }
    } catch (e: Exception) {
        "error" to (e.message ?: e.javaClass.simpleName)
    }

    private fun timeToHeaders(client: OkHttpClient, url: String): Long {
        val start = System.nanoTime()
        execute(client.newCall(Request.Builder().url(url).build()))
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun measureStock(clients: Clients, url: String): ConnSample {
        val call = clients.stockClient.newCall(Request.Builder().url(url).build())
        val (protocol, error) = execute(call)
        val m = clients.stockEventListener.callMetrics.remove(call) ?: StockCallMetrics()
        fun d(a: Long?, b: Long?) = if (a != null && b != null) b - a else null
        return ConnSample(
            dnsMs = d(m.dnsStartMs, m.dnsEndMs),
            connMs = d(m.connectStartMs, m.connectEndMs),
            tlsMs = d(m.secureConnectStartMs, m.secureConnectEndMs),
            ttfbMs = d(m.callStartMs, m.responseHeadersStartMs),
            reused = m.connectStartMs == null,
            protocol = protocol,
            error = error,
        )
    }

    private fun measureSarie(clients: Clients, url: String): ConnSample {
        val call = clients.sarieClient.newCall(Request.Builder().url(url).build())
        val (protocol, error) = execute(call)
        val future = DemoLog.finishedCalls.computeIfAbsent(call) { CompletableFuture() }
        val t = try {
            future.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } finally {
            DemoLog.finishedCalls.remove(call)
        }
        val reused = t?.socketReused ?: false
        val ttfb = if (t?.responseStartAtMillis != null && t.requestStartAtMillis != null) {
            t.responseStartAtMillis!! - t.requestStartAtMillis!!
        } else {
            t?.ttfbMs
        }
        return ConnSample(
            dnsMs = if (reused) null else t?.dnsMs,
            connMs = if (reused) null else t?.connectMs,
            tlsMs = if (reused) null else t?.tlsMs,
            ttfbMs = ttfb,
            reused = reused,
            protocol = t?.negotiatedProtocol ?: protocol,
            error = error,
        )
    }
}
