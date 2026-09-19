package dev.okhttpcronet.plugin

/** Which upstream okhttp artifact layout a recipe entry covers. */
enum class Variant { ANDROID, JVM }

/**
 * Everything the plugin needs to support one okhttp version: the structural guard for the
 * trampoline rewrite and the fingerprint artifacts + script-generated goldens for the
 * build-time fingerprint check. Adding a newly verified okhttp 5.x version = one registry
 * line plus a generate-fingerprints.sh run.
 */
data class Recipe(
    val okhttpVersion: String,
    val guard: GuardSpec,
    val fingerprintArtifacts: Map<Variant, String>,
    val fingerprints: Map<Variant, String>,
)

/** Build-time verdict for a resolved okhttp version against the supported allowlist. */
sealed class VersionDecision {
    data class Verified(val recipe: Recipe) : VersionDecision()
    data class Untested(val version: String, val guard: GuardSpec) : VersionDecision()
    data class Unsupported(val version: String) : VersionDecision()
}

object RecipeRegistry {

    /**
     * The one registered 5.x ConnectInterceptor shape: Kotlin null-check preamble, a single
     * CHECKCAST to RealInterceptorChain within the first 5 instructions, initExchange$okhttp /
     * copy$okhttp$default / proceed each exactly once, ARETURN. Shared by every supported
     * recipe and used as the structural net for UNTESTED (newer) versions.
     */
    val CANONICAL_GUARD: GuardSpec = GuardSpec(
        checkcastOwner = "okhttp3/internal/http/RealInterceptorChain",
        checkcastFirstMaxIndex = 4,
        checkcastCount = 1..1,
        requiredInvokes = listOf(
            InvokeRule("INVOKEVIRTUAL okhttp3/internal/connection/RealCall.initExchange\$okhttp", 1),
            InvokeRule("INVOKESTATIC okhttp3/internal/http/RealInterceptorChain.copy\$okhttp\$default ", 1),
            InvokeRule("INVOKEVIRTUAL okhttp3/internal/http/RealInterceptorChain.proceed (Lokhttp3/Request;)", 1),
        ),
        mustEndWith = "ARETURN",
    )

    val familyGuard: GuardSpec get() = CANONICAL_GUARD

    // Supported, fingerprint-verified versions. Keep these as `"<version>" to` lines so
    // generate-fingerprints.sh can grep the pin set. Older than the oldest entry is
    // unsupported (okhttp 4 and 5.0–5.3); newer than the oldest and not in the map is UNTESTED.
    val recipes: Map<String, Recipe> = mapOf(
        "5.4.0" to recipe("5.4.0"),
        "5.5.0" to recipe("5.5.0"),
    )

    fun isVerified(v: String): Boolean = v in recipes

    /** Fail-closed recipe lookup: unknown versions have no fingerprint recipe. */
    fun forVersion(v: String): Recipe =
        recipes[v] ?: error("okhttp-cronet: no recipe for okhttp $v; known: ${recipes.keys}")

    fun guardFor(v: String): GuardSpec = recipes[v]?.guard ?: familyGuard

    /**
     * Allowlist of supported versions. Anything newer than the oldest supported version (and
     * not in the map) is UNTESTED: warn, apply [familyGuard], skip fingerprints. Anything older
     * — including every okhttp 4 — is not supported.
     */
    fun decide(v: String): VersionDecision {
        recipes[v]?.let { return VersionDecision.Verified(it) }
        val parsed = parseOkHttpVersion(v)
        val oldest = recipes.keys.mapNotNull { parseOkHttpVersion(it) }.minOrNull()
        return if (parsed != null && oldest != null && parsed >= oldest) {
            VersionDecision.Untested(v, familyGuard)
        } else {
            VersionDecision.Unsupported(v)
        }
    }

    fun untestedWarning(v: String): String =
        "okhttp-cronet: okhttp $v is UNTESTED (verified: ${recipes.keys.sorted()}); the " +
            "structural guard still applies but the fingerprint identity check is skipped"

    fun unsupportedMessage(v: String): String =
        "okhttp-cronet: okhttp $v is not supported (supported: ${recipes.keys.sorted()}; " +
            "older versions including okhttp 4 are not supported)"

    private fun recipe(version: String) = Recipe(
        okhttpVersion = version,
        guard = CANONICAL_GUARD,
        fingerprintArtifacts = mapOf(
            Variant.ANDROID to "com.squareup.okhttp3:okhttp-android:$version",
            Variant.JVM to "com.squareup.okhttp3:okhttp-jvm:$version",
        ),
        fingerprints = checkNotNull(GoldenFingerprints.fingerprintsFor(version)) {
            "no generated fingerprints for okhttp $version; rerun plugin-build/scripts/generate-fingerprints.sh"
        },
    )
}

internal data class OkHttpVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<OkHttpVersion> {
    override fun compareTo(other: OkHttpVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}

internal fun parseOkHttpVersion(v: String): OkHttpVersion? {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(v) ?: return null
    return OkHttpVersion(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt(),
    )
}
