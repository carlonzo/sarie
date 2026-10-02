package sarie.bridge

import org.chromium.net.CronetEngine
import org.chromium.net.NetLogCaptureMode

/**
 * Controls Cronet's NetLog capture on the installed engine.
 *
 * NetLog records Cronet's internal events to a file for post-mortem analysis of a request that
 * misbehaved. Cronet exposes it as an instance method on the built engine, so this is a runtime
 * control rather than an install setting: it drives whichever engine [SarieBridge] currently holds,
 * Sarie-built or borrowed alike, and needs no configuration on [SarieConfig].
 *
 * ```kotlin
 * SarieNetLog.start("/data/local/tmp/cronet.json")
 * // ... reproduce ...
 * SarieNetLog.stop()
 * ```
 *
 * [start] before an engine is installed is a no-op returning false, and [stop] without a capture in
 * progress is a no-op. A failed start (an unwritable path, an engine that rejects it) is reported
 * by [start] returning false and never leaves [isCapturing] set. Neither call throws.
 *
 * Capture is bound to the engine that started it: installing a different engine mid-capture does not
 * retarget [stop] at the newcomer, and uninstalling mid-capture leaves [stop] to flush the engine
 * that is still holding the native buffers. Capture holds those buffers; stop it as soon as the log
 * is written.
 *
 * Cronet caps a log file at 10 MB and rotates it to `<path>.1`.
 */
public object SarieNetLog {
    /** Highest defined `NetLogCaptureMode`; Cronet has no sentinel for "unset". */
    private const val MAX_CAPTURE_MODE = NetLogCaptureMode.EVERYTHING

    private val lock = Any()

    /** The engine [capturingEngine] belongs to; null when nothing is being captured. */
    @Volatile
    private var capturingEngine: CronetEngine? = null

    /**
     * Whether capture is running.
     */
    @get:JvmName("isCapturing")
    public val isCapturing: Boolean
        get() = capturingEngine != null

    /**
     * Starts NetLog capture to [path] on the installed engine.
     *
     * Returns false without touching any engine when no engine is installed, when capture is
     * already running, or when Cronet rejects the start. Never throws for a rejected start.
     *
     * @param path File to write the log to; its parent directory must exist.
     * @param captureMode One of [NetLogCaptureMode.HEAVILY_REDACTED] (headers and cookie names
     *   redacted), [NetLogCaptureMode.DEFAULT], or [NetLogCaptureMode.INCLUDE_SENSITIVE]. The last
     *   records credentials in clear text and must not run on a user's device.
     * @return Whether capture is running when this call returns.
     * @throws IllegalArgumentException if [captureMode] is not a Cronet capture mode.
     */
    @Suppress("DEPRECATION")
    public fun start(
        path: String,
        captureMode: Int,
    ): Boolean {
        require(captureMode in NetLogCaptureMode.HEAVILY_REDACTED..MAX_CAPTURE_MODE) {
            "captureMode must be a NetLogCaptureMode value, was $captureMode"
        }
        val engine = SarieBridge.engine ?: return false
        synchronized(lock) {
            if (capturingEngine != null) return true
            val started = runCatching {
                engine.startNetLogToDisk(path, true, captureMode)
            }.isSuccess
            if (started) capturingEngine = engine
            return started
        }
    }

    /**
     * Starts NetLog capture with [NetLogCaptureMode.DEFAULT]; see [start] for the full contract.
     *
     * An explicit overload rather than a default argument: a default compiles to a public
     * `start$default` synthetic, which pins that exact signature into the published ABI.
     */
    @Suppress("DEPRECATION")
    public fun start(path: String): Boolean = start(path, NetLogCaptureMode.DEFAULT)

    /**
     * Stops capture and flushes the log.
     *
     * A no-op when capture is not running. It stops the engine that started the capture, which may
     * no longer be the installed one, so an uninstall mid-capture still flushes the log. Never
     * throws, so it is safe in a shutdown or teardown path.
     */
    @Suppress("DEPRECATION")
    public fun stop() {
        val engine = synchronized(lock) {
            val started = capturingEngine
            capturingEngine = null
            started
        } ?: return
        runCatching { engine.stopNetLog() }
    }
}