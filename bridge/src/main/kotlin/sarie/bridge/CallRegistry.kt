@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.internal.connection.RealCall
import org.chromium.net.UrlRequest

/**
 * Tracks in-flight Cronet-path calls so a cancel from any thread reaches the engine request
 * exactly once. The listener is attached through the public [Call.addEventListener]; after
 * [unregister] (terminal) it is a no-op and holds no references.
 */
internal object CallRegistry {

    private val requests = ConcurrentHashMap<RealCall, RegisteredRequest>()

    class RegisteredRequest internal constructor(
        internal val call: RealCall,
        val urlRequestRef: AtomicReference<UrlRequest?>,
    ) {
        private val cancelDelivered = AtomicBoolean(false)

        internal val listener = Listener(this)

        /** Delivers at most one [UrlRequest.cancel]; no-op while the ref is empty. */
        fun cancelUrlRequestOnce() {
            if (cancelDelivered.compareAndSet(false, true)) {
                urlRequestRef.get()?.cancel()
            }
        }
    }

    internal class Listener(target: RegisteredRequest) : EventListener() {
        @Volatile
        private var target: RegisteredRequest? = target

        override fun canceled(call: Call) {
            val current = target ?: return // post-terminal: no-op
            current.cancelUrlRequestOnce()
            unregister(current.call)
        }

        /** Post-terminal: drop the reference so the listener keeps nothing alive. */
        fun deactivate() {
            target = null
        }
    }

    class ListenerHandle internal constructor(private val registered: RegisteredRequest) {
        /** Used by the ordered protocol re-checks; shares the single-delivery guard. */
        fun cancelUrlRequestOnce() = registered.cancelUrlRequestOnce()
    }

    fun register(call: RealCall, urlRequestRef: AtomicReference<UrlRequest?>): ListenerHandle {
        val registered = RegisteredRequest(call, urlRequestRef)
        // Map first, listener second: a cancel landing in between is caught by the protocol's
        // re-checks instead of racing an unattached listener delivery.
        requests[call] = registered
        call.addEventListener(registered.listener)
        return ListenerHandle(registered)
    }

    /** Post-terminal teardown: entry dropped, listener inert. Idempotent. */
    fun unregister(call: RealCall) {
        requests.remove(call)?.listener?.deactivate()
    }

    internal fun activeCount(): Int = requests.size

    internal fun clearForTest() = requests.clear()
}
