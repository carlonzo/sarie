package sarie.bridge

@RequiresOptIn(
    message = "Internal to Sarie bytecode rewriting; not part of public API.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class SarieInternalApi
