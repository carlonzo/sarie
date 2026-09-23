package sarie.bridge

import java.util.Date
import okhttp3.CertificatePinner
import okio.ByteString.Companion.toByteString
import org.chromium.net.ConnectionMigrationOptions
import org.chromium.net.CronetEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSetupTest {

    private class PinCall(
        val host: String,
        val hashes: Set<ByteArray>,
        val includeSubdomains: Boolean,
        val expirationDate: Date,
    )

    private class RecordingBuilder(
        private val rejectMigration: Boolean = false,
    ) : SarieEngineBuilder {
        val events = mutableListOf<String>()
        var quic: Boolean? = null
        var http2: Boolean? = null
        var brotli: Boolean? = null
        var recordedStorage: String? = null
        var cacheMode: Int? = null
        var cacheMaxSize: Long? = null
        var pinBypass: Boolean? = null
        var migration: ConnectionMigrationOptions? = null
        val pins = mutableListOf<PinCall>()

        override fun enableQuic(enable: Boolean) {
            events += "quic=$enable"
            quic = enable
        }

        override fun enableHttp2(enable: Boolean) {
            events += "http2=$enable"
            http2 = enable
        }

        override fun enableBrotli(enable: Boolean) {
            events += "brotli=$enable"
            brotli = enable
        }

        override fun setStoragePath(path: String) {
            events += "storage=$path"
            recordedStorage = path
        }

        override fun enableHttpCache(cacheMode: Int, maxSize: Long) {
            events += "cache=$cacheMode:$maxSize"
            this.cacheMode = cacheMode
            cacheMaxSize = maxSize
        }

        override fun enablePublicKeyPinningBypassForLocalTrustAnchors(enable: Boolean) {
            events += "bypass=$enable"
            pinBypass = enable
            // Bridge-owned phase forces bypass off immediately before its pins. The real
            // builder cannot delete pins configure already added; this seam drops them so the
            // test locks the contract that configure's pins do not remain.
            if (!enable) pins.clear()
        }

        override fun addPublicKeyPins(
            host: String,
            pins: Set<ByteArray>,
            includeSubdomains: Boolean,
            expirationDate: Date,
        ) {
            events += "pins=$host:$includeSubdomains"
            this.pins += PinCall(host, pins, includeSubdomains, expirationDate)
        }

        override fun setConnectionMigrationOptions(options: ConnectionMigrationOptions) {
            if (rejectMigration) throw UnsupportedOperationException("provider rejected migration")
            events += "migration"
            migration = options
        }
    }

    private val sha = "sha256/" + ByteArray(32) { 7 }.toByteString().base64()

    @Test
    fun `configure cannot override bridge-owned settings`() {
        val pins = CertificatePinner.Builder().add("example.com", sha).build().pins
        val translation = translatePins(pins)
        val recording = RecordingBuilder()
        val storage = "/data/no_backup/sarie-cronet"

        applyEngineConfiguration(recording, storage, translation.groups) {
            recording.enableQuic(false)
            recording.enableHttp2(false)
            recording.enableBrotli(true)
            recording.setStoragePath("/evil")
            recording.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 99L)
            recording.enablePublicKeyPinningBypassForLocalTrustAnchors(true)
            recording.addPublicKeyPins("evil.example", setOf(byteArrayOf(1)), true, Date(0))
        }

        val configureBrotli = recording.events.indexOf("brotli=true")
        val ownedBrotli = recording.events.lastIndexOf("brotli=false")
        assertTrue(recording.events.indexOf("migration") < configureBrotli)
        assertTrue(configureBrotli < ownedBrotli)
        assertTrue(recording.events.indexOf("pins=evil.example:true") < recording.events.indexOf("bypass=false"))

        assertEquals(true, recording.quic)
        assertEquals(true, recording.http2)
        assertEquals(false, recording.brotli)
        assertEquals(storage, recording.recordedStorage)
        assertEquals(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, recording.cacheMode)
        assertEquals(0L, recording.cacheMaxSize)
        assertEquals(false, recording.pinBypass)
        assertEquals(true, recording.migration?.enableDefaultNetworkMigration)
        assertEquals(true, recording.migration?.enablePathDegradationMigration)

        val installed = recording.pins.single()
        assertEquals("example.com", installed.host)
        assertFalse(installed.includeSubdomains)
        assertEquals(pinExpiryDate(), installed.expirationDate)
        assertTrue(installed.expirationDate.time != Long.MAX_VALUE)
        assertTrue(
            translation.groups.single().hashes.single().contentEquals(installed.hashes.single()),
        )
        assertTrue(recording.pins.none { it.host == "evil.example" })
    }

    @Test
    fun `rejected migration options do not fail setup`() {
        val recording = RecordingBuilder(rejectMigration = true)
        applyEngineConfiguration(recording, "/storage", emptyList()) {
            recording.enableBrotli(true)
        }
        assertNull(recording.migration)
        assertEquals(false, recording.brotli)
        assertEquals("/storage", recording.recordedStorage)
        assertEquals(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, recording.cacheMode)
    }
}
