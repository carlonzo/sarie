@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import java.net.Proxy
import java.net.ProxySelector
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.RealInterceptorChain

/**
 * Effective per-request routing inputs for [PolicyEngine].
 *
 * Chain-effective configuration (everything [okhttp3.Interceptor.Chain] publicly exposes
 * since OkHttp 5.4.0, i.e. what an application interceptor may have adjusted via `withX`
 * before ConnectInterceptor) is read from the chain. `networkInterceptors`, `protocols` and
 * `forWebSocket` are not on the chain and come from the [RealCall]/its [okhttp3.OkHttpClient].
 * [originalRequest] is [RealCall.originalRequest] (the app's request, before interceptors add
 * `Accept-Encoding`). `dns` is [Interceptor.Chain.dns].
 */
data class PolicyInput(
    val request: Request,
    val originalRequest: Request,
    val isCanceled: Boolean,
    val forWebSocket: Boolean,
    /** Present on the chain. Not a routing deny: OkHttp's cache runs above the Cronet hop. */
    val cache: Cache?,
    val networkInterceptors: List<Interceptor>,
    val protocols: List<Protocol>,
    val proxy: Proxy?,
    val proxySelector: ProxySelector,
    val socketFactory: SocketFactory,
    val hostnameVerifier: HostnameVerifier,
    val dns: Dns,
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
                originalRequest = call.originalRequest,
                isCanceled = call.isCanceled(),
                forWebSocket = call.forWebSocket,
                cache = chain.cache,
                networkInterceptors = call.client.networkInterceptors,
                protocols = call.client.protocols,
                proxy = chain.proxy,
                proxySelector = chain.proxySelector,
                socketFactory = chain.socketFactory,
                hostnameVerifier = chain.hostnameVerifier,
                dns = chain.dns,
                certificatePinner = chain.certificatePinner,
                sslSocketFactoryOrNull = chain.sslSocketFactoryOrNull,
                x509TrustManagerOrNull = chain.x509TrustManagerOrNull,
            )
        }
    }
}
