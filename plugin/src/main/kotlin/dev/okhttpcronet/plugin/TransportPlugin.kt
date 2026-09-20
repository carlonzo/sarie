package dev.okhttpcronet.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property

/**
 * Host-app extension: `okhttpCronet { enabled; okhttpVersion; allowUnfingerprinted; failOnUntested }`.
 */
abstract class OkhttpCronetExtension {
    abstract val enabled: Property<Boolean>
    abstract val okhttpVersion: Property<String>
    abstract val allowUnfingerprinted: Property<Boolean>
    /** When true, an UNTESTED (newer) okhttp fails the pin instead of warning. Default false. */
    abstract val failOnUntested: Property<Boolean>

    init {
        enabled.convention(true)
        // okhttpVersion is optional: the pin/fingerprint tasks read the resolved classpath.
        allowUnfingerprinted.convention(false)
        failOnUntested.convention(false)
    }
}

/**
 * Registers the AGP ASM instrumentation (exactly ConnectInterceptor) and the pin/fingerprint
 * guards on Android application modules; a no-op with a warning everywhere else.
 */
class TransportPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("okhttpCronet", OkhttpCronetExtension::class.java)
        target.plugins.withId("com.android.application") {
            target.logger.lifecycle("[okhttp-cronet] META-INF marker skipped: no public Variant API for generated assets")
            registerInstrumentation(target, extension)
            registerGuards(target, extension)
        }
        // Fires once after evaluation, so it is accurate even when this plugin is applied
        // before com.android.application in the plugins block.
        target.afterEvaluate {
            if (!it.plugins.hasPlugin("com.android.application")) {
                it.logger.warn("[okhttp-cronet] plugin is a no-op outside an Android application module")
            }
        }
    }

    private fun registerInstrumentation(project: Project, extension: OkhttpCronetExtension) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (extension.enabled.get()) {
                variant.instrumentation.transformClassesWith(
                    ConnectInterceptorVisitorFactory::class.java,
                    InstrumentationScope.ALL,
                ) { params -> params.okhttpVersion.set("family") }
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
                )
            }
        }
    }

    private fun registerGuards(project: Project, extension: OkhttpCronetExtension) {
        val classpaths = project.provider {
            project.configurations.matching { config ->
                config.isCanBeResolved &&
                    (config.name == "runtimeClasspath" || config.name.endsWith("RuntimeClasspath"))
            }.toList()
        }
        val pin = project.tasks.register("verifyOkHttpPin", VerifyOkHttpPinTask::class.java) { task ->
            task.group = "verification"
            task.description = "Accepts supported okhttp versions, warns on untested (newer) ones, " +
                "fails on older/unsupported ones (including okhttp 4)."
            task.runtimeClasspaths.set(classpaths)
            task.failOnUntested.set(extension.failOnUntested)
        }
        val fingerprint = project.tasks.register(
            "verifyOkHttpFingerprint",
            VerifyOkHttpFingerprintTask::class.java,
        ) { task ->
            task.group = "verification"
            task.description = "SHA-256-checks ConnectInterceptor.class inside the recipe's okhttp " +
                "artifacts (android AAR + jvm jar); skipped with a warning for untested versions."
            task.runtimeClasspaths.set(classpaths)
            task.allowUnfingerprinted.set(extension.allowUnfingerprinted)
            task.failOnUntested.set(extension.failOnUntested)
        }
        // AGP's project-level preBuild is the earliest hook every variant assembly depends on.
        project.tasks.matching { it.name == "preBuild" }.configureEach { preBuild ->
            preBuild.dependsOn(pin, fingerprint)
        }
    }

    companion object {
        const val PLUGIN_ID: String = "com.carlonzo.sarie"
    }
}
