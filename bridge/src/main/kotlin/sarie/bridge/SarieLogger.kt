package sarie.bridge

import android.util.Log

public fun interface SarieLogger {
    /** [priority] is an `android.util.Log` constant (WARN, ERROR, ...). */
    public fun log(priority: Int, message: String, throwable: Throwable?): Unit

    public companion object {
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
