package dev.okhttpcronet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeRegistryTest {

    @Test
    fun `pinned 5_5_0 recipe is registered with both variant fingerprints`() {
        val recipe = RecipeRegistry.recipes["5.5.0"]
        assertNotNull(recipe)
        assertEquals("com.squareup.okhttp3:okhttp-android:5.5.0", recipe!!.fingerprintArtifacts.getValue(Variant.ANDROID))
        assertEquals("com.squareup.okhttp3:okhttp-jvm:5.5.0", recipe.fingerprintArtifacts.getValue(Variant.JVM))
        assertEquals(GoldenFingerprints.OKHTTP_ANDROID_5_5_0, recipe.fingerprints.getValue(Variant.ANDROID))
        assertEquals(GoldenFingerprints.OKHTTP_JVM_5_5_0, recipe.fingerprints.getValue(Variant.JVM))
    }

    @Test
    fun `unknown version fails closed with the known-version list`() {
        val e = assertThrows(IllegalStateException::class.java) { RecipeRegistry.forVersion("4.12.0") }
        assertTrue(e.message, e.message!!.contains("okhttp-cronet: no recipe for okhttp 4.12.0"))
        for (known in RecipeRegistry.recipes.keys) {
            assertTrue(e.message, e.message!!.contains(known))
        }
    }
}
