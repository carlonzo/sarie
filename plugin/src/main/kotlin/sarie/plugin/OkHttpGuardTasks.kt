package sarie.plugin

import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
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
/** Hosts warn on UNTESTED; this repo sets failOnUntested so Renovate okhttp bumps go red. */
internal fun applyFailOnUntested(decision: PinDecision, failOnUntested: Boolean): PinDecision =
    if (failOnUntested && decision is PinDecision.Warn) PinDecision.Fail(decision.message) else decision

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

    // Holds the *result* of the metadata resolution (okhttp versions), not raw Configuration
    // handles: serializing Configuration task properties makes the configuration-cache store
    // resolve them as files, which is variant-ambiguous in flavor-aware apps.
    @get:Internal
    abstract val okhttpVersions: ListProperty<String>

    @get:Input
    abstract val failOnUntested: Property<Boolean>

    @TaskAction
    fun verify() {
        when (
            val decision = applyFailOnUntested(
                pinDecision(okhttpVersions.get().toSet()),
                failOnUntested.get(),
            )
        ) {
            PinDecision.Ok -> Unit
            is PinDecision.Warn -> logger.warn("[okhttp-cronet] ${decision.message}")
            is PinDecision.Fail -> throw GradleException(decision.message)
        }
    }
}

/**
 * Re-hashes every registered target class inside the recipe's okhttp artifacts (android AAR +
 * jvm jar) and compares against the script-generated goldens; any drift fails the build unless
 * [VerifyOkHttpFingerprintTask.allowUnfingerprinted] downgrades it to a warning (the structural
 * bytecode guard still hard-fails shape drift).
 */
abstract class VerifyOkHttpFingerprintTask : DefaultTask() {

    @get:Input
    abstract val allowUnfingerprinted: Property<Boolean>

    @get:Input
    abstract val failOnUntested: Property<Boolean>

    @get:Internal
    abstract val okhttpVersions: ListProperty<String>

    // Pinned artifacts resolved at configuration time by the plugin (one AAR for the android
    // variant, one plain jar for jvm). Resolving them at execution time would require
    // Task.project, which the configuration cache forbids.
    @get:Classpath
    abstract val fingerprintArtifacts: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val versions = okhttpVersions.get().toSet()
        when (val decision = applyFailOnUntested(pinDecision(versions), failOnUntested.get())) {
            is PinDecision.Fail -> throw GradleException(decision.message)
            is PinDecision.Warn -> {
                logger.warn("[okhttp-cronet] ${decision.message}")
                return
            }
            PinDecision.Ok -> Unit
        }
        val recipe = RecipeRegistry.forVersion(versions.first())
        val allow = allowUnfingerprinted.get()
        val byExtension = fingerprintArtifacts.files.associateBy { it.extension }
        for (target in InstrumentTarget.entries) {
            for (variant in Variant.entries) {
                val coordinates = recipe.fingerprintArtifacts.getValue(variant)
                val artifact = byExtension[if (variant == Variant.ANDROID) "aar" else "jar"] ?: continue
                val expected = recipe.fingerprints.getValue(target).getValue(variant)
                val actual = classEntrySha256(artifact, coordinates, variant, target.classEntry)
                val failure = fingerprintFailure(
                    coordinates,
                    expected,
                    actual,
                    allow,
                    target.classEntry,
                ) ?: continue
                if (allow) {
                    logger.warn("[okhttp-cronet] $failure")
                } else {
                    throw GradleException(failure)
                }
            }
        }
    }
}

/** Null on match; otherwise the failure message, with the escape-hatch note when tolerated. */
internal fun fingerprintFailure(
    coordinates: String,
    expected: String,
    actual: String,
    allowUnfingerprinted: Boolean,
    classEntry: String = InstrumentTarget.CONNECT_INTERCEPTOR.classEntry,
): String? {
    if (actual.equals(expected, ignoreCase = true)) return null
    val message = "okhttp-cronet: $classEntry fingerprint mismatch for " +
        "$coordinates (expected=$expected actual=$actual); the pinned rewrite is not safe " +
        "for this okhttp build. Align your okhttp version with the pinned one."
    return if (allowUnfingerprinted) {
        "$message allowUnfingerprinted=true tolerates the fingerprint mismatch, but the " +
            "structural bytecode guard still hard-fails any shape drift."
    } else {
        message
    }
}

internal fun classEntrySha256(
    artifact: File,
    coordinates: String,
    variant: Variant,
    classEntry: String,
): String = Fingerprint.sha256Hex(classEntryBytes(artifact, coordinates, variant, classEntry))

internal fun connectInterceptorSha256(artifact: File, coordinates: String, variant: Variant): String =
    classEntrySha256(artifact, coordinates, variant, InstrumentTarget.CONNECT_INTERCEPTOR.classEntry)

/**
 * Extracts [classEntry] from the variant's artifact: ANDROID = AAR with the class nested
 * inside classes.jar; JVM = flat jar with the class at the top level.
 */
internal fun classEntryBytes(
    artifact: File,
    coordinates: String,
    variant: Variant,
    classEntry: String,
): ByteArray {
    ZipInputStream(artifact.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == classEntry) return zip.readBytes()
            if (variant == Variant.ANDROID && entry.name == "classes.jar") {
                return classEntryInJar(zip.readBytes(), classEntry)
                    ?: throw GradleException(
                        "okhttp-cronet: $classEntry not found inside classes.jar of $coordinates",
                    )
            }
            entry = zip.nextEntry
        }
        throw GradleException("okhttp-cronet: $classEntry not found in $coordinates")
    }
}

internal fun connectInterceptorBytes(artifact: File, coordinates: String, variant: Variant): ByteArray =
    classEntryBytes(artifact, coordinates, variant, InstrumentTarget.CONNECT_INTERCEPTOR.classEntry)

private fun classEntryInJar(jarBytes: ByteArray, classEntry: String): ByteArray? {
    ZipInputStream(jarBytes.inputStream().buffered()).use { jarZip ->
        var entry = jarZip.nextEntry
        while (entry != null) {
            if (entry.name == classEntry) return jarZip.readBytes()
            entry = jarZip.nextEntry
        }
    }
    return null
}
