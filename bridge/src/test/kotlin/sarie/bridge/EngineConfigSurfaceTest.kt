package sarie.bridge

import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.util.Date
import java.util.concurrent.Executor
import org.chromium.net.CronetEngine
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.ICronetEngineBuilder
import org.chromium.net.NetLogCaptureMode
import org.chromium.net.UrlRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The [SarieConfig] surface for engine settings: what a borrowed install rejects, what survives a
 * [SarieConfig.newBuilder], and the NetLog runtime control.
 */
class EngineConfigSurfaceTest {

    /** Records the NetLog calls a test drives through [SarieNetLog]. */
    private class RecordingEngine : CronetEngine() {
        val starts = mutableListOf<Triple<String, Boolean, Int>>()
        var stopCalls = 0
        var rejectStart = false

        override fun getVersionString(): String = "recording"

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun startNetLogToDisk(
            fileName: String,
            includeBasicInfo: Boolean,
            captureMode: Int,
        ) {
            if (rejectStart) throw UnsupportedOperationException("no netlog")
            starts += Triple(fileName, includeBasicInfo, captureMode)
        }

        @Suppress("DEPRECATION")
        override fun startNetLogToFile(fileName: String, logAll: Boolean) = Unit

        @Suppress("DEPRECATION")
        override fun stopNetLog() {
            stopCalls++
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun shutdown() = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getGlobalMetricsDeltas(): ByteArray = ByteArray(0)

        override fun openConnection(url: URL): URLConnection =
            throw UnsupportedOperationException("recording engine")

        override fun createURLStreamHandlerFactory(): URLStreamHandlerFactory =
            throw UnsupportedOperationException("recording engine")

        override fun newUrlRequestBuilder(
            url: String,
            callback: UrlRequest.Callback,
            executor: Executor,
        ): UrlRequest.Builder = throw UnsupportedOperationException("recording engine")
    }

    /** Delegates every builder call to a no-op except [setUserAgent], which it records. */
    private class UserAgentSpy : ICronetEngineBuilder() {
        val userAgents = mutableListOf<String?>()

        override fun getDefaultUserAgent(): String = "spy"
        override fun build(): ExperimentalCronetEngine = throw UnsupportedOperationException()
        override fun addPublicKeyPins(
            host: String,
            pins: Set<ByteArray>,
            includeSubdomains: Boolean,
            expirationDate: Date,
        ): ICronetEngineBuilder = this

        override fun addQuicHint(host: String?, port: Int, alternatePort: Int) = this
        override fun enableHttp2(enable: Boolean) = this
        override fun enableHttpCache(cacheMode: Int, maxSize: Long) = this
        override fun enablePublicKeyPinningBypassForLocalTrustAnchors(enable: Boolean) = this
        override fun enableQuic(enable: Boolean) = this
        override fun enableSdch(enable: Boolean) = this
        override fun setExperimentalOptions(options: String?) = this
        override fun setLibraryLoader(loader: CronetEngine.Builder.LibraryLoader?) = this
        override fun setStoragePath(path: String) = this

        override fun setUserAgent(userAgent: String?): ICronetEngineBuilder {
            userAgents += userAgent
            return this
        }
    }

    @Before
    fun setUp() {
        SarieBridge.uninstall()
        SarieNetLog.stop()
    }

    @After
    fun tearDown() {
        SarieNetLog.stop()
        SarieBridge.uninstall()
    }

    @Test
    fun `default config leaves the configure hook unset`() {
        val config = SarieConfig.DEFAULT
        assertFalse(config.isConfigureSet)
        assertNull(config.certificatePinner)
    }

    @Test
    fun `default config is accepted by a borrowed install`() {
        SarieBridge.install(RecordingEngine(), SarieConfig.DEFAULT)
        assertTrue(SarieBridge.isEnabled())
    }

