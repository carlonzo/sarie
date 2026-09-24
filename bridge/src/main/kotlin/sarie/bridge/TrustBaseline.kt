package sarie.bridge

import java.net.ProxySelector
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.IdentityHashMap
import javax.net.SocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Platform TLS trust baseline captured ONCE per process (lazy, thread-safe) from a stock
 * [OkHttpClient] built in-process.
 *
 * Metis B1: platform-default SSL factory / trust manager instances are freshly created per
 * client, so identity comparison against them is impossible. The baseline therefore stores
 * CLASSES plus an acceptedIssuers SHA-256 fingerprint, and trust verdicts are memoized per
 * [X509TrustManager] instance in a synchronized map capped at 1024 entries (clear-on-overflow).
 */
internal object TrustBaseline {

    class Baseline(
        val sslFactoryClass: Class<*>,
        val trustManagerClass: Class<*>,
        val trustFingerprint: String,
        /** The stock client's proxySelector instance; identity baseline for the proxy rule. */
        val proxySelector: ProxySelector,
        /** [SocketFactory.getDefault] class, captured once so the hot path skips that lock. */
        val socketFactoryClass: Class<*>,
    )

    class Verdict(val managerClass: Class<*>, val fingerprint: String)

    internal val baseline: Baseline by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val stock = OkHttpClient()
        val stockTm = requireNotNull(stock.x509TrustManager) { "stock client must have platform TLS" }
        Baseline(
            sslFactoryClass = stock.sslSocketFactory.javaClass,
            trustManagerClass = stockTm.javaClass,
            trustFingerprint = acceptedIssuersFingerprint(stockTm),
            proxySelector = stock.proxySelector,
            socketFactoryClass = SocketFactory.getDefault().javaClass,
        )
    }

    private val memo = TrustVerdictMemo(1024)

    /**
     * Memoized (class, fingerprint) verdict for [trustManager]. The last manager is served
     * from the memo cache without taking the lock. Recomputed only after overflow.
     */
    fun verdictFor(trustManager: X509TrustManager): Verdict = memo.verdictFor(trustManager)

    /**
     * SHA-256 over the DER-encoded acceptedIssuers sorted by encoded bytes. ByteBuffer.compareTo
     * is unsigned lexicographic and available from Android API 1 (java.util.Arrays.compare is not).
     */
    fun acceptedIssuersFingerprint(trustManager: X509TrustManager): String {
        val digest = MessageDigest.getInstance("SHA-256")
        trustManager.acceptedIssuers
            .map { it.encoded }
            .sortedWith { a, b -> ByteBuffer.wrap(a).compareTo(ByteBuffer.wrap(b)) }
            .forEach { digest.update(it) }
        return digest.digest().toHexString()
    }
}

internal class TrustVerdictMemo(private val max: Int = 1024) {
    private val verdicts = IdentityHashMap<X509TrustManager, TrustBaseline.Verdict>()
    private val verdictsLock = Any()

    private class Seen(val manager: X509TrustManager, val verdict: TrustBaseline.Verdict)

    @Volatile
    private var lastSeen: Seen? = null

    /**
     * Memoized (class, fingerprint) verdict for [trustManager]. The last manager is served
     * from [lastSeen] without taking [verdictsLock]. Recomputed only after overflow.
     */
    fun verdictFor(trustManager: X509TrustManager): TrustBaseline.Verdict {
        val seen = lastSeen
        if (seen != null && seen.manager === trustManager) return seen.verdict
        return synchronized(verdictsLock) {
            val again = lastSeen
            if (again != null && again.manager === trustManager) return again.verdict
            val verdict = verdicts[trustManager] ?: run {
                val computed = TrustBaseline.Verdict(
                    trustManager.javaClass,
                    TrustBaseline.acceptedIssuersFingerprint(trustManager),
                )
                if (verdicts.size >= max) verdicts.clear()
                verdicts[trustManager] = computed
                computed
            }
            lastSeen = Seen(trustManager, verdict)
            verdict
        }
    }
}
