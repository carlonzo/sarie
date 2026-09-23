package sarie.bridge

import android.util.Log

/**
 * Receiver for internal diagnostic, lifecycle, and routing messages from Sarie.
 *
 * Configured via [SarieConfig.Builder.debugLogger]. When unset, logging overhead is limited
 * to null-checks and message strings are never allocated.
 *
 * Calls to [log] may originate from any thread: the caller thread during [SarieBridge.install]
 * and per-call routing, background threads during Play Services initialization, or Cronet
 * network threads when response headers arrive. Implementations should execute quickly and
 * avoid blocking or throwing.
 */
public fun interface SarieLogger {
    /**
     * Records a diagnostic message.
     *
     * @param priority An `android.util.Log` constant such as [android.util.Log.DEBUG],
     *   [android.util.Log.INFO], [android.util.Log.WARN], or [android.util.Log.ERROR].
     * @param message The message text.
     * @param throwable An optional exception associated with the event, or null.
     */
    public fun log(priority: Int, message: String, throwable: Throwable?): Unit

    public companion object {
        /**
         * A ready-made [SarieLogger] implementation that forwards messages to Android Logcat
         * under the log tag `"Sarie"`.
         *
         * Recommended for debug builds:
         * ```kotlin
         * SarieBridge.install(context) {
         *     if (BuildConfig.DEBUG) {
         *         debugLogger(SarieLogger.Logcat)
         *     }
         * }
         * ```
         */
        @JvmField
        public val Logcat: SarieLogger = SarieLogger { priority, message, throwable ->
            val text = if (throwable != null) {
                "$message\n${Log.getStackTraceString(throwable)}"
            } else {
                message
            }
            Log.println(priority, "Sarie", text)
        }
    }
}
