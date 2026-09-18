package dev.okhttpcronet.plugin

internal const val TRAMPOLINE_DESC: String = "(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"

internal fun stock(variant: String): ByteArray =
    checkNotNull(ConnectInterceptorRewriterTest::class.java.getResourceAsStream("/stock/$variant/ConnectInterceptor.class")) {
        "missing golden resource /stock/$variant/ConnectInterceptor.class"
    }.readBytes()
