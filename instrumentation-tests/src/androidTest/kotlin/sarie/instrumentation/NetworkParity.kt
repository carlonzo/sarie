package sarie.instrumentation

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Device checks for network interceptors on the Cronet path. Both connected suites call these.
 * Not run in this workspace (no emulator).
 */
internal object NetworkParity {
    private const val PUBLIC_H3 = "https://cloudflare-quic.com/"

    fun loggingAndChuckerSeeHttp3(install: () -> Unit) {
        install()
        val logged = StringBuilder()
        val logging = HttpLoggingInterceptor { message -> logged.append(message).append('\n') }
        logging.level = HttpLoggingInterceptor.Level.BODY
        val recorder = ChuckerShapedRecorder()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(logging)
            .addNetworkInterceptor(recorder)
            .build()

        client.newCall(Request.Builder().url(PUBLIC_H3).build()).execute().use { response ->
            assertEquals(Protocol.HTTP_3, response.protocol)
            assertEquals(200, response.code)
            response.body.string()
        }
        assertTrue("logger missed the request\n$logged", logged.contains(PUBLIC_H3))
        assertTrue("logger missed the response\n$logged", logged.contains("200"))
        assertEquals(PUBLIC_H3, recorder.requestUrl)
        assertEquals(200, recorder.code)
        assertEquals(Protocol.HTTP_3, recorder.protocol)
        assertTrue("recorder missed the body", recorder.bodySnippet.isNotEmpty())
        assertCronetServed()
    }

    fun addedHeaderReachesOrigin(install: () -> Unit, origin: String) {
        install()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder().header("X-Sarie-Net", "seen").build()
                chain.proceed(request)
            }
            .build()
        client.newCall(Request.Builder().url("$origin/headers").build()).execute().use { response ->
            assertEquals(200, response.code)
            val echoed = response.body.string()
            assertTrue("origin did not observe X-Sarie-Net, got:\n$echoed", echoed.contains("seen"))
        }
        assertCronetServed()
    }

    fun urlGuardsThrowStockMessages(install: () -> Unit, origin: String) {
        install()
        val host = Interceptor { chain ->
            val url = chain.request().url.newBuilder().host("example.com").build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }
        assertAddress(host, "$origin/ok")

        val scheme = Interceptor { chain ->
            val url = chain.request().url.newBuilder().scheme("http").build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }
        assertAddress(scheme, "$origin/ok")

        val port = Interceptor { chain ->
            val url = chain.request().url.newBuilder().port(9).build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }
        assertAddress(port, "$origin/ok")

        val twice = Interceptor { chain ->
            chain.proceed(chain.request()).close()
            chain.proceed(chain.request())
        }
        assertExactlyOnce(twice, "$origin/ok")

        val shortCircuit = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("ok")
                .body("short".toResponseBody(null))
                .build()
        }
        assertExactlyOnce(shortCircuit, "$origin/ok")
    }

    fun readTimeoutFromNetworkInterceptorAborts(install: () -> Unit, origin: String) {
        install()
        val warm = OkHttpClient()
        warm.newCall(Request.Builder().url("$origin/ok").build()).execute().use { it.body.string() }
        val client = warm.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                chain.withReadTimeout(500, TimeUnit.MILLISECONDS).proceed(chain.request())
            }
            .build()
        client.newCall(Request.Builder().url("$origin/slow").build()).execute().use { response ->
            assertEquals(200, response.code)
            val error = runCatching { response.body.bytes() }.exceptionOrNull()
            assertTrue(
                "expected a read-timeout abort, got: $error",
                error is IOException &&
                    (error.message?.contains("Timed out") == true),
            )
        }
        assertCronetServed(minCount = 2)
    }

    fun eventListenerHeaderOrder(install: () -> Unit, origin: String) {
        install()
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: okhttp3.Call) { seen += "callStart" }
                override fun requestHeadersStart(call: okhttp3.Call) { seen += "requestHeadersStart" }
                override fun responseHeadersEnd(call: okhttp3.Call, response: Response) {
                    seen += "responseHeadersEnd"
                }
                override fun callEnd(call: okhttp3.Call) { seen += "callEnd" }
            })
            .build()
        client.newCall(Request.Builder().url("$origin/ok").build()).execute().use { it.body.string() }
        val callStart = seen.indexOf("callStart")
        val requestHeaders = seen.indexOf("requestHeadersStart")
        val responseHeaders = seen.indexOf("responseHeadersEnd")
        val callEnd = seen.indexOf("callEnd")
        assertTrue(
            "expected callStart < requestHeadersStart < responseHeadersEnd < callEnd, got $seen",
            callStart >= 0 && callStart < requestHeaders && requestHeaders < responseHeaders && responseHeaders < callEnd,
        )
        assertCronetServed()
    }

    private fun assertAddress(interceptor: Interceptor, url: String) {
        val thrown = executeExpecting(interceptor, url)
        assertEquals(
            "network interceptor $interceptor must retain the same host and port",
            thrown.message,
        )
    }

    private fun assertExactlyOnce(interceptor: Interceptor, url: String) {
        val thrown = executeExpecting(interceptor, url)
        assertEquals(
            "network interceptor $interceptor must call proceed() exactly once",
            thrown.message,
        )
    }

    private fun executeExpecting(interceptor: Interceptor, url: String): IllegalStateException {
        val client = OkHttpClient.Builder().addNetworkInterceptor(interceptor).build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            return expected
        }
        error("unreachable")
    }

    private fun assertCronetServed(minCount: Int = 1) {
        assertTrue(
            "expected the cronet path, cronet=${TestAppRuntime.routes.cronetCount()}",
            TestAppRuntime.routes.cronetCount() >= minCount,
        )
        assertNull(
            "cronet-path request recorded a fallback reason: ${TestAppRuntime.routes.lastReason()}",
            TestAppRuntime.routes.lastReason(),
        )
    }

    /**
     * Test-local stand-in for a Chucker-shaped network interceptor: it records the request
     * and peeks the response (including the body) without consuming the caller's stream.
     */
    private class ChuckerShapedRecorder : Interceptor {
        var requestUrl: String? = null
        var code: Int = -1
        var protocol: Protocol? = null
        var bodySnippet: String = ""

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requestUrl = request.url.toString()
            val response = chain.proceed(request)
            code = response.code
            protocol = response.protocol
            bodySnippet = response.peekBody(64 * 1024).string()
            return response
        }
    }
}
