package sarie.bridge

import org.chromium.net.CronetProvider

/**
 * A Cronet provider Sarie might build an engine from. JVM tests pass fakes; production wraps
 * [CronetProvider].
 */
internal interface CronetProviderCandidate {
    val name: String
    val version: String
    fun isEnabled(): Boolean
}

/** Play Services name. Cronet 500.0.2 has no constant for it (`CronetProviderInstaller.PROVIDER_NAME`). */
internal const val PLAY_SERVICES_CRONET_PROVIDER = "Google-Play-Services-Cronet-Provider"

/**
 * Preference among enabled providers. [CronetProvider.PROVIDER_NAME_FALLBACK] is absent on purpose:
 * that provider is Java `HttpURLConnection`, not Cronet.
 */
internal val CRONET_PROVIDER_PREFERENCE: List<String> = listOf(
    CronetProvider.PROVIDER_NAME_APP_PACKAGED,
    CronetProvider.PROVIDER_NAME_HTTPENGINE_NATIVE,
    PLAY_SERVICES_CRONET_PROVIDER,
)

/**
 * First enabled provider in [CRONET_PROVIDER_PREFERENCE]. Disabled entries are skipped. Returns
 * null when nothing qualifies (including fallback-only).
 */
internal fun <T : CronetProviderCandidate> selectCronetProvider(providers: List<T>): T? {
    val enabled = providers.filter { it.isEnabled() }
    for (name in CRONET_PROVIDER_PREFERENCE) {
        val match = enabled.firstOrNull { it.name == name }
        if (match != null) return match
    }
    return null
}

internal class LiveCronetProvider(
    val source: CronetProvider,
) : CronetProviderCandidate {
    override val name: String get() = source.name
    override val version: String get() = source.version
    override fun isEnabled(): Boolean = source.isEnabled
}
