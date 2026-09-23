package sarie.plugin

/**
 * Stock shape of `CallServerInterceptor.intercept` for the pinned OkHttp family.
 *
 * The prefix is finding F7 (identical on okhttp-android and okhttp-jvm for 5.4.0 and 5.5.0):
 * the Kotlin null-check preamble, the cast to RealInterceptorChain, and the
 * `getExchange$okhttp` / `checkNotNull` that stock uses before touching the exchange.
 * Everything after that prefix is pinned by a SHA-256 of the stable instruction stream
 * (labels numbered in encounter order). A drift in either half fails the build.
 */
internal object CallServerGuard {
    val PREFIX: List<String> = listOf(
        "ALOAD 1",
        "LDC chain",
        "INVOKESTATIC kotlin/jvm/internal/Intrinsics.checkNotNullParameter (Ljava/lang/Object;Ljava/lang/String;)V",
        "ALOAD 1",
        "CHECKCAST okhttp3/internal/http/RealInterceptorChain",
        "ASTORE 2",
        "ALOAD 2",
        "INVOKEVIRTUAL okhttp3/internal/http/RealInterceptorChain.getExchange\$okhttp ()Lokhttp3/internal/connection/Exchange;",
        "DUP",
        "INVOKESTATIC kotlin/jvm/internal/Intrinsics.checkNotNull (Ljava/lang/Object;)V",
        "ASTORE 3",
    )

    /**
     * SHA-256 of the instruction stream after [PREFIX], newline-joined, UTF-8.
     * Recomputed by the guard tests when the pinned OkHttp family changes; do not
     * widen the prefix or drop this check to admit a new shape.
     */
    const val BODY_SHA256: String = "5278fb7ce3364131cc01dcae306a9959555df1ce870fc50f2d0d53935dd9a659"

    /** Instructions the prefix injection consumes before splicing. The Kotlin preamble only. */
    const val PREAMBLE_INSNS: Int = 3

    fun verify(insns: List<String>): List<String> {
        val problems = mutableListOf<String>()
        if (insns.size < PREFIX.size || insns.subList(0, PREFIX.size) != PREFIX) {
            problems += "expected CallServerInterceptor.intercept to start with the pinned F7 prefix"
        }
        val body = if (insns.size >= PREFIX.size) insns.drop(PREFIX.size) else insns
        val actual = Fingerprint.sha256Hex(body.joinToString("\n").toByteArray(Charsets.UTF_8))
        if (actual != BODY_SHA256) {
            problems += "CallServerInterceptor.intercept stock body shape mismatch " +
                "(expected=$BODY_SHA256 actual=$actual)"
        }
        return problems
    }
}
