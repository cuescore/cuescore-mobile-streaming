package app.brix.streaming

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.brix.bonding.SrtlaClient
import app.brix.core.diagnostics.PeriodicTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Samples bonding and transport state once a second, updates HUD-visible
 * values and writes one machine-readable log line.
 *
 * Extracted from [SrtlaStreamer]: a read-only responsibility with no effect
 * on the pipeline, unrelated to camera, overlays and reconnection.
 */
internal class StatsReporter(
    private val appContext: Context,
    private val srtlaClient: SrtlaClient,
    private val stream: SrtlaStream,
    private val scope: CoroutineScope,
    private val phase: () -> SessionPhase,
    /** Session reconnect count. Previously absent from logs, although "did it
         *  disconnect, and how often?" is the first question when analyzing a report. */
    private val reconnects: () -> Int,
    /** Session constants: encoder, codec, resolution. One line before the CSV
         *  header avoids duplicating them every second. */
    private val sessionNote: () -> String,
    private val updateState: ((StreamState) -> StreamState) -> Unit,
    /** Stream clock tick. Deliberately separate from [updateState]; see LiveStreamer.uptimeTick. */
    private val publishTick: (Long) -> Unit,
) {
    private var job: Job? = null

    private val recorder = SessionRecorder(appContext)

    /** Whether to write the session to a file. Read on stream start, not every tick:
         *  toggling recording mid-stream would leave a gap in the file. */
    @Volatile
    var debugLog: Boolean = false

    fun start() {
        if (job?.isActive == true) return
        if (debugLog) recorder.start(sessionNote())
        job = scope.launch {
            var prevBytes = mapOf<String, Long>()
            PeriodicTasks.register("stats", 1000).use {
                while (true) {
                    updateState { it.copy(connections = srtlaClient.connectionStats()) }
                    publishTick(SystemClock.elapsedRealtime())
                    prevBytes = logStatLine(prevBytes)
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        recorder.stop()
        job?.cancel()
        job = null
    }

    /** Device thermal throttling level alongside transport metrics: on a phone,
         *  heat and bitrate are the same tradeoff; reading them from separate
         *  sources made later correlation impossible.
     *  NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN. */
    private fun thermalStatus(): String = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            else -> "emergency+"
        }
    }.getOrDefault("?")

    /**
     * One machine-readable state line per second, tagged `BrixStat`.
     *
     * Event logs explain what happened; bonded sessions need link state AT
     * THAT MOMENT. Fields reflect actual investigations: `in=` (seconds since
     * anything arrived on the link) reveals a dead inbound path while the link
     * remains registered and reports outbound bytes. This is the situation
     * behind "Wi-Fi alone works; add cellular and the picture breaks up",
     * which no current app HUD can show. `srtrtt=0` means ABR is not running.
     *
     * `tasks=` lists periodic tasks running at that moment. Without it, logs
     * cannot reconstruct what was enabled, leaving thermal conclusions to
     * guesswork. This happened during analysis of the 07.09 session, where
     * even whether electronic stabilization was enabled could not be established.
     *
     * @return byte counters for the next tick's delta.
     */
    private fun logStatLine(prevBytes: Map<String, Long>): Map<String, Long> {
        val links = srtlaClient.debugLinks()
        if (links.isEmpty()) return prevBytes
        val srt = stream.srtDebug()
        val next = HashMap<String, Long>(links.size)
        val linkText = links.joinToString(" | ") { l ->
            val prev = prevBytes[l.type] ?: l.bytesSent
            next[l.type] = l.bytesSent
            val kbps = ((l.bytesSent - prev).coerceAtLeast(0) * 8 / 1000)
            "${l.type}:${l.state} rtt=${l.rttMs} w=${l.windowSize} inf=${l.inFlight} " +
                "in=${l.msSinceInbound / 1000.0}s out=${kbps}kbps score=${l.score}"
        }
        Log.i(
            "BrixStat",
            "phase=${phase()} srtconn=${srt.connected} srtrtt=${srt.rttMs.toInt()} " +
                "srtinf=${srt.inFlight} srtdrop=${srt.droppedPackets} reconn=${reconnects()} " +
                "rate=${"%.1f".format(java.util.Locale.US, srt.sendRateMbps)}Mbps " +
                "abr=${if (srt.abrEnabled) srt.abrKbps.toString() else "off"} " +
                "therm=${thermalStatus()} | tasks=[${PeriodicTasks.snapshot()}] | $linkText",
        )
        if (recorder.isActive) {
            // Semicolons inside the links field keep it in one CSV column.
            // The log line's vertical bar is just as good a separator,
            // provided it is not a comma.
            recorder.record(
                phase = phase().toString(),
                reconnects = reconnects(),
                transport = listOf(
                    srt.connected.toString(),
                    srt.rttMs.toInt().toString(),
                    srt.inFlight.toString(),
                    srt.droppedPackets.toString(),
                    "%.1f".format(java.util.Locale.US, srt.sendRateMbps),
                    if (srt.abrEnabled) srt.abrKbps.toString() else "",
                    "\"${linkText.replace(",", ";").replace("\"", "'")}\"",
                ).joinToString(","),
            )
        }
        return next
    }
}
