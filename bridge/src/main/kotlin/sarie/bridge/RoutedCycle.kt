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
 */
internal object RoutedCycle {
    class State(
        val scheme: String,
        val host: String,
        val port: Int,
        val terminalReached: AtomicBoolean = AtomicBoolean(false),
    )

    private val cycles = ConcurrentHashMap<RealCall, State>()

    fun open(call: RealCall, url: HttpUrl) {
        cycles[call] = State(url.scheme, url.host, url.port)
    }

    fun current(call: RealCall): State? = cycles[call]

    /** First terminal arrival wins. A second arrival in the same cycle returns false. */
    fun arrive(call: RealCall): Boolean =
        cycles[call]?.terminalReached?.compareAndSet(false, true) == true

    fun reached(call: RealCall): Boolean = cycles[call]?.terminalReached?.get() == true

    fun close(call: RealCall) {
        cycles.remove(call)
    }

    internal fun clearForTest() = cycles.clear()
}
