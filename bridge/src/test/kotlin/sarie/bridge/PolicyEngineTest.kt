@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge

import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
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
import okhttp3.Dns
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    private fun snap(
        policy: CronetPolicy = policy("example.com"),
        installedPins: Set<CertificatePinner.Pin>? = null,
    ): RuntimeSnapshot = RuntimeSnapshot(
        engine,
        policy,
        mapper,
        System.currentTimeMillis(),
        sarieBuilt = installedPins != null,
        installedPins = installedPins.orEmpty(),
    )

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

    /**
     * [original] is the call's originalRequest. [atSwap] is the request ConnectInterceptor sees
     * (after BridgeInterceptor). Defaults to [original].
     */
    private fun chainInput(
        client: OkHttpClient = this.client,
        original: Request,
        atSwap: Request = original,
    ): PolicyInput {
        val call = client.newCall(original) as RealCall
        val chain = RealInterceptorChain(call, emptyList(), 0, null, atSwap, client)
        return PolicyInput.fromChain(chain)
    }

    private fun decision(
        input: PolicyInput = inputFor(),
        snapshot: RuntimeSnapshot? = snap(),
    ): FallbackReason? = PolicyEngine.shouldHandle(input, snapshot)

    @Before
    fun setUp() {
        System.clearProperty("okhttp.cronet.enabled")
        TrustBaseline.clearMemoForTest()
        SarieBridge.install(
            engine,
            SarieConfig {
                policy(policy("example.com"))
                mapper(mapper)
            },
        )
    }

    @After
    fun tearDown() {
        System.clearProperty("okhttp.cronet.enabled")
        SarieBridge.uninstall()
        TrustBaseline.clearMemoForTest()
    }

    // --- every rule, first hit wins ---

    @Test
    fun `null snapshot yields engine_missing`() {
        val d = PolicyEngine.shouldHandle(inputFor(), null)
        assertEquals(FallbackReason.engine_missing, d)
    }

    @Test
    fun `kill switch off yields disabled`() {
        try {
            System.setProperty("okhttp.cronet.enabled", "false")
            val d = decision()
            assertEquals(FallbackReason.disabled, d)
        } finally {
            System.clearProperty("okhttp.cronet.enabled")
        }
    }

    @Test
    fun `policy disabled yields disabled`() {
        val d = decision(snapshot = snap(policy("example.com", enabled = false)))
        assertEquals(FallbackReason.disabled, d)
    }

    @Test
    fun `canceled call yields engine_missing (canceled is not a routing concern)`() {
        val d = decision(input = inputFor(canceled = true))
        assertEquals(FallbackReason.engine_missing, d)
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
        assertEquals(FallbackReason.tag_opt_out, d)
    }

    @Test
    fun `cleartext scheme yields cleartext`() {
        val d = decision(input = inputFor(url = "http://example.com/"))
        assertEquals(FallbackReason.cleartext, d)
    }

    @Test
    fun `websocket call yields websocket`() {
        val d = decision(input = inputFor(forWebSocket = true))
        assertEquals(FallbackReason.websocket, d)
    }

    @Test
    fun `client cache is not denied`() {
        val cached = OkHttpClient.Builder()
            .cache(Cache(Files.createTempDirectory("policy-cache").toFile(), 1024L * 1024))
            .build()
        val d = decision(input = inputFor(client = cached))
        assertNull(d)
    }

    @Test
    fun `network interceptors are not denied`() {
        val withNetInterceptor = OkHttpClient.Builder()
            .addNetworkInterceptor(Interceptor { throw UnsupportedOperationException("never invoked") })
            .build()
        val d = decision(input = inputFor(client = withNetInterceptor))
        assertNull(d)
    }

    @Test
    fun `H2_PRIOR_KNOWLEDGE protocol yields h2_prior_knowledge`() {
        val h2pk = OkHttpClient.Builder()
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .build()
        val d = decision(input = inputFor(client = h2pk))
        assertEquals(FallbackReason.h2_prior_knowledge, d)
    }

    @Test
    fun `custom authenticator or proxyAuthenticator no longer denies`() {
        val auth = OkHttpClient.Builder()
            .authenticator { _, _ -> null }
            .build()
        assertNull(decision(input = inputFor(client = auth)))

        val proxyAuth = OkHttpClient.Builder()
            .proxyAuthenticator { _, _ -> null }
            .build()
        assertNull(decision(input = inputFor(client = proxyAuth)))

        // A proxy still denies, and it wins over a custom authenticator.
        val proxied = OkHttpClient.Builder()
            .authenticator { _, _ -> null }
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("192.0.2.1", 8080)))
            .build()
        val d = decision(input = inputFor(client = proxied))
        assertEquals(FallbackReason.proxy, d)
    }

    @Test
    fun `explicit proxy yields proxy`() {
        val proxied = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("192.0.2.1", 8080)))
            .build()
        val d = decision(input = inputFor(client = proxied))
        assertEquals(FallbackReason.proxy, d)
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
        assertEquals(FallbackReason.proxy, d)
    }

    @Test
    fun `custom socketFactory class yields socket_factory`() {
        val customSockets = OkHttpClient.Builder()
            .socketFactory(FakePlainSocketFactory())
            .build()
        val d = decision(input = inputFor(client = customSockets))
        assertEquals(FallbackReason.socket_factory, d)
    }

    @Test
    fun `custom hostnameVerifier yields hostname_verifier`() {
        val customVerifier = OkHttpClient.Builder()
            .hostnameVerifier { _, _ -> true }
            .build()
        val d = decision(input = inputFor(client = customVerifier))
        assertEquals(FallbackReason.hostname_verifier, d)
    }

    @Test
    fun `custom dns yields dns`() {
        val custom = OkHttpClient.Builder()
            .dns { throw UnsupportedOperationException("not called") }
            .build()
        val d = decision(input = inputFor(client = custom))
        assertEquals(FallbackReason.dns, d)
    }

    @Test
    fun `Dns SYSTEM does not deny`() {
        val system = OkHttpClient.Builder().dns(Dns.SYSTEM).build()
        assertNull(decision(input = inputFor(client = system)))
    }

    @Test
    fun `dns deny precedes pins`() {
        val client = OkHttpClient.Builder()
            .dns { throw UnsupportedOperationException("not called") }
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build(),
            )
            .build()
        assertEquals(FallbackReason.dns, decision(input = inputFor(client = client)))
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
        assertEquals(FallbackReason.pins, d)
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
        assertEquals(FallbackReason.pins, d)
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
        assertNull(d)
    }

    @Test
    fun `sarie-built engine allows pins that match the installed set`() {
        val sha = "sha256/" + ByteArray(32) { 4 }.toByteString().base64()
        val pinner = CertificatePinner.Builder().add("example.com", sha).build()
        val client = OkHttpClient.Builder().certificatePinner(pinner).build()
        val installed = translatePins(pinner.pins).installedPins
        val d = decision(
            input = inputFor(client = client),
            snapshot = snap(installedPins = installed),
        )
        assertNull(d)
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
            snapshot = snap(installedPins = installed),
        )
        assertEquals(FallbackReason.pins, d)
    }

    @Test
    fun `client without pins falls back when the engine pins the host`() {
        val sha = "sha256/" + ByteArray(32) { 4 }.toByteString().base64()
        val installed = translatePins(
            CertificatePinner.Builder().add("example.com", sha).build().pins,
        ).installedPins
        val d = decision(
            input = inputFor(client = OkHttpClient()),
            snapshot = snap(installedPins = installed),
        )
        assertEquals(FallbackReason.pins, d)
    }

    @Test
    fun `pins from overlapping patterns fall back`() {
        val exact = "sha256/" + ByteArray(32) { 4 }.toByteString().base64()
        val wide = "sha256/" + ByteArray(32) { 6 }.toByteString().base64()
        val pinner = CertificatePinner.Builder()
            .add("a.example.com", exact)
            .add("**.example.com", wide)
            .build()
        val client = OkHttpClient.Builder().certificatePinner(pinner).build()
        val d = decision(
            input = inputFor(client = client, url = "https://a.example.com/"),
            snapshot = snap(policy("a.example.com"), installedPins = translatePins(pinner.pins).installedPins),
        )
        assertEquals(FallbackReason.pins, d)
    }

    @Test
    fun `single label wildcard pin is denied on a sarie-built engine`() {
        val sha = "sha256/" + ByteArray(32) { 4 }.toByteString().base64()
        val client = OkHttpClient.Builder()
            .certificatePinner(CertificatePinner.Builder().add("*.example.com", sha).build())
            .build()
        val d = decision(
            input = inputFor(client = client, url = "https://a.example.com/"),
            snapshot = snap(policy("a.example.com"), installedPins = emptySet()),
        )
        assertEquals(FallbackReason.pins, d)
    }

    @Test
    fun `double wildcard pin installed for the host is allowed`() {
        val sha = "sha256/" + ByteArray(32) { 6 }.toByteString().base64()
        val pinner = CertificatePinner.Builder().add("**.example.com", sha).build()
        val client = OkHttpClient.Builder().certificatePinner(pinner).build()
        val installed = translatePins(pinner.pins).installedPins
        val d = decision(
            input = inputFor(client = client, url = "https://a.example.com/"),
            snapshot = snap(policy("a.example.com"), installedPins = installed),
        )
        assertNull(d)
    }

    @Test
    fun `custom sslSocketFactory and TM classes yield trust`() {
        val customTls = OkHttpClient.Builder()
            .sslSocketFactory(FakeSSLSocketFactory(), FakeTrustManager())
            .build()
        val d = decision(input = inputFor(client = customTls))
        assertEquals(FallbackReason.trust, d)
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
        assertEquals(FallbackReason.trust, d)
    }

    @Test
    fun `originalRequest Accept-Encoding yields content_encoding`() {
        // gzip would be allowed on the swap request; the app's own header still denies.
        val request = Request.Builder()
            .url("https://example.com/")
            .header("Accept-Encoding", "gzip")
            .build()
        val d = decision(input = chainInput(original = request))
        assertEquals(FallbackReason.content_encoding, d)
    }

    @Test
    fun `swap Accept-Encoding without gzip yields content_encoding`() {
        val original = Request.Builder().url("https://example.com/").build()
        val atSwap = original.newBuilder().header("Accept-Encoding", "identity").build()
        val d = decision(input = chainInput(original = original, atSwap = atSwap))
        assertEquals(FallbackReason.content_encoding, d)

        val quality = original.newBuilder().header("Accept-Encoding", "identity;q=1, br").build()
        assertEquals(
            FallbackReason.content_encoding,
            decision(input = chainInput(original = original, atSwap = quality)),
        )
    }

    @Test
    fun `absent Accept-Encoding is allowed`() {
        val original = Request.Builder()
            .url("https://example.com/")
            .header("Range", "bytes=0-1")
            .build()
        assertNull(decision(input = chainInput(original = original)))
    }

    @Test
    fun `swap Accept-Encoding listing gzip is allowed`() {
        val original = Request.Builder().url("https://example.com/").build()
        val atSwap = original.newBuilder().header("Accept-Encoding", "br, gzip").build()
        assertNull(decision(input = chainInput(original = original, atSwap = atSwap)))

        val quality = original.newBuilder()
            .header("Accept-Encoding", "br;q=1.0, GZip;q=0.5")
            .build()
        assertNull(decision(input = chainInput(original = original, atSwap = quality)))
    }

    @Test
    fun `content_encoding is after trust and before loopback`() {
        val customTls = OkHttpClient.Builder()
            .sslSocketFactory(FakeSSLSocketFactory(), FakeTrustManager())
            .build()
        val owned = Request.Builder()
            .url("https://example.com/")
            .header("Accept-Encoding", "identity")
            .build()
        assertEquals(
            FallbackReason.trust,
            decision(input = chainInput(client = customTls, original = owned)),
        )

        val original = Request.Builder().url("https://localhost/").build()
        val atSwap = original.newBuilder().header("Accept-Encoding", "identity").build()
        assertEquals(
            FallbackReason.content_encoding,
            decision(
                input = chainInput(original = original, atSwap = atSwap),
                snapshot = snap(policy("localhost")),
            ),
        )
    }

    @Test
    fun `loopback https denied yields cleartext (cleartext reason reused for loopback)`() {
        val d = decision(input = inputFor(url = "https://localhost/"), snapshot = snap(policy("localhost")))
        assertEquals(FallbackReason.cleartext, d)

        val d2 = decision(input = inputFor(url = "https://10.0.2.2/"), snapshot = snap(policy("10.0.2.2")))
        assertEquals(FallbackReason.cleartext, d2)
    }

    @Test
    fun `loopback https with allowLoopbackHttps yields allow`() {
        val p = policy("localhost", allowLoopback = true)
        val d = decision(input = inputFor(url = "https://localhost/"), snapshot = snap(p))
        assertNull(d)
    }

    @Test
    fun `empty allowedOrigins admits every https origin`() {
        val d = decision(
            input = inputFor(url = "https://other.com/"),
            snapshot = snap(policy()),
        )
        assertNull(d)
    }

    @Test
    fun `star token admits every https origin`() {
        val d = decision(
            input = inputFor(url = "https://other.com/"),
            snapshot = snap(policy("*")),
        )
        assertNull(d)
    }

    @Test
    fun `origin not allowlisted yields allowlist`() {
        val d = decision(input = inputFor(url = "https://other.com/"))
        assertEquals(FallbackReason.allowlist, d)
    }

    @Test
    fun `allowlisted https origin on default client yields allow`() {
        assertNull(decision())
    }

    // --- rule ordering: first hit wins ---

    @Test
    fun `rule order - opt-out tag beats cleartext, websocket beats cache`() {
        val request = Request.Builder().url("http://example.com/").build()
        val tagged = request.newBuilder().tag(CronetOptOut::class.java, CronetOptOut).build()
        val call = client.newCall(tagged) as RealCall
        val chain = RealInterceptorChain(call, emptyList(), 0, null, tagged, client)
        assertEquals(FallbackReason.tag_opt_out, PolicyEngine.shouldHandle(PolicyInput.fromChain(chain), snap()))

        val cached = OkHttpClient.Builder()
            .cache(Cache(Files.createTempDirectory("policy-cache").toFile(), 1024L * 1024))
            .build()
        val d = decision(input = inputFor(client = cached, forWebSocket = true))
        assertEquals(FallbackReason.websocket, d)
    }

    // --- allowlist port semantics ---

    @Test
    fun `allowlist port semantics - bare entry means default port 443, host with port is exact`() {
        val p = policy("10.0.2.2", "10.0.2.2:8443", allowLoopback = true)

        // Bare "10.0.2.2" matches https://10.0.2.2:443/...
        assertNull(decision(input = inputFor(url = "https://10.0.2.2/"), snapshot = snap(p)))
        // Explicit "10.0.2.2:8443" matches only port 8443.
        assertNull(decision(input = inputFor(url = "https://10.0.2.2:8443/"), snapshot = snap(p)))
        // No entry covers 9443 -> default-deny.
        assertEquals(
            FallbackReason.allowlist,
            decision(input = inputFor(url = "https://10.0.2.2:9443/"), snapshot = snap(p)),
        )
        // Bare "example.com" (443 only) does not cover 8443.
        assertEquals(
            FallbackReason.allowlist,
            decision(
                input = inputFor(url = "https://example.com:8443/"),
                snapshot = snap(policy("example.com")),
            ),
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
        assertNull(decision(input = inputFor(client = OkHttpClient.Builder().build())))
        val auth = OkHttpClient.Builder().authenticator { _, _ -> null }.build()
        assertNull(decision(input = inputFor(client = auth)))
    }

    @Test
    fun `allow path allocates nothing after warmup`() {
        val threadMx = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(threadMx.isThreadAllocatedMemorySupported)
        if (!threadMx.isThreadAllocatedMemoryEnabled) {
            threadMx.setThreadAllocatedMemoryEnabled(true)
        }
        val client = OkHttpClient()
        val original = Request.Builder().url("https://example.com/").build()
        val atSwap = original.newBuilder().header("Accept-Encoding", "gzip").build()
        val input = chainInput(client = client, original = original, atSwap = atSwap)
        val snapshot = snap(policy())
        TrustBaseline.baseline
        var warm = 0
        while (warm < 50) {
            assertNull(PolicyEngine.shouldHandle(input, snapshot))
            warm++
        }
        @Suppress("DEPRECATION")
        val threadId = Thread.currentThread().id
        val beforeOne = threadMx.getThreadAllocatedBytes(threadId)
        assertNull(PolicyEngine.shouldHandle(input, snapshot))
        assertEquals(0L, threadMx.getThreadAllocatedBytes(threadId) - beforeOne)

        val before = threadMx.getThreadAllocatedBytes(threadId)
        var n = 0
        while (n < 10_000) {
            if (PolicyEngine.shouldHandle(input, snapshot) != null) {
                throw AssertionError("expected allow")
            }
            n++
        }
        val allocated = threadMx.getThreadAllocatedBytes(threadId) - before
        // A per-call object is at least 16 bytes. The counter also reports a few KB of
        // allocator noise across a long loop, so the bound is one byte per call.
        assertTrue("allocated $allocated bytes across 10000 calls", allocated < 10_000)
    }
}
