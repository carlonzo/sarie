package sarie.bridge.mapping

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sarie.bridge.SarieBridge
import sarie.bridge.SarieConfig
import sarie.bridge.SarieLogger

/**
 * The bridge asks the installed logger whether a priority is wanted before building the message
 * for it. Response headers are the hot path - one debug line per response, and building the string
 * calls `UrlResponseInfo.getUrl()` - so a host that filters DEBUG pays for none of it.
 */
class SarieLoggerGateTest {

    @After
    fun tearDown() {
        SarieBridge.uninstall()
    }

    private val info: UrlResponseInfo = FakeUrlResponseInfo(
        url = "https://example.com/stream",
        negotiatedProtocol = "h2",
    )

    private fun newRequest(callback: UrlRequest.Callback): FakeUrlRequest =
        FakeUrlRequestBuilder("https://example.com/", callback, Executor { it.run() }).build()

    /** Records what the bridge asked the gate and what it went on to log. */
    private class RecordingLogger(private val loggable: (Int) -> Boolean) : SarieLogger {
        val asked = CopyOnWriteArrayList<Int>()
        val logged = CopyOnWriteArrayList<Pair<Int, String>>()

        override fun log(priority: Int, message: String, throwable: Throwable?) {
            logged.add(priority to message)
        }

        override fun isLoggable(priority: Int): Boolean {
            asked.add(priority)
            return loggable(priority)
        }
    }

    private fun install(logger: SarieLogger) {
        SarieBridge.install(FakeCronetEngine(), SarieConfig { debugLogger(logger) })
    }

    private fun deliverResponseHeaders() {
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        cb.onResponseStarted(newRequest(cb), info)
    }

    /** DEBUG output only: install() itself logs about the engine at other priorities. */
    private fun RecordingLogger.debugLines(): List<Pair<Int, String>> =
        logged.filter { it.first == Log.DEBUG }

    private fun responseLines(messages: List<String>): List<String> =
        messages.filter { it.contains(" -> ") }

    @Test
    fun `a logger that does not want DEBUG is never asked to log one`() {
        val logger = RecordingLogger { priority -> priority != Log.DEBUG }
        install(logger)

        deliverResponseHeaders()

        assertTrue("the bridge must consult the gate", logger.asked.contains(Log.DEBUG))
        assertEquals(
            "no message may be built or logged for a priority the logger declined",
            emptyList<Pair<Int, String>>(),
            logger.debugLines(),
        )
    }

    @Test
    fun `a logger that wants DEBUG still gets the response line`() {
        val logger = RecordingLogger { true }
        install(logger)

        deliverResponseHeaders()

        assertEquals(
            listOf(Log.DEBUG to "https://example.com/stream -> h2"),
            logger.debugLines(),
        )
    }

    @Test
    fun `isLoggable defaults to accepting every priority`() {
        // A plain lambda: SAM conversion must keep working with the added default member.
        val logged = CopyOnWriteArrayList<String>()
        install(SarieLogger { _, message, _ -> logged.add(message) })

        deliverResponseHeaders()

        assertEquals(listOf("https://example.com/stream -> h2"), responseLines(logged))
    }

    @Test
    fun `a throwing isLoggable is treated as not loggable instead of failing the response`() {
        val logged = CopyOnWriteArrayList<String>()
        val logger = object : SarieLogger {
            override fun log(priority: Int, message: String, throwable: Throwable?) {
                logged.add(message)
            }

            override fun isLoggable(priority: Int): Boolean =
                throw IllegalStateException("host logger misbehaved")
        }
        install(logger)

        // The response still has to be delivered: a broken gate may only cost a log line.
        val cb = OkHttpBridgeCallback(readTimeoutMillis = 1_000)
        cb.onResponseStarted(newRequest(cb), info)

        assertEquals(info, cb.headersFuture.get(1, TimeUnit.SECONDS))
        assertTrue(cb.bodySourceFuture.isDone)
        assertEquals(emptyList<String>(), responseLines(logged))
    }
}
