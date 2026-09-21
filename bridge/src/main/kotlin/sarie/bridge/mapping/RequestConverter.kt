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

import sarie.bridge.SarieBridge
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.logging.Logger
import okhttp3.Request
import okhttp3.Response
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest

/** Converts OkHttp requests to Cronet requests. */
class RequestConverter(
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
     * build() so host tags survive on the Cronet request.
     */
    @Throws(IOException::class)
    fun convert(
        okHttpRequest: Request,
        readTimeoutMillis: Long,
        writeTimeoutMillis: Long,
    ): ConvertedRequest {
        val callback = OkHttpBridgeCallback(readTimeoutMillis)

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

        val headers = okHttpRequest.headers
        for (i in 0 until headers.size) {
            builder.addHeader(headers.name(i), headers.value(i))
        }

        val body = okHttpRequest.body
        if (body != null) {
            // If provided by the user, set the RequestBody.contentType(). This matches OkHttp.
            val contentType = body.contentType()
            if (contentType != null) {
                builder.addHeader(CONTENT_TYPE_HEADER_NAME, contentType.toString())
            }

            if (okHttpRequest.header(CONTENT_LENGTH_HEADER_NAME) == null &&
                body.contentLength() != -1L
            ) {
                builder.addHeader(CONTENT_LENGTH_HEADER_NAME, body.contentLength().toString())
            }

            if (body.contentLength() != 0L) {
                // Cronet requires a non-empty Content-Type header when an UploadDataProvider is
                // set.
                val contentTypeHeader = okHttpRequest.header(CONTENT_TYPE_HEADER_NAME)
                if (contentType == null &&
                    (contentTypeHeader == null || contentTypeHeader.trim().isEmpty())
                ) {
                    logger.warning(
                        "Cronet OkHttp transport was passed a request body with a missing or " +
                            "empty Content-Type header. This is not supported by Cronet. " +
                            "Content-Type has been overridden to " +
                            "\"$CONTENT_TYPE_HEADER_DEFAULT_VALUE\"",
                    )
                    builder.addHeader(CONTENT_TYPE_HEADER_NAME, CONTENT_TYPE_HEADER_DEFAULT_VALUE)
                }

                builder.setUploadDataProvider(
                    UploadDataProviders.create(body, bodyReaderExecutor, writeTimeoutMillis),
                    uploadDataProviderExecutor,
                )
            }
        }

        SarieBridge.snapshot()?.mapper?.map(okHttpRequest, builder)

        return ConvertedRequest(builder.build(), callback, okHttpRequest, responseConverter)
    }

    /** Bundles the Cronet request with its in-progress OkHttp response. */
    class ConvertedRequest internal constructor(
        val urlRequest: UrlRequest,
        val callback: OkHttpBridgeCallback,
        private val request: Request,
        private val responseConverter: ResponseConverter,
    ) {

        /** Blocks until the response headers are available. */
        @Throws(IOException::class)
        fun getResponse(): Response = responseConverter.toResponse(request, callback)
    }

    private companion object {
        private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
        private const val CONTENT_TYPE_HEADER_NAME = "Content-Type"
        private const val CONTENT_TYPE_HEADER_DEFAULT_VALUE = "application/octet-stream"
        private val logger = Logger.getLogger("CronetTransportForOkHttp")
        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
