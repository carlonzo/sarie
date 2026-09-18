@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(okhttp3.internal.OkHttpInternalApi::class)

package dev.okhttpcronet.bridge

import java.net.Proxy
import java.net.ProxySelector
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.RealInterceptorChain

/**
 * Effective per-request routing inputs for [PolicyEngine].
 *
 * Chain-effective configuration (everything [okhttp3.Interceptor.Chain] publicly exposes in
 * OkHttp 5.5.0, i.e. what an application interceptor may have adjusted via `withX` before
 * ConnectInterceptor) is read from the chain. `networkInterceptors`, `protocols` and
 * `forWebSocket` are not on the chain and come from the [RealCall]/its [okhttp3.OkHttpClient]
 * (both are public members on the internal `RealCall`, reachable only with the file-level
 * suppressions/opt-in — Kotlin 2.2.20 pinned so the mangled internals stay resolvable).
 */
data class PolicyInput(
    val request: Request,
    val isCanceled: Boolean,
    val forWebSocket: Boolean,
    val cache: Cache?,
    val networkInterceptors: List<Interceptor>,
    val protocols: List<Protocol>,
    val authenticator: Authenticator,
    val proxyAuthenticator: Authenticator,
    val proxy: Proxy?,
    val proxySelector: ProxySelector,
    val socketFactory: SocketFactory,
    val hostnameVerifier: HostnameVerifier,
    val certificatePinner: CertificatePinner,
    val sslSocketFactoryOrNull: SSLSocketFactory?,
    val x509TrustManagerOrNull: X509TrustManager?,
) {
    companion object {
        /** Extracts the effective inputs from a real [okhttp3.Interceptor.Chain]. */
        fun fromChain(chain: Interceptor.Chain): PolicyInput {
            val realChain = chain as RealInterceptorChain
            val call: RealCall = realChain.call
            return PolicyInput(
                request = chain.request(),
                isCanceled = call.isCanceled(),
                forWebSocket = call.forWebSocket,
                cache = chain.cache,
                networkInterceptors = call.client.networkInterceptors,
                protocols = call.client.protocols,
                authenticator = chain.authenticator,
                proxyAuthenticator = chain.proxyAuthenticator,
                proxy = chain.proxy,
                proxySelector = chain.proxySelector,
                socketFactory = chain.socketFactory,
                hostnameVerifier = chain.hostnameVerifier,
                certificatePinner = chain.certificatePinner,
                sslSocketFactoryOrNull = chain.sslSocketFactoryOrNull,
                x509TrustManagerOrNull = chain.x509TrustManagerOrNull,
            )
        }
    }
}
