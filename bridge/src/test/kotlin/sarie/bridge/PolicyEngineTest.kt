@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.util.concurrent.Executor
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Cache
import okhttp3.CertificatePinner
import okio.ByteString.Companion.toByteString
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http.RealInterceptorChain
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicyEngineTest {

    /** Minimal cronet-api double; RuntimeSnapshot needs a non-null engine reference only. */
    private class FakeCronetEngine : CronetEngine() {
        override fun getVersionString(): String = "fake"
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shutdown() = Unit
        override fun startNetLogToFile(fileName: String, logAll: Boolean) = Unit
        override fun stopNetLog() = Unit
        override fun getGlobalMetricsDeltas(): ByteArray = ByteArray(0)
        override fun openConnection(url: URL): URLConnection =
            throw UnsupportedOperationException("fake engine")

        override fun createURLStreamHandlerFactory(): URLStreamHandlerFactory =
            throw UnsupportedOperationException("fake engine")

        override fun newUrlRequestBuilder(
            url: String,
            callback: UrlRequest.Callback,
            executor: Executor,
        ): UrlRequest.Builder = throw UnsupportedOperationException("fake engine")
    }

    private class FakeSSLSocketFactory : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = emptyArray()
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: String?, port: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int,
        ): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = throw UnsupportedOperationException()
    }

    private class FakePlainSocketFactory : SocketFactory() {
        override fun createSocket(host: String?, port: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int,
        ): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = throw UnsupportedOperationException()
    }

    private class FakeTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
            throw UnsupportedOperationException()

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) =
            throw UnsupportedOperationException()

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Distinct instances for the memo-overflow test; identity-keyed by IdentityHashMap. */
    private class UniqueTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
            throw UnsupportedOperationException()

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) =
            throw UnsupportedOperationException()

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val engine = FakeCronetEngine()
    private val mapper = RequestToUrlRequestMapper { _, _ -> }
    private val client = OkHttpClient()

    private fun policy(
        vararg origins: String,
        allowLoopback: Boolean = false,
        enabled: Boolean = true,
    ): CronetPolicy = object : CronetPolicy {
        override val allowedOrigins: Set<String> = origins.toSet()
        override val allowLoopbackHttps: Boolean = allowLoopback
        override fun enabled(): Boolean = enabled
    }

    private fun snap(policy: CronetPolicy = policy("example.com")): RuntimeSnapshot =
        RuntimeSnapshot(engine, policy, mapper, System.currentTimeMillis())

    /** Builds a REAL chain through the suppressed internal constructor and extracts PolicyInput. */
    private fun inputFor(
        client: OkHttpClient = this.client,
        url: String = "https://example.com/",
        forWebSocket: Boolean = false,
        canceled: Boolean = false,
    ): PolicyInput {
        val request: Request = Request.Builder().url(url).build()
        val call: RealCall =
            if (forWebSocket) RealCall(client, request, true)
            else client.newCall(request) as RealCall
        if (canceled) call.cancel()
        val chain = RealInterceptorChain(call, emptyList(), 0, null, request, client)
        return PolicyInput.fromChain(chain)
    }

    private fun decision(
        input: PolicyInput = inputFor(),
        snapshot: RuntimeSnapshot? = snap(),
    ): Decision = PolicyEngine.shouldHandle(input, snapshot)

    @Before
    fun setUp() {
        System.clearProperty("okhttp.cronet.enabled")
        Metrics.resetForTest()
        TrustBaseline.clearMemoForTest()
        // isEnabled() requires a snapshot present in SarieBridge itself.
        SarieBridge.install(engine, policy("example.com"), mapper)
    }

    @After
    fun tearDown() {
        System.clearProperty("okhttp.cronet.enabled")
        SarieBridge.uninstall()
        Metrics.resetForTest()
        TrustBaseline.clearMemoForTest()
    }

    // --- every rule, first hit wins ---

    @Test
    fun `null snapshot yields engine_missing`() {
        val d = PolicyEngine.shouldHandle(inputFor(), null)
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.engine_missing, d.reason)
    }

    @Test
    fun `kill switch off yields disabled`() {
        try {
            System.setProperty("okhttp.cronet.enabled", "false")
            val d = decision()
            assertFalse(d.allow)
            assertEquals(Metrics.Reason.disabled, d.reason)
        } finally {
            System.clearProperty("okhttp.cronet.enabled")
        }
    }

    @Test
    fun `policy disabled yields disabled`() {
        val d = decision(snapshot = snap(policy("example.com", enabled = false)))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.disabled, d.reason)
    }

    @Test
    fun `canceled call yields engine_missing (canceled is not a routing concern)`() {
        val d = decision(input = inputFor(canceled = true))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.engine_missing, d.reason)
    }

    @Test
    fun `CronetOptOut tag yields tag_opt_out`() {
        val request = Request.Builder()
            .url("https://example.com/")
            .tag(CronetOptOut::class.java, CronetOptOut)
            .build()
        val call = client.newCall(request) as RealCall
        val chain = RealInterceptorChain(call, emptyList(), 0, null, request, client)
        val d = PolicyEngine.shouldHandle(PolicyInput.fromChain(chain), snap())
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.tag_opt_out, d.reason)
    }

    @Test
    fun `cleartext scheme yields cleartext`() {
        val d = decision(input = inputFor(url = "http://example.com/"))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.cleartext, d.reason)
    }

    @Test
    fun `websocket call yields websocket`() {
        val d = decision(input = inputFor(forWebSocket = true))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.websocket, d.reason)
    }

    @Test
    fun `client cache yields cache`() {
        val cached = OkHttpClient.Builder()
            .cache(Cache(Files.createTempDirectory("policy-cache").toFile(), 1024L * 1024))
            .build()
        val d = decision(input = inputFor(client = cached))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.cache, d.reason)
    }

    @Test
    fun `network interceptor yields network_interceptors`() {
        val withNetInterceptor = OkHttpClient.Builder()
            .addNetworkInterceptor(Interceptor { throw UnsupportedOperationException("never invoked") })
            .build()
        val d = decision(input = inputFor(client = withNetInterceptor))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.network_interceptors, d.reason)
    }

    @Test
    fun `H2_PRIOR_KNOWLEDGE protocol yields h2_prior_knowledge`() {
        val h2pk = OkHttpClient.Builder()
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .build()
        val d = decision(input = inputFor(client = h2pk))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.h2_prior_knowledge, d.reason)
    }

    @Test
    fun `custom authenticator or proxyAuthenticator yields authenticator`() {
        val auth = OkHttpClient.Builder()
            .authenticator { _, _ -> null }
            .build()
        val d = decision(input = inputFor(client = auth))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.authenticator, d.reason)

        val proxyAuth = OkHttpClient.Builder()
            .proxyAuthenticator { _, _ -> null }
            .build()
        val d2 = decision(input = inputFor(client = proxyAuth))
        assertFalse(d2.allow)
        assertEquals(Metrics.Reason.authenticator, d2.reason)
    }

    @Test
    fun `explicit proxy yields proxy`() {
        val proxied = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("192.0.2.1", 8080)))
            .build()
        val d = decision(input = inputFor(client = proxied))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.proxy, d.reason)
    }

    @Test
    fun `custom proxySelector yields proxy`() {
        val customSelector = OkHttpClient.Builder()
            .proxySelector(object : ProxySelector() {
                override fun select(uri: java.net.URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
                override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
            })
            .build()
        val d = decision(input = inputFor(client = customSelector))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.proxy, d.reason)
    }

    @Test
    fun `custom socketFactory class yields socket_factory`() {
        val customSockets = OkHttpClient.Builder()
            .socketFactory(FakePlainSocketFactory())
            .build()
        val d = decision(input = inputFor(client = customSockets))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.socket_factory, d.reason)
    }

    @Test
    fun `custom hostnameVerifier yields hostname_verifier`() {
        val customVerifier = OkHttpClient.Builder()
            .hostnameVerifier { _, _ -> true }
            .build()
        val d = decision(input = inputFor(client = customVerifier))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.hostname_verifier, d.reason)
    }

    @Test
    fun `certificate pins yields pins`() {
        val pinned = OkHttpClient.Builder()
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build(),
            )
            .build()
        val d = decision(input = inputFor(client = pinned))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.pins, d.reason)
    }

    @Test
    fun `sdkPins does not exempt certificate pins`() {
        val pinned = OkHttpClient.Builder()
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build(),
            )
            .build()
        val withSdkPins = object : CronetPolicy {
            override val allowedOrigins: Set<String> = setOf("example.com")
            override val sdkPins: Set<String> = setOf("example.com")
        }
        val d = decision(input = inputFor(client = pinned), snapshot = snap(withSdkPins))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.pins, d.reason)
    }

    @Test
    fun `pins for another host do not deny this host`() {
        val pinnedElsewhere = OkHttpClient.Builder()
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("other.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build(),
            )
            .build()
        val d = decision(input = inputFor(client = pinnedElsewhere))
        assertEquals(Decision(true, null), d)
    }

    @Test
    fun `sarie-built engine allows pins that match the installed set`() {
        val sha = "sha256/" + ByteArray(32) { 4 }.toByteString().base64()
        val pinner = CertificatePinner.Builder().add("example.com", sha).build()
        val client = OkHttpClient.Builder().certificatePinner(pinner).build()
        val installed = translatePins(pinner.pins).installedPins
        val d = decision(
            input = inputFor(client = client),
            snapshot = snap().copy(sarieBuilt = true, installedPins = installed),
        )
        assertEquals(Decision(true, null), d)
    }

    @Test
    fun `derived client with different pins falls back`() {
        val installedSha = "sha256/" + ByteArray(32) { 4 }.toByteString().base64()
        val otherSha = "sha256/" + ByteArray(32) { 5 }.toByteString().base64()
        val installed = translatePins(
            CertificatePinner.Builder().add("example.com", installedSha).build().pins,
        ).installedPins
        val derived = OkHttpClient.Builder()
            .certificatePinner(CertificatePinner.Builder().add("example.com", otherSha).build())
            .build()
        val d = decision(
            input = inputFor(client = derived),
            snapshot = snap().copy(sarieBuilt = true, installedPins = installed),
        )
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.pins, d.reason)
    }

    @Test
    fun `single label wildcard pin is denied on a sarie-built engine`() {
        val sha = "sha256/" + ByteArray(32) { 4 }.toByteString().base64()
        val client = OkHttpClient.Builder()
            .certificatePinner(CertificatePinner.Builder().add("*.example.com", sha).build())
            .build()
        val d = decision(
            input = inputFor(client = client, url = "https://a.example.com/"),
            snapshot = snap(policy("a.example.com")).copy(sarieBuilt = true, installedPins = emptySet()),
        )
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.pins, d.reason)
    }

    @Test
    fun `double wildcard pin installed for the host is allowed`() {
        val sha = "sha256/" + ByteArray(32) { 6 }.toByteString().base64()
        val pinner = CertificatePinner.Builder().add("**.example.com", sha).build()
        val client = OkHttpClient.Builder().certificatePinner(pinner).build()
        val installed = translatePins(pinner.pins).installedPins
        val d = decision(
            input = inputFor(client = client, url = "https://a.example.com/"),
            snapshot = snap(policy("a.example.com")).copy(sarieBuilt = true, installedPins = installed),
        )
        assertEquals(Decision(true, null), d)
    }

    @Test
    fun `custom sslSocketFactory and TM classes yield trust`() {
        val customTls = OkHttpClient.Builder()
            .sslSocketFactory(FakeSSLSocketFactory(), FakeTrustManager())
            .build()
        val d = decision(input = inputFor(client = customTls))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.trust, d.reason)
    }

    @Test
    fun `custom TM with platform default class but different issuers yields trust (Metis B1)`() {
        // Same CLASS as the platform default (empty keystore => SunX509/PKIX manager), so the
        // class check alone cannot catch it; only the acceptedIssuers fingerprint can.
        val emptyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        emptyStore.load(null, null)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(emptyStore)
        val customTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(customTm), null)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, customTm)
            .build()

        // Preconditions: class matches baseline, fingerprint does not.
        assertEquals(TrustBaseline.baseline.trustManagerClass, customTm.javaClass)
        assertNotEquals(
            TrustBaseline.baseline.trustFingerprint,
            TrustBaseline.acceptedIssuersFingerprint(customTm),
        )

        val d = decision(input = inputFor(client = client))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.trust, d.reason)
    }

    @Test
    fun `loopback https denied yields cleartext (cleartext reason reused for loopback)`() {
        val d = decision(input = inputFor(url = "https://localhost/"), snapshot = snap(policy("localhost")))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.cleartext, d.reason)

        val d2 = decision(input = inputFor(url = "https://10.0.2.2/"), snapshot = snap(policy("10.0.2.2")))
        assertFalse(d2.allow)
        assertEquals(Metrics.Reason.cleartext, d2.reason)
    }

    @Test
    fun `loopback https with allowLoopbackHttps yields allow`() {
        val p = policy("localhost", allowLoopback = true)
        val d = decision(input = inputFor(url = "https://localhost/"), snapshot = snap(p))
        assertEquals(Decision(true, null), d)
    }

    @Test
    fun `empty allowedOrigins admits every https origin`() {
        val d = decision(
            input = inputFor(url = "https://other.com/"),
            snapshot = snap(policy()),
        )
        assertEquals(Decision(true, null), d)
    }

    @Test
    fun `star token admits every https origin`() {
        val d = decision(
            input = inputFor(url = "https://other.com/"),
            snapshot = snap(policy("*")),
        )
        assertEquals(Decision(true, null), d)
    }

    @Test
    fun `origin not allowlisted yields allowlist`() {
        val d = decision(input = inputFor(url = "https://other.com/"))
        assertFalse(d.allow)
        assertEquals(Metrics.Reason.allowlist, d.reason)
    }

    @Test
    fun `allowlisted https origin on default client yields allow`() {
        assertEquals(Decision(true, null), decision())
    }

    // --- rule ordering: first hit wins ---

    @Test
    fun `rule order - opt-out tag beats cleartext, websocket beats cache`() {
        val request = Request.Builder().url("http://example.com/").build()
        val tagged = request.newBuilder().tag(CronetOptOut::class.java, CronetOptOut).build()
        val call = client.newCall(tagged) as RealCall
        val chain = RealInterceptorChain(call, emptyList(), 0, null, tagged, client)
        assertEquals(Metrics.Reason.tag_opt_out, PolicyEngine.shouldHandle(PolicyInput.fromChain(chain), snap()).reason)

        val cached = OkHttpClient.Builder()
            .cache(Cache(Files.createTempDirectory("policy-cache").toFile(), 1024L * 1024))
            .build()
        val d = decision(input = inputFor(client = cached, forWebSocket = true))
        assertEquals(Metrics.Reason.websocket, d.reason)
    }

    // --- allowlist port semantics ---

    @Test
    fun `allowlist port semantics - bare entry means default port 443, host with port is exact`() {
        val p = policy("10.0.2.2", "10.0.2.2:8443", allowLoopback = true)

        // Bare "10.0.2.2" matches https://10.0.2.2:443/...
        assertEquals(
            Decision(true, null),
            decision(input = inputFor(url = "https://10.0.2.2/"), snapshot = snap(p)),
        )
        // Explicit "10.0.2.2:8443" matches only port 8443.
        assertEquals(
            Decision(true, null),
            decision(input = inputFor(url = "https://10.0.2.2:8443/"), snapshot = snap(p)),
        )
        // No entry covers 9443 -> default-deny.
        assertEquals(
            Metrics.Reason.allowlist,
            decision(input = inputFor(url = "https://10.0.2.2:9443/"), snapshot = snap(p)).reason,
        )
        // Bare "example.com" (443 only) does not cover 8443.
        assertEquals(
            Metrics.Reason.allowlist,
            decision(
                input = inputFor(url = "https://example.com:8443/"),
                snapshot = snap(policy("example.com")),
            ).reason,
        )
    }

    // --- trust verdict memoization (Metis B1) ---

    @Test
    fun `trust verdicts memoized per TM instance and bounded at 1024`() {
        TrustBaseline.clearMemoForTest()
        try {
            val tm = OkHttpClient().x509TrustManager!!
            val before = TrustBaseline.memoizedCount()
            val first = TrustBaseline.verdictFor(tm)
            assertEquals(before + 1, TrustBaseline.memoizedCount())
            val second = TrustBaseline.verdictFor(tm)
            assertSame(first, second)
            assertEquals(before + 1, TrustBaseline.memoizedCount())

            repeat(1100) { TrustBaseline.verdictFor(UniqueTrustManager()) }
            assertTrue(
                "memo must stay <= 1024, was ${TrustBaseline.memoizedCount()}",
                TrustBaseline.memoizedCount() <= 1024,
            )
        } finally {
            TrustBaseline.clearMemoForTest()
        }
    }

    // --- reasons that belong to engine lifecycle, not routing ---

    @Test
    fun `protocols and engine_cold are never routing reasons`() {
        val d = decision(input = inputFor(client = OkHttpClient.Builder().build()))
        assertEquals(Decision(true, null), d)
        assertEquals("protocols", Metrics.Reason.protocols.name)
        assertEquals("engine_cold", Metrics.Reason.engine_cold.name)
    }
}
