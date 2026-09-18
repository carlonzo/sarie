package dev.okhttpcronet.bridge

import okhttp3.Request
import org.chromium.net.UrlRequest

/**
 * Host hook applied to the [UrlRequest.Builder] before it is built, so host tags survive
 * on the Cronet request. Defined here (todo 2); todo 5 applies it in the converters.
 */
fun interface RequestToUrlRequestMapper {
    fun map(request: Request, builder: UrlRequest.Builder)
}
