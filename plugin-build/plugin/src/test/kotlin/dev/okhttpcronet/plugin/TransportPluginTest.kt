package dev.okhttpcronet.plugin

import java.io.File
import java.nio.file.Files
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPluginTest {

    @Test
    fun `plugin id is the stable transport coordinate`() {
        assertEquals("dev.okhttpcronet.transport", TransportPlugin.PLUGIN_ID)
    }

    @Test(timeout = 1_800_000L)
    fun `fixture with pinned okhttp assembles and both guards run`() {
        val dir = prepareFixture(PIN_OKHTTP_VERSION)
        val result = GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(dir)
            .withArguments("assembleDebug", "--console=plain")
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
    fun `fixture with mismatched okhttp fails the pin guard`() {
        val dir = prepareFixture("5.4.0")
        val result = GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(dir)
            .withArguments("assembleDebug", "--console=plain")
            .withEnvironment(System.getenv() + ("JAVA_HOME" to testJavaHome()))
            .buildAndFail()
        println(result.output) // evidence: full fixture build log with the pin failure
        assertTrue(
            "expected the actionable pin message, got:\n${result.output}",
            result.output.contains("okhttp-cronet pins okhttp exactly $PIN_OKHTTP_VERSION, resolved 5.4.0") &&
                result.output.contains("align your okhttp version"),
        )
    }

    @Test(timeout = 1_800_000L)
    fun `unknown okhttp version in the extension fails at configuration with the known list`() {
        val dir = prepareFixture(PIN_OKHTTP_VERSION, extensionVersion = "9.9.9")
        val result = GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(dir)
            .withArguments("assembleDebug", "--console=plain")
            .withEnvironment(System.getenv() + ("JAVA_HOME" to testJavaHome()))
            .buildAndFail()
        println(result.output) // evidence: configuration-time failure with the known-version list
        assertTrue(
            "expected the fail-closed recipe message, got:\n${result.output}",
            result.output.contains("okhttp-cronet: no recipe for okhttp 9.9.9") &&
                result.output.contains("known: [5.5.0]"),
        )
    }
}

private const val PIN_OKHTTP_VERSION = "5.5.0"

private const val ANDROID_SDK_DEFAULT = "/home/carlo/Android/Sdk"

// Machine default JDK is 26 and breaks AGP; TestKit forks must always use temurin-21.
private const val TEST_JAVA_HOME = "/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS"

private fun testJavaHome(): String = System.getenv("JAVA_HOME") ?: TEST_JAVA_HOME

/**
 * Copies the fixture into a fresh temp dir (TestKit must never build in-place), points it at the
 * Android SDK, optionally swaps the pinned okhttp version for a mismatch scenario and optionally
 * injects an okhttpCronet extension block (unknown-version configuration failure scenario).
 */
private fun prepareFixture(okhttpVersion: String, extensionVersion: String? = null): File {
    val source = File("src/test/fixtures/sample-app")
    check(source.isDirectory) { "fixture not found at ${source.absolutePath}" }
    val dir = Files.createTempDirectory("okhttp-cronet-fixture-").toFile()
    source.copyRecursively(dir)
    val buildScript = File(dir, "build.gradle.kts")
    buildScript.writeText(buildScript.readText().replace("okhttp:5.5.0", "okhttp:$okhttpVersion"))
    if (extensionVersion != null) {
        buildScript.appendText("\nokhttpCronet { okhttpVersion.set(\"$extensionVersion\") }\n")
    }
    val sdk = System.getenv("ANDROID_HOME") ?: ANDROID_SDK_DEFAULT
    File(dir, "local.properties").writeText("sdk.dir=${sdk.replace("\\", "\\\\")}\n")
    return dir
}
