package sarie.bridge

import androidx.annotation.OptIn
import java.util.Date
import okhttp3.CertificatePinner
import okio.ByteString.Companion.toByteString
import org.chromium.net.ConnectionMigrationOptions
import org.chromium.net.CronetEngine
import org.chromium.net.DnsOptions
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.ICronetEngineBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(markerClass = [ConnectionMigrationOptions.Experimental::class, DnsOptions.Experimental::class])
class EngineSetupTest {

    private class PinCall(
        val host: String,
        val hashes: Set<ByteArray>,
        val includeSubdomains: Boolean,
        val expirationDate: Date,
    )

    private class RecordingBuilder(
        private val rejectMigration: Boolean = false,
        private val rejectDns: Boolean = false,
    ) : ICronetEngineBuilder() {
        val events = mutableListOf<String>()
        var quic: Boolean? = null
        var http2: Boolean? = null
        var brotli: Boolean? = null
        var recordedStorage: String? = null
        var cacheMode: Int? = null
        var cacheMaxSize: Long? = null
        var pinBypass: Boolean? = null
        var migration: ConnectionMigrationOptions? = null
        var dns: DnsOptions? = null
        val pins = mutableListOf<PinCall>()

        override fun getSupportedConfigOptions(): Set<Int> =
            setOf(CONNECTION_MIGRATION_OPTIONS, DNS_OPTIONS)

        override fun enableQuic(enable: Boolean): ICronetEngineBuilder {
            events += "quic=$enable"
            quic = enable
            return this
        }

        override fun enableHttp2(enable: Boolean): ICronetEngineBuilder {
            events += "http2=$enable"
            http2 = enable
            return this
        }

        override fun enableBrotli(enable: Boolean): ICronetEngineBuilder {
            events += "brotli=$enable"
            brotli = enable
            return this
        }

        override fun setStoragePath(path: String): ICronetEngineBuilder {
            events += "storage=$path"
            recordedStorage = path
            return this
        }

        override fun enableHttpCache(cacheMode: Int, maxSize: Long): ICronetEngineBuilder {
            events += "cache=$cacheMode:$maxSize"
            this.cacheMode = cacheMode
            cacheMaxSize = maxSize
            return this
        }

        override fun enablePublicKeyPinningBypassForLocalTrustAnchors(enable: Boolean): ICronetEngineBuilder {
            events += "bypass=$enable"
            pinBypass = enable
            return this
        }

        override fun addPublicKeyPins(
            host: String,
            pins: Set<ByteArray>,
            includeSubdomains: Boolean,
            expirationDate: Date,
        ): ICronetEngineBuilder {
            events += "pins=$host:$includeSubdomains"
            this.pins += PinCall(host, pins, includeSubdomains, expirationDate)
            return this
        }

        override fun setConnectionMigrationOptions(options: ConnectionMigrationOptions): ICronetEngineBuilder {
            if (rejectMigration) throw UnsupportedOperationException("provider rejected migration")
            events += "migration"
            migration = options
            return this
        }

        override fun setDnsOptions(options: DnsOptions): ICronetEngineBuilder {
            if (rejectDns) throw UnsupportedOperationException("provider rejected dns")
            events += "dns"
            dns = options
            return this
        }

        override fun getDefaultUserAgent(): String = "recording"
        override fun build(): ExperimentalCronetEngine = throw UnsupportedOperationException()
        override fun addQuicHint(host: String?, port: Int, alternatePort: Int): ICronetEngineBuilder = this
        override fun enableSdch(enable: Boolean): ICronetEngineBuilder = this
        override fun setExperimentalOptions(options: String?): ICronetEngineBuilder = this
        override fun setLibraryLoader(loader: CronetEngine.Builder.LibraryLoader?): ICronetEngineBuilder = this
        override fun setUserAgent(userAgent: String?): ICronetEngineBuilder = this
    }

    private val sha = "sha256/" + ByteArray(32) { 7 }.toByteString().base64()

