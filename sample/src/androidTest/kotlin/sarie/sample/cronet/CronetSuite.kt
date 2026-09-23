package sarie.sample.cronet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import sarie.bridge.Metrics
import sarie.bridge.SarieBridge
import sarie.sample.NetworkParity
import sarie.sample.SampleAppRuntime
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import okio.BufferedSink
import org.chromium.net.CronetEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real Cronet device suite against the Caddy origin on the host (10.0.2.2:8443, started per
 * scripts/Caddyfile header). Every cronet-path test asserts Metrics.cronet >= 1 AND
 * lastReason == null (nothing fell back); every stock-path test asserts cronet == 0 with the
 * exact fallback reason.
 *
 * HTTP/3 evidence structure:
 * - Client side: [h3NegotiatedAgainstPublicOrigin] asserts Protocol.HTTP_3 through the
 *   trampoline against an h3-only origin, so no h2 downgrade can fake the result.
 * - Local-origin h3 (quic hint on 10.0.2.2:8443) is BLOCKED by the pinned engine: Chromium's
 *   QUIC proof verifier closes the handshake with alert 46 (certificate unknown, QUIC wire
 *   error 302) right after the NSC-based chain verification succeeds, whenever the chain is
 *   anchored outside the engine's built-in root store - netlog-verified on
 *   cronet-embedded 143.7445.0 (evidence task-9/netlog-h3-blocked.json), with leaf-only AND
 *   full-chain (leaf+root) server chains, with and without
 *   enablePublicKeyPinningBypassForLocalTrustAnchors. TCP-TLS honors the same NSC CA (all
 *   other tests). No public CronetEngine.Builder knob exists to trust custom QUIC roots
 *   (no setMockCertVerifierForTesting in cronet-api 143.7445.0). [h2LocalOriginWhileQuicBlocked]
 *   pins that fallback so a future engine that DOES negotiate local h3 is immediately visible.
 * - Server side: startTestOrigin probes https://127.0.0.1:8443/ok with a host
 *   `--http3-only` curl (scripts/bin/curl-http3). The emulator cannot complete
 *   local-origin h3. verifyH3ServerEvidence then greps scripts/bin/caddy-access.log
 *   for "proto":"HTTP/3" entries.
 */
class CronetSuite {

    companion object {
        const val HOST = "10.0.2.2"
        const val PORT = 8443
        const val ORIGIN = "https://10.0.2.2:8443"
        const val MIB = 1024 * 1024
    }

    @Before
    fun setUp() {
        Metrics.resetForTest()
    }

    /** Engine installed by THIS test (tests without an install must not touch any engine). */
    private var installedEngine: CronetEngine? = null

    @After
    fun tearDown() {
        SarieBridge.uninstall()
        installedEngine?.let { engine ->
            if (SampleAppRuntime.netLogRequested) {
                @Suppress("DEPRECATION")
                engine.stopNetLog()
            }
            @Suppress("DEPRECATION") // suite owns these engines; stop them to keep the emulator healthy
            engine.shutdown()
        }
        installedEngine = null
        Metrics.resetForTest()
    }

    /** Installs the cronet-mode runtime with an isolated engine (fresh storage dir). */
    private fun installCronet(quicHintHost: String? = HOST, quicHintPort: Int = PORT) {
        SampleAppRuntime.install(
            mode = SampleAppRuntime.MODE_CRONET,
            quicHintHost = quicHintHost,
            quicHintPort = quicHintPort,
            freshStorage = true,
            netLog = SampleAppRuntime.netLogRequested,
        )
        installedEngine = SampleAppRuntime.lastEngine
    }

    /** Borrowed host-built engine. [brotli] and [diskCache] are the two tests that need one. */
    private fun installBorrowed(
        brotli: Boolean = false,
        diskCache: Boolean = false,
        quicHintHost: String? = null,
    ) {
        SampleAppRuntime.install(
            mode = SampleAppRuntime.MODE_BORROWED,
            quicHintHost = quicHintHost,
            freshStorage = true,
            brotli = brotli,
            diskCache = diskCache,
        )
        installedEngine = SampleAppRuntime.lastEngine
    }

