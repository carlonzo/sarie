package sarie.bridge

import okhttp3.CertificatePinner
import org.chromium.net.CronetEngine

/**
 * Immutable install-time state. The engine is borrowed: never closed, shut down, or wrapped,
 * including when [sarieBuilt] is true. [uninstall][SarieBridge.uninstall] only drops this
 * reference.
 *
 * [installedPins] is empty for a borrowed [SarieBridge.install] engine. [providerName] and
 * [providerVersion] are set only for the Sarie-built path.
 */
data class RuntimeSnapshot(
    val engine: CronetEngine,
    val policy: CronetPolicy,
    val mapper: RequestToUrlRequestMapper,
    val installedAtMillis: Long,
    val sarieBuilt: Boolean = false,
    val installedPins: Set<CertificatePinner.Pin> = emptySet(),
    val providerName: String? = null,
    val providerVersion: String? = null,
)
