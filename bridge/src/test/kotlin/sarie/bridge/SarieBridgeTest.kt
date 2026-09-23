package sarie.bridge

import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.util.concurrent.Executor
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SarieBridgeTest {

    /** Minimal cronet-api double; counts shutdown() calls to prove the engine is never stopped. */
    private class FakeCronetEngine : CronetEngine() {
        var shutdownCalls = 0

        override fun getVersionString(): String = "fake"
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shutdown() {
            shutdownCalls++
        }

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

    private val policy = object : CronetPolicy {
        override val allowedOrigins: Set<String> = setOf("example.com")
    }

    private val mapper = RequestToUrlRequestMapper { _, _ -> }
    private val config = SarieConfig {
        policy(this@SarieBridgeTest.policy)
        mapper(this@SarieBridgeTest.mapper)
    }

    @Before
    fun setUp() {
        System.clearProperty("okhttp.cronet.enabled")
    }

    @After
    fun tearDown() {
        System.clearProperty("okhttp.cronet.enabled")
        SarieBridge.uninstall()
    }

    @Test
    fun `verified set is exactly the supported okhttp versions`() {
        assertEquals(setOf("5.4.0", "5.5.0"), VerifiedOkHttpVersions)
        warnIfUnverified("5.5.0")
        warnIfUnverified("5.4.0")
        warnIfUnverified("5.5.1")
        warnIfUnverified("4.12.0")
    }

    @Test
    fun `install with engine only uses DefaultPolicy and a no-op mapper`() {
        val engine = FakeCronetEngine()
        SarieBridge.install(engine)

        val snap = SarieBridge.snapshot()
        assertNotNull(snap)
        assertSame(engine, snap!!.engine)
        assertTrue(snap.policy is DefaultPolicy)
        assertTrue(snap.policy.allowedOrigins.isEmpty())
        assertSame(RequestToUrlRequestMapper.NOOP, snap.mapper)
        assertTrue(SarieBridge.isEnabled())
    }

    @Test
    fun `install stores snapshot`() {
        val engine = FakeCronetEngine()
        SarieBridge.install(engine, config)

        val snap = SarieBridge.snapshot()
        assertNotNull(snap)
        assertSame(engine, snap!!.engine)
        assertSame(policy, snap.policy)
        assertSame(mapper, snap.mapper)
        assertTrue(snap.installedAtMillis > 0)
        assertTrue(SarieBridge.isEnabled())
    }

    @Test
    fun `second install replaces snapshot atomically and never closes engines`() {
        val engine1 = FakeCronetEngine()
        val engine2 = FakeCronetEngine()

        SarieBridge.install(engine1, config)
        val first = SarieBridge.snapshot()!!
        SarieBridge.install(engine2, config)
        val second = SarieBridge.snapshot()!!

        assertSame(engine2, second.engine)
        assertTrue(first.engine !== second.engine)
        assertEquals(0, engine1.shutdownCalls)
        assertEquals(0, engine2.shutdownCalls)
    }

    @Test
    fun `borrowed install is not sarie-built and records no pins or provider`() {
        val engine = FakeCronetEngine()
        SarieBridge.install(engine, config)

        val snap = SarieBridge.snapshot()!!
        assertFalse(snap.sarieBuilt)
        assertTrue(snap.installedPins.isEmpty())
        assertNull(snap.providerName)
        assertNull(snap.providerVersion)
        assertEquals(0, engine.shutdownCalls)
    }

    @Test
    fun `borrowed install rejects certificatePinner`() {
        val engine = FakeCronetEngine()
        val pinner = CertificatePinner.Builder()
            .add("example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .build()
        val pinnerConfig = SarieConfig { certificatePinner(pinner) }
        val err = assertThrows(IllegalArgumentException::class.java) {
            SarieBridge.install(engine, pinnerConfig)
        }
        assertTrue(err.message!!.contains("certificatePinner"))
    }

    @Test
    fun `borrowed install rejects configure`() {
        val engine = FakeCronetEngine()
        val configureConfig = SarieConfig { configure { } }
        val err = assertThrows(IllegalArgumentException::class.java) {
            SarieBridge.install(engine, configureConfig)
        }
        assertTrue(err.message!!.contains("configure"))
    }

    @Test
    fun `uninstall drops snapshot and disables`() {
        SarieBridge.install(FakeCronetEngine(), config)
        SarieBridge.uninstall()

        assertNull(SarieBridge.snapshot())
        assertFalse(SarieBridge.isEnabled())
    }

    @Test
    fun `kill switch system property disables and restores`() {
        SarieBridge.install(FakeCronetEngine(), config)
        assertTrue(SarieBridge.isEnabled())

        try {
            System.setProperty("okhttp.cronet.enabled", "false")
            assertFalse(SarieBridge.isEnabled())
        } finally {
            System.clearProperty("okhttp.cronet.enabled")
        }
        assertTrue(SarieBridge.isEnabled())
    }

    @Test
    fun `no snapshot means disabled`() {
        assertNull(SarieBridge.snapshot())
        assertFalse(SarieBridge.isEnabled())
    }

    @Test
    fun `reason enum lists the pre-send denies`() {
        assertEquals(
            listOf(
                "disabled", "engine_missing", "tag_opt_out", "allowlist", "cleartext", "websocket",
                "h2_prior_knowledge", "proxy", "socket_factory", "hostname_verifier", "pins",
                "trust", "dns", "content_encoding", "policy_error", "content_type",
            ),
            FallbackReason.values().map { it.name },
        )
    }

    @Test
    fun `logger receives unverified okhttp warning`() {
        val messages = mutableListOf<String>()
        val priorities = mutableListOf<Int>()
        val testLogger = SarieLogger { priority, message, _ ->
            priorities += priority
            messages += message
        }
        val engine = FakeCronetEngine()
        SarieBridge.install(engine, SarieConfig { debugLogger(testLogger) })
        warnIfUnverified("9.9.9")
        assertEquals(listOf(Log.INFO, Log.WARN), priorities)
        assertTrue(messages.any { it.contains("9.9.9") })
    }

    @Test
    fun `borrowed install logs summary line`() {
        val messages = mutableListOf<String>()
        val priorities = mutableListOf<Int>()
        val testLogger = SarieLogger { priority, message, _ ->
            priorities += priority
            messages += message
        }
        val engine = FakeCronetEngine()
        SarieBridge.install(engine, SarieConfig { debugLogger(testLogger) })
        assertEquals(listOf(Log.INFO), priorities)
        assertEquals(
            "Cronet installed (borrowed): version=fake, pins=0",
            messages.single(),
        )
    }

    @Test
    fun `SarieLogger Logcat smoke test`() {
        SarieLogger.Logcat.log(Log.INFO, "test info", null)
        SarieLogger.Logcat.log(Log.WARN, "test warn", RuntimeException("boom"))
    }

    @Test
    fun `a newer install generation makes the older one stale`() {
        val generations = InstallGeneration()
        val older = generations.next()
        assertTrue(generations.isCurrent(older))
        val newer = generations.next()
        assertFalse(generations.isCurrent(older))
        assertTrue(generations.isCurrent(newer))
    }

    @Test
    fun `rejected borrowed install keeps the current snapshot`() {
        val engine = FakeCronetEngine()
        SarieBridge.install(engine, config)
        assertThrows(IllegalArgumentException::class.java) {
            SarieBridge.install(FakeCronetEngine(), SarieConfig { configure { } })
        }
        assertSame(engine, SarieBridge.snapshot()?.engine)
    }

    @Test
    fun `install records bypassable dns on snapshot`() {
        val dns = Dns { emptyList() }
        val cfg = SarieConfig { bypassableDns(dns) }
        SarieBridge.install(FakeCronetEngine(), cfg)
        val snap = SarieBridge.snapshot()
        assertNotNull(snap)
        assertTrue(dns in snap!!.bypassableDns)
    }
}