    private fun assertCronetServed(minCount: Long = 1) {
        assertTrue("expected the cronet path, cronet=${Metrics.cronet.get()}", Metrics.cronet.get() >= minCount)
        assertNull(
            "cronet-path request recorded a fallback reason: ${Metrics.lastReason}",
            Metrics.lastReason,
        )
    }

    private fun assertStockServed(reason: Metrics.Reason) {
        assertEquals(0, Metrics.cronet.get())
        assertTrue(
            "expected at least one fallback, got ${Metrics.okhttpFallback.get()}",
            Metrics.okhttpFallback.get() >= 1,
        )
        assertEquals(reason, Metrics.lastReason)
    }

    @Test
    fun h3NegotiatedAgainstPublicOrigin() {
        // cloudflare-quic.com serves HTTP/3 ONLY (no TCP listener), so the request cannot
        // downgrade to h2: if this returns, the device negotiated real h3 through the
        // trampoline. Its cert chains to a known public root, which the embedded engine's
        // QUIC proof verifier requires - locally-anchored CAs are rejected (see the class
        // KDoc and h2LocalOriginWhileQuicBlocked).
        installCronet(quicHintHost = "cloudflare-quic.com", quicHintPort = 443)

        OkHttpClient().newCall(Request.Builder().url("https://cloudflare-quic.com/").build())
            .execute()
            .use { response ->
                assertEquals(
                    "expected real HTTP/3 through the Cronet path (client saw ${response.protocol})",
                    Protocol.HTTP_3,
                    response.protocol,
                )
                assertEquals(200, response.code)
            }
        assertCronetServed()
    }

