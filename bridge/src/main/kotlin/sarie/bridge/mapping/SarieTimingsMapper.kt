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
package sarie.bridge.mapping

import java.util.Date
import java.util.Locale
import org.chromium.net.NetworkException
import org.chromium.net.QuicException
import org.chromium.net.RequestFinishedInfo
import sarie.bridge.SarieProtocol
import sarie.bridge.SarieTimings

internal object SarieTimingsMapper {

    fun parseProtocol(negotiatedProtocol: String?): SarieProtocol {
        if (negotiatedProtocol == null) return SarieProtocol.UNKNOWN
        val lower = negotiatedProtocol.lowercase(Locale.US)
        return when {
            lower == "h3" || lower.startsWith("h3-") || lower == "quic" || lower.startsWith("quic/") ->
                SarieProtocol.HTTP_3
            lower == "h2" -> SarieProtocol.HTTP_2
            lower == "http/1.1" -> SarieProtocol.HTTP_1_1
            else -> SarieProtocol.UNKNOWN
        }
    }

    fun map(
        info: RequestFinishedInfo,
        attempt: Int,
        isRedirect: Boolean,
        deliveredLate: Boolean,
    ): SarieTimings {
        val result = when (info.finishedReason) {
            RequestFinishedInfo.SUCCEEDED -> SarieTimings.Result.SUCCEEDED
            RequestFinishedInfo.FAILED -> SarieTimings.Result.FAILED
            RequestFinishedInfo.CANCELED -> SarieTimings.Result.CANCELED
            else -> SarieTimings.Result.FAILED
        }

        val responseInfo = info.responseInfo
        val negotiatedProtocol = responseInfo?.negotiatedProtocol
        val protocol = parseProtocol(negotiatedProtocol)
        val httpStatusCode = responseInfo?.httpStatusCode

        val metrics = info.metrics
        val socketReused = metrics?.socketReused ?: false

        fun durationMs(start: Date?, end: Date?): Long? =
            if (start != null && end != null) end.time - start.time else null

        val dnsMs = durationMs(metrics?.dnsStart, metrics?.dnsEnd)
        val connectMs = durationMs(metrics?.connectStart, metrics?.connectEnd)
        val tlsMs = durationMs(metrics?.sslStart, metrics?.sslEnd)
        val sendMs = durationMs(metrics?.sendingStart, metrics?.sendingEnd)
        val ttfbMs = metrics?.ttfbMs
        val totalMs = metrics?.totalTimeMs

        val networkException = (info.exception as? NetworkException)
            ?: (info.exception?.cause as? NetworkException)
        val quicException = (info.exception as? QuicException)
            ?: (info.exception?.cause as? QuicException)

        return SarieTimings(
            result = result,
            protocol = protocol,
            negotiatedProtocol = negotiatedProtocol,
            httpStatusCode = httpStatusCode,
            socketReused = socketReused,
            dnsMs = dnsMs,
            connectMs = connectMs,
            tlsMs = tlsMs,
            sendMs = sendMs,
            ttfbMs = ttfbMs,
            totalMs = totalMs,
            requestStartAtMillis = metrics?.requestStart?.time,
            dnsStartAtMillis = metrics?.dnsStart?.time,
            dnsEndAtMillis = metrics?.dnsEnd?.time,
            connectStartAtMillis = metrics?.connectStart?.time,
            connectEndAtMillis = metrics?.connectEnd?.time,
            tlsStartAtMillis = metrics?.sslStart?.time,
            tlsEndAtMillis = metrics?.sslEnd?.time,
            sendingStartAtMillis = metrics?.sendingStart?.time,
            sendingEndAtMillis = metrics?.sendingEnd?.time,
            responseStartAtMillis = metrics?.responseStart?.time,
            requestEndAtMillis = metrics?.requestEnd?.time,
            sentBytes = metrics?.sentByteCount,
            receivedBytes = metrics?.receivedByteCount,
            errorCode = networkException?.errorCode,
            cronetInternalErrorCode = networkException?.cronetInternalErrorCode,
            quicErrorCode = quicException?.quicDetailedErrorCode,
            attempt = attempt,
            isRedirect = isRedirect,
            deliveredLate = deliveredLate,
        )
    }
}
