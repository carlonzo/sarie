package sarie.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CronetProviderSelectionTest {

    private class FakeProvider(
        override val name: String,
        override val version: String,
        private val enabled: Boolean,
    ) : CronetProviderCandidate {
        override fun isEnabled(): Boolean = enabled
    }

    private fun provider(name: String, version: String = "1", enabled: Boolean = true) =
        FakeProvider(name, version, enabled)

    @Test
    fun `app packaged wins over later providers including fallback`() {
        val app = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_APP_PACKAGED, "app-9")
        val http = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_HTTPENGINE_NATIVE, "http-1")
        val play = provider(PLAY_SERVICES_CRONET_PROVIDER, "play-2")
        val fallback = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_FALLBACK, "fb")
        val chosen = selectCronetProvider(listOf(fallback, play, http, app))
        assertSame(app, chosen)
        assertEquals("app-9", chosen!!.version)
    }

    @Test
    fun `disabled app packaged is skipped for HttpEngine`() {
        val app = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_APP_PACKAGED, enabled = false)
        val http = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_HTTPENGINE_NATIVE, "http-4")
        val play = provider(PLAY_SERVICES_CRONET_PROVIDER, "play")
        assertSame(http, selectCronetProvider(listOf(app, play, http)))
    }

    @Test
    fun `disabled HttpEngine is skipped for Play Services`() {
        val http = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_HTTPENGINE_NATIVE, enabled = false)
        val play = provider(PLAY_SERVICES_CRONET_PROVIDER, "play-7")
        assertSame(play, selectCronetProvider(listOf(http, play)))
        assertEquals("play-7", play.version)
    }

    @Test
    fun `fallback alone is rejected`() {
        val fallback = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_FALLBACK, "fb")
        assertNull(selectCronetProvider(listOf(fallback)))
    }

    @Test
    fun `disabled providers and unknown names are skipped`() {
        val disabledPlay = provider(PLAY_SERVICES_CRONET_PROVIDER, enabled = false)
        val unknown = provider("Some-Other-Provider", "9")
        val disabledApp = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_APP_PACKAGED, enabled = false)
        assertNull(selectCronetProvider(listOf(disabledApp, unknown, disabledPlay)))
    }

    @Test
    fun `first enabled duplicate of the preferred name wins`() {
        val first = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_APP_PACKAGED, "first")
        val second = provider(org.chromium.net.CronetProvider.PROVIDER_NAME_APP_PACKAGED, "second")
        assertSame(first, selectCronetProvider(listOf(first, second)))
        assertSame(second, selectCronetProvider(listOf(second, first)))
    }
}
