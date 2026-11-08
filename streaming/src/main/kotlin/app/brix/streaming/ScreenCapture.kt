package app.brix.streaming

import android.media.projection.MediaProjection
import android.util.Log

/**
 * The single owner of the current screen capture.
 *
 * **Why it is needed.** The UI receives the capture token: Activity requests
 * consent, and before 05.09 the token stayed there in Compose state. But UI
 * controls cannot stop capture when most needed: users activate a screen
 * scene to minimize the app and show a game, hiding the app's own button.
 * Only the notification, hence the service, retains control and needs the token.
 *
 * The system capture indicator is unsuitable: it lives in the status bar,
 * hidden by the fullscreen streaming screen (verified on S21 on 05.09:
 * capture was active and visible in `dumpsys media_projection`, but there
 * was no onscreen icon).
 *
 * **Why an object rather than a streamer field.** Capture survives streamer
 * recreation when changing transport, but not process death, just like the
 * token itself, which Android does not allow us to persist.
 */
object ScreenCapture {

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var pendingAllowed: (() -> Unit)? = null

    val isActive: Boolean get() = projection != null

    /** Store the issued token. Stop the previous one: the system does not
     *  permit two active captures simultaneously anyway. */
    @Synchronized
    fun set(next: MediaProjection?) {
        val previous = projection
        projection = next
        if (previous !== next) previous?.let { runCatching { it.stop() } }
    }

    /**
     * Wait for the service to enable the mediaProjection type before
     * touching capture.
     *
     * **Why this ordering is necessary.** Consent arrives in Activity, but the
     * SERVICE grants capture eligibility: until it declares mediaProjection,
     * the system will not issue a token. `startService` commands take a round
     * trip through ActivityManager and reach the service only after our entire
     * consent handler has finished.
     *
     * This caused the 05.09 measurement failure: the first screen-scene
     * activation always failed, `getMediaProjection` returned nothing and
     * the scene fell back to camera. The second worked because the previous
     * attempt had already enabled the type. It looked like
     * "asks for permission twice".
     *
     * Register the continuation BEFORE sending the command to avoid
     * missing the response.
     */
    @Synchronized
    fun awaitAllowed(block: () -> Unit) {
        pendingAllowed = block
    }

    /** The service enabled the type; capture can proceed. Called from its
     *  onStartCommand, already on the main thread. */
    @Synchronized
    fun notifyAllowed() {
        val block = pendingAllowed ?: return
        pendingAllowed = null
        block()
    }

    /** Consent denied or cancelled: discard the continuation so it cannot
     *  run for a later, unrelated permission grant. */
    @Synchronized
    fun cancelAwait() {
        pendingAllowed = null
    }

    /**
     * Stop capture.
     *
     * Do NOT return to camera here: `stop()` invokes
     * `MediaProjection.Callback.onStop`, already registered by the UI and
     * aware of which scene to show next. A second implementation of the
     * same transition would diverge on the first edit.
     *
     * @return false if there was nothing to stop.
     */
    @Synchronized
    fun stop(): Boolean {
        val current = projection ?: return false
        projection = null
        Log.i("BrixScreen", "захват экрана остановлен по команде")
        runCatching { current.stop() }.onFailure {
            Log.w("BrixScreen", "остановка захвата: ${it.message}")
        }
        return true
    }
}
