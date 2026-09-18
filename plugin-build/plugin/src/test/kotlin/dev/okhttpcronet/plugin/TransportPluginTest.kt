package dev.okhttpcronet.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportPluginTest {
    @Test
    fun `plugin id is the stable transport coordinate`() {
        assertEquals("dev.okhttpcronet.transport", TransportPlugin.PLUGIN_ID)
    }
}
