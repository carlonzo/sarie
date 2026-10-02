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
 * it) instead of [Protocol.QUIC], so real h3 is visible to callers. Also populates
 * sentRequestAtMillis/receivedResponseAtMillis from bridge-owned clocks (upstream leaves both
 * unset); handshake/networkResponse are never fabricated.
 */
internal class ResponseConverter {

    /**
     * Creates an OkHttp Response from the bridging callback. Non-blocking once the callback's
     * UrlResponseInfo is available; the body streams on read.
     */
    @Throws(IOException::class)
    fun toResponse(request: Request, callback: OkHttpBridgeCallback): Response {
        val cronetResponseInfo = getFutureValue(callback.headersFuture)
        val bodySource = getFutureValue(callback.bodySourceFuture)
        return createResponse(request, cronetResponseInfo, bodySource)
            .sentRequestAtMillis(callback.sentAtMillis)
            .receivedResponseAtMillis(callback.receivedHeadersAtMillis)
            .build()
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

        // Content-Encoding is absent on most responses, so the coding list is only built when
        // there is something to split; see keepsEncodingAffectedHeaders.
        val keepEncodingAffectedHeaders = keepsEncodingAffectedHeaders(
            cronetResponseInfo.allHeaders[CONTENT_ENCODING_HEADER_NAME],
        )

        val contentLengthString = if (keepEncodingAffectedHeaders) {
            getLastHeaderValue(CONTENT_LENGTH_HEADER_NAME, cronetResponseInfo)
        } else {
            null
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

        // allHeadersAsList, not allHeaders: the map groups a name's values together, so a
        // response like "A: 1, B: 2, A: 3" would come back re-ordered and OkHttp's header order
        // (observable through Headers.toMultimap) would change. The list is built per response by
        // Cronet, which is the allocation this trades for exact wire order.
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

    /**
     * Whether Content-Encoding and Content-Length must survive into the OkHttp response.
     *
     * True when Cronet did not decode the body itself: no Content-Encoding at all, a coding it
     * does not implement, or a header that carries no coding (so there is nothing it could have
     * decoded). Content encodings can be scattered across multiple comma-separated
     * Content-Encoding headers, so every value is split in turn; the common no-header case
     * returns without splitting or listing anything.
     */
    private fun keepsEncodingAffectedHeaders(headerValues: List<String>?): Boolean {
        if (headerValues == null) return true
        var sawCoding = false
        for (value in headerValues) {
            for (coding in value.split(',')) {
                val name = coding.trim()
                if (name.isEmpty()) continue
                sawCoding = true
                if (name !in ENCODINGS_HANDLED_BY_CRONET) return true
            }
        }
        return !sawCoding
    }

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
        private val ENCODINGS_HANDLED_BY_CRONET = setOf("br", "deflate", "gzip", "x-gzip", "zstd")
    }
}
