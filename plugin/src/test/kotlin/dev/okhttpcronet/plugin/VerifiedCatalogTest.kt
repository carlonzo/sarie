package dev.okhttpcronet.plugin

import java.io.File
import org.junit.Assert.assertEquals
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
    fun `plugin compileOnly okhttp is the oldest verified recipe`() {
        val build = File("build.gradle.kts").readText()
        val versions = Regex("""okhttp:([0-9]+\.[0-9]+\.[0-9]+)""")
            .findAll(build)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(setOf(RecipeRegistry.recipes.keys.minOrNull()), versions)
    }

    @Test
    fun `TestKit fixture okhttp is a verified recipe`() {
        val fixture = File("src/test/fixtures/sample-app/build.gradle.kts").readText()
        val version = Regex("""okhttp:([0-9]+\.[0-9]+\.[0-9]+)""").find(fixture)
            ?: throw AssertionError("no okhttp version in TestKit fixture")
        assertTrue(version.groupValues[1], RecipeRegistry.isVerified(version.groupValues[1]))
    }
}

private fun tomlVersion(text: String, key: String): String {
    val match = Regex("""^$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(text)
        ?: throw AssertionError("gradle/libs.versions.toml is missing $key")
    return match.groupValues[1]
}

private fun repoFile(relative: String): File {
    var dir = File(".").canonicalFile
    repeat(8) {
        val candidate = File(dir, relative)
        if (candidate.isFile) return candidate
        dir = dir.parentFile ?: return@repeat
    }
    throw AssertionError("cannot find $relative from ${File(".").canonicalFile}")
}
