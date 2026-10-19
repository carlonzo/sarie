/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Ported from google/cronet-transport-for-okhttp@eda650fbc9b5279b6219160c2a0b210b28303fd7
package sarie.bridge.mapping

import sarie.bridge.RuntimeSnapshot
import sarie.bridge.SarieBridge
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.chromium.net.CronetEngine
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.UrlRequest

/** Converts OkHttp requests to Cronet requests. */
internal class RequestConverter(
    private val cronetEngine: CronetEngine,
    private val uploadDataProviderExecutor: Executor,
    private val bodyReaderExecutor: ExecutorService,
    private val responseConverter: ResponseConverter,
) {

    /**
     * Converts OkHttp's [Request] to a corresponding Cronet [UrlRequest].
     *
     * Since Cronet delivers responses through callbacks, the returned holder also exposes
     * [ConvertedRequest.getResponse], which blocks until the status code and headers are
     * available. The host mapper from [SarieBridge] is applied to the builder just before
     * build() so host tags survive on the Cronet request. [UrlRequest.Builder.disableCache] runs
     * immediately before build(), on both the Sarie-built and borrowed engines.
     *
     * [callTimeoutMillis] bounds the whole attempt, headers and body alike, so the caller reads
     * it off the call's [okhttp3.Timeout]. `0` means no overall bound and leaves the body bounded
     * by [readTimeoutMillis] alone, exactly as before.
     */
    @Throws(IOException::class)
    fun convert(
        okHttpRequest: Request,
        readTimeoutMillis: Long,
        writeTimeoutMillis: Long,
        requestBodyEvents: RequestBodyEvents? = null,
        onResponseHeadersStart: (() -> Unit)? = null,
        call: Call? = null,
        attempt: Int = 1,
        callTimeoutMillis: Long = 0L,
    ): ConvertedRequest {
        val callback = OkHttpBridgeCallback(
            readTimeoutMillis,
            onResponseHeadersStart,
            call,
            attempt,
            callTimeoutMillis,
        )

        // The callback methods are lightweight (queue inserts); run them directly on Cronet's
        // internal thread to avoid extra thread hops.
        val builder = cronetEngine
            .newUrlRequestBuilder(
                okHttpRequest.url.toString(),
                callback,
                DIRECT_EXECUTOR,
            )
            .allowDirectExecutor()

        builder.setHttpMethod(okHttpRequest.method)

        // One addHeader per name. A repeated name keeps its last value ("; "-joined for Cookie).
        // Content-Type and Content-Length the converter adds are folded into that same set.
        val requestHeaders = RequestHeaders(builder)

        val requestHeaderList = okHttpRequest.headers
        for (i in 0 until requestHeaderList.size) {
            requestHeaders.add(requestHeaderList.name(i), requestHeaderList.value(i))
        }

        val body = okHttpRequest.body
        if (body != null) {
            // If provided by the user, set the RequestBody.contentType(). This matches OkHttp.
            val contentType = body.contentType()
            if (contentType != null) {
                requestHeaders.add(CONTENT_TYPE_HEADER_NAME, contentType.toString())
            }

            // Measured exactly once: a MultipartBody re-sums every part per call, and a custom
            // RequestBody may do I/O in contentLength(). The single value feeds the
            // Content-Length header, the empty-body check below and the upload provider.
            val contentLength = body.contentLength()

            if (okHttpRequest.header(CONTENT_LENGTH_HEADER_NAME) == null && contentLength != -1L) {
                requestHeaders.add(CONTENT_LENGTH_HEADER_NAME, contentLength.toString())
            }

            if (contentLength != 0L) {
                // Cronet requires a non-empty Content-Type header when an UploadDataProvider is
                // set.
                val contentTypeHeader = okHttpRequest.header(CONTENT_TYPE_HEADER_NAME)
                if (contentType == null &&
                    (contentTypeHeader == null || contentTypeHeader.trim().isEmpty())
                ) {
                    SarieBridge.logger?.log(
                        Log.WARN,
                        "Cronet OkHttp transport was passed a request body with a missing or " +
                            "empty Content-Type header. This is not supported by Cronet. " +
                            "Content-Type has been overridden to " +
                            "\"$CONTENT_TYPE_HEADER_DEFAULT_VALUE\"",
                        null,
                    )
                    requestHeaders.add(CONTENT_TYPE_HEADER_NAME, CONTENT_TYPE_HEADER_DEFAULT_VALUE)
                }

                builder.setUploadDataProvider(
                    UploadDataProviders.create(
                        body,
                        bodyReaderExecutor,
                        writeTimeoutMillis,
                        requestBodyEvents,
                        contentLength = contentLength,
                    ),
                    uploadDataProviderExecutor,
                )
            }
        }

        requestHeaders.emit()

        val snapshot = SarieBridge.snapshot()
        snapshot?.mapper?.map(okHttpRequest, builder)
        attachFinishedListener(builder, snapshot, call, callback)
        builder.disableCache()

        return ConvertedRequest(builder.build(), callback, okHttpRequest, responseConverter)
    }

    private fun attachFinishedListener(
        builder: UrlRequest.Builder,
        snapshot: RuntimeSnapshot?,
        call: Call?,
        callback: OkHttpBridgeCallback,
    ) {
        val listener = SarieBridge.listener ?: return
        if (snapshot == null || call == null || snapshot.finishedListenerUnsupported.get()) return
        val attached = runCatching {
            builder.setRequestFinishedListener(
                object : RequestFinishedInfo.Listener(DIRECT_EXECUTOR) {
                    override fun onRequestFinished(info: RequestFinishedInfo) {
                        callback.onFinishedInfoReceived(info)
                    }
                },
            )
        }
        if (attached.isSuccess) {
            callback.finishedListenerActive.set(true)
        } else if (snapshot.finishedListenerUnsupported.compareAndSet(false, true)) {
            SarieBridge.logger?.log(
                Log.WARN,
                "Cronet provider rejected setRequestFinishedListener; onFinished will be skipped " +
                    "for this engine (${attached.exceptionOrNull()?.message})",
                attached.exceptionOrNull(),
            )
        }
    }

    /** Bundles the Cronet request with its in-progress OkHttp response. */
    internal class ConvertedRequest internal constructor(
        val urlRequest: UrlRequest,
        val callback: OkHttpBridgeCallback,
        private val request: Request,
        private val responseConverter: ResponseConverter,
    ) {

        /** Blocks until the response headers are available. */
        @Throws(IOException::class)
        fun getResponse(): Response = responseConverter.toResponse(request, callback)
    }

    /**
     * Collects the request headers of a single conversion and emits one
     * [UrlRequest.Builder.addHeader] call per name, in first-appearance order.
     *
     * A repeated name (compared ignoring case) keeps its first position and spelling, and the
     * last value wins: most fields allow one value, and joining two of them with ", " sends a
     * value no server parses. Cookie is the exception: its values join with "; ", the form
     * HTTP/2 itself reassembles cookie crumbs into. The header count is small, so the quadratic
     * name scan without allocation wins over a map.
     */
    private class RequestHeaders(private val builder: UrlRequest.Builder) {

        private var names = arrayOfNulls<String>(INITIAL_CAPACITY)
        private var values = arrayOfNulls<String>(INITIAL_CAPACITY)
        private var count = 0

        /** Adds [value], replacing a value already held for [name] (joining, for Cookie). */
        fun add(name: String, value: String) {
            val index = indexOf(name)
            if (index < 0) {
                if (count == names.size) {
                    names = names.copyOf(INITIAL_CAPACITY + count)
                    values = values.copyOf(INITIAL_CAPACITY + count)
                }
                names[count] = name
                values[count] = value
                count++
            } else if (name.equals(COOKIE_HEADER_NAME, ignoreCase = true)) {
                values[index] = values[index] + "; " + value
            } else {
                values[index] = value
            }
        }

        /** Hands the collected headers to the builder, one call per name. */
        fun emit() {
            for (i in 0 until count) builder.addHeader(names[i]!!, values[i]!!)
        }

        /** Index of the first held name equal to [name] ignoring case, or -1. */
        private fun indexOf(name: String): Int {
            for (i in 0 until count) {
                if (names[i]!!.equals(name, ignoreCase = true)) return i
            }
            return -1
        }
    }

    private companion object {
        private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
        private const val CONTENT_TYPE_HEADER_NAME = "Content-Type"
        private const val COOKIE_HEADER_NAME = "Cookie"
        private const val CONTENT_TYPE_HEADER_DEFAULT_VALUE = "application/octet-stream"
        private const val INITIAL_CAPACITY = 8
        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
