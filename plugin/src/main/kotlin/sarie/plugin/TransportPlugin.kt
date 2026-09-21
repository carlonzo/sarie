package sarie.plugin

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
    /**
     * When true, invalidates AGP's transformed dependency cache on every build.
     * Useful during plugin/transform development or debugging with InstrumentationScope.ALL.
     */
    abstract val forceInstrument: Property<Boolean>

    init {
        enabled.convention(true)
        // okhttpVersion is optional: the pin/fingerprint tasks read the resolved classpath.
        allowUnfingerprinted.convention(false)
        failOnUntested.convention(false)
        forceInstrument.convention(false)
    }
}

/**
 * Registers the AGP ASM instrumentation (exactly ConnectInterceptor) on Android application
 * modules and the pin/fingerprint guards on application **and** library modules. A no-op with
 * a warning everywhere else.
 *
 * The rewrite uses [InstrumentationScope.ALL], which AGP allows only on apps: OkHttp lives in
 * a dependency AAR, and instrumenting that AAR from a library has no effect on consumers.
 * Apply this plugin to the application that packages the APK even when OkHttp is declared
 * only in a library — the app's merged classpath still contains it. Applying it to the
 * library as well runs the version guards where OkHttp is declared.
 */
class TransportPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("okhttpCronet", OkhttpCronetExtension::class.java)
        target.plugins.withId("com.android.application") {
            target.logger.lifecycle("[okhttp-cronet] META-INF marker skipped: no public Variant API for generated assets")
            registerInstrumentation(target, extension)
            registerGuards(target, extension)
        }
        target.plugins.withId("com.android.library") {
            registerGuards(target, extension)
            target.logger.lifecycle(LIBRARY_REWRITE_NOTE)
        }
        // Fires once after evaluation, so it is accurate even when this plugin is applied
        // before com.android.application / com.android.library in the plugins block.
        target.afterEvaluate {
            val android = it.plugins.hasPlugin("com.android.application") ||
                it.plugins.hasPlugin("com.android.library")
            if (!android) {
                it.logger.warn(
                    "[okhttp-cronet] plugin is a no-op outside an Android application or library module",
                )
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
                ) { params ->
                    params.okhttpVersion.set(extension.okhttpVersion.orElse("family"))
                    val force = extension.forceInstrument.get() ||
                        project.providers.gradleProperty("okhttpCronet.forceInstrument").orNull == "true"
                    if (force) {
                        params.invalidateToken.set(System.currentTimeMillis())
                    }
                }
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

        internal const val LIBRARY_REWRITE_NOTE: String =
            "[okhttp-cronet] library module: pin/fingerprint guards registered. " +
                "The ConnectInterceptor rewrite requires this plugin on the application " +
                "(AGP cannot instrument dependencies into a library AAR)."
    }
}
