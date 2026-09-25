package sarie.bridge

import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.CertificatePinner
import org.chromium.net.CronetEngine
import sarie.bridge.mapping.RequestConverter
import sarie.bridge.mapping.ResponseConverter

/**
 * Immutable install-time state. The engine is borrowed: never closed, shut down, or wrapped,
 * including when [sarieBuilt] is true. [uninstall][SarieBridge.uninstall] only drops this
 * reference.
 *
 * [installedPins] is empty for a borrowed [SarieBridge.install] engine. [providerName] and
 * [providerVersion] are set only for the Sarie-built path.
 *
 * A plain class, not a data class: the derived fields are bound to [engine] and [policy], and a
 * `copy` that changed either would silently keep stale ones.
 */
internal class RuntimeSnapshot(
    val engine: CronetEngine,
    val policy: CronetPolicy,
    val mapper: RequestToUrlRequestMapper,
    val sarieBuilt: Boolean = false,
    val installedPins: Set<CertificatePinner.Pin> = emptySet(),
    val providerName: String? = null,
    val providerVersion: String? = null,
) {
    /** Null admits every origin (empty allowlist or `"*"`). Parsed once from [policy]. */
    val originRules: List<ParsedOrigin>? = parseAllowedOrigins(policy.allowedOrigins)

    val requestConverter: RequestConverter =
        RequestConverter(engine, CronetUploadExecutor, CronetExecutor, ResponseConverter())

    /** Set when this engine's provider rejects [org.chromium.net.UrlRequest.Builder.setRequestFinishedListener]. */
    val finishedListenerUnsupported: AtomicBoolean = AtomicBoolean(false)
}
