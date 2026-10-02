@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl
import okhttp3.internal.connection.RealCall

/**
 * One intercept → proceed cycle on the Cronet path.
 *
 * A redirect re-enters [CronetBridge.intercept] on the same [RealCall] after the previous
 * body is closed. That is a new cycle, not a second proceed. Cancellation stays in
 * [CallRegistry]; this map only holds the routing checks the null exchange skips.
 *
 * A hop costs three map operations, not five: [open] publishes the [State], the terminal hop
 * takes it back with a single [current] and claims the arrival on the object it already holds,
 * and [close] drops it. The claim and the reachability check are plain reads of the returned
 * [State], so they need no further lookup.
 */
internal object RoutedCycle {

    class State internal constructor(
        val scheme: String,
        val host: String,
        val port: Int,
    ) {
        private val terminalReached = AtomicBoolean(false)

        /** First terminal arrival wins; a later arrival in the same cycle is rejected. */
        fun claimTerminal(): Boolean = terminalReached.compareAndSet(false, true)

        fun reachedTerminal(): Boolean = terminalReached.get()
    }

    private val cycles = ConcurrentHashMap<RealCall, State>()

    /**
     * Starts a cycle for [call] and returns it. The caller holds the [State] for the whole
     * cycle, so the checks that follow `proceed()` cost no map operation.
     */
    fun open(call: RealCall, url: HttpUrl): State =
        State(url.scheme, url.host, url.port).also { cycles[call] = it }

    /** The terminal hop's single lookup: its cycle, or null when it was never routed. */
    fun current(call: RealCall): State? = cycles[call]

    fun close(call: RealCall) {
        cycles.remove(call)
    }
}
