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

import sarie.bridge.RequestFinishedExecutor
import sarie.bridge.RuntimeSnapshot
import sarie.bridge.SarieBridge
import java.io.IOException
import java.util.Locale
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
     */
    @Throws(IOException::class)
    fun convert(
        okHttpRequest: Request,
        readTimeoutMillis: Long,
        writeTimeoutMillis: Long,
        requestBodyEvents: RequestBodyEvents? = null,
        onResponseHeadersStart: (() -> Unit)? = null,
        call: Call? = null,
    ): ConvertedRequest {
        val callback = OkHttpBridgeCallback(readTimeoutMillis, onResponseHeadersStart)

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

        // One addHeader per name. Repeated request values join with ", " ("; " for Cookie).
        // Content-Type and Content-Length the converter adds are folded into that same map.
        val headerGroups = linkedMapOf<String, JoinedHeader>()
        fun add(name: String, value: String) {
            val key = name.lowercase(Locale.US)
            val group = headerGroups[key]
            if (group == null) {
                headerGroups[key] = JoinedHeader(name, mutableListOf(value))
            } else {
                group.values.add(value)
            }
        }
        fun addDistinct(name: String, value: String) {
            val key = name.lowercase(Locale.US)
            val group = headerGroups[key]
            if (group == null || group.values.none { it.equals(value, ignoreCase = true) }) {
                add(name, value)
            }
        }
        fun replace(name: String, value: String) {
            headerGroups[name.lowercase(Locale.US)] = JoinedHeader(name, mutableListOf(value))
        }

        val headers = okHttpRequest.headers
        for (i in 0 until headers.size) {
            add(headers.name(i), headers.value(i))
        }

        val body = okHttpRequest.body
        if (body != null) {
            // If provided by the user, set the RequestBody.contentType(). This matches OkHttp.
            val contentType = body.contentType()
            if (contentType != null) {
                addDistinct(CONTENT_TYPE_HEADER_NAME, contentType.toString())
            }

            if (okHttpRequest.header(CONTENT_LENGTH_HEADER_NAME) == null &&
                body.contentLength() != -1L
            ) {
                add(CONTENT_LENGTH_HEADER_NAME, body.contentLength().toString())
            }

            if (body.contentLength() != 0L) {
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
                    replace(CONTENT_TYPE_HEADER_NAME, CONTENT_TYPE_HEADER_DEFAULT_VALUE)
                }

                builder.setUploadDataProvider(
                    UploadDataProviders.create(
                        body,
                        bodyReaderExecutor,
                        writeTimeoutMillis,
                        requestBodyEvents,
                    ),
                    uploadDataProviderExecutor,
                )
            }
        }

        for ((key, group) in headerGroups) {
            val separator = if (key == "cookie") "; " else ", "
            builder.addHeader(group.name, group.values.joinToString(separator))
        }

        val snapshot = SarieBridge.snapshot()
        snapshot?.mapper?.map(okHttpRequest, builder)
        attachFinishedListener(builder, snapshot, call)
        builder.disableCache()

        return ConvertedRequest(builder.build(), callback, okHttpRequest, responseConverter)
    }

    private fun attachFinishedListener(
        builder: UrlRequest.Builder,
        snapshot: RuntimeSnapshot?,
        call: Call?,
    ) {
        val listener = SarieBridge.listener ?: return
        if (snapshot == null || call == null || snapshot.finishedListenerUnsupported.get()) return
        val attached = runCatching {
            builder.setRequestFinishedListener(
                object : RequestFinishedInfo.Listener(RequestFinishedExecutor.executor) {
                    override fun onRequestFinished(info: RequestFinishedInfo) {
                        // Host code on Sarie's executor thread: an escaped throw would reach the
                        // uncaught-exception handler and kill the process.
                        try {
                            listener.onFinished(call, info)
                        } catch (t: Throwable) {
                            if (finishedThrewLogged.compareAndSet(false, true)) {
                                SarieBridge.logger?.log(Log.WARN, "SarieListener.onFinished threw", t)
                            }
                        }
                    }
                },
            )
        }
        if (attached.isFailure &&
            snapshot.finishedListenerUnsupported.compareAndSet(false, true)
        ) {
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

    private class JoinedHeader(val name: String, val values: MutableList<String>)

    private companion object {
        private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
        private const val CONTENT_TYPE_HEADER_NAME = "Content-Type"
        private const val CONTENT_TYPE_HEADER_DEFAULT_VALUE = "application/octet-stream"
        private val finishedThrewLogged = AtomicBoolean(false)
        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
