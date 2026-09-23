package sarie.bridge

/**
 * Marks declarations that are internal to the bytecode transformations applied by the Sarie Gradle plugin.
 *
 * Direct use by application code results in a compiler error.
 */
@RequiresOptIn(
    message = "Internal to Sarie bytecode rewriting; not part of public API.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class SarieInternalApi
