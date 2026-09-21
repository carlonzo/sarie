package sarie.bridge

import org.chromium.net.CronetEngine

/**
 * Immutable install-time state. The engine is borrowed from the host: never closed,
 * shut down, or wrapped by the bridge.
 */
data class RuntimeSnapshot(
    val engine: CronetEngine,
    val policy: CronetPolicy,
    val mapper: RequestToUrlRequestMapper,
    val installedAtMillis: Long,
)
