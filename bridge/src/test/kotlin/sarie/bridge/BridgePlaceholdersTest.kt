package sarie.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgePlaceholdersTest {
    @Test
    fun `placeholder compiles and runs on the JVM`() {
        assertEquals("BridgePlaceholders", BridgePlaceholders::class.java.simpleName)
    }
}
