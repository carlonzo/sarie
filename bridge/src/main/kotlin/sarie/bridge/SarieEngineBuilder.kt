package sarie.bridge

import androidx.annotation.OptIn
import java.util.Date
import org.chromium.net.ConnectionMigrationOptions
import org.chromium.net.CronetEngine
import org.chromium.net.DnsOptions

/**
 * Order: overridable defaults, then [configure], then bridge-owned settings. [configure] cannot
 * leave brotli, cache mode, storage path, or pin bypass at its own values. Migration options and
 * stale DNS are overridable; a provider that rejects either does not fail setup.
 */
internal fun applyEngineConfiguration(
    builder: CronetEngine.Builder,
    storagePath: String,
    groups: List<PinGroup>,
    configure: () -> Unit = {},
) {
    applyOverridableDefaults(builder)
    configure()
    applyBridgeOwned(builder, storagePath, groups)
}

@OptIn(markerClass = [ConnectionMigrationOptions.Experimental::class, DnsOptions.Experimental::class])
private fun applyOverridableDefaults(builder: CronetEngine.Builder) {
    runCatching {
        builder.setConnectionMigrationOptions(
            ConnectionMigrationOptions.builder()
                .enableDefaultNetworkMigration(true)
                .enablePathDegradationMigration(true)
                .build(),
        )
    }
    runCatching {
        builder.setDnsOptions(
            DnsOptions.builder()
                .enableStaleDns(true)
                .preestablishConnectionsToStaleDnsResults(true)
                .build(),
        )
    }
}

private fun applyBridgeOwned(
    builder: CronetEngine.Builder,
    storagePath: String,
    groups: List<PinGroup>,
) {
    val expiry = pinExpiryDate()
    builder.enableQuic(true)
    builder.enableHttp2(true)
    builder.enableBrotli(false)
    builder.setStoragePath(storagePath)
    builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 0L)
    builder.enablePublicKeyPinningBypassForLocalTrustAnchors(false)
    for (group in groups) {
        builder.addPublicKeyPins(
            group.host,
            group.hashes.toSet(),
            group.includeSubdomains,
            expiry,
        )
    }
}
