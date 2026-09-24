package sarie.demo

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import sarie.bridge.CronetOptOut

val optOutInterceptor: Interceptor = Interceptor { chain ->
    val request = chain.request().newBuilder()
        .tag(CronetOptOut::class.java, CronetOptOut)
        .build()
    chain.proceed(request)
}

class StockCallMetrics {
    var callStartMs: Long? = null
    var dnsStartMs: Long? = null
    var dnsEndMs: Long? = null
    var connectStartMs: Long? = null
    var connectEndMs: Long? = null
    var secureConnectStartMs: Long? = null
    var secureConnectEndMs: Long? = null
    var responseHeadersStartMs: Long? = null
}

class StockEventListener : EventListener() {
    val callMetrics = ConcurrentHashMap<Call, StockCallMetrics>()

    private fun getOrCreate(call: Call): StockCallMetrics =
        callMetrics.getOrPut(call) { StockCallMetrics() }

    override fun callStart(call: Call) {
        getOrCreate(call).callStartMs = System.currentTimeMillis()
    }

    override fun dnsStart(call: Call, domainName: String) {
        getOrCreate(call).dnsStartMs = System.currentTimeMillis()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        getOrCreate(call).dnsEndMs = System.currentTimeMillis()
    }

    override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
        getOrCreate(call).connectStartMs = System.currentTimeMillis()
    }

    override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: Protocol?) {
        getOrCreate(call).connectEndMs = System.currentTimeMillis()
    }

    override fun secureConnectStart(call: Call) {
        getOrCreate(call).secureConnectStartMs = System.currentTimeMillis()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        getOrCreate(call).secureConnectEndMs = System.currentTimeMillis()
    }

    override fun responseHeadersStart(call: Call) {
        getOrCreate(call).responseHeadersStartMs = System.currentTimeMillis()
    }
}

class Clients(
    val stockClient: OkHttpClient,
    val sarieClient: OkHttpClient,
    val stockEventListener: StockEventListener,
)

fun createClients(context: Context): Clients {
    val stockEventListener = StockEventListener()
    val chucker = ChuckerInterceptor.Builder(context).build()
    val dispatcher = Dispatcher().apply {
        maxRequests = 128
        maxRequestsPerHost = 128
    }
    val base = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .addNetworkInterceptor(chucker)
        .addNetworkInterceptor(DemoLog)
        .build()

    val sarieClient = base
    val stockClient = base.newBuilder()
        .addInterceptor(optOutInterceptor)
        .eventListener(stockEventListener)
        .build()

    return Clients(stockClient, sarieClient, stockEventListener)
}
