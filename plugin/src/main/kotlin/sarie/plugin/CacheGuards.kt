package sarie.plugin

/**
 * Stock shape of the two cache call sites, identical on okhttp-android and okhttp-jvm
 * for 5.4.0 and 5.5.0 (javap). Each targeted method has exactly one `isHttps`.
 * `Cache.Entry.writeTo` has the other `HttpUrl.isHttps` and is not a target.
 *
 * `computeCandidate` compiles `request.isHttps && cacheResponse.handshake == null` as
 * `isHttps; ifeq L; aload_0; getfield cacheResponse; handshake; ifnonnull L`. The `ifeq`
 * sits between `isHttps` and `handshake()`; both jumps share L. The guard pins that
 * window. The rewrite replaces only the `isHttps` invoke.
 */
internal object CacheEntryGuard {
    const val HTTPS: String = "INVOKEVIRTUAL okhttp3/HttpUrl.isHttps ()Z"
    const val BUFFER: String = "INVOKESTATIC okio/Okio.buffer (Lokio/Source;)Lokio/BufferedSource;"
    const val STORE_LOCAL: String = "ASTORE ${InstrumentationTargets.CACHE_SOURCE_LOCAL}"

    fun verify(insns: List<String>): List<String> {
        val problems = mutableListOf<String>()
        val httpsCount = insns.count { it == HTTPS }
        if (httpsCount != 1) {
            problems += "expected exactly one HttpUrl.isHttps in Cache.Entry.<init>(Source), found $httpsCount"
        }
        val storeCount = insns.count { it == STORE_LOCAL }
        if (storeCount != 1) {
            problems += "expected exactly one $STORE_LOCAL (Okio.buffer result), found $storeCount"
        }
        val bufferAt = insns.indexOf(BUFFER)
        if (bufferAt < 0 || bufferAt + 1 >= insns.size || insns[bufferAt + 1] != STORE_LOCAL) {
            problems += "local ${InstrumentationTargets.CACHE_SOURCE_LOCAL} is not the Okio.buffer result"
        }
        val httpsAt = insns.indexOf(HTTPS)
        if (bufferAt >= 0 && httpsAt >= 0 && bufferAt > httpsAt) {
            problems += "Okio.buffer store must precede HttpUrl.isHttps"
        }
        return problems
    }
}

internal object CacheStrategyGuard {
    const val HTTPS: String = "INVOKEVIRTUAL okhttp3/Request.isHttps ()Z"
    const val HANDSHAKE_FIELD: String =
        "GETFIELD okhttp3/internal/cache/CacheStrategy\$Factory.cacheResponse Lokhttp3/Response;"
    const val HANDSHAKE: String = "INVOKEVIRTUAL okhttp3/Response.handshake ()Lokhttp3/Handshake;"

    fun verify(insns: List<String>): List<String> {
        val problems = mutableListOf<String>()
        val hits = insns.indices.filter { insns[it] == HTTPS }
        if (hits.size != 1) {
            problems += "expected exactly one Request.isHttps in computeCandidate, found ${hits.size}"
            return problems
        }
        val at = hits[0]
        if (at + 5 >= insns.size || !handshakeWindow(insns, at)) {
            problems += "Request.isHttps must be followed by the handshake(); ifnonnull sequence"
        }
        return problems
    }

    private fun handshakeWindow(insns: List<String>, at: Int): Boolean {
        val ifeq = insns[at + 1]
        val ifnn = insns[at + 5]
        if (!ifeq.startsWith("IFEQ ") || !ifnn.startsWith("IFNONNULL ")) return false
        if (ifeq.removePrefix("IFEQ ") != ifnn.removePrefix("IFNONNULL ")) return false
        return insns[at + 2] == "ALOAD 0" &&
            insns[at + 3] == HANDSHAKE_FIELD &&
            insns[at + 4] == HANDSHAKE
    }
}
