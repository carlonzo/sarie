package dev.okhttpcronet.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Placeholder scaffold. The AGP ASM instrumentation wiring (AsmClassVisitorFactory,
 * pin/fingerprint guards) is implemented in later todos.
 */
class TransportPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Scaffold only: intentionally empty.
    }

    companion object {
        const val PLUGIN_ID: String = "dev.okhttpcronet.transport"
    }
}
