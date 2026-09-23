package sarie.bridge

/**
 * Request tag marking a call as opted out of the Cronet path (see [FallbackReason.tag_opt_out]).
 * Hosts attach it via `request.newBuilder().tag(CronetOptOut::class.java, CronetOptOut)`.
 */
public object CronetOptOut
