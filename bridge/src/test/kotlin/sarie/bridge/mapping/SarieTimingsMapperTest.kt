package sarie.bridge.mapping

import java.util.Date
import org.chromium.net.RequestFinishedInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sarie.bridge.SarieProtocol
import sarie.bridge.SarieTimings

class SarieTimingsMapperTest {

    @Test
    fun `durations and raw timestamps derived from dates`() {
        val t0 = 1_000_000L
        val metrics = FakeMetrics(
            requestStart = Date(t0),
            dnsStart = Date(t0 + 10),
            dnsEnd = Date(t0 + 35),
            connectStart = Date(t0 + 35),
            connectEnd = Date(t0 + 90),
            sslStart = Date(t0 + 50),
            sslEnd = Date(t0 + 90),
            sendingStart = Date(t0 + 95),
            sendingEnd = Date(t0 + 110),
            responseStart = Date(t0 + 150),
            requestEnd = Date(t0 + 200),
            socketReused = false,
            ttfbMs = 55L,
            totalTimeMs = 200L,
            sentByteCount = 512L,
            receivedByteCount = 2048L,
        )
        val responseInfo = FakeUrlResponseInfo(
            statusCode = 200,
            negotiatedProtocol = "h2",
        )
        val info = FakeRequestFinishedInfo(
            metrics = metrics,
            finishedReason = RequestFinishedInfo.SUCCEEDED,
            responseInfo = responseInfo,
        )

        val timings = SarieTimingsMapper.map(
            info = info,
            attempt = 1,
            isRedirect = false,
            deliveredLate = false,
        )

        assertEquals(SarieTimings.Result.SUCCEEDED, timings.result)
        assertEquals(SarieProtocol.HTTP_2, timings.protocol)
        assertEquals("h2", timings.negotiatedProtocol)
        assertEquals(200, timings.httpStatusCode)
        assertFalse(timings.socketReused)
        assertEquals(25L, timings.dnsMs)
        assertEquals(55L, timings.connectMs)
        assertEquals(40L, timings.tlsMs)
        assertEquals(15L, timings.sendMs)
        assertEquals(55L, timings.ttfbMs)
        assertEquals(200L, timings.totalMs)

        assertEquals(t0, timings.requestStartAtMillis)
        assertEquals(t0 + 10, timings.dnsStartAtMillis)
        assertEquals(t0 + 35, timings.dnsEndAtMillis)
        assertEquals(t0 + 35, timings.connectStartAtMillis)
        assertEquals(t0 + 90, timings.connectEndAtMillis)
        assertEquals(t0 + 50, timings.tlsStartAtMillis)
        assertEquals(t0 + 90, timings.tlsEndAtMillis)
        assertEquals(t0 + 95, timings.sendingStartAtMillis)
        assertEquals(t0 + 110, timings.sendingEndAtMillis)
        assertEquals(t0 + 150, timings.responseStartAtMillis)
        assertEquals(t0 + 200, timings.requestEndAtMillis)
        assertEquals(512L, timings.sentBytes)
        assertEquals(2048L, timings.receivedBytes)

        assertEquals(1, timings.attempt)
        assertFalse(timings.isRedirect)
        assertFalse(timings.deliveredLate)
    }

