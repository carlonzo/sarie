package dev.okhttpcronet.bridge

import okhttp3.Request
import org.chromium.net.UrlRequest

/**
 * Optional host hook applied to the Cronet [UrlRequest.Builder] after the OkHttp
 * [Request] has been copied and before [UrlRequest.Builder.build].
 *
 * Use this for Cronet-only knobs that have no OkHttp equivalent (priority,
 * traffic-stats uid, request annotations). Do not mutate the OkHttp request.
 * Most hosts never need one: [CronetRuntime.install] defaults to [NOOP].
 */
fun interface RequestToUrlRequestMapper {
    fun map(request: Request, builder: UrlRequest.Builder)

    companion object {
        /** Identity mapper: leave the builder as [RequestConverter] filled it. */
        @JvmField
        val NOOP: RequestToUrlRequestMapper = RequestToUrlRequestMapper { _, _ -> }
    }
}
