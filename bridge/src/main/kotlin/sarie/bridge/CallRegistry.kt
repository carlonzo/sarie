@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.internal.connection.RealCall
import org.chromium.net.UrlRequest

/**
 * Tracks in-flight Cronet-path calls so a cancel from any thread reaches the engine request
 * exactly once.
 *
 * Delivery comes from [notifyCanceled], which the bytecode rewrite appends to
 * `RealCall.cancel()` ([CronetBridge.notifyCanceled]). OkHttp's own `EventListener` is not used:
 * `RealCall.addEventListener` rebuilds an aggregate listener inside a CAS retry loop, which is
 * per-call garbage the bridge can avoid now that the cancel path is a direct static call.
 *
 * [notifyCanceled] runs for EVERY cancel in the process, including calls Sarie never routed and
 * calls that already reached a terminal path, so it must stay a cheap no-op when [requests] holds
 * no entry for the call. After [unregister] (terminal) the entry is gone, which is what makes a
 * late cancel inert.
 */
internal object CallRegistry {

    private val requests = ConcurrentHashMap<RealCall, RegisteredRequest>()

    class RegisteredRequest internal constructor(
        val urlRequestRef: AtomicReference<UrlRequest?>,
    ) {
        private val cancelDelivered = AtomicBoolean(false)

        /** Delivers at most one [UrlRequest.cancel]; no-op while the ref is empty. */
        fun cancelUrlRequestOnce() {
            if (cancelDelivered.compareAndSet(false, true)) {
                urlRequestRef.get()?.cancel()
            }
        }
    }

    class Registration internal constructor(
        private val call: RealCall,
        private val registered: RegisteredRequest,
    ) {
        /** Used by the ordered protocol re-checks; shares the single-delivery guard. */
        fun cancelUrlRequestOnce() = registered.cancelUrlRequestOnce()

        /**
         * Post-terminal teardown, scoped to *this* attempt.
         *
         * A value-aware remove matters: an earlier attempt that closes late (a transport-failure
         * retry, a body closed after the follow-up already started) must not tear down the newer
         * attempt's registration, which would leave a live engine request uncancellable.
         */
        fun unregister() {
            requests.remove(call, registered)
        }
    }

    /**
     * Publishes [call] as cancellable. Re-registering the same call (the transport-failure retry)
     * replaces the entry: the previous attempt's guard is spent, so the fresh attempt gets its own
     * single cancel.
     */
    fun register(call: RealCall, urlRequestRef: AtomicReference<UrlRequest?>): Registration {
        val registered = RegisteredRequest(urlRequestRef)
        requests[call] = registered
        return Registration(call, registered)
    }

    /**
     * The cancel delivery the rewritten `RealCall.cancel()` hands us: at most one engine cancel,
     * then the entry is dropped. A no-op for a call Sarie never routed, or one that already
     * unregistered; the single [ConcurrentHashMap] removal covers both.
     */
    fun notifyCanceled(call: RealCall) {
        requests.remove(call)?.cancelUrlRequestOnce()
    }
}
