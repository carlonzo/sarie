package dev.okhttpcronet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `verified 5_4_0 recipe is registered with both variant fingerprints`() {
        val recipe = RecipeRegistry.recipes["5.4.0"]
        assertNotNull(recipe)
        assertEquals("com.squareup.okhttp3:okhttp-android:5.4.0", recipe!!.fingerprintArtifacts.getValue(Variant.ANDROID))
        assertEquals("com.squareup.okhttp3:okhttp-jvm:5.4.0", recipe.fingerprintArtifacts.getValue(Variant.JVM))
        assertEquals(GoldenFingerprints.OKHTTP_ANDROID_5_4_0, recipe.fingerprints.getValue(Variant.ANDROID))
        assertEquals(GoldenFingerprints.OKHTTP_JVM_5_4_0, recipe.fingerprints.getValue(Variant.JVM))
    }

    @Test
    fun `unknown version fails closed with the known-version list`() {
        val e = assertThrows(IllegalStateException::class.java) { RecipeRegistry.forVersion("4.12.0") }
        assertTrue(e.message, e.message!!.contains("okhttp-cronet: no recipe for okhttp 4.12.0"))
        for (known in RecipeRegistry.recipes.keys) {
            assertTrue(e.message, e.message!!.contains(known))
        }
    }

    @Test
    fun `isVerified is true exactly for registry versions`() {
        assertTrue(RecipeRegistry.isVerified("5.4.0"))
        assertTrue(RecipeRegistry.isVerified("5.5.0"))
        for (v in listOf("5.0.0", "5.3.2", "5.5.1", "4.12.0")) {
            assertFalse(v, RecipeRegistry.isVerified(v))
        }
    }

    @Test
    fun `decide sends verified versions to their exact recipe`() {
        for (v in listOf("5.4.0", "5.5.0")) {
            val decision = RecipeRegistry.decide(v)
            assertTrue(v, decision is VersionDecision.Verified)
            assertEquals(v, (decision as VersionDecision.Verified).recipe.okhttpVersion)
        }
    }

    @Test
    fun `decide sends newer than oldest supported to the family guard`() {
        for (v in listOf("5.4.1", "5.5.1", "6.0.0", "9.9.9")) {
            val decision = RecipeRegistry.decide(v)
            assertTrue(v, decision is VersionDecision.Untested)
            decision as VersionDecision.Untested
            assertEquals(v, decision.version)
            assertEquals(RecipeRegistry.familyGuard, decision.guard)
        }
    }

    @Test
    fun `decide hard-fails older than oldest supported including okhttp 4`() {
        for (v in listOf(
            "5.0.0", "5.1.0", "5.2.0", "5.2.1", "5.2.2", "5.2.3",
            "5.3.0", "5.3.1", "5.3.2", "4.12.0", "3.14.9",
        )) {
            val decision = RecipeRegistry.decide(v)
            assertTrue(v, decision is VersionDecision.Unsupported)
            assertEquals(v, (decision as VersionDecision.Unsupported).version)
            assertTrue(RecipeRegistry.unsupportedMessage(v).contains("okhttp 4"))
        }
    }

    @Test
    fun `decide hard-fails unparseable versions as unsupported`() {
        for (v in listOf("banana", "")) {
            assertTrue(v, RecipeRegistry.decide(v) is VersionDecision.Unsupported)
        }
    }

    @Test
    fun `guardFor returns the recipe guard for verified versions and the family guard otherwise`() {
        assertEquals(RecipeRegistry.forVersion("5.5.0").guard, RecipeRegistry.guardFor("5.5.0"))
        assertEquals(RecipeRegistry.forVersion("5.4.0").guard, RecipeRegistry.guardFor("5.4.0"))
        assertEquals(RecipeRegistry.familyGuard, RecipeRegistry.guardFor("5.5.1"))
    }

    @Test
    fun `family guard is the single spec shared by every verified recipe`() {
        for (v in RecipeRegistry.recipes.keys) {
            assertEquals(v, RecipeRegistry.familyGuard, RecipeRegistry.forVersion(v).guard)
        }
    }

    @Test
    fun `untested warning names the version, the verified set and the skipped check`() {
        val warning = RecipeRegistry.untestedWarning("5.5.1")
        assertTrue(warning, warning.contains("okhttp-cronet: okhttp 5.5.1 is UNTESTED"))
        assertTrue(warning, warning.contains("verified: ${RecipeRegistry.recipes.keys.sorted()}"))
        assertTrue(warning, warning.contains("structural guard"))
        assertTrue(warning, warning.contains("fingerprint identity check is skipped"))
    }
}
