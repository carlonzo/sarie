package sarie.plugin

/**
 * One OkHttp class the plugin may rewrite. ConnectInterceptor is a full replace.
 * CallServerInterceptor keeps its stock body behind a prefix. The two cache classes
 * each replace a single `isHttps` invoke.
 */
enum class InstrumentTarget(
    val dotName: String,
    val internalName: String,
    val classEntry: String,
    val fileName: String,
) {
    CONNECT_INTERCEPTOR(
        dotName = "okhttp3.internal.connection.ConnectInterceptor",
        internalName = "okhttp3/internal/connection/ConnectInterceptor",
        classEntry = "okhttp3/internal/connection/ConnectInterceptor.class",
        fileName = "ConnectInterceptor.class",
    ),
    CALL_SERVER_INTERCEPTOR(
        dotName = "okhttp3.internal.http.CallServerInterceptor",
        internalName = "okhttp3/internal/http/CallServerInterceptor",
        classEntry = "okhttp3/internal/http/CallServerInterceptor.class",
        fileName = "CallServerInterceptor.class",
    ),
    CACHE_ENTRY(
        dotName = "okhttp3.Cache\$Entry",
        internalName = "okhttp3/Cache\$Entry",
        classEntry = "okhttp3/Cache\$Entry.class",
        fileName = "Cache\$Entry.class",
    ),
    CACHE_STRATEGY_FACTORY(
        dotName = "okhttp3.internal.cache.CacheStrategy\$Factory",
        internalName = "okhttp3/internal/cache/CacheStrategy\$Factory",
        classEntry = "okhttp3/internal/cache/CacheStrategy\$Factory.class",
        fileName = "CacheStrategy\$Factory.class",
    ),
}

/** Registered rewrite sites. [isTarget] is the exclusive instrumentation filter. */
internal object InstrumentationTargets {
    const val INTERCEPT_NAME: String = "intercept"
    const val INTERCEPT_DESC: String = "(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"
    const val BRIDGE_OWNER: String = "sarie/bridge/CronetBridge"
    const val CALL_SERVER_METHOD: String = "callServer"
    const val CACHE_HOOKS_OWNER: String = "sarie/bridge/CacheHooks"
    const val CACHE_ENTRY_INIT: String = "<init>"
    const val CACHE_ENTRY_INIT_DESC: String = "(Lokio/Source;)V"
    const val EXPECT_TLS_BLOCK: String = "expectTlsBlock"
    const val EXPECT_TLS_BLOCK_DESC: String = "(Lokhttp3/HttpUrl;Lokio/BufferedSource;)Z"
    const val COMPUTE_CANDIDATE: String = "computeCandidate"
    const val COMPUTE_CANDIDATE_DESC: String = "()Lokhttp3/internal/cache/CacheStrategy;"
    const val REQUIRE_HANDSHAKE: String = "requireHandshake"
    const val REQUIRE_HANDSHAKE_DESC: String = "(Lokhttp3/Request;)Z"
    /** `Okio.buffer` result in `Cache.Entry.<init>(Source)`, confirmed by javap on 5.4.0 and 5.5.0. */
    const val CACHE_SOURCE_LOCAL: Int = 6

    fun byDotName(className: String): InstrumentTarget? =
        InstrumentTarget.entries.firstOrNull { it.dotName == className }

    fun byInternalName(className: String): InstrumentTarget? =
        InstrumentTarget.entries.firstOrNull { it.internalName == className }

    fun isTarget(className: String): Boolean = byDotName(className) != null
}
