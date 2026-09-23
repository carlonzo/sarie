package sarie.bridge

public fun interface SarieLogger {
    /** [priority] is an `android.util.Log` constant (WARN, ERROR, ...). */
    public fun log(priority: Int, message: String, throwable: Throwable?): Unit
}