    @Test
    fun `a borrowed install rejects configure by name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SarieBridge.install(RecordingEngine(), SarieConfig { configure { } })
        }
        assertEquals("configure cannot be used with a borrowed CronetEngine", error.message)
        assertFalse(SarieBridge.isEnabled())
    }

    @Test
    fun `a rejected borrowed install keeps the engine already published`() {
        val engine = RecordingEngine()
        SarieBridge.install(engine, SarieConfig.DEFAULT)
        assertThrows(IllegalArgumentException::class.java) {
            SarieBridge.install(RecordingEngine(), SarieConfig { configure { } })
        }
        assertSame(engine, SarieBridge.engine)
    }

    @Test
    fun `newBuilder carries the configure hook`() {
        val original = SarieConfig { configure { } }
        assertTrue(original.isConfigureSet)
        assertTrue(original.newBuilder().build().isConfigureSet)
    }

    @Test
    fun `a default config calls nothing on the builder`() {
        val spy = UserAgentSpy()

        SarieConfig.DEFAULT.configure(CronetEngine.Builder(spy))

        assertTrue(spy.userAgents.isEmpty())
    }

    @Test
    fun `netlog start before install is a no-op`() {
        assertNull(SarieBridge.engine)
        assertFalse(SarieNetLog.start("/tmp/early.json"))
        assertFalse(SarieNetLog.isCapturing)
    }

    @Test
    fun `netlog start and stop drive the installed engine`() {
        val engine = RecordingEngine()
        SarieBridge.install(engine, SarieConfig.DEFAULT)

        assertTrue(SarieNetLog.start("/tmp/cronet.json"))
        assertTrue(SarieNetLog.isCapturing)
        assertEquals(
            listOf(Triple("/tmp/cronet.json", true, NetLogCaptureMode.DEFAULT)),
            engine.starts,
        )

        SarieNetLog.stop()
        assertFalse(SarieNetLog.isCapturing)
        assertEquals(1, engine.stopCalls)
    }

    @Test
    fun `netlog passes the capture mode through`() {
        val engine = RecordingEngine()
        SarieBridge.install(engine, SarieConfig.DEFAULT)

        assertTrue(SarieNetLog.start("/tmp/x.json", NetLogCaptureMode.INCLUDE_SENSITIVE))
        assertEquals(
            listOf(Triple("/tmp/x.json", true, NetLogCaptureMode.INCLUDE_SENSITIVE)),
            engine.starts,
        )
    }

    @Test
    fun `netlog works on a borrowed engine too`() {
        val engine = RecordingEngine()
        SarieBridge.install(engine, SarieConfig { policy(object : CronetPolicy {
            override val allowedOrigins: Set<String> = setOf("example.com")
        }) })

        assertTrue(SarieNetLog.start("/tmp/borrowed.json"))
        SarieNetLog.stop()
        assertEquals(1, engine.starts.size)
        assertEquals(1, engine.stopCalls)
    }

    @Test
    fun `netlog rejects an unknown capture mode`() {
        SarieBridge.install(RecordingEngine(), SarieConfig.DEFAULT)
        assertThrows(IllegalArgumentException::class.java) {
            SarieNetLog.start("/tmp/x.json", 99)
        }
        assertFalse(SarieNetLog.isCapturing)
    }

    @Test
    fun `a rejected netlog start is reported and leaves capture off`() {
        val engine = RecordingEngine()
        engine.rejectStart = true
        SarieBridge.install(engine, SarieConfig.DEFAULT)

        assertFalse(SarieNetLog.start("/tmp/x.json"))
        assertFalse(SarieNetLog.isCapturing)

        // stop() after a failed start must not reach the engine either.
        SarieNetLog.stop()
        assertEquals(0, engine.stopCalls)
    }

    @Test
    fun `netlog start twice keeps the first capture`() {
        val engine = RecordingEngine()
        SarieBridge.install(engine, SarieConfig.DEFAULT)

        assertTrue(SarieNetLog.start("/tmp/first.json"))
        assertTrue(SarieNetLog.start("/tmp/second.json"))
        assertEquals(1, engine.starts.size)
        assertEquals("/tmp/first.json", engine.starts.single().first)
    }

    @Test
    fun `stop after stop is a no-op`() {
        val engine = RecordingEngine()
        SarieBridge.install(engine, SarieConfig.DEFAULT)
        SarieNetLog.start("/tmp/x.json")

        SarieNetLog.stop()
        SarieNetLog.stop()

        assertEquals(1, engine.stopCalls)
    }

    @Test
    fun `stop after uninstall still flushes the engine that was capturing`() {
        val engine = RecordingEngine()
        SarieBridge.install(engine, SarieConfig.DEFAULT)
        SarieNetLog.start("/tmp/x.json")

        SarieBridge.uninstall()
        SarieNetLog.stop()

        assertEquals(1, engine.stopCalls)
        assertFalse(SarieNetLog.isCapturing)
    }

    @Test
    fun `stop without an installed engine does not throw`() {
        SarieNetLog.stop()
        assertFalse(SarieNetLog.isCapturing)
    }
}