package sarie.bridge

import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import okhttp3.CertificatePinner
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTranslationTest {

    private fun sha256(fill: Byte): String = "sha256/" + ByteArray(32) { fill }.toByteString().base64()

    private fun sha1(fill: Byte): String = "sha1/" + ByteArray(20) { fill }.toByteString().base64()

    private val shaA = sha256(1)
    private val shaB = sha256(2)

    private fun pinner(builder: CertificatePinner.Builder.() -> Unit): CertificatePinner =
        CertificatePinner.Builder().apply(builder).build()

    @Test
    fun `exact host is installed without subdomains`() {
        val pins = pinner { add("example.com", shaA) }.pins
        val translation = translatePins(pins)
        val group = translation.groups.single()
        assertEquals("example.com", group.host)
        assertFalse(group.includeSubdomains)
        assertTrue(pins.single().hash.toByteArray().contentEquals(group.hashes.single()))
        assertEquals(pins, translation.installedPins)
    }

    @Test
    fun `hashes for one pattern are one group`() {
        val pins = pinner { add("example.com", shaA, shaB) }.pins
        val group = translatePins(pins).groups.single()
        assertEquals(2, group.hashes.size)
        val expected = pins.sortedBy { it.hash.base64() }.map { it.hash.toByteArray() }
        assertEquals(expected.size, group.hashes.size)
        expected.zip(group.hashes).forEach { (want, got) ->
            assertTrue(want.contentEquals(got))
        }
    }

    @Test
    fun `double wildcard includes subdomains and single wildcard is not installed`() {
        val built = pinner {
            add("example.com", shaA)
            add("**.example.com", shaB)
            add("*.example.com", shaA)
        }
        val translation = translatePins(built.pins)
        assertEquals(2, translation.groups.size)
        val exact = translation.groups.single { !it.includeSubdomains }
        val sub = translation.groups.single { it.includeSubdomains }
        assertEquals("example.com", exact.host)
        assertEquals("example.com", sub.host)
        assertTrue(translation.installedPins.none { isSingleLabelWildcard(it.pattern) })
        assertTrue(built.pins.any { isSingleLabelWildcard(it.pattern) })
        assertEquals(
            built.pins.filter { !isSingleLabelWildcard(it.pattern) }.toSet(),
            translation.installedPins,
        )
    }

    @Test
    fun `sha1 and digit-dot hosts are not installed`() {
        val built = pinner {
            add("example.com", sha1(3))
            add("1.2.3.4", shaA)
        }
        val translation = translatePins(built.pins)
        assertTrue(translation.groups.isEmpty())
        assertTrue(translation.installedPins.isEmpty())
    }

    @Test
    fun `single label wildcard matches exactly one label`() {
        val built = pinner { add("*.0.2.2", shaA) }
        assertTrue(built.findMatchingPins("10.0.2.2").isNotEmpty())
        assertTrue(built.findMatchingPins("10.0.2.2").all { isSingleLabelWildcard(it.pattern) })
        assertTrue(translatePins(built.pins).groups.isEmpty())
        assertFalse(built.findMatchingPins("10.1.0.2.2").isNotEmpty())
    }

    @Test
    fun `pin expiry is 2100-01-01 UTC and not Long MAX_VALUE`() {
        val expiry = pinExpiryDate()
        assertEquals(Instant.parse("2100-01-01T00:00:00Z").toEpochMilli(), expiry.time)
        assertTrue(expiry.time != Long.MAX_VALUE)
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = expiry }
        assertEquals(2100, utc.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, utc.get(Calendar.MONTH))
        assertEquals(1, utc.get(Calendar.DAY_OF_MONTH))
    }
}
