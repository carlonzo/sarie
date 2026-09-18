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
 * Re-hashes ConnectInterceptor.class inside the pinned okhttp-android AAR and compares it
 * against the todo-3 generated golden; any drift fails the build.
 */
abstract class VerifyOkHttpFingerprintTask : DefaultTask() {

    @get:Input
    abstract val okhttpVersion: Property<String>

    @TaskAction
    fun verify() {
        val version = okhttpVersion.get()
        val expected = Fingerprint.OKHTTP_ANDROID_CLASS_SHA256
        val actual = connectInterceptorSha256(resolveAar(version))
        if (!actual.equals(expected, ignoreCase = true)) {
            throw GradleException(
                "okhttp-cronet: ConnectInterceptor.class fingerprint mismatch for okhttp-android " +
                    "$version (expected=$expected actual=$actual); the pinned rewrite is not safe " +
                    "for this okhttp build. Align your okhttp version with the pinned one.",
            )
        }
    }

    private fun resolveAar(version: String): File {
        val dependency = project.dependencies.create("com.squareup.okhttp3:okhttp-android:$version")
        return project.configurations
            .detachedConfiguration(dependency)
            .setTransitive(false)
            .singleFile
    }

    private fun connectInterceptorSha256(aar: File): String {
        ZipInputStream(aar.inputStream().buffered()).use { aarZip ->
            var entry = aarZip.nextEntry
            while (entry != null) {
                if (entry.name == "classes.jar") {
                    ZipInputStream(aarZip.readBytes().inputStream().buffered()).use { jarZip ->
                        var classEntry = jarZip.nextEntry
                        while (classEntry != null) {
                            if (classEntry.name == CONNECT_INTERCEPTOR_ENTRY) {
                                return Fingerprint.sha256Hex(jarZip.readBytes())
                            }
                            classEntry = jarZip.nextEntry
                        }
                        throw GradleException(
                            "okhttp-cronet: $CONNECT_INTERCEPTOR_ENTRY not found inside classes.jar " +
                                "of okhttp-android ${okhttpVersion.get()}",
                        )
                    }
                }
                entry = aarZip.nextEntry
            }
            throw GradleException(
                "okhttp-cronet: classes.jar not found in okhttp-android ${okhttpVersion.get()} AAR",
            )
        }
    }
}