    @Test
    fun `nulls when socket reused`() {
        val t0 = 2_000_000L
        val metrics = FakeMetrics(
            requestStart = Date(t0),
            dnsStart = null,
            dnsEnd = null,
            connectStart = null,
            connectEnd = null,
            sslStart = null,
            sslEnd = null,
            sendingStart = Date(t0 + 5),
            sendingEnd = Date(t0 + 15),
            responseStart = Date(t0 + 40),
            requestEnd = Date(t0 + 60),
            socketReused = true,
            ttfbMs = 25L,
            totalTimeMs = 60L,
            sentByteCount = 120L,
            receivedByteCount = 500L,
        )
        val info = FakeRequestFinishedInfo(
            metrics = metrics,
            finishedReason = RequestFinishedInfo.SUCCEEDED,
            responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h2"),
        )

        val timings = SarieTimingsMapper.map(info, attempt = 1, isRedirect = false, deliveredLate = false)

        assertTrue(timings.socketReused)
        assertNull(timings.dnsMs)
        assertNull(timings.connectMs)
        assertNull(timings.tlsMs)
        assertNull(timings.dnsStartAtMillis)
        assertNull(timings.dnsEndAtMillis)
        assertNull(timings.connectStartAtMillis)
        assertNull(timings.connectEndAtMillis)
        assertNull(timings.tlsStartAtMillis)
        assertNull(timings.tlsEndAtMillis)
        assertEquals(10L, timings.sendMs)
        assertEquals(25L, timings.ttfbMs)
        assertEquals(60L, timings.totalMs)
    }

    @Test
    fun `QUIC folds TLS into connect and leaves tls null`() {
        val t0 = 3_000_000L
        val metrics = FakeMetrics(
            requestStart = Date(t0),
            dnsStart = Date(t0 + 5),
            dnsEnd = Date(t0 + 20),
            connectStart = Date(t0 + 20),
            connectEnd = Date(t0 + 60),
            sslStart = null,
            sslEnd = null,
            sendingStart = Date(t0 + 60),
            sendingEnd = Date(t0 + 65),
            responseStart = Date(t0 + 90),
            requestEnd = Date(t0 + 110),
            socketReused = false,
            ttfbMs = 30L,
            totalTimeMs = 110L,
        )
        val info = FakeRequestFinishedInfo(
            metrics = metrics,
            finishedReason = RequestFinishedInfo.SUCCEEDED,
            responseInfo = FakeUrlResponseInfo(statusCode = 200, negotiatedProtocol = "h3"),
        )

        val timings = SarieTimingsMapper.map(info, attempt = 1, isRedirect = false, deliveredLate = false)

        assertEquals(SarieProtocol.HTTP_3, timings.protocol)
        assertEquals(40L, timings.connectMs)
        assertNull(timings.tlsMs)
        assertNull(timings.tlsStartAtMillis)
        assertNull(timings.tlsEndAtMillis)
    }

    @Test
    fun `protocol normalization table`() {
        assertEquals(SarieProtocol.HTTP_3, SarieTimingsMapper.parseProtocol("h3"))
        assertEquals(SarieProtocol.HTTP_3, SarieTimingsMapper.parseProtocol("H3"))
        assertEquals(SarieProtocol.HTTP_3, SarieTimingsMapper.parseProtocol("h3-29"))
        assertEquals(SarieProtocol.HTTP_3, SarieTimingsMapper.parseProtocol("quic"))
        assertEquals(SarieProtocol.HTTP_3, SarieTimingsMapper.parseProtocol("quic/46"))
        assertEquals(SarieProtocol.HTTP_3, SarieTimingsMapper.parseProtocol("QUIC/50"))

        assertEquals(SarieProtocol.HTTP_2, SarieTimingsMapper.parseProtocol("h2"))
        assertEquals(SarieProtocol.HTTP_2, SarieTimingsMapper.parseProtocol("H2"))

        assertEquals(SarieProtocol.HTTP_1_1, SarieTimingsMapper.parseProtocol("http/1.1"))
        assertEquals(SarieProtocol.HTTP_1_1, SarieTimingsMapper.parseProtocol("HTTP/1.1"))

        assertEquals(SarieProtocol.UNKNOWN, SarieTimingsMapper.parseProtocol(null))
        assertEquals(SarieProtocol.UNKNOWN, SarieTimingsMapper.parseProtocol(""))
        assertEquals(SarieProtocol.UNKNOWN, SarieTimingsMapper.parseProtocol("unknown"))
        assertEquals(SarieProtocol.UNKNOWN, SarieTimingsMapper.parseProtocol("http/1.0"))
        assertEquals(SarieProtocol.UNKNOWN, SarieTimingsMapper.parseProtocol("spdy/3.1"))
    }

