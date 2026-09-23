package sarie.plugin

/**
 * One OkHttp class the plugin may rewrite. ConnectInterceptor is a full replace;
 * CallServerInterceptor keeps its stock body behind a prefix.
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
}

/** Registered rewrite sites. [isTarget] is the exclusive instrumentation filter. */
internal object InstrumentationTargets {
    const val INTERCEPT_NAME: String = "intercept"
    const val INTERCEPT_DESC: String = "(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"
    const val BRIDGE_OWNER: String = "sarie/bridge/CronetBridge"
    const val CALL_SERVER_METHOD: String = "callServer"

    fun byDotName(className: String): InstrumentTarget? =
        InstrumentTarget.entries.firstOrNull { it.dotName == className }

    fun byInternalName(className: String): InstrumentTarget? =
        InstrumentTarget.entries.firstOrNull { it.internalName == className }

    fun isTarget(className: String): Boolean = byDotName(className) != null
}
