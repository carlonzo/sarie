package sarie.bridge

import androidx.annotation.OptIn
import java.util.Date
import org.chromium.net.ConnectionMigrationOptions
import org.chromium.net.CronetEngine
import org.chromium.net.DnsOptions

/**
 * The Cronet builder methods Sarie calls. Production delegates to [CronetEngine.Builder]. Tests
 * record calls. Brotli, cache mode, storage path, QUIC, HTTP/2, and pin bypass are setters, so
 * the last call wins. `addPublicKeyPins` appends and is not cleared.
 *
 * [configure] on [SarieBridge.install] still receives the real [CronetEngine.Builder]. It runs
 * after [applyOverridableDefaults] and before [applyBridgeOwned], which delegates immediately, so
 * a later setter overwrites configure. `addPublicKeyPins` is additive on the real builder; Sarie
 * cannot drop pins configure already added.
 */
internal interface SarieEngineBuilder {
    fun enableQuic(enable: Boolean)
    fun enableHttp2(enable: Boolean)
    fun enableBrotli(enable: Boolean)
    fun setStoragePath(path: String)
    fun enableHttpCache(cacheMode: Int, maxSize: Long)
    fun enablePublicKeyPinningBypassForLocalTrustAnchors(enable: Boolean)
    fun addPublicKeyPins(
        host: String,
        pins: Set<ByteArray>,
        includeSubdomains: Boolean,
        expirationDate: Date,
    )
    fun setConnectionMigrationOptions(options: ConnectionMigrationOptions)
    fun setDnsOptions(options: DnsOptions)
}

@OptIn(markerClass = [ConnectionMigrationOptions.Experimental::class, DnsOptions.Experimental::class])
internal class CronetEngineBuilderAdapter(
    private val delegate: CronetEngine.Builder,
) : SarieEngineBuilder {
    override fun enableQuic(enable: Boolean) {
        delegate.enableQuic(enable)
    }

    override fun enableHttp2(enable: Boolean) {
        delegate.enableHttp2(enable)
    }

    override fun enableBrotli(enable: Boolean) {
        delegate.enableBrotli(enable)
    }

    override fun setStoragePath(path: String) {
        delegate.setStoragePath(path)
    }

    override fun enableHttpCache(cacheMode: Int, maxSize: Long) {
        delegate.enableHttpCache(cacheMode, maxSize)
    }

    override fun enablePublicKeyPinningBypassForLocalTrustAnchors(enable: Boolean) {
        delegate.enablePublicKeyPinningBypassForLocalTrustAnchors(enable)
    }

    override fun addPublicKeyPins(
        host: String,
        pins: Set<ByteArray>,
        includeSubdomains: Boolean,
        expirationDate: Date,
    ) {
        delegate.addPublicKeyPins(host, pins, includeSubdomains, expirationDate)
    }

    override fun setConnectionMigrationOptions(options: ConnectionMigrationOptions) {
        delegate.setConnectionMigrationOptions(options)
    }

    override fun setDnsOptions(options: DnsOptions) {
        delegate.setDnsOptions(options)
    }
}

/**
 * Order: overridable defaults, then [configure], then bridge-owned settings. [configure] cannot
 * leave brotli, cache mode, storage path, or pin bypass at its own values. Migration options and
 * stale DNS are overridable; a provider that rejects either does not fail setup.
 */
internal fun applyEngineConfiguration(
    builder: SarieEngineBuilder,
    storagePath: String,
    groups: List<PinGroup>,
    configure: () -> Unit = {},
) {
    applyOverridableDefaults(builder)
    configure()
    applyBridgeOwned(builder, storagePath, groups)
}

@OptIn(markerClass = [ConnectionMigrationOptions.Experimental::class, DnsOptions.Experimental::class])
private fun applyOverridableDefaults(builder: SarieEngineBuilder) {
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
    builder: SarieEngineBuilder,
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
