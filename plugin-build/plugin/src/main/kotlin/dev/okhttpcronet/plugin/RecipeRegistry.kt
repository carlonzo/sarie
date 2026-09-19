package dev.okhttpcronet.plugin

/** Which upstream okhttp artifact layout a recipe entry covers. */
enum class Variant { ANDROID, JVM }

/**
 * Everything the plugin needs to support one okhttp version: the structural guard for the
 * trampoline rewrite and the fingerprint artifacts + script-generated goldens for the
 * build-time fingerprint check. Adding a future okhttp 5.x version = one registry line plus
 * a generate-fingerprints.sh run.
 */
data class Recipe(
    val okhttpVersion: String,
    val guard: GuardSpec,
    val fingerprintArtifacts: Map<Variant, String>,
    val fingerprints: Map<Variant, String>,
)

object RecipeRegistry {

    val recipes: Map<String, Recipe> = mapOf(
        "5.5.0" to Recipe(
            okhttpVersion = "5.5.0",
            guard = GuardSpec(
                checkcastOwner = "okhttp3/internal/http/RealInterceptorChain",
                checkcastFirstMaxIndex = 4,
                checkcastCount = 1..1,
                requiredInvokes = listOf(
                    InvokeRule("INVOKEVIRTUAL okhttp3/internal/connection/RealCall.initExchange\$okhttp", 1),
                    InvokeRule("INVOKESTATIC okhttp3/internal/http/RealInterceptorChain.copy\$okhttp\$default ", 1),
                    InvokeRule("INVOKEVIRTUAL okhttp3/internal/http/RealInterceptorChain.proceed (Lokhttp3/Request;)", 1),
                ),
                mustEndWith = "ARETURN",
            ),
            fingerprintArtifacts = mapOf(
                Variant.ANDROID to "com.squareup.okhttp3:okhttp-android:5.5.0",
                Variant.JVM to "com.squareup.okhttp3:okhttp-jvm:5.5.0",
            ),
            fingerprints = mapOf(
                Variant.ANDROID to GoldenFingerprints.OKHTTP_ANDROID_5_5_0,
                Variant.JVM to GoldenFingerprints.OKHTTP_JVM_5_5_0,
            ),
        ),
    )

    /** Fail-closed: an unknown version must stop the build, never silently skip the rewrite. */
    fun forVersion(v: String): Recipe =
        recipes[v] ?: error("okhttp-cronet: no recipe for okhttp $v; known: ${recipes.keys}")
}
