package sarie.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression: the guard tasks used to hold raw [org.gradle.api.artifacts.Configuration]
 * handles as task properties. The configuration-cache *store* serializes task properties and
 * resolving those handles as files is variant-ambiguous in flavor-aware apps (plain library
 * dependencies lack the flavor attribute), which failed every build with
 * "Configuration cache state could not be cached". The fixture below — flavor-aware app plus a
 * plain (flavor-less) library dependency — must assemble with the configuration cache on.
 */
class ConfigurationCacheStoreTest {

    @Test(timeout = 1_800_000L)
    fun `flavored app with plain library dep stores the configuration cache`() {
        val dir = prepareFixture(fixture = "sample-app-flavored")
        val result = GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(dir)
            .withArguments("assembleDevDebug", "--configuration-cache", "--console=plain")
            .withEnvironment(System.getenv() + ("JAVA_HOME" to testJavaHome()))
            .build()
        println(result.output) // evidence: full fixture build log with the guard task lines
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyOkHttpPin")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyOkHttpFingerprint")!!.outcome)
        assertFalse(
            "configuration cache store must not fail:\n${result.output}",
            result.output.contains("Configuration cache state could not be cached"),
        )
        assertFalse(
            "configuration cache entry must be stored, not discarded:\n${result.output}",
            result.output.contains("Configuration cache entry discarded"),
        )
    }
}
