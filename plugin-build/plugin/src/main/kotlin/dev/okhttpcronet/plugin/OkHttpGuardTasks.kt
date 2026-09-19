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

internal sealed class PinDecision {
    data object Ok : PinDecision()
    data class Warn(val message: String) : PinDecision()
    data class Fail(val message: String) : PinDecision()
}

internal fun collectOkHttpVersions(classpaths: List<Configuration>): Set<String> {
    val versions = sortedSetOf<String>()
    for (configuration in classpaths) {
        for (component in configuration.incoming.resolutionResult.allComponents) {
            val module = component.moduleVersion ?: continue
            if (module.group == "com.squareup.okhttp3" && module.name in OKHTTP_MODULES) {
                versions += module.version
            }
        }
    }
    return versions
}

/**
 * Allowlist check against the resolved okhttp version: supported = ok, newer = warn,
 * older / missing / mixed = fail.
 */
internal fun pinDecision(versions: Set<String>): PinDecision {
    val supported = RecipeRegistry.recipes.keys.sorted()
    if (versions.isEmpty()) {
        return PinDecision.Fail(
            "okhttp-cronet: no com.squareup.okhttp3:okhttp resolved on any runtimeClasspath; " +
                "the trampoline rewrite has nothing to apply to. Add a supported okhttp version ($supported).",
        )
    }
    if (versions.size != 1) {
        return PinDecision.Fail(
            "okhttp-cronet: multiple okhttp versions resolved (${versions.joinToString()}); " +
                "supported: $supported",
        )
    }
    val version = versions.first()
    return when (RecipeRegistry.decide(version)) {
        is VersionDecision.Verified -> PinDecision.Ok
        is VersionDecision.Untested -> PinDecision.Warn(RecipeRegistry.untestedWarning(version))
        is VersionDecision.Unsupported -> PinDecision.Fail(RecipeRegistry.unsupportedMessage(version))
    }
}

/**
 * Fails on unsupported/older/missing okhttp, warns on untested (newer) versions, and
 * accepts any version in the supported allowlist. Resolution is deferred to task execution.
 */
abstract class VerifyOkHttpPinTask : DefaultTask() {

    // Configuration handles are wiring, not input state; their contents are read lazily below.
    @get:Internal
    abstract val runtimeClasspaths: ListProperty<Configuration>

    @TaskAction
    fun verify() {
        when (val decision = pinDecision(collectOkHttpVersions(runtimeClasspaths.get()))) {
            PinDecision.Ok -> Unit
            is PinDecision.Warn -> logger.warn("[okhttp-cronet] ${decision.message}")
            is PinDecision.Fail -> throw GradleException(decision.message)
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
    abstract val allowUnfingerprinted: Property<Boolean>

    @get:Internal
    abstract val runtimeClasspaths: ListProperty<Configuration>

    @TaskAction
    fun verify() {
        val versions = collectOkHttpVersions(runtimeClasspaths.get())
        when (val decision = pinDecision(versions)) {
            is PinDecision.Fail -> throw GradleException(decision.message)
            is PinDecision.Warn -> {
                logger.warn("[okhttp-cronet] ${decision.message}")
                return
            }
            PinDecision.Ok -> Unit
        }
        val recipe = RecipeRegistry.forVersion(versions.first())
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
