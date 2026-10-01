package sarie.instrumentation

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking

/**
 * App-side Ktor 2 caller for `CronetSuite.ktor2OkHttpEngineOverH3`. Ktor lives in the app APK
 * (not androidTest) so the minified variant shrinks and optimizes it, including the patched
 * `OkUtilsKt$WhenMappings`, the way a real app ships it. Only this class is kept.
 */
object KtorProbe {
    class Result(val status: Int, val version: String, val bodyLength: Int)

    @JvmStatic
    fun get(url: String): Result = runBlocking {
        HttpClient(OkHttp).use { client ->
            val response = client.get(url)
            Result(response.status.value, response.version.toString(), response.bodyAsText().length)
        }
    }
}
