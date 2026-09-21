package sarie.bridge

import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.util.concurrent.Executor
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Before
    fun setUp() {
        System.clearProperty("okhttp.cronet.enabled")
        Metrics.resetForTest()
    }

    @After
    fun tearDown() {
        System.clearProperty("okhttp.cronet.enabled")
        SarieBridge.uninstall()
        Metrics.resetForTest()
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
        SarieBridge.install(engine, policy, mapper)

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

        SarieBridge.install(engine1, policy, mapper)
        val first = SarieBridge.snapshot()!!
        SarieBridge.install(engine2, policy, mapper)
        val second = SarieBridge.snapshot()!!

        assertSame(engine2, second.engine)
        assertTrue(first.engine !== second.engine)
        assertEquals(0, engine1.shutdownCalls)
        assertEquals(0, engine2.shutdownCalls)
    }

    @Test
    fun `uninstall drops snapshot and disables`() {
        SarieBridge.install(FakeCronetEngine(), policy, mapper)
        SarieBridge.uninstall()

        assertNull(SarieBridge.snapshot())
        assertFalse(SarieBridge.isEnabled())
    }

    @Test
    fun `kill switch system property disables and restores`() {
        SarieBridge.install(FakeCronetEngine(), policy, mapper)
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
    @Suppress("DEPRECATION")
    fun `CronetRuntime alias points to SarieBridge`() {
        val engine = FakeCronetEngine()
        CronetRuntime.install(engine)
        assertSame(engine, SarieBridge.snapshot()?.engine)
        assertSame(engine, CronetRuntime.snapshot()?.engine)
        assertTrue(CronetRuntime.isEnabled())
        CronetRuntime.uninstall()
        assertNull(SarieBridge.snapshot())
    }

    @Test
    fun `record increments path counters and sets lastReason`() {
        Metrics.record(Metrics.Path.CRONET, null)
        Metrics.record(Metrics.Path.CRONET, null)
        Metrics.record(Metrics.Path.FALLBACK, Metrics.Reason.engine_missing)

        assertEquals(2L, Metrics.cronet.get())
        assertEquals(1L, Metrics.okhttpFallback.get())
        assertEquals(Metrics.Reason.engine_missing, Metrics.lastReason)
    }

    @Test
    fun `record without reason leaves lastReason untouched`() {
        Metrics.record(Metrics.Path.FALLBACK, Metrics.Reason.allowlist)
        Metrics.record(Metrics.Path.CRONET, null)

        assertEquals(1L, Metrics.cronet.get())
        assertEquals(1L, Metrics.okhttpFallback.get())
        assertEquals(Metrics.Reason.allowlist, Metrics.lastReason)
    }

    @Test
    fun `resetForTest clears counters and lastReason`() {
        Metrics.record(Metrics.Path.CRONET, Metrics.Reason.cleartext)
        Metrics.resetForTest()

        assertEquals(0L, Metrics.cronet.get())
        assertEquals(0L, Metrics.okhttpFallback.get())
        assertNull(Metrics.lastReason)
    }

    @Test
    fun `reason enum covers all 17 values`() {
        assertEquals(
            listOf(
                "disabled", "engine_missing", "tag_opt_out", "allowlist", "cleartext", "websocket",
                "cache", "network_interceptors", "h2_prior_knowledge", "authenticator", "proxy",
                "socket_factory", "hostname_verifier", "pins", "trust", "protocols", "engine_cold",
            ),
            Metrics.Reason.values().map { it.name },
        )
    }
}
