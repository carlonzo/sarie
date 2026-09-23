package sarie.bridge

fun interface SarieLogger {
    /** [priority] is an `android.util.Log` constant (WARN, ERROR, ...). */
    fun log(priority: Int, message: String, throwable: Throwable?)
}
