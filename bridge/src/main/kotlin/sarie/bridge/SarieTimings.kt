package sarie.bridge

/**
 * Detailed transport metrics and phase timings for a finished Cronet request attempt.
 */
public class SarieTimings internal constructor(
    public val result: Result,
    public val protocol: SarieProtocol,
    public val negotiatedProtocol: String?,
    public val httpStatusCode: Int?,
    public val socketReused: Boolean,
    public val dnsMs: Long?,
    public val connectMs: Long?,
    public val tlsMs: Long?,
    public val sendMs: Long?,
    public val ttfbMs: Long?,
    public val totalMs: Long?,
    public val requestStartAtMillis: Long?,
    public val dnsStartAtMillis: Long?,
    public val dnsEndAtMillis: Long?,
    public val connectStartAtMillis: Long?,
    public val connectEndAtMillis: Long?,
    public val tlsStartAtMillis: Long?,
    public val tlsEndAtMillis: Long?,
    public val sendingStartAtMillis: Long?,
    public val sendingEndAtMillis: Long?,
    public val responseStartAtMillis: Long?,
    public val requestEndAtMillis: Long?,
    public val sentBytes: Long?,
    public val receivedBytes: Long?,
    public val errorCode: Int?,
    public val cronetInternalErrorCode: Int?,
    public val quicErrorCode: Int?,
    public val attempt: Int,
    public val isRedirect: Boolean,
    public val deliveredLate: Boolean,
) {
    public enum class Result {
        SUCCEEDED,
        FAILED,
        CANCELED,
    }

    override fun toString(): String =
        "SarieTimings(" +
            "result=$result, " +
            "protocol=$protocol, " +
            "negotiatedProtocol=$negotiatedProtocol, " +
            "httpStatusCode=$httpStatusCode, " +
            "socketReused=$socketReused, " +
            "dnsMs=$dnsMs, " +
            "connectMs=$connectMs, " +
            "tlsMs=$tlsMs, " +
            "sendMs=$sendMs, " +
            "ttfbMs=$ttfbMs, " +
            "totalMs=$totalMs, " +
            "requestStartAtMillis=$requestStartAtMillis, " +
            "dnsStartAtMillis=$dnsStartAtMillis, " +
            "dnsEndAtMillis=$dnsEndAtMillis, " +
            "connectStartAtMillis=$connectStartAtMillis, " +
            "connectEndAtMillis=$connectEndAtMillis, " +
            "tlsStartAtMillis=$tlsStartAtMillis, " +
            "tlsEndAtMillis=$tlsEndAtMillis, " +
            "sendingStartAtMillis=$sendingStartAtMillis, " +
            "sendingEndAtMillis=$sendingEndAtMillis, " +
            "responseStartAtMillis=$responseStartAtMillis, " +
            "requestEndAtMillis=$requestEndAtMillis, " +
            "sentBytes=$sentBytes, " +
            "receivedBytes=$receivedBytes, " +
            "errorCode=$errorCode, " +
            "cronetInternalErrorCode=$cronetInternalErrorCode, " +
            "quicErrorCode=$quicErrorCode, " +
            "attempt=$attempt, " +
            "isRedirect=$isRedirect, " +
            "deliveredLate=$deliveredLate)"
}
