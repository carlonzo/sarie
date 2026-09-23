package sarie.bridge

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Request
import okio.BufferedSource

/**
 * Call-site hooks the plugin splices into OkHttp's cache reader.
 *
 * [expectTlsBlock] replaces the single `HttpUrl.isHttps` in `Cache.Entry.<init>(Source)`.
 * [requireHandshake] replaces the single `Request.isHttps` in
 * `CacheStrategy.Factory.computeCandidate`. Neither fabricates a [okhttp3.Handshake]:
 * a Cronet response is stored with a null handshake (OkHttp already omits the TLS block),
 * and a hit serves that null. With Sarie off, both hooks are the stock checks, so an entry
 * written without a TLS block fails the stock read and is a miss.
 *
 * Not Cronet callbacks. [expectTlsBlock] may read the cache source; that happens on the
 * call thread inside OkHttp's cache lookup, which is allowed to block.
 */
object CacheHooks {
    /**
     * Stock reads a TLS block iff the URL is https. When Sarie is on and the metadata
     * source is already exhausted, the entry has no TLS block and the read must be skipped.
     * [BufferedSource.exhausted] only fills the buffer; it does not consume a following block.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun expectTlsBlock(url: HttpUrl, source: BufferedSource): Boolean =
        url.isHttps && !(SarieBridge.isEnabled() && source.exhausted())

    /** Stock rejects an https cache hit whose handshake is null. Sarie hits are exactly that. */
    @JvmStatic
    fun requireHandshake(request: Request): Boolean =
        request.isHttps && !SarieBridge.isEnabled()
}
