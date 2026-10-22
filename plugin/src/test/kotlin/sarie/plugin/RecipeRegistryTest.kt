package sarie.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeRegistryTest {

    @Test
    fun `every pinned recipe is registered with both variant fingerprints`() {
        assertTrue(RecipeRegistry.recipes.isNotEmpty())
        for ((version, recipe) in RecipeRegistry.recipes) {
            assertEquals(
                "com.squareup.okhttp3:okhttp-android:$version",
                recipe.fingerprintArtifacts.getValue(Variant.ANDROID),
            )
            assertEquals(
                "com.squareup.okhttp3:okhttp-jvm:$version",
                recipe.fingerprintArtifacts.getValue(Variant.JVM),
            )
            val verId = version.replace('.', '_')
            for (target in InstrumentTarget.entries) {
                for (variant in Variant.entries) {
                    val field = "${target.name}_${variant.name}_$verId"
                    val golden = GoldenFingerprints::class.java
                        .getMethod("get$field")
                        .invoke(GoldenFingerprints) as String
                    assertEquals(
                        "$version $field",
                        golden,
                        recipe.fingerprints.getValue(target).getValue(variant),
                    )
                    assertEquals(64, golden.length)
                }
            }
        }
    }

    @Test
    fun `unknown version fails closed with the known-version list`() {
        val e = assertThrows(IllegalStateException::class.java) { RecipeRegistry.forVersion("4.12.0") }
        assertTrue(e.message, e.message!!.contains("sarie: no recipe for okhttp 4.12.0"))
        for (known in RecipeRegistry.recipes.keys) {
            assertTrue(e.message, e.message!!.contains(known))
        }
    }

    @Test
    fun `isVerified is true exactly for registry versions`() {
        for (v in RecipeRegistry.recipes.keys) {
            assertTrue(v, RecipeRegistry.isVerified(v))
        }
        for (v in listOf("5.0.0", "5.3.2", "4.12.0", newerThanNewestVerified())) {
            assertFalse(v, RecipeRegistry.isVerified(v))
        }
    }

    @Test
    fun `decide sends verified versions to their exact recipe`() {
        for (v in RecipeRegistry.recipes.keys) {
            val decision = RecipeRegistry.decide(v)
            assertTrue(v, decision is VersionDecision.Verified)
            assertEquals(v, (decision as VersionDecision.Verified).recipe.okhttpVersion)
        }
    }

    @Test
    fun `decide sends newer than oldest supported to the family guard`() {
        for (v in listOf("5.4.1", newerThanNewestVerified(), "6.0.0", "9.9.9")) {
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
        for (v in RecipeRegistry.recipes.keys) {
            assertEquals(RecipeRegistry.forVersion(v).guard, RecipeRegistry.guardFor(v))
        }
        assertEquals(RecipeRegistry.familyGuard, RecipeRegistry.guardFor(newerThanNewestVerified()))
    }

    @Test
    fun `family guard is the single spec shared by every verified recipe`() {
        for (v in RecipeRegistry.recipes.keys) {
            assertEquals(v, RecipeRegistry.familyGuard, RecipeRegistry.forVersion(v).guard)
        }
    }

    @Test
    fun `untested warning names the version, the verified set and the skipped check`() {
        val version = newerThanNewestVerified()
        val warning = RecipeRegistry.untestedWarning(version)
        assertTrue(warning, warning.contains("sarie: okhttp $version is UNTESTED"))
        assertTrue(warning, warning.contains("verified: ${RecipeRegistry.recipes.keys.sorted()}"))
        assertTrue(warning, warning.contains("structural guard"))
        assertTrue(warning, warning.contains("fingerprint identity check is skipped"))
    }
}
