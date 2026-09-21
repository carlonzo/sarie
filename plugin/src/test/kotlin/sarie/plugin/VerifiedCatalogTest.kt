package sarie.plugin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renovate may bump version-catalog / hardcoded okhttp coordinates. Those bumps must fail CI
 * until a human adds the version to [RecipeRegistry] (goldens + matrix). These tests are that gate.
 */
class VerifiedCatalogTest {

    @Test
    fun `version catalog okhttp pins are verified recipes`() {
        val catalog = repoFile("gradle/libs.versions.toml").readText()
        val okhttp = tomlVersion(catalog, "okhttp")
        val min = tomlVersion(catalog, "okhttpMin")
        assertTrue("catalog okhttp $okhttp", RecipeRegistry.isVerified(okhttp))
        assertTrue("catalog okhttpMin $min", RecipeRegistry.isVerified(min))
        assertTrue(
            "mockwebserver3 must track the okhttp version pin",
            catalog.contains("mockwebserver3") && catalog.contains("version.ref = \"okhttp\""),
        )
        assertEquals(
            "okhttpMin must be the oldest verified recipe (bridge compileOnly floor)",
            RecipeRegistry.recipes.keys.minOrNull(),
            min,
        )
    }

    @Test
    fun `plugin compileOnly okhttp is the catalog min pin`() {
        val build = File("build.gradle.kts").readText()
        assertTrue(
            "plugin must compileOnly(libs.okhttp.min), got:\n$build",
            build.contains("compileOnly(libs.okhttp.min)"),
        )
    }

    @Test
    fun `TestKit fixtures depend on okhttp through the version catalog`() {
        for (name in listOf("sample-app", "sample-lib")) {
            val build = File("src/test/fixtures/$name/build.gradle.kts").readText()
            assertTrue(
                "$name must implementation(libs.okhttp), got:\n$build",
                build.contains("implementation(libs.okhttp)"),
            )
            assertFalse(
                "$name must not hardcode an okhttp coordinate, got:\n$build",
                Regex("""okhttp:[0-9]""").containsMatchIn(build),
            )
        }
    }
}

private fun tomlVersion(text: String, key: String): String {
    val match = Regex("""^$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(text)
        ?: throw AssertionError("gradle/libs.versions.toml is missing $key")
    return match.groupValues[1]
}
