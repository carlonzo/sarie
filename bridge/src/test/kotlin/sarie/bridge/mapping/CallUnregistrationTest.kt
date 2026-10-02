@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package sarie.bridge.mapping

import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.connection.RealCall
import okio.Buffer
import okio.Source
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sarie.bridge.CronetBridge
import sarie.bridge.CallRegistry
import sarie.bridge.SarieBridge

/**
 * The body source is the last owner of the Cronet exchange, so it - not a wrapper around the
 * response body - is what tears the call's [CallRegistry] entry down. Without that, a call whose
 * body is never closed keeps its registration, and the request reference inside it, alive forever.
 */
class CallUnregistrationTest {

    @After
    fun tearDown() {
        SarieBridge.uninstall()
    }

    private val info: UrlResponseInfo = FakeUrlResponseInfo()

    private class Body(
        val call: RealCall,
        val callback: OkHttpBridgeCallback,
        val request: FakeUrlRequest,
        val source: Source,
    )

    private fun newRequest(callback: UrlRequest.Callback): FakeUrlRequest =
        FakeUrlRequestBuilder("https://example.com/", callback, Executor { it.run() }).build()

    /** A live body source whose call is registered, exactly as CronetBridge registers it. */
    private fun newRegisteredBody(): Body {
        val call = OkHttpClient()
            .newCall(Request.Builder().url("https://example.com/").build()) as RealCall
        val callback = OkHttpBridgeCallback(readTimeoutMillis = 5_000, call = call)
        val request = newRequest(callback)
        callback.onResponseStarted(request, info)
        val source = callback.bodySourceFuture.get(1, TimeUnit.SECONDS)
        // The callback tears the registry entry down itself, scoped to this exact registration -
        // a late close from an earlier attempt must not remove a newer attempt's entry.
        callback.attach(CallRegistry.register(call, AtomicReference<UrlRequest?>(request)))
        assertTrue("precondition: the call is registered", registryHolds(call))
        return Body(call, callback, request, source)
    }

    /** Whether [CallRegistry] still holds an entry for [call]. */
    private fun registryHolds(call: Call): Boolean {
        val field = CallRegistry::class.java.declaredFields
            .first { Map::class.java.isAssignableFrom(it.type) }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val entries = field.get(CallRegistry) as Map<Any, Any>
        return entries.containsKey(call)
    }

    @Test(timeout = 20_000)
    fun `closing the body source unregisters the call`() {
        val body = newRegisteredBody()

        body.source.close()

        assertFalse("close must drop the registry entry", registryHolds(body.call))
        // Unregistering deactivates the cancel listener, so a cancel arriving after the body was
        // closed no longer reaches the UrlRequest: the registration is really gone.
        val cancelsAtClose = body.request.cancelCalls
        body.call.cancel()
        assertEquals(cancelsAtClose, body.request.cancelCalls)
    }

    @Test(timeout = 20_000)
    fun `closing twice does not unregister twice or cancel twice`() {
        val body = newRegisteredBody()

        body.source.close()
        body.source.close()
        body.source.close()

        assertFalse(registryHolds(body.call))
        // The single cancel comes from the first close(); the later ones do nothing at all.
        assertEquals(1, body.request.cancelCalls)
    }

    @Test(timeout = 20_000)
    fun `reading the body to the end unregisters the call and a later close stays correct`() {
        val body = newRegisteredBody()
        val callback = body.callback
        val request = body.request
        val source = body.source
        var completions = 0
        request.readHandler = { buffer ->
            completions++
            if (completions == 1) {
                buffer.put("body".toByteArray())
                callback.onReadCompleted(request, info, buffer)
            } else {
                callback.onSucceeded(request, info)
            }
        }

        val sink = Buffer()
        assertEquals(4L, source.read(sink, 1024))
        assertEquals(-1L, source.read(sink, 1024))
        assertEquals("body", sink.readUtf8())
        assertFalse("EOF must unregister even if the caller never closes", registryHolds(body.call))

        source.close()

        assertFalse(registryHolds(body.call))
        // The request finished, so closing afterwards must not cancel it.
        assertEquals(0, request.cancelCalls)
    }

    @Test(timeout = 20_000)
    fun `a failed body unregisters the call`() {
        val body = newRegisteredBody()
        val callback = body.callback
        val request = body.request
        val source = body.source
        request.readHandler = { callback.onFailed(request, info, FakeCronetException("mid-body")) }

        val thrown = runCatching { source.read(Buffer(), 1024) }.exceptionOrNull()

        assertTrue("expected IOException but was $thrown", thrown is java.io.IOException)
        assertFalse(registryHolds(body.call))
    }

    @Test(timeout = 20_000)
    fun `closing the response body of an unread 407-style response unregisters the call`() {
        val body = newRegisteredBody()
        val request = Request.Builder().url("https://example.com/").build()
        val response = ResponseConverter().toResponse(request, body.callback)

        // CronetBridge's proxy-auth path: the body is closed without ever being read.
        response.body!!.close()

        assertFalse(registryHolds(body.call))
        assertEquals(1, body.request.cancelCalls)
    }

    /**
     * A redirect hop is unregistered by `CronetBridge` the moment the 3xx headers arrive, not by
     * the body. Cronet has already canceled the engine request by then, so the body is a plain
     * empty source with no teardown of its own: closing it is inert and must neither cancel the
     * engine again nor touch the registry.
     */
    @Test(timeout = 20_000)
    fun `a redirect body owns no teardown - CronetBridge unregisters the hop at header time`() {
        val call = OkHttpClient()
            .newCall(Request.Builder().url("https://example.com/").build()) as RealCall
        val callback = OkHttpBridgeCallback(readTimeoutMillis = 5_000, call = call)
        val request = newRequest(callback)
        callback.attach(CallRegistry.register(call, AtomicReference<UrlRequest?>(request)))
        assertTrue("precondition: the call is registered", registryHolds(call))

        callback.onRedirectReceived(
            request,
            FakeUrlResponseInfo(statusCode = 302, statusText = "Found"),
            "https://example.com/b",
        )

        // The redirect hop cancels the engine request itself, exactly once.
        assertEquals(1, request.cancelCalls)
        val source = callback.bodySourceFuture.get(1, TimeUnit.SECONDS)
        assertEquals(-1L, source.read(Buffer(), 1024))

        source.close()
        source.close()

        // Nothing here owns the teardown, so the entry is still there for CronetBridge to drop.
        assertTrue(
            "closing a redirect body must not unregister; CronetBridge does it at header time",
            registryHolds(call),
        )
        assertEquals(
            "closing a redirect body must not cancel the engine request a second time",
            1,
            request.cancelCalls,
        )
        // With the entry still there, a later cancel delivers exactly one engine cancel; once
        // CronetBridge drops the entry at header time the same cancel reaches nothing.
        CronetBridge.notifyCanceled(call)
        assertEquals(2, request.cancelCalls)
    }
}
