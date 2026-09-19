package dev.okhttpcronet.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property

/**
 * Host-app extension: `okhttpCronet { enabled; okhttpVersion; allowUnfingerprinted }`.
 */
abstract class OkhttpCronetExtension {
    abstract val enabled: Property<Boolean>
    abstract val okhttpVersion: Property<String>
    abstract val allowUnfingerprinted: Property<Boolean>

    init {
        enabled.convention(true)
        okhttpVersion.convention("5.5.0")
        allowUnfingerprinted.convention(false)
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
            // Optional META-INF/okhttp-cronet-marker.txt breadcrumb skipped on purpose:
            // AGP 8.13 exposes no public Variant API for generated assets (Sources.assets
            // was removed); recorded as a deviation in the todo-7 notepad.
            target.logger.lifecycle("[okhttp-cronet] META-INF marker skipped: no public AGP 8.13 API for generated assets")
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
            // Fail the build at configuration time (with the known-version list) when the
            // extension carries an okhttp version no recipe covers.
            val recipe = RecipeRegistry.forVersion(extension.okhttpVersion.get())
            if (extension.enabled.get()) {
                variant.instrumentation.transformClassesWith(
                    ConnectInterceptorVisitorFactory::class.java,
                    InstrumentationScope.ALL,
                ) { params -> params.okhttpVersion.set(recipe.okhttpVersion) }
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
                )
            }
        }
    }

    private fun registerGuards(project: Project, extension: OkhttpCronetExtension) {
        val pin = project.tasks.register("verifyOkHttpPin", VerifyOkHttpPinTask::class.java) { task ->
            task.group = "verification"
            task.description = "Fails unless the resolved okhttp version matches the pinned one."
            task.expectedVersion.set(extension.okhttpVersion)
            task.runtimeClasspaths.set(
                project.provider {
                    project.configurations.matching { config ->
                        config.isCanBeResolved &&
                            (config.name == "runtimeClasspath" || config.name.endsWith("RuntimeClasspath"))
                    }.toList()
                },
            )
        }
        val fingerprint = project.tasks.register(
            "verifyOkHttpFingerprint",
            VerifyOkHttpFingerprintTask::class.java,
        ) { task ->
            task.group = "verification"
            task.description = "SHA-256-checks ConnectInterceptor.class inside the recipe's okhttp " +
                "artifacts (android AAR + jvm jar)."
            task.okhttpVersion.set(extension.okhttpVersion)
            task.allowUnfingerprinted.set(extension.allowUnfingerprinted)
        }
        // AGP's project-level preBuild is the earliest hook every variant assembly depends on.
        project.tasks.matching { it.name == "preBuild" }.configureEach { preBuild ->
            preBuild.dependsOn(pin, fingerprint)
        }
    }

    companion object {
        const val PLUGIN_ID: String = "dev.okhttpcronet.transport"
    }
}
