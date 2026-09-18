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
package dev.okhttpcronet.bridge.mapping

import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Source
import okio.buffer
import org.chromium.net.UrlResponseInfo

/**
 * Converts Cronet's responses (as delivered by the [OkHttpBridgeCallback]) to OkHttp's Response.
 *
 * Deviation from upstream: h3-family negotiated protocols map to [Protocol.HTTP_3] (OkHttp 5 has
 * it) instead of [Protocol.QUIC], so real h3 is visible to callers.
 */
class ResponseConverter {

    /**
     * Creates an OkHttp Response from the bridging callback. Non-blocking once the callback's
     * UrlResponseInfo is available; the body streams on read.
     */
    @Throws(IOException::class)
    fun toResponse(request: Request, callback: OkHttpBridgeCallback): Response {
        val cronetResponseInfo = getFutureValue(callback.headersFuture)
        val bodySource = getFutureValue(callback.bodySourceFuture)
        return createResponse(request, cronetResponseInfo, bodySource).build()
    }

    private fun createResponse(
        request: Request,
        cronetResponseInfo: UrlResponseInfo,
        bodySource: Source?,
    ): Response.Builder {
        val responseBuilder = Response.Builder()

        val contentType = getLastHeaderValue(CONTENT_TYPE_HEADER_NAME, cronetResponseInfo)

        // If all content encodings are known to Cronet natively, Cronet decodes the body stream.
        // Otherwise it's delivered verbatim. For consistency with OkHttp, only keep the
        // Content-Encoding headers if Cronet didn't decode; strip Content-Length of decoded
        // responses for the same reason.

        var contentLengthString: String? = null

        // Theoretically content encodings can be scattered across multiple comma-separated
        // Content-Encoding headers. This list contains individual encodings.
        val contentEncodingItems = (cronetResponseInfo.allHeaders[CONTENT_ENCODING_HEADER_NAME]
            ?: emptyList())
            .flatMap { it.split(',').map(String::trim).filter(String::isNotEmpty) }

        val keepEncodingAffectedHeaders = contentEncodingItems.isEmpty() ||
            !ENCODINGS_HANDLED_BY_CRONET.containsAll(contentEncodingItems)

        if (keepEncodingAffectedHeaders) {
            contentLengthString = getLastHeaderValue(CONTENT_LENGTH_HEADER_NAME, cronetResponseInfo)
        }

        responseBuilder
            .request(request)
            .code(cronetResponseInfo.httpStatusCode)
            .message(cronetResponseInfo.httpStatusText)
            .protocol(convertProtocol(cronetResponseInfo.negotiatedProtocol))

        // If there's no response body, don't set anything into the builder so it keeps the
        // correct default value for the OkHttp version in use.
        if (bodySource != null) {
            responseBuilder.body(
                createResponseBody(
                    request,
                    cronetResponseInfo.httpStatusCode,
                    contentType,
                    contentLengthString,
                    bodySource,
                ),
            )
        }

        for (header in cronetResponseInfo.allHeadersAsList) {
            val copyHeader = keepEncodingAffectedHeaders || (
                !header.key.equals(CONTENT_LENGTH_HEADER_NAME, ignoreCase = true) &&
                    !header.key.equals(CONTENT_ENCODING_HEADER_NAME, ignoreCase = true)
                )
            if (copyHeader) {
                responseBuilder.addHeader(header.key, header.value)
            }
        }

        return responseBuilder
    }

    /** Creates an OkHttp ResponseBody from the bridging callback's body source. */
    private fun createResponseBody(
        request: Request,
        httpStatusCode: Int,
        contentType: String?,
        contentLengthString: String?,
        bodySource: Source,
    ): ResponseBody {
        val contentLength = if (request.method == "HEAD") {
            // Ignore content-length header for HEAD requests (consistency with OkHttp)
            0L
        } else {
            contentLengthString?.toLongOrNull() ?: -1L
        }

        // Check for absence of body in No Content / Reset Content responses (OkHttp consistency)
        if ((httpStatusCode == 204 || httpStatusCode == 205) && contentLength > 0) {
            throw ProtocolException(
                "HTTP $httpStatusCode had non-zero Content-Length: $contentLengthString",
            )
        }

        return bodySource.buffer().asResponseBody(
            contentType?.toMediaTypeOrNull(),
            contentLength,
        )
    }

    /** Converts Cronet's negotiated protocol string to OkHttp's Protocol. */
    private fun convertProtocol(negotiatedProtocol: String): Protocol = when {
        // See https://www.iana.org/assignments/tls-extensiontype-values/
        // tls-extensiontype-values.xhtml#alpn-protocol-ids
        negotiatedProtocol.contains("quic") || negotiatedProtocol.startsWith("h3") ->
            // Deliberate deviation from upstream (h3 -> QUIC): OkHttp 5 exposes Protocol.HTTP_3.
            Protocol.HTTP_3
        negotiatedProtocol.contains("spdy") || negotiatedProtocol.contains("h2") -> Protocol.HTTP_2
        negotiatedProtocol.contains("http/1.1") -> Protocol.HTTP_1_1
        else -> Protocol.HTTP_1_0
    }

    /** Returns the last header value for the given name, or null if the header isn't present. */
    private fun getLastHeaderValue(name: String, responseInfo: UrlResponseInfo): String? =
        responseInfo.allHeaders[name]?.lastOrNull()

    private fun <T> getFutureValue(future: CompletableFuture<T>): T = try {
        future.get()
    } catch (e: ExecutionException) {
        // Unwrap so the IOException carries the actual failure (CronetException, "Canceled"...).
        throw IOException(e.cause ?: e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException(e)
    }

    private companion object {
        private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
        private const val CONTENT_TYPE_HEADER_NAME = "Content-Type"
        private const val CONTENT_ENCODING_HEADER_NAME = "Content-Encoding"

        // https://source.chromium.org/search?q=symbol:FilterSourceStream::ParseEncodingType%20f:cc
        private val ENCODINGS_HANDLED_BY_CRONET = setOf("br", "deflate", "gzip", "x-gzip")
    }
}