    @Test
    fun `NetworkException and QuicException codes mapped`() {
        val netEx = FakeNetworkException(errorCode = 2, cronetInternalErrorCode = -105)
        val infoNet = FakeRequestFinishedInfo(
            finishedReason = RequestFinishedInfo.FAILED,
            exception = netEx,
        )
        val timingsNet = SarieTimingsMapper.map(infoNet, attempt = 1, isRedirect = false, deliveredLate = false)
        assertEquals(SarieTimings.Result.FAILED, timingsNet.result)
        assertEquals(2, timingsNet.errorCode)
        assertEquals(-105, timingsNet.cronetInternalErrorCode)
        assertNull(timingsNet.quicErrorCode)

        val quicEx = FakeQuicException(errorCode = 11, cronetInternalErrorCode = -350, quicDetailedErrorCode = 42)
        val infoQuic = FakeRequestFinishedInfo(
            finishedReason = RequestFinishedInfo.FAILED,
            exception = quicEx,
        )
        val timingsQuic = SarieTimingsMapper.map(infoQuic, attempt = 1, isRedirect = false, deliveredLate = false)
        assertEquals(SarieTimings.Result.FAILED, timingsQuic.result)
        assertEquals(11, timingsQuic.errorCode)
        assertEquals(-350, timingsQuic.cronetInternalErrorCode)
        assertEquals(42, timingsQuic.quicErrorCode)

        val plainEx = FakeCronetException("plain error")
        val infoPlain = FakeRequestFinishedInfo(
            finishedReason = RequestFinishedInfo.FAILED,
            exception = plainEx,
        )
        val timingsPlain = SarieTimingsMapper.map(infoPlain, attempt = 1, isRedirect = false, deliveredLate = false)
        assertNull(timingsPlain.errorCode)
        assertNull(timingsPlain.cronetInternalErrorCode)
        assertNull(timingsPlain.quicErrorCode)
    }

    @Test
    fun `finishedReason maps to Result enum`() {
        val infoSucceeded = FakeRequestFinishedInfo(finishedReason = RequestFinishedInfo.SUCCEEDED)
        assertEquals(
            SarieTimings.Result.SUCCEEDED,
            SarieTimingsMapper.map(infoSucceeded, 1, false, false).result,
        )

        val infoFailed = FakeRequestFinishedInfo(finishedReason = RequestFinishedInfo.FAILED)
        assertEquals(
            SarieTimings.Result.FAILED,
            SarieTimingsMapper.map(infoFailed, 1, false, false).result,
        )

        val infoCanceled = FakeRequestFinishedInfo(finishedReason = RequestFinishedInfo.CANCELED)
        assertEquals(
            SarieTimings.Result.CANCELED,
            SarieTimingsMapper.map(infoCanceled, 1, false, false).result,
        )
    }

    @Test
    fun `null responseInfo maps to UNKNOWN protocol and null status`() {
        val info = FakeRequestFinishedInfo(
            finishedReason = RequestFinishedInfo.FAILED,
            responseInfo = null,
        )
        val timings = SarieTimingsMapper.map(info, 1, false, false)
        assertEquals(SarieProtocol.UNKNOWN, timings.protocol)
        assertNull(timings.negotiatedProtocol)
        assertNull(timings.httpStatusCode)
    }

    @Test
    fun `attempt, isRedirect, and deliveredLate passed through`() {
        val info = FakeRequestFinishedInfo(finishedReason = RequestFinishedInfo.SUCCEEDED)
        val timings = SarieTimingsMapper.map(
            info = info,
            attempt = 2,
            isRedirect = true,
            deliveredLate = true,
        )
        assertEquals(2, timings.attempt)
        assertTrue(timings.isRedirect)
        assertTrue(timings.deliveredLate)
    }
}
