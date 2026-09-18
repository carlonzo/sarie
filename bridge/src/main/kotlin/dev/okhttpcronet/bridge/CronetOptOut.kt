package dev.okhttpcronet.bridge

/**
 * Request tag marking a call as opted out of the Cronet path (see [Metrics.Reason.tag_opt_out]).
 * Hosts attach it via `request.newBuilder().tag(CronetOptOut::class.java, CronetOptOut)`.
 */
object CronetOptOut
