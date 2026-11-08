package app.brix.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.util.Log
import android.app.PendingIntent
import android.content.Context
import android.os.PowerManager
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StreamService : Service() {

    companion object {
        private const val CHANNEL_ID = "streaming"
        private const val NOTIF_ID = 1

        const val ACTION_STOP = "app.brix.streaming.action.STOP"
        const val ACTION_START = "app.brix.streaming.action.START"
        const val EXTRA_URL = "url"
        const val ACTION_RECONNECT = "app.brix.streaming.action.RECONNECT"
        const val ACTION_MUTE = "app.brix.streaming.action.MUTE"

        /**
         * Allow the service to capture the screen.
         *
         * On Android 14+, a service with the mediaProjection type can be started ONLY
         * after the user has consented to capture. Therefore, the type is added in a
         * separate step: first consent, then this command,
         * and only then is the virtual display created.
         */
        const val ACTION_ALLOW_SCREEN = "app.brix.streaming.action.ALLOW_SCREEN"

        /** Screen capture ended — remove its type from the service. */
        const val ACTION_REVOKE_SCREEN = "app.brix.streaming.action.REVOKE_SCREEN"

        /**
         * Stop screen capture without stopping the live stream.
         *
         * This lives in the notification because a screen scene is enabled so that
         * the app can be minimized — and a minimized app cannot show its own button.
         * The system capture icon is not suitable for this: it is in the status bar,
         * which a full-screen live stream hides.
         */
        const val ACTION_STOP_SCREEN = "app.brix.streaming.action.STOP_SCREEN"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StreamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** What is currently shown in the notification: text and the state that the
     *  buttons depend on (mute label, screen-capture stop button). */
    private var lastNotifiedKey: String? = null

    private fun notificationKey(text: String): String =
        "$text|${StreamController.current()?.state?.value?.micMuted == true}|${ScreenCapture.isActive}"
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * A PARTIAL_WAKE_LOCK is what actually keeps an IRL session alive with the
     * phone in a pocket: the Activity's FLAG_KEEP_SCREEN_ON only holds while
     * the UI is visible, and a foreground service alone does NOT stop the CPU
     * from sleeping once the user hits the power button mid-stream.
     *
     * Acquired in onCreate, not from ACTION_START: this service is only ever
     * started for a streaming session, and the real Start path
     * (StreamScreen -> StreamService.start()) sends an action-less intent, so
     * an ACTION_START-only acquire never ran at all and every screen-off
     * stalled the stream.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "brix:streaming").apply {
            setReferenceCounted(false)
            // No 4h safety cap: an IRL session can exceed it and would silently
            // fall into Doze mid-stream (M2). The lock is released in onDestroy.
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Whether the user has allowed screen capture in this run.
     *
     * The flag is needed because the service type is set as a WHOLE with each
     * `startForeground` call; it is not appended. Any subsequent command — “mute”,
     * “reconnect”, or even a repeated start — would redeclare the types, and without
     * this flag, mediaProjection would drop out of the set, ending screen capture
     * in the middle of the live stream.
     */
    @Volatile
    private var screenAllowed = false

    /**
     * The service is already running in the foreground.
     *
     * The system rejects a repeated `startForeground` call from the background:
     * service started from background can not have location/camera/microphone
     * access". Observed 05.09: a command sent from a notification button
     * came from the background, type redeclaration was rejected
     * three times in a row, and after a minute the service was stopped as idle,
     * along with the live stream. So we redeclare types only where necessary, and
     * update the notification text through notify().
     */
    @Volatile
    private var inForeground = false

    /**
     * Service types, declared EXPLICITLY.
     *
     * The two-argument `startForeground` takes the union of all types in the
     * manifest, which has included mediaProjection since 04.09. On Android 14+,
     * this type is forbidden without user consent, so the two-argument form caused
     * a SecurityException on EVERY live-stream start, not just screen capture.
     * Therefore, the set is assembled here and always passed explicitly.
     */
    private fun foregroundTypes(): Int =
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            if (screenAllowed) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    /**
     * Start the service in the foreground with the current set of types.
     *
     * **There are two different failure types, previously handled identically.**
     *
     * 1. **The initial start failed** — the service is not in the foreground at all.
     *    The live stream continues with no guarantees: the system can kill the process
     *    at any time, and the user will find out when the broadcast cuts out. This is
     *    a real problem, and it must not go unreported.
     * 2. **Type redeclaration was rejected** — the service is already in the foreground;
     *    the command simply came from the background, and the system deliberately rejects
     *    («Foreground service started from background can not have
     *    such requests (“location/camera/microphone access”; reproduced on 05.09 via a
     *    notification button). Nothing is lost here: the type set stays unchanged, and
     *
     *    the live stream continues.
     * We do not crash the process in either case — cutting off a live stream is worse
     * than running without a guarantee. The two cases are now distinguishable in the
     */
    private fun startForegroundSafely(text: String) {
        val wasForeground = inForeground
        lastNotifiedKey = notificationKey(text)
        runCatching { startForeground(NOTIF_ID, buildNotification(text), foregroundTypes()) }
            .onSuccess { inForeground = true }
            .onFailure {
                if (wasForeground) {
                    Log.w(
                        "StreamService",
                        "переобъявление типов отвергнуто (команда из фона), " +
                            "служба остаётся на переднем плане: ${it.message}",
                    )
                } else {
                    Log.e(
                        "StreamService",
                        "СЛУЖБА НЕ ПОДНЯТА: эфир идёт без гарантии переднего плана, " +
                            "система вправе прибить процесс — ${it.message}",
                    )
                }
            }
    }
    @Volatile
    private var startThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSafely(getString(R.string.notif_streaming_title))
        acquireWakeLock()

        scope.launch {
            // Re-resolve the current streamer on every emission: the controller
            // may swap it (transport switch / release) while this service is
            // alive, and collecting a dead instance's state would freeze the
            // notification on stale text.
            // flatMapLatest: a nested collect never returns, so a plain
            // outer collect would never observe streamer swaps
            // and the notification/thermal would freeze on a dead instance.
            StreamController.currentFlow.flatMapLatest { streamer ->
                streamer?.state ?: kotlinx.coroutines.flow.flowOf(StreamState())
            }.collect { state ->
                    val text = when {
                        state.phase == SessionPhase.Reconnecting -> getString(R.string.notif_reconnecting)
                        state.status == StreamStatus.Connecting -> getString(R.string.notif_connecting)
                        state.status == StreamStatus.Connected -> getString(R.string.notif_connected)
                        state.status == StreamStatus.Rejected -> getString(R.string.notif_failed, state.message ?: "")
                        state.status == StreamStatus.Failed -> getString(R.string.notif_failed, state.error?.message ?: state.message ?: "")
                        state.status == StreamStatus.Idle -> getString(R.string.notif_interrupted)
                        else -> getString(R.string.notif_streaming_title)
                    }
                    // Only when the text changes. State changes once per second
                    // (statistics) and up to 5 times/sec during ABR, but the visible text changes
                    // only when the status changes. Previously, every update
                    // rebuilt the notification and made a binder call to
                    // system_server — throughout the live stream (audit 23.09).
                    if (notificationKey(text) != lastNotifiedKey) notifyText(text)
                // NEVER releaseAll() here: killing the streamer on an
                // error destroys the camera mid-preview ("frozen frame"
                // field bug) and poisons the next Start (released cached
                // instance). Recovery belongs to the reconnect logic /
                // explicit user Stop.
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = StreamController.current()
        // Every start path must reach startForeground within the ANR window —
        // but only while the service is not yet in the foreground. Once it is there,
        // redeclaring the types adds nothing, but from the background it is rejected
        // and risks stopping the service (see inForeground).
        if (inForeground) {
            notifyText(getString(R.string.notif_connecting))
        } else {
            startForegroundSafely(getString(R.string.notif_connecting))
        }
        when (intent?.action) {
            ACTION_START -> {
                acquireWakeLock()
                // Single-flight: overlapping STARTs used to overwrite
                // startThread, leaving an orphan thread still calling
                // st.start(). start() is @Synchronized, but the
                // loser would still run to completion afterwards.
                if (startThread?.isAlive == true) {
                    Log.w("StreamService", "ACTION_START ignored — start already in progress")
                    return START_NOT_STICKY
                }
                current?.let { st ->
                    // Camera ops must stay off main. Store the reference so
                    // onDestroy() can wait for it instead of racing.
                    val t = Thread {
                        try {
                            // release() may have raced us between current() and
                            // this thread running.
                            if (st.isReleased) return@Thread
                            if (!st.isStreaming) {
                                st.start(intent.getStringExtra(EXTRA_URL) ?: return@Thread)
                                StreamService.start(this)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("StreamService", "ACTION_START failed", e)
                        }
                    }
                    startThread = t
                    t.start()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                releaseWakeLock()
                // Soft stop ONLY. releaseAll() here was the root cause of
                // "connects once then never again": it killed the prepared
                // encoder and zeroed activeGen, and the cached instance kept
                // dropping every callback of the next session. Full resource
                // teardown belongs to a dedicated Phase 3 mechanism.
                // Stop off the main thread. The SRT sender waits for
                // SHUTDOWN for up to 500 ms in runBlocking, the Moblink server does a
                // join without a timeout, and the SRTLA client may wait for DNS while holding
                // its lock. stop() was moved to IO from the UI long ago; the notification button
                // — the main way to stop the live stream with the phone in
                // your pocket — was still on the main thread (audit 23.09).
                val target = current
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { target?.stop() }
                            .onFailure { android.util.Log.e("StreamService", "ACTION_STOP failed", it) }
                    }
                    // Capture lasts exactly as long as the session; otherwise the “screen recording”
                    // icon would remain after the live stream ended, and
                    // the system would continue to consider capture active.
                    ScreenCapture.stop()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                // Also off the main thread — see ACTION_STOP.
                current?.let { target ->
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { target.reconnect() } }
                }
                if (current == null) {
                    notifyText(getString(R.string.notif_interrupted))
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_ALLOW_SCREEN -> {
                acquireWakeLock()
                // The requested type must be a subset of the types declared in the
                // manifest, or the system throws IllegalArgumentException and
                // crashes the ENTIRE process — along with the ongoing live stream. That is exactly
                // what happened on 04.09: the debug manifest replaces the service declaration
                // entirely via tools:node="replace", and mediaProjection
                // was missing from it. The manifest is fixed, but the app must no longer crash
                // because of it: screen capture is a feature, while
                // the live stream is the work.
                screenAllowed = true
                // Always declare the type EXPLICITLY here: the command arrives immediately
                // after consent, while the app is in the foreground.
                inForeground = false
                startForegroundSafely(getString(R.string.notif_streaming_title))
                // The UI can request the token only now: before the type is declared,
                // the system will not provide it (see ScreenCapture.awaitAllowed).
                ScreenCapture.notifyAllowed()
                // Redraw: the screen-capture stop button appears only
                // after the UI passes the token to ScreenCapture.
                notifyText(getString(R.string.notif_streaming_title))
                return START_NOT_STICKY
            }
            ACTION_STOP_SCREEN -> {
                // ACTION_REVOKE_SCREEN will remove the service type — the UI sends it
                // from onStop when the system dispatches callbacks.
                // This only performs the stop itself, so the order is the same
                // for both our button and a system-initiated stop.
                if (!ScreenCapture.stop()) {
                    Log.i("StreamService", "останавливать нечего: захват не идёт")
                }
                notifyText(getString(R.string.notif_streaming_title))
                return START_NOT_STICKY
            }
            ACTION_REVOKE_SCREEN -> {
                // Capture has ended — whether the user stopped it from the
                // notification shade or the scene left the screen. The type must be removed:
                // the system does not have to tolerate mediaProjection without active capture,
                // and the “screen recording” icon would remain visible.
                screenAllowed = false
                // Do NOT redeclare types: the command comes from the background (capture
                // is stopped while the app is minimized), and the system rejects redeclaration
                // from there, potentially stopping the entire service.
                // An extra type without active capture is harmless — it is
                // checked when declared, not continuously; it will go away
                // the next time the service is properly started.
                notifyText(getString(R.string.notif_streaming_title))
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                if (current != null) {
                    current.setMuted(!current.state.value.micMuted)
                } else {
                    notifyText(getString(R.string.notif_interrupted))
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        // Don't let the system restart us after the process is killed: we have
        // no session metadata to resume a stream, so a restarted service would
        // show a stale "live" notification with no actual stream.
        if (current == null) {
            notifyText(getString(R.string.notif_interrupted))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app from recents ends the streaming session: the
        // foreground service outlives the UI, so without this the stream would
        // keep running with no way to control or observe it. Release the
        // streamer and tear the service down. releaseAll() nulls the cached
        // instance, so the next getOrCreate() builds a fresh, prepared
        // streamer instead of handing back a scope-cancelled one (the crash
        // the old comment warned about).
        StreamController.releaseAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Capture does not outlive the service: it is part of the session, not the app.
        ScreenCapture.stop()
        // Wait for the start thread so onDestroy doesn't race with st.start().
        // Bounded tightly: this runs on the main thread, and a long join here
        // blocks the UI on the system's own teardown path. start() is
        // @Synchronized and generation-guarded, so a straggler cannot corrupt
        // the next session — the join is a courtesy, not a correctness need.
        startThread?.let { t ->
            try { t.join(500) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        startThread = null
        // System-initiated destroy (task removal, OOM trim) never goes through
        // ACTION_STOP — without this the PARTIAL_WAKE_LOCK is held up to its
        // 4h safety timeout.
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        val stopPending = PendingIntent.getService(
            this, 0,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val reconnectPending = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val muted = StreamController.current()?.state?.value?.micMuted == true
        val mutePending = PendingIntent.getService(
            this, 2,
            Intent(this, StreamService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Tapping the notification body returns to the app (P0: a foreground
        // notification with no contentIntent is dead — the user cannot get
        // back to the stream without re-launching from the launcher).
        // Resolve the launcher activity via PackageManager so we don't need a
        // compile-time dependency on the :app module (MainActivity lives there).
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this, 3,
            launchIntent ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(getString(R.string.notif_streaming_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_action_stop), stopPending)
        // **The notification fits exactly three buttons.** Android silently discards a
        // fourth — it is present in the object (`actions=4` in dumpsys), but
        // not on screen. Reproduced on 05.09: the “Return to camera” button was
        // added as the fourth; the owner could not see it, while capture continued
        // as expected. So the third slot is shared: while capture is active, it goes to
        // returning to the camera, and “Reconnect” yields its place — reconnection
        // happens automatically anyway, and there is no other way to exit capture.
        if (ScreenCapture.isActive) {
            val stopScreenPending = PendingIntent.getService(
                this, 4,
                Intent(this, StreamService::class.java).setAction(ACTION_STOP_SCREEN),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.notif_action_stop_screen), stopScreenPending)
        } else {
            builder.addAction(0, getString(R.string.notif_action_reconnect), reconnectPending)
        }
        builder.addAction(
            0,
            getString(if (muted) R.string.notif_action_unmute else R.string.notif_action_mute),
            mutePending,
        )
        return builder.build()
    }

    /** All text updates go through this function so the state subscription knows
     *  what is currently on screen and does not rebuild it unnecessarily. */
    private fun notifyText(text: String) {
        lastNotifiedKey = notificationKey(text)
        notify(buildNotification(text))
    }

    private fun notify(notification: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification)
    }
}
