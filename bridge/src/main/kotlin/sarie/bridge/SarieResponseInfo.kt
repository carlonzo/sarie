package sarie.bridge

/**
 * Response metadata emitted on the caller thread when headers arrive from Cronet.
 */
public class SarieResponseInfo internal constructor(
    public val protocol: SarieProtocol,
    public val negotiatedProtocol: String,
    public val httpStatusCode: Int,
    public val wasCached: Boolean,
    public val handoffAtMillis: Long,
    public val headersAtMillis: Long,
    public val attempt: Int,
    public val isRedirect: Boolean,
) {
    override fun toString(): String =
        "SarieResponseInfo(" +
            "protocol=$protocol, " +
            "negotiatedProtocol='$negotiatedProtocol', " +
            "httpStatusCode=$httpStatusCode, " +
            "wasCached=$wasCached, " +
            "handoffAtMillis=$handoffAtMillis, " +
            "headersAtMillis=$headersAtMillis, " +
            "attempt=$attempt, " +
            "isRedirect=$isRedirect)"
}
