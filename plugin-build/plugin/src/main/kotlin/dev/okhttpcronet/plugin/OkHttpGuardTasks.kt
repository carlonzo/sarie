package dev.okhttpcronet.plugin

import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.artifacts.Configuration
import java.io.File
import java.util.zip.ZipInputStream

private val OKHTTP_MODULES = setOf("okhttp", "okhttp-android", "okhttp-jvm")

/**
 * Fails unless the okhttp version resolved on every runtimeClasspath matches
 * [expectedVersion] exactly. Resolution is deferred to task execution.
 */
abstract class VerifyOkHttpPinTask : DefaultTask() {

    @get:Input
    abstract val expectedVersion: Property<String>

    // Configuration handles are wiring, not input state; their contents are read lazily below.
    @get:Internal
    abstract val runtimeClasspaths: ListProperty<Configuration>

    @TaskAction
    fun verify() {
        val expected = expectedVersion.get()
        val versions = sortedSetOf<String>()
        for (configuration in runtimeClasspaths.get()) {
            for (component in configuration.incoming.resolutionResult.allComponents) {
                val module = component.moduleVersion ?: continue
                if (module.group == "com.squareup.okhttp3" && module.name in OKHTTP_MODULES) {
                    versions += module.version
                }
            }
        }
        if (versions.isEmpty()) {
            throw GradleException(
                "okhttp-cronet: no com.squareup.okhttp3:okhttp resolved on any runtimeClasspath; " +
                    "the trampoline rewrite has nothing to apply to. Add okhttp $expected as an app dependency.",
            )
        }
        if (versions.size != 1 || versions.first() != expected) {
            throw GradleException(
                "okhttp-cronet pins okhttp exactly $expected, resolved ${versions.joinToString(", ")}; " +
                    "align your okhttp version",
            )
        }
    }
}

private const val CONNECT_INTERCEPTOR_ENTRY = "okhttp3/internal/connection/ConnectInterceptor.class"

/**
 * Re-hashes ConnectInterceptor.class inside the recipe's okhttp artifacts (android AAR + jvm
 * jar) and compares against the script-generated goldens; any drift fails the build unless
 * [VerifyOkHttpFingerprintTask.allowUnfingerprinted] downgrades it to a warning (the structural
 * bytecode guard still hard-fails shape drift).
 */
abstract class VerifyOkHttpFingerprintTask : DefaultTask() {

    @get:Input
    abstract val okhttpVersion: Property<String>

    @get:Input
    abstract val allowUnfingerprinted: Property<Boolean>

    @TaskAction
    fun verify() {
        val recipe = RecipeRegistry.forVersion(okhttpVersion.get())
        val allow = allowUnfingerprinted.get()
        for (variant in Variant.entries) {
            val coordinates = recipe.fingerprintArtifacts.getValue(variant)
            val expected = recipe.fingerprints.getValue(variant)
            val actual = connectInterceptorSha256(resolveArtifact(coordinates), coordinates, variant)
            val failure = fingerprintFailure(coordinates, expected, actual, allow) ?: continue
            if (allow) {
                logger.warn("[okhttp-cronet] $failure")
            } else {
                throw GradleException(failure)
            }
        }
    }

    private fun resolveArtifact(coordinates: String): File {
        val dependency = project.dependencies.create(coordinates)
        return project.configurations
            .detachedConfiguration(dependency)
            .setTransitive(false)
            .singleFile
    }
}

/** Null on match; otherwise the failure message, with the escape-hatch note when tolerated. */
internal fun fingerprintFailure(
    coordinates: String,
    expected: String,
    actual: String,
    allowUnfingerprinted: Boolean,
): String? {
    if (actual.equals(expected, ignoreCase = true)) return null
    val message = "okhttp-cronet: ConnectInterceptor.class fingerprint mismatch for " +
        "$coordinates (expected=$expected actual=$actual); the pinned rewrite is not safe " +
        "for this okhttp build. Align your okhttp version with the pinned one."
    return if (allowUnfingerprinted) {
        "$message allowUnfingerprinted=true tolerates the fingerprint mismatch, but the " +
            "structural bytecode guard still hard-fails any shape drift."
    } else {
        message
    }
}

internal fun connectInterceptorSha256(artifact: File, coordinates: String, variant: Variant): String =
    Fingerprint.sha256Hex(connectInterceptorBytes(artifact, coordinates, variant))

/**
 * Extracts ConnectInterceptor.class from the variant's artifact: ANDROID = AAR with the class
 * nested inside classes.jar; JVM = flat jar with the class at the top level.
 */
internal fun connectInterceptorBytes(artifact: File, coordinates: String, variant: Variant): ByteArray {
    ZipInputStream(artifact.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == CONNECT_INTERCEPTOR_ENTRY) return zip.readBytes()
            if (variant == Variant.ANDROID && entry.name == "classes.jar") {
                return connectInterceptorEntry(zip.readBytes())
                    ?: throw GradleException(
                        "okhttp-cronet: $CONNECT_INTERCEPTOR_ENTRY not found inside classes.jar of $coordinates",
                    )
            }
            entry = zip.nextEntry
        }
        throw GradleException("okhttp-cronet: $CONNECT_INTERCEPTOR_ENTRY not found in $coordinates")
    }
}

private fun connectInterceptorEntry(jarBytes: ByteArray): ByteArray? {
    ZipInputStream(jarBytes.inputStream().buffered()).use { jarZip ->
        var entry = jarZip.nextEntry
        while (entry != null) {
            if (entry.name == CONNECT_INTERCEPTOR_ENTRY) return jarZip.readBytes()
            entry = jarZip.nextEntry
        }
    }
    return null
}