    @Test
    fun h2LocalOriginWhileQuicBlocked() {
        // Pinned engine limitation (netlog-verified, evidence task-9): the quic hint makes
        // the engine TRY QUIC first, every attempt is rejected with alert 46 (see the class
        // KDoc), and the request completes over the TCP fallback as h2. h2 is NOT h3: this
        // test must flip if a future engine ever negotiates local-origin h3.
        installCronet(quicHintHost = HOST, quicHintPort = PORT)

        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
            assertTrue(
                "local-CA origin must not negotiate h3 (client saw ${response.protocol}); " +
                    "a change here invalidates the pinned known-root limitation",
                response.protocol != Protocol.HTTP_3,
            )
        }
        assertCronetServed()
    }

    @Test
    fun h2FirstRequestWithoutHint() {
        installCronet(quicHintHost = null) // fresh storage: no cached QUIC server config

        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            // Without a quic hint (and without cached server config) Cronet connects over TCP
            // and ALPN-negotiates h2. h2 is NOT h3: this pins the no-hint baseline.
            assertEquals(
                "expected HTTP/2 for the first no-hint request (client saw ${response.protocol})",
                Protocol.HTTP_2,
                response.protocol,
            )
            assertEquals("ok", response.body.string())
        }
        assertCronetServed()
    }

    @Test
    fun postUploadByteExact() {
        installCronet(quicHintHost = null)

        val payload = ByteArray(MIB).also(Random()::nextBytes)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = payload.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(payload)
            }
        }
        val request = Request.Builder().url("$ORIGIN/echo").post(body).build()

        // The 1 MiB upload completes in ~70ms on-device (access-log observed). The earlier
        // 10s stalls were an executor self-deadlock in the bridge (one single-thread
        // CronetExecutor driving both the UploadDataProvider callbacks and the provider's
        // own materialize() task); fixed by separating the two executors in CronetBridge.
        OkHttpClient().newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(
                "echoed body not byte-identical (got ${response.body.contentLength()} bytes)",
                payload.contentEquals(response.body.bytes()),
            )
        }
        assertCronetServed()
    }

    @Test
    fun streamingLargeBody() {
        installCronet()
        val expected = 5L * MIB

        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        client.newCall(Request.Builder().url("$ORIGIN/big?mb=5").build()).execute().use { response ->
            assertEquals(200, response.code)
            val source = response.body.source()
            val chunk = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = source.read(chunk)
                if (read == -1) break
                total += read
            }
            assertEquals(
                "streamed byte count mismatch",
                expected,
                total,
            )
        }
        assertCronetServed()
    }

    @Test
    fun readTimeoutStallAborts() {
        installCronet(quicHintHost = null)

        // Warm the connection pool first: the 500ms budget bounds the header wait, and a
        // cold-engine connect+TLS handshake over slirp does not fit it. The timed client
        // shares the pool, so /slow reuses the warm connection; the abort then happens on
        // the stalled body read.
        val warm = OkHttpClient()
        warm.newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { it.body.string() }
        val client = warm.newBuilder().readTimeout(500, TimeUnit.MILLISECONDS).build()
        client.newCall(Request.Builder().url("$ORIGIN/slow").build()).execute().use { response ->
            // /slow delivers 200 + headers immediately, then stalls; the abort happens on the
            // body read. Header-wait stalls throw SocketTimeoutException; the body poll in
            // OkHttpBridgeCallback throws IOException("Timed out reading the response body").
            assertEquals(200, response.code)
            val error = runCatching { response.body.bytes() }.exceptionOrNull()
            assertTrue(
                "expected a read-timeout abort, got: $error",
                error is IOException &&
                    (error is SocketTimeoutException || error.message?.contains("Timed out") == true),
            )
        }
        assertCronetServed()
    }

    @Test
    fun cancelAfterHeadersAbortsBody() {
        installCronet()

        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val call = client.newCall(Request.Builder().url("$ORIGIN/big?mb=5").build())
        val headers = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val reader = Thread {
            try {
                call.execute().use { response ->
                    headers.countDown() // execute() returns once headers arrive
                    cancelled.await(10, TimeUnit.SECONDS)
                    val source = response.body.source()
                    val chunk = ByteArray(8 * 1024)
                    while (source.read(chunk) != -1) {
                        // drain; the cancel must abort this loop
                    }
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        reader.start()
        assertTrue("headers never arrived", headers.await(15, TimeUnit.SECONDS))
        call.cancel()
        cancelled.countDown()
        reader.join(15_000)
        assertFalse("reader thread still alive after cancel", reader.isAlive)

        val thrown = error.get()
        assertTrue(
            "expected body read to abort with IOException(Canceled), got: $thrown",
            thrown is IOException && thrown.message?.contains("Canceled") == true,
        )
        assertCronetServed()
    }

    @Test
    fun compressionGzipDecoded() {
        installCronet(quicHintHost = null)
        val client = OkHttpClient()

        // (a) gzip: Caddy compresses (the engine's own Accept-Encoding always includes
        // gzip), Cronet decodes the body transparently, and the bridge strips
        // Content-Encoding/Content-Length (keepEncodingAffectedHeaders): the caller sees
        // plaintext with no encoding headers, mirroring OkHttp's transparent gzip.
        client.newCall(Request.Builder().url("$ORIGIN/compress/gzip").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(
                "Content-Encoding should be gone after decode, headers=${response.headers}",
                response.header("Content-Encoding"),
            )
            assertEquals("gzip-payload-ok", response.body.string())
        }

        // (b) Unencoded response: the bridge keeps Content-Length. An explicit
        // Accept-Encoding: identity is denied (content_encoding), so /ok pins that
        // behavior on the Cronet path. The Sarie-built engine advertises gzip, deflate.
        client.newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(response.header("Content-Encoding"))
            assertEquals(2L, response.body.contentLength())
            assertEquals("ok", response.body.string())
        }

        assertCronetServed(minCount = 2)
    }

    @Test
    fun brotliDecodedOnBorrowedEngine() {
        // /compress/br forces Content-Encoding: br. The Sarie-built engine leaves brotli off,
        // so the successful decode runs on a borrowed engine that enables it.
        installBorrowed(brotli = true)
        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/compress/br").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(
                "expected Cronet to decode br, headers=${response.headers}",
                response.header("Content-Encoding"),
            )
            assertEquals("br-payload-ok", response.body.string())
        }
        assertCronetServed()
    }

    @Test
    fun acceptEncodingCallerHeaderReplacedAndDecodeKeptConsistent() {
        installCronet(quicHintHost = null)
        val client = OkHttpClient()

        // Default request: no Accept-Encoding, so the call stays on Cronet. The Sarie-built
        // engine has brotli off and advertises gzip, deflate.
        client.newCall(Request.Builder().url("$ORIGIN/headers").build()).execute().use { response ->
            assertEquals(200, response.code)
            val echoed = response.body.string()
            println("AE-ECHO-BEGIN\n$echoed\nAE-ECHO-END")
            assertTrue(
                "Sarie-built engine must advertise gzip, deflate (no br), got:\n$echoed",
                echoed.contains("Accept-Encoding: [gzip, deflate]"),
            )
            assertFalse(
                "brotli must not be advertised on the Sarie-built engine, got:\n$echoed",
                echoed.contains("Accept-Encoding: [gzip, deflate, br]") || echoed.contains(", br]"),
            )
        }
        assertCronetServed()

        Metrics.resetForTest()

        // Explicit identity: the app owns decoding, so the call falls back to stock.
        client.newCall(
            Request.Builder().url("$ORIGIN/headers")
                .header("Accept-Encoding", "identity")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            val echoed = response.body.string()
            println("AE-IDENTITY-ECHO-BEGIN\n$echoed\nAE-IDENTITY-ECHO-END")
            assertTrue(
                "stock must forward the caller Accept-Encoding, got:\n$echoed",
                echoed.contains("Accept-Encoding: [identity]"),
            )
        }
        assertStockServed(Metrics.Reason.content_encoding)
    }

    @Test
    fun rangeRequestGoesViaCronetWithIdentityEncoding() {
        installCronet(quicHintHost = null)
        val client = OkHttpClient()

        // No Accept-Encoding, but a Range header: BridgeInterceptor does not add gzip, and
        // Chromium sends identity. The bridge must keep Content-Length.
        client.newCall(
            Request.Builder().url("$ORIGIN/headers")
                .header("Range", "bytes=0-")
                .build(),
        ).execute().use { response ->
            assertTrue("unexpected status ${response.code}", response.code in 200..299)
            val echoed = response.body.string()
            println("RANGE-ECHO-BEGIN\n$echoed\nRANGE-ECHO-END")
            assertTrue(
                "expected Chromium Accept-Encoding identity, got:\n$echoed",
                echoed.contains("Accept-Encoding: [identity]"),
            )
            val length = response.header("Content-Length")
            assertEquals(
                "identity body must keep Content-Length, headers=${response.headers}",
                echoed.toByteArray(Charsets.UTF_8).size.toString(),
                length,
            )
        }
        assertCronetServed()
    }

    @Test
    fun hostHeaderAndDuplicatesDocumented() {
        installCronet(quicHintHost = null)
        val client = OkHttpClient()

        // (a) duplicate header values through the mapping layer.
        client.newCall(
            Request.Builder().url("$ORIGIN/headers")
                .header("X-Dup", "one")
                .addHeader("X-Dup", "two")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            val echoed = response.body.string()
            println("HEADER-ECHO-BEGIN\n$echoed\nHEADER-ECHO-END")
            // Repeated names are one field: ", "-joined. The server sees "one, two".
            assertTrue(
                "expected joined duplicate values on the wire, got:\n$echoed",
                echoed.contains("X-Dup: [one, two]"),
            )
            assertFalse(
                "duplicates must not collapse to the last value, got:\n$echoed",
                echoed.contains("X-Dup: [two]"),
            )
            // Host is derived from the URL by the engine stack.
            assertTrue(
                "expected URL-derived Host header, got:\n$echoed",
                echoed.contains("Host: [$HOST:$PORT]"),
            )
        }

        // (b) app-set Host override: pin what the pinned engine actually does with it.
        client.newCall(
            Request.Builder().url("$ORIGIN/headers")
                .header("Host", "override.example")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            val echoed = response.body.string()
            println("HOST-OVERRIDE-ECHO-BEGIN\n$echoed\nHOST-OVERRIDE-ECHO-END")
            assertTrue(
                "Host override handling changed; observed:\n$echoed",
                echoed.contains("Host: [$HOST:$PORT]"),
            )
        }
        assertCronetServed(minCount = 2)
    }

    @Test
    fun killSwitchRoutesStock() {
        // Policy enabled=false (MODE_FALLBACK): kill switch off -> everything served stock.
        SampleAppRuntime.install(SampleAppRuntime.MODE_FALLBACK, freshStorage = true)
        installedEngine = SampleAppRuntime.lastEngine

        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
        }
        // PolicyEngine records the deny as reason=disabled (kill-switch rule precedes
        // cleartext/allowlist in the rule order).
        assertStockServed(Metrics.Reason.disabled)
    }

    @Test
    fun redirectFollowsAndReentersCronet() {
        installCronet()

        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/redirect").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("$ORIGIN/ok", response.request.url.toString())
            assertEquals("ok", response.body.string())
        }
        // The 302 surfaced to OkHttp's RetryAndFollowUp, and the follow-up request re-entered
        // the trampoline: both hops served by Cronet.
        assertCronetServed(minCount = 2)
    }

    @Test
    fun authenticatorRetries401OverCronet() {
        installCronet(quicHintHost = null)
        val routes = mutableListOf<Route?>()
        val client = OkHttpClient.Builder()
            .authenticator { route, response ->
                routes += route
                if (response.request.header("Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Authorization", "Bearer token")
                        .build()
                }
            }
            .build()

        client.newCall(Request.Builder().url("$ORIGIN/auth").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
            // No quic hint, and local QUIC is blocked (h2LocalOriginWhileQuicBlocked),
            // so this origin's Cronet response is HTTP_2. The plan's HTTP_3 end state
            // is not observable here; two Cronet hops and route == null are.
            assertEquals(Protocol.HTTP_2, response.protocol)
        }
        assertEquals(listOf<Route?>(null), routes)
        // 401 and the Authorization retry both stay on Cronet.
        assertCronetServed(minCount = 2)
    }

    @Test
    fun networkInterceptorsSeeRequestResponseAndHttp3() {
        NetworkParity.loggingAndChuckerSeeHttp3 {
            installCronet(quicHintHost = "cloudflare-quic.com", quicHintPort = 443)
        }
    }

    @Test
    fun networkInterceptorHeaderReachesOrigin() {
        NetworkParity.addedHeaderReachesOrigin({ installCronet(quicHintHost = null) }, ORIGIN)
    }

    @Test
    fun networkInterceptorUrlAndProceedGuards() {
        NetworkParity.urlGuardsThrowStockMessages({ installCronet(quicHintHost = null) }, ORIGIN)
    }

    @Test
    fun networkInterceptorReadTimeoutAbortsStall() {
        NetworkParity.readTimeoutFromNetworkInterceptorAborts({ installCronet(quicHintHost = null) }, ORIGIN)
    }

    @Test
    fun eventListenerHeaderOrderAroundCronetHandoff() {
        NetworkParity.eventListenerHeaderOrder({ installCronet(quicHintHost = null) }, ORIGIN)
    }

    @Test
    fun cacheableGetSecondRequestIsHitWithNullHandshake() {
        // Local origin negotiates HTTP/2 (QUIC to this CA is blocked; see the class KDoc).
        // A cache hit never re-enters the bridge, so the origin is not contacted again.
        installCronet(quicHintHost = null)
        val cache = newCache()
        var cacheHits = 0
        val client = OkHttpClient.Builder()
            .cache(cache)
            .eventListener(object : EventListener() {
                override fun cacheHit(call: Call, response: Response) {
                    cacheHits++
                }
            })
            .build()
        try {
            client.newCall(Request.Builder().url("$ORIGIN/cacheable").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("cacheable-ok", response.body.string())
                assertNull(response.cacheResponse)
                assertNull(response.handshake)
            }
            assertCronetServed()
            val cronetAfterStore = Metrics.cronet.get()
            client.newCall(Request.Builder().url("$ORIGIN/cacheable").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("cacheable-ok", response.body.string())
                assertNotNull(response.cacheResponse)
                assertNull(response.handshake)
                assertNull(response.cacheResponse!!.handshake)
            }
            assertEquals(cronetAfterStore, Metrics.cronet.get())
            assertEquals(1, cacheHits)
            assertNull(Metrics.lastReason)
        } finally {
            cache.close()
        }
    }

    @Test
    fun etagRevalidationStaysOnCronet() {
        // This origin negotiates HTTP/2, not HTTP/3 (local QUIC is blocked; see the class KDoc).
        // The 304 is still the Cronet transport: Metrics.cronet and lastReason == null.
        // Do not require Protocol.HTTP_3 here.
        installCronet(quicHintHost = null)
        val cache = newCache()
        val client = OkHttpClient.Builder().cache(cache).build()
        try {
            client.newCall(Request.Builder().url("$ORIGIN/etag").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("\"sarie-etag\"", response.header("ETag"))
                assertEquals("etag-body", response.body.string())
            }
            assertCronetServed()
            client.newCall(Request.Builder().url("$ORIGIN/etag").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("etag-body", response.body.string())
                assertNotNull(response.cacheResponse)
                assertNotNull(response.networkResponse)
                assertEquals(304, response.networkResponse!!.code)
            }
            assertCronetServed(minCount = 2)
        } finally {
            cache.close()
        }
    }

    @Test
    fun onlyIfCachedServesSarieEntry() {
        installCronet(quicHintHost = null)
        val cache = newCache()
        val client = OkHttpClient.Builder().cache(cache).build()
        try {
            client.newCall(Request.Builder().url("$ORIGIN/cacheable").build()).execute().use { response ->
                assertEquals("cacheable-ok", response.body.string())
            }
            val cronetAfterStore = Metrics.cronet.get()
            client.newCall(
                Request.Builder()
                    .url("$ORIGIN/cacheable")
                    .cacheControl(CacheControl.FORCE_CACHE)
                    .build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("cacheable-ok", response.body.string())
                assertNotNull(response.cacheResponse)
                assertNull(response.handshake)
            }
            assertEquals(cronetAfterStore, Metrics.cronet.get())
            assertNull(Metrics.lastReason)
        } finally {
            cache.close()
        }
    }

    @Test
    fun killSwitchMissesSarieEntryAndStockRefetchHasHandshake() {
        installCronet(quicHintHost = null)
        val cache = newCache()
        val client = OkHttpClient.Builder().cache(cache).build()
        try {
            client.newCall(Request.Builder().url("$ORIGIN/cacheable").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertNull(response.handshake)
                assertEquals("cacheable-ok", response.body.string())
            }
            assertCronetServed()
            System.setProperty("okhttp.cronet.enabled", "false")
            client.newCall(Request.Builder().url("$ORIGIN/cacheable").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("cacheable-ok", response.body.string())
                assertNull(response.cacheResponse)
                assertNotNull(response.networkResponse)
                assertNotNull(response.handshake)
            }
            assertTrue(Metrics.okhttpFallback.get() >= 1)
            assertEquals(Metrics.Reason.disabled, Metrics.lastReason)
            client.newCall(Request.Builder().url("$ORIGIN/cacheable").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertNotNull(response.cacheResponse)
                assertNotNull(response.handshake)
                assertEquals("cacheable-ok", response.body.string())
            }
        } finally {
            System.clearProperty("okhttp.cronet.enabled")
            cache.close()
        }
    }

    @Test
    fun evictAllDropsSarieEntries() {
        installCronet(quicHintHost = null)
        val cache = newCache()
        val client = OkHttpClient.Builder().cache(cache).build()
        try {
            client.newCall(Request.Builder().url("$ORIGIN/cacheable").build()).execute().use { response ->
                assertEquals("cacheable-ok", response.body.string())
            }
            cache.evictAll()
            assertFalse(cache.urls().hasNext())
            val cronetAfterStore = Metrics.cronet.get()
            client.newCall(
                Request.Builder()
                    .url("$ORIGIN/cacheable")
                    .cacheControl(CacheControl.FORCE_CACHE)
                    .build(),
            ).execute().use { response ->
                assertEquals(504, response.code)
            }
            assertEquals(cronetAfterStore, Metrics.cronet.get())
        } finally {
            cache.close()
        }
    }

    private fun newCache(): Cache {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "okhttp-cache-" + System.nanoTime())
        return Cache(dir, 10L * 1024 * 1024)
    }

    @Test
    fun engineMissingFallsBackStock() {
        // No install: the trampoline finds no snapshot -> exact-stock fallback.
        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
        }
        assertStockServed(Metrics.Reason.engine_missing)
    }

    @Test
    fun correctPinNegotiatesH3() {
        // Stock probe learns the live SPKI pin. The Sarie-built engine installs that pin and
        // the same client is allowed through; cloudflare-quic.com is h3-only.
        val pin = OkHttpClient().newCall(Request.Builder().url("https://cloudflare-quic.com/").build())
            .execute()
            .use { response ->
                CertificatePinner.pin(checkNotNull(response.handshake).peerCertificates.first())
            }
        Metrics.resetForTest()
        val client = OkHttpClient.Builder()
            .certificatePinner(CertificatePinner.Builder().add("cloudflare-quic.com", pin).build())
            .build()
        SampleAppRuntime.install(
            mode = SampleAppRuntime.MODE_CRONET,
            quicHintHost = "cloudflare-quic.com",
            quicHintPort = 443,
            client = client,
        )
        installedEngine = SampleAppRuntime.lastEngine

        client.newCall(Request.Builder().url("https://cloudflare-quic.com/").build()).execute().use { response ->
            assertEquals(Protocol.HTTP_3, response.protocol)
            assertEquals(200, response.code)
        }
        assertCronetServed()
    }

    @Test
    fun wrongPinIsPeerUnverifiedWithoutRetryOrStock() {
        val client = OkHttpClient.Builder()
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("cloudflare-quic.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build(),
            )
            .build()
        SampleAppRuntime.install(
            mode = SampleAppRuntime.MODE_CRONET,
            quicHintHost = "cloudflare-quic.com",
            quicHintPort = 443,
            client = client,
        )
        installedEngine = SampleAppRuntime.lastEngine

        val thrown = assertThrows(SSLPeerUnverifiedException::class.java) {
            client.newCall(Request.Builder().url("https://cloudflare-quic.com/").build()).execute()
        }
        // Exact message: stock's failure includes the peer chain after this prefix.
        assertEquals("Certificate pinning failure!", thrown.message)
        assertEquals(0L, Metrics.retries.get())
        assertTrue("pin failure must stay on Cronet, cronet=${Metrics.cronet.get()}", Metrics.cronet.get() >= 1)
        assertEquals(0L, Metrics.okhttpFallback.get())
        assertNull(Metrics.lastReason)
    }

    @Test
    fun singleLabelWildcardPinFallsBack() {
        // `*.0.2.2` matches 10.0.2.2 (one label) and has no Cronet equivalent, so it is not
        // installed even when this client is the one Sarie built the engine from.
        val client = OkHttpClient.Builder()
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("*.0.2.2", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build(),
            )
            .build()
        SampleAppRuntime.install(
            mode = SampleAppRuntime.MODE_CRONET,
            quicHintHost = null,
            client = client,
        )
        installedEngine = SampleAppRuntime.lastEngine

        try {
            client.newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().close()
        } catch (_: IOException) {
            // Stock pin check or connect failure. The routing reason is what this test locks.
        }
        assertStockServed(Metrics.Reason.pins)
    }

    @Test
    fun secondEngineReusesH3WithoutQuicHint() {
        // Open question: HTTP/3 server info survives disableCache + HTTP_CACHE_DISK_NO_HTTP.
        // A failure here must stay obvious — do not accept h2 or a thrown connect error.
        installCronet(quicHintHost = "cloudflare-quic.com", quicHintPort = 443)
        OkHttpClient().newCall(Request.Builder().url("https://cloudflare-quic.com/").build())
            .execute()
            .use { response ->
                assertEquals(Protocol.HTTP_3, response.protocol)
            }
        val first = installedEngine
        SarieBridge.uninstall()
        // Storage dir is locked while the first engine is alive. Shutdown here is the test
        // simulating process exit; SarieBridge never calls shutdown.
        @Suppress("DEPRECATION")
        first?.shutdown()
        installedEngine = null
        Metrics.resetForTest()

        installCronet(quicHintHost = null)
        OkHttpClient().newCall(Request.Builder().url("https://cloudflare-quic.com/").build())
            .execute()
            .use { response ->
                assertEquals(
                    "second engine on the same storage path must negotiate h3 on its first " +
                        "request with no QUIC hint (client saw ${response.protocol})",
                    Protocol.HTTP_3,
                    response.protocol,
                )
            }
        assertCronetServed()
    }

    @Test
    fun borrowedDiskCacheReachesOriginTwice() {
        installBorrowed(diskCache = true)
        val client = OkHttpClient()
        val bodies = mutableListOf<String>()
        repeat(2) {
            client.newCall(Request.Builder().url("$ORIGIN/cacheable-unique").build()).execute().use { response ->
                assertEquals(200, response.code)
                // Cronet path does not fabricate these, including when Cronet itself served a cache hit.
                assertNull(response.networkResponse)
                assertNull(response.cacheResponse)
                bodies += response.body.string()
            }
        }
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { it.isNotBlank() })
        assertTrue(
            "borrowed HTTP_CACHE_DISK served the second GET (same body); disableCache() did not stick. " +
                "bodies=$bodies",
            bodies[0] != bodies[1],
        )
        assertCronetServed(minCount = 2)
    }

    @Test
    fun wireHeaderParityStockVersusCronet() {
        // Full echo of one request through stock and through the Sarie-built engine.
        // Prepared to notice Cronet-added headers (do not strip or override them here),
        // including Accept-Language, plus value changes on User-Agent, Accept-Encoding,
        // and Connection. A diff fails this test so the set stays visible.
        val request = Request.Builder()
            .url("$ORIGIN/headers")
            .header("X-Parity", "same")
            .build()
        val stockEcho = OkHttpClient().newCall(request).execute().use { it.body.string() }
        Metrics.resetForTest()
        installCronet(quicHintHost = null)
        val cronetEcho = OkHttpClient().newCall(request).execute().use { it.body.string() }
        assertCronetServed()

        val stock = echoedHeaders(stockEcho)
        val cronet = echoedHeaders(cronetEcho)
        assertEquals(listOf("same"), cronet["X-Parity"])
        val diff = headerDiff(stock, cronet)
        assertTrue("wire header diff (Cronet-added names must be recorded, not stripped):\n$diff", diff.isEmpty())
    }

    /** Caddy `headers.tmpl`: `Name: [v1] [v2]`, plus a leading `Host: [...]` line. */
    private fun echoedHeaders(body: String): Map<String, List<String>> {
        val values = Regex("\\[([^\\]]*)\\]")
        val headers = linkedMapOf<String, List<String>>()
        for (line in body.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val parsed = values.findAll(line.substring(colon + 1)).map { it.groupValues[1] }.toList()
            if (name.isNotEmpty() && parsed.isNotEmpty()) headers[name] = parsed
        }
        return headers
    }

    private fun headerDiff(
        stock: Map<String, List<String>>,
        cronet: Map<String, List<String>>,
    ): String {
        val added = (cronet.keys - stock.keys).sorted()
        val removed = (stock.keys - cronet.keys).sorted()
        val changed = stock.keys.intersect(cronet.keys).filter { stock[it] != cronet[it] }.sorted()
        return buildString {
            if (added.isNotEmpty()) append("cronet-added: $added\n")
            if (removed.isNotEmpty()) append("stock-only: $removed\n")
            for (name in changed) append("$name stock=${stock[name]} cronet=${cronet[name]}\n")
        }
    }
}
