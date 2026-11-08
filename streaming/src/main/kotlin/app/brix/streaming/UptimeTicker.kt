package app.brix.streaming

import android.os.SystemClock
import app.brix.core.diagnostics.PeriodicTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Updates [LiveStreamer.uptimeTick] once per second.
 *
 * The live-stream timer in the UI is calculated as the difference between this
 * timestamp and `connectedAtElapsedMs`. It deliberately has no loop of its own,
 * to avoid waking the processor with a second timer at the same interval. In the
 * SRTLA path, [StatsReporter] updates the timestamp along with link telemetry;
 * RTMP and WHIP have no links or telemetry, and until Sep 3 nobody updated it—so
 * the live-stream timer stayed at 0:00.
 */
internal class UptimeTicker(
    private val scope: CoroutineScope,
    private val publishTick: (Long) -> Unit,
    /** Work performed alongside the same tick. We deliberately don't create a
     *  separate loop for adaptive bitrate: two timers with the same interval would
     *  wake the processor twice per second for one task (same reasoning as in the
     *  comment for `statsTickElapsedMs`). */
    private val onTick: () -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            PeriodicTasks.register("uptime", 1000).use {
                while (true) {
                    publishTick(SystemClock.elapsedRealtime())
                    onTick()
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
