package sarie.demo

import okhttp3.OkHttpClient
import okhttp3.Request

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

object Scenarios {

    private var sarieColdCaptured = false
    private var sarieInitialColdMs: Long? = null

    enum class Stack { STOCK, SARIE }

    data class RoundTripRun(
        val coldMs: Long,
        val warmP50Ms: Long,
        val warmP95Ms: Long,
        val protocol: String,
        val isColdReal: Boolean,
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
                    val run = executeRoundTripRun(clients.stockClient, isSarie = false)
                    stockRuns.add(run)
                }
                Stack.SARIE -> {
                    val run = executeRoundTripRun(clients.sarieClient, isSarie = true)
                    sarieRuns.add(run)
                }
            }
        }

        val stockColds = stockRuns.map { it.coldMs }.toLongArray()
        val stockP50s = stockRuns.map { it.warmP50Ms }.toLongArray()
        val stockP95s = stockRuns.map { it.warmP95Ms }.toLongArray()
        val stockProtocol = stockRuns.lastOrNull()?.protocol ?: "h2"

        val sarieP50s = sarieRuns.map { it.warmP50Ms }.toLongArray()
        val sarieP95s = sarieRuns.map { it.warmP95Ms }.toLongArray()
        val sarieProtocol = sarieRuns.lastOrNull()?.protocol ?: "h3"

        return RoundTripResult(
            stockColdMs = Stats.median(stockColds),
            stockWarmP50Ms = Stats.median(stockP50s),
            stockWarmP95Ms = Stats.median(stockP95s),
            stockProtocol = stockProtocol,
            sarieColdMs = sarieInitialColdMs,
            sarieWarmP50Ms = Stats.median(sarieP50s),
            sarieWarmP95Ms = Stats.median(sarieP95s),
            sarieProtocol = sarieProtocol,
        )
    }

    private fun executeRoundTripRun(client: OkHttpClient, isSarie: Boolean): RoundTripRun {
        val request = Request.Builder()
            .url("https://cloudflare-quic.com/")
            .head()
            .build()

        val durations = LongArray(20)
        var lastProtocol = ""

        for (i in 0 until 20) {
            val startNs = System.nanoTime()
            val response = client.newCall(request).execute()
            response.use {
                lastProtocol = it.protocol.toString()
            }
            durations[i] = (System.nanoTime() - startNs) / 1_000_000
        }

        val coldMs = durations[0]
        val warmDurations = durations.copyOfRange(1, 20)
        val warmP50 = Stats.percentile(warmDurations, 50.0)
        val warmP95 = Stats.percentile(warmDurations, 95.0)

        var isColdReal = true
        if (isSarie) {
            synchronized(this) {
                if (!sarieColdCaptured) {
                    sarieColdCaptured = true
                    sarieInitialColdMs = coldMs
                    isColdReal = true
                } else {
                    isColdReal = false
                }
            }
        }

        return RoundTripRun(
            coldMs = coldMs,
            warmP50Ms = warmP50,
            warmP95Ms = warmP95,
            protocol = lastProtocol,
            isColdReal = isColdReal,
        )
    }
}