    @Test
    fun `configure cannot override bridge-owned settings`() {
        val pins = CertificatePinner.Builder().add("example.com", sha).build().pins
        val translation = translatePins(pins)
        val recording = RecordingBuilder()
        val builder = CronetEngine.Builder(recording)
        val storage = "/data/cache/cronet-cache"

        applyEngineConfiguration(builder, storage, translation.groups) {
            builder.enableQuic(false)
            builder.enableHttp2(false)
            builder.enableBrotli(true)
            builder.setStoragePath("/evil")
            builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 99L)
            builder.enablePublicKeyPinningBypassForLocalTrustAnchors(true)
            builder.addPublicKeyPins("evil.example", setOf(byteArrayOf(1)), true, Date(0))
        }

        val configureBrotli = recording.events.indexOf("brotli=true")
        val ownedBrotli = recording.events.lastIndexOf("brotli=false")
        val migration = recording.events.indexOf("migration")
        val dns = recording.events.indexOf("dns")
        assertTrue(migration < dns)
        assertTrue(dns < configureBrotli)
        assertTrue(configureBrotli < ownedBrotli)
        val evilPinEvent = recording.events.indexOf("pins=evil.example:true")
        val ownedPinEvent = recording.events.indexOf("pins=example.com:false")
        assertTrue(evilPinEvent < recording.events.indexOf("bypass=false"))
        assertTrue(evilPinEvent < ownedPinEvent)

        assertEquals(true, recording.quic)
        assertEquals(true, recording.http2)
        assertEquals(false, recording.brotli)
        assertEquals(storage, recording.recordedStorage)
        assertEquals(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, recording.cacheMode)
        assertEquals(0L, recording.cacheMaxSize)
        assertEquals(false, recording.pinBypass)
        assertEquals(true, recording.migration?.enableDefaultNetworkMigration)
        assertEquals(true, recording.migration?.enablePathDegradationMigration)
        assertEquals(true, recording.dns?.enableStaleDns)
        assertEquals(true, recording.dns?.preestablishConnectionsToStaleDnsResults)

        // addPublicKeyPins appends. Configure's pin stays; Sarie's pin is applied after it.
        assertEquals(listOf("evil.example", "example.com"), recording.pins.map { it.host })
        val evil = recording.pins.first()
        assertTrue(evil.includeSubdomains)
        val installed = recording.pins.last()
        assertEquals("example.com", installed.host)
        assertFalse(installed.includeSubdomains)
        assertEquals(pinExpiryDate(), installed.expirationDate)
        assertTrue(installed.expirationDate.time != Long.MAX_VALUE)
        assertTrue(
            translation.groups.single().hashes.single().contentEquals(installed.hashes.single()),
        )
    }

    @Test
    fun `rejected migration options do not fail setup`() {
        val recording = RecordingBuilder(rejectMigration = true)
        val builder = CronetEngine.Builder(recording)
        applyEngineConfiguration(builder, "/storage", emptyList()) {
            builder.enableBrotli(true)
        }
        assertNull(recording.migration)
        assertEquals(true, recording.dns?.enableStaleDns)
        assertEquals(false, recording.brotli)
        assertEquals("/storage", recording.recordedStorage)
        assertEquals(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, recording.cacheMode)
    }

    @Test
    fun `configure can turn stale dns off`() {
        val recording = RecordingBuilder()
        val builder = CronetEngine.Builder(recording)
        applyEngineConfiguration(builder, "/storage", emptyList()) {
            builder.setDnsOptions(DnsOptions.builder().enableStaleDns(false).build())
        }
        val defaults = recording.events.indexOf("dns")
        val override = recording.events.lastIndexOf("dns")
        assertTrue(defaults < override)
        assertEquals(false, recording.dns?.enableStaleDns)
        assertEquals(false, recording.brotli)
    }

    @Test
    fun `rejected dns options do not fail setup`() {
        val recording = RecordingBuilder(rejectDns = true)
        val builder = CronetEngine.Builder(recording)
        applyEngineConfiguration(builder, "/storage", emptyList())
        assertNull(recording.dns)
        assertEquals(true, recording.migration?.enableDefaultNetworkMigration)
        assertEquals(false, recording.brotli)
        assertEquals("/storage", recording.recordedStorage)
        assertEquals(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, recording.cacheMode)
    }
}
