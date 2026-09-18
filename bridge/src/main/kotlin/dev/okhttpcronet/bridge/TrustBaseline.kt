package dev.okhttpcronet.bridge

import java.net.ProxySelector
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.IdentityHashMap
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
object TrustBaseline {

    data class Baseline(
        val sslFactoryClass: Class<*>,
        val trustManagerClass: Class<*>,
        val trustFingerprint: String,
        /** The stock client's proxySelector instance; identity baseline for the proxy rule. */
        val proxySelector: ProxySelector,
    )

    data class Verdict(val managerClass: Class<*>, val fingerprint: String)

    internal val baseline: Baseline by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val stock = OkHttpClient()
        val stockTm = requireNotNull(stock.x509TrustManager) { "stock client must have platform TLS" }
        Baseline(
            sslFactoryClass = stock.sslSocketFactory.javaClass,
            trustManagerClass = stockTm.javaClass,
            trustFingerprint = acceptedIssuersFingerprint(stockTm),
            proxySelector = stock.proxySelector,
        )
    }

    private val verdicts = IdentityHashMap<X509TrustManager, Verdict>()
    private val verdictsLock = Any()

    private const val MEMO_MAX = 1024

    /** Memoized (class, fingerprint) verdict for [trustManager]; recomputed only after overflow. */
    fun verdictFor(trustManager: X509TrustManager): Verdict = synchronized(verdictsLock) {
        verdicts[trustManager] ?: run {
            val verdict = Verdict(trustManager.javaClass, acceptedIssuersFingerprint(trustManager))
            if (verdicts.size >= MEMO_MAX) verdicts.clear()
            verdicts[trustManager] = verdict
            verdict
        }
    }

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
        return digest.digest().toHex()
    }

    internal fun memoizedCount(): Int = synchronized(verdictsLock) { verdicts.size }

    internal fun clearMemoForTest() = synchronized(verdictsLock) { verdicts.clear() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
