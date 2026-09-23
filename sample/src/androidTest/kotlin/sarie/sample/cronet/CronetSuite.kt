package sarie.sample.cronet

import sarie.bridge.Metrics
import sarie.bridge.SarieBridge
import sarie.sample.NetworkParity
import sarie.sample.SampleAppRuntime
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.chromium.net.CronetEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        // (b) Unencoded response: the bridge keeps Content-Length (identity passthrough
        // clause of keepEncodingAffectedHeaders). Pinned cronet-embedded 143 REPLACES the
        // caller's Accept-Encoding with its own "gzip, deflate, br" (observed server-side),
        // so an identity request on /compress/gzip is not expressible on the Cronet path;
        // an unencoded endpoint pins the same bridge behavior.
        client.newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(response.header("Content-Encoding"))
            assertEquals(2L, response.body.contentLength())
            assertEquals("ok", response.body.string())
        }

        // (c) Brotli: the engine's own Accept-Encoding always includes br, so Cronet decodes
        // the br response exactly like gzip and the bridge strips the encoding headers -
        // raw brotli bytes are never surfaced to OkHttp (which itself cannot decode br).
        client.newCall(Request.Builder().url("$ORIGIN/compress/br").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(
                "expected Cronet to decode br, headers=${response.headers}",
                response.header("Content-Encoding"),
            )
            assertEquals("br-payload-ok", response.body.string())
        }
        assertCronetServed(minCount = 3)
    }

    @Test
    fun acceptEncodingCallerHeaderReplacedAndDecodeKeptConsistent() {
        installCronet(quicHintHost = null)
        val client = OkHttpClient()

        // (a) An explicit caller Accept-Encoding does NOT reach the wire on the pinned engine
        // (cronet-embedded 143.7445.0, observed server-side): the engine replaces it with its
        // own "gzip, deflate, br" and transparently decodes. There is no per-request control
        // (no API on UrlRequest.Builder; the mapper hook also runs at the builder level, but
        // the replacement happens natively at request execution) and engine-level
        // enableBrotli only affects brotli ADVERTISING - so the bridge keeps Cronet's decode
        // and strips Content-Encoding/Content-Length for all-engine-handled encodings.
        client.newCall(
            Request.Builder().url("$ORIGIN/headers")
                .header("Accept-Encoding", "identity")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            val echoed = response.body.string()
            println("AE-ECHO-BEGIN\n$echoed\nAE-ECHO-END")
            assertTrue(
                "pinned engine must replace the caller Accept-Encoding, got:\n$echoed",
                echoed.contains("Accept-Encoding: [gzip, deflate, br]"),
            )
            assertFalse(
                "the caller's explicit Accept-Encoding must not survive:\n$echoed",
                echoed.contains("[identity]"),
            )
        }

        // (b) Invariant (never double-decode, never a header/body mismatch): exactly ONE
        // decode happens - the engine's - even when the caller asked for something else.
        // If the bridge delivered encoded bytes with the headers stripped, this body would
        // be raw gzip garbage; if it decoded AND OkHttp's BridgeInterceptor decoded, the
        // GzipSource would throw on plaintext. Plaintext proves exactly one decode and
        // consistent headers.
        client.newCall(
            Request.Builder().url("$ORIGIN/compress/gzip")
                .header("Accept-Encoding", "identity")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(
                "decoded body must not keep Content-Encoding, headers=${response.headers}",
                response.header("Content-Encoding"),
            )
            assertEquals("gzip-payload-ok", response.body.string())
        }
        assertCronetServed(minCount = 2)
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
            // Pinned against cronet-embedded 143.7445.0 (observed, not assumed): the mapper
            // adds both duplicate values, but the engine collapses them to the LAST value on
            // the wire. Regression-visible contract for COMPATIBILITY.md: duplicate request
            // headers do not survive the Cronet path - only the final value is sent.
            assertTrue(
                "expected only the last duplicate value on the wire, got:\n$echoed",
                echoed.contains("X-Dup: [two]"),
            )
            assertFalse(
                "earlier duplicate values must not reach the server, got:\n$echoed",
                echoed.contains("[one]"),
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
    fun authenticatorRoutesToStockFallback() {
        installCronet(quicHintHost = null)
        val client = OkHttpClient.Builder()
            .authenticator { _, _ -> null } // custom (non-NONE) authenticator -> stock contract
            .build()

        client.newCall(Request.Builder().url("$ORIGIN/auth").build()).execute().use { response ->
            assertEquals(401, response.code)
            assertEquals("unauthorized", response.body.string())
        }
        // Custom authenticator traffic stays stock by design (Metis B2: 407s crash follow-ups;
        // policy diverts the whole client pre-send with reason=authenticator).
        assertStockServed(Metrics.Reason.authenticator)
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
    fun engineMissingFallsBackStock() {
        // No install: the trampoline finds no snapshot -> exact-stock fallback.
        OkHttpClient().newCall(Request.Builder().url("$ORIGIN/ok").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
        }
        assertStockServed(Metrics.Reason.engine_missing)
    }
}
