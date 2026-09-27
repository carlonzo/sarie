package sarie.demo

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Connection-benchmark URLs, persisted in SharedPreferences (one URL per line, in order). */
object BenchUrls {

    val DEFAULTS = listOf(
        "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=100&q=60",
        "https://cloudflare-quic.com/",
        "https://www.google.com/",
        "https://cdn.jsdelivr.net/npm/jquery@3.7.1/package.json",
    )

    private const val PREFS = "bench"
    private const val KEY_URLS = "urls"

    fun load(context: Context): List<String> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URLS, null)
            ?: return DEFAULTS
        return stored.lines().filter { it.isNotBlank() }
    }

    fun save(context: Context, urls: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URLS, urls.joinToString("\n"))
            .apply()
    }

    /** The normalized URL, or an error message. Sarie only routes HTTPS. */
    fun validate(input: String): Result<String> {
        val url = input.trim().toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("not a valid URL"))
        if (!url.isHttps) return Result.failure(IllegalArgumentException("must be https"))
        return Result.success(url.toString())
    }
}
