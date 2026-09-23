package sarie.bridge

import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.util.concurrent.Executor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formulas for the two cache call-site hooks. Off means stock; on skips a missing TLS block. */
class CacheHooksTest {

    @After
    fun tearDown() {
        System.clearProperty("okhttp.cronet.enabled")
        SarieBridge.uninstall()
    }

    @Test
    fun `disabled hooks match stock and do not read the source`() {
        val https = "https://example.com/a".toHttpUrl()
        val http = "http://example.com/a".toHttpUrl()
        val source = Buffer().writeUtf8("tls-block")
        assertTrue(CacheHooks.expectTlsBlock(https, source))
        assertEquals("tls-block", source.readUtf8())
        assertFalse(CacheHooks.expectTlsBlock(http, Buffer()))
        assertTrue(CacheHooks.requireHandshake(request(https)))
        assertFalse(CacheHooks.requireHandshake(request(http)))
    }

    @Test
    fun `enabled hooks skip an exhausted tls block and do not require a handshake`() {
        SarieBridge.install(FakeCronetEngine())
        val https = "https://example.com/a".toHttpUrl()
        assertFalse(CacheHooks.expectTlsBlock(https, Buffer()))
        val present = Buffer().writeUtf8("cipher")
        assertTrue(CacheHooks.expectTlsBlock(https, present))
        assertEquals("cipher", present.readUtf8())
        assertFalse(CacheHooks.expectTlsBlock("http://example.com/a".toHttpUrl(), Buffer()))
        assertFalse(CacheHooks.requireHandshake(request(https)))

        System.setProperty("okhttp.cronet.enabled", "false")
        val afterKill = Buffer().writeUtf8("still-there")
        assertTrue(CacheHooks.expectTlsBlock(https, afterKill))
        assertEquals("still-there", afterKill.readUtf8())
        assertTrue(CacheHooks.requireHandshake(request(https)))
    }

    @Test
    fun `uninstall restores the stock checks`() {
        val https = "https://example.com/a".toHttpUrl()
        SarieBridge.install(FakeCronetEngine())
        assertFalse(CacheHooks.requireHandshake(request(https)))
        SarieBridge.uninstall()
        assertTrue(CacheHooks.requireHandshake(request(https)))
        assertTrue(CacheHooks.expectTlsBlock(https, Buffer().writeUtf8("x")))
    }

    private fun request(url: okhttp3.HttpUrl): Request = Request.Builder().url(url).build()

    private class FakeCronetEngine : CronetEngine() {
        override fun getVersionString(): String = "fake"
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shutdown() = Unit
        override fun startNetLogToFile(fileName: String, logAll: Boolean) = Unit
        override fun stopNetLog() = Unit
        override fun getGlobalMetricsDeltas(): ByteArray = ByteArray(0)
        override fun openConnection(url: URL): URLConnection = throw UnsupportedOperationException("fake")
        override fun createURLStreamHandlerFactory(): URLStreamHandlerFactory = throw UnsupportedOperationException("fake")
        override fun newUrlRequestBuilder(
            url: String,
            callback: UrlRequest.Callback,
            executor: Executor,
        ): UrlRequest.Builder = throw UnsupportedOperationException("fake")
    }
}
