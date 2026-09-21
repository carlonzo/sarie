package sarie.plugin

import java.io.File
import java.nio.file.Files
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPluginTest {

    @Test
    fun `plugin id is the stable transport coordinate`() {
        assertEquals("com.carlonzo.sarie", TransportPlugin.PLUGIN_ID)
    }

    @Test(timeout = 1_800_000L)
    fun `fixture with pinned okhttp assembles and both guards run`() {
        val dir = prepareFixture()
        val result = GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(dir)
            .withArguments(fixtureArgs())
            .withEnvironment(System.getenv() + ("JAVA_HOME" to testJavaHome()))
            .build()
        println(result.output) // evidence: full fixture build log with the guard task lines
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyOkHttpPin")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyOkHttpFingerprint")!!.outcome)
        assertTrue(
            "guard tasks must run before assembly",
            result.output.indexOf("> Task :verifyOkHttpPin") < result.output.indexOf("> Task :preBuild"),
        )
    }

    @Test(timeout = 1_800_000L)
    fun `library fixture registers guards and is not a no-op`() {
        val dir = prepareFixture(fixture = "sample-lib")
        val result = GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(dir)
            .withArguments(fixtureArgs())
            .withEnvironment(System.getenv() + ("JAVA_HOME" to testJavaHome()))
            .build()
        println(result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyOkHttpPin")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyOkHttpFingerprint")!!.outcome)
        assertTrue(
            "library apply must log that the rewrite still needs the application:\n${result.output}",
            result.output.contains(TransportPlugin.LIBRARY_REWRITE_NOTE),
        )
        assertFalse(
            "library apply must not be reported as a no-op:\n${result.output}",
            result.output.contains("plugin is a no-op"),
        )
    }

    @Test(timeout = 1_800_000L)
    fun `fixture with okhttp 4 fails as unsupported`() {
        val dir = prepareFixture(okhttpVersion = "4.12.0")
        val result = GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(dir)
            .withArguments(fixtureArgs())
            .withEnvironment(System.getenv() + ("JAVA_HOME" to testJavaHome()))
            .buildAndFail()
        println(result.output) // evidence: full fixture build log with the unsupported failure
        assertTrue(
            "expected the unsupported message, got:\n${result.output}",
            result.output.contains("okhttp-cronet: okhttp 4.12.0 is not supported") &&
                result.output.contains("okhttp 4"),
        )
    }
}

internal const val ANDROID_SDK_DEFAULT = "/home/carlo/Android/Sdk"

// Machine default JDK is 26 and breaks AGP; TestKit forks must always use temurin-21.
internal const val TEST_JAVA_HOME = "/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS"

// Shared with ConfigurationCacheStoreTest.
internal fun testJavaHome(): String = System.getenv("JAVA_HOME") ?: TEST_JAVA_HOME

private fun fixtureArgs(): List<String> = listOf("assembleDebug", "--console=plain")

/**
 * Copies the fixture into a fresh temp dir (TestKit must never build in-place), points it at the
 * Android SDK, and drops in the repo version catalog. [okhttpVersion] rewrites only the catalog
 * `okhttp =` pin (the unsupported-okhttp-4 scenario); the default is the catalog as committed.
 */
internal fun prepareFixture(okhttpVersion: String? = null, fixture: String = "sample-app"): File {
    val source = File("src/test/fixtures/$fixture")
    check(source.isDirectory) { "fixture not found at ${source.absolutePath}" }
    val dir = Files.createTempDirectory("okhttp-cronet-fixture-").toFile()
    source.copyRecursively(dir)
    val catalogDest = File(dir, "gradle/libs.versions.toml")
    catalogDest.parentFile.mkdirs()
    repoFile("gradle/libs.versions.toml").copyTo(catalogDest)
    if (okhttpVersion != null) {
        catalogDest.writeText(
            catalogDest.readText().replace(
                Regex("""^okhttp\s*=\s*"[^"]+"""", RegexOption.MULTILINE),
                """okhttp = "$okhttpVersion"""",
            ),
        )
    }
    val sdk = System.getenv("ANDROID_HOME") ?: ANDROID_SDK_DEFAULT
    File(dir, "local.properties").writeText("sdk.dir=${sdk.replace("\\", "\\\\")}\n")
    return dir
}
