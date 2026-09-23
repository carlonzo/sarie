package sarie.bridge

import android.app.Application
import android.os.Build
import java.io.File
import java.io.FileInputStream

internal const val STORAGE_DIR_NAME = "cronet-cache"

private val NON_SAFE_CHARS = Regex("[^A-Za-z0-9._-]")

/**
 * Resolves the Cronet disk cache storage directory name for the current process.
 *
 * The main process uses [STORAGE_DIR_NAME] (`cronet-cache`). Secondary processes use
 * `cronet-cache-<suffix>`, where the suffix is the part of [processName] after `:`
 * (or the whole name if there is no `:`), with any character outside `[A-Za-z0-9._-]`
 * replaced by `_`.
 *
 * If [processName] cannot be read (null, empty, or blank), returns [STORAGE_DIR_NAME].
 */
internal fun cronetStorageDirName(
    processName: String?,
    packageName: String? = null,
): String {
    if (processName.isNullOrBlank() || (packageName != null && processName == packageName)) {
        return STORAGE_DIR_NAME
    }
    val rawSuffix = if (processName.contains(':')) {
        processName.substringAfter(':')
    } else {
        processName
    }
    val sanitizedSuffix = rawSuffix.replace(NON_SAFE_CHARS, "_")
    return if (sanitizedSuffix.isEmpty()) {
        STORAGE_DIR_NAME
    } else {
        "$STORAGE_DIR_NAME-$sanitizedSuffix"
    }
}

/**
 * Reads the current process name.
 *
 * Uses [Application.getProcessName] on API 28+, and falls back to reading `/proc/self/cmdline`
 * up to the first NUL byte on older API levels or if [Application.getProcessName] fails.
 */
internal fun currentProcessName(): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val name = try {
            Application.getProcessName()
        } catch (_: Throwable) {
            null
        }
        if (!name.isNullOrBlank()) return name
    }
    return readProcessNameFromCmdline()
}

internal fun readProcessNameFromCmdline(file: File = File("/proc/self/cmdline")): String? {
    return try {
        if (!file.canRead()) return null
        FileInputStream(file).use { input ->
            val buffer = ByteArray(512)
            var count = 0
            while (count < buffer.size) {
                val b = input.read()
                if (b <= 0) break // EOF (-1) or NUL (0)
                buffer[count++] = b.toByte()
            }
            if (count == 0) null else String(buffer, 0, count, Charsets.US_ASCII).trim()
        }
    } catch (_: Throwable) {
        null
    }
}
