package app.brix.streaming

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recording a stream session to a CSV file on the phone: power, temperature, thermal
 * status, and transport metrics, one row per second.
 *
 * Why separate from `BrixStat` in logcat: all thermal measurements in the project were taken
 * with the phone plugged in, on battery, for 5–15 minutes. A real failure looks
 * different—a two-hour stream with a power bank, without adb. In this
 * scenario, logcat is completely unavailable, so there was no way to see the real heating curve.
 * .
 *
 * Charging power on this phone (~5.5 W) is comparable to the power of the entire stream
 * (5.31 W), so `plugged` and the sign of `curr_ua` are not incidental details but the main
 * unchecked variable: power from a power bank adds heat on top of
 * filming, and none of the measurements accounted for this.
 *
 * **Lesson from the 07.09 session.** The very first field recording turned out to have been made while plugged in,
 * but marked itself as battery-powered, and the watts column was zero from start to finish.
 * Hence the two rules implemented below: external power is determined by
 * `EXTRA_PLUGGED` (a fully charged phone while plugged in reports `BATTERY_STATUS_FULL`,
 * not `CHARGING`), and watts are not written at all on external power—the empty
 * cell is more honest than zero, because zero is interpreted as a measurement.
 */
internal class SessionRecorder(private val appContext: Context) {

    private var writer: BufferedWriter? = null
    private var startedAtMs = 0L
    private var currentFile: File? = null

    val isActive: Boolean get() = writer != null

    fun start(note: String? = null) {
        if (writer != null) return
        val dir = File(appContext.getExternalFilesDir(null), "debug")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "не удалось создать каталог для записи сессии: $dir")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "session-$stamp.csv")
        runCatching {
            val w = BufferedWriter(FileWriter(file, true))
            // Note line before the header: values that do not change during a session—
            // encoder, codec, resolution. Keeping these as separate columns in every
            // second would duplicate the same information a thousand times, and without it
            // a report from someone else is impossible to explain: “why is theirs 9 W?” starts with
            // the question “what were they encoding, and with what?”
            if (!note.isNullOrBlank()) {
                w.write("# $note")
                w.newLine()
            }
            w.write(HEADER)
            w.newLine()
            w.flush()
            writer = w
            currentFile = file
            startedAtMs = System.currentTimeMillis()
            Log.i(TAG, "запись сессии начата: ${file.absolutePath}")
        }.onFailure {
            Log.w(TAG, "запись сессии не открылась: ${it.message}")
        }
    }

    fun stop() {
        val w = writer ?: return
        writer = null
        runCatching { w.flush(); w.close() }
        Log.i(TAG, "запись сессии закончена: ${currentFile?.absolutePath}")
        currentFile = null
    }

    /**
     * @param phase session phase, as seen by [StatsReporter].
     * @param transport collected transport metrics, in the order of the columns
     *   [HEADER] after `charging`.
     */
    fun record(phase: String, reconnects: Int, transport: String) {
        val w = writer ?: return
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else Float.NaN
        val voltMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        // External power is determined by EXTRA_PLUGGED, NOT by
        // EXTRA_STATUS == BATTERY_STATUS_CHARGING. A fully charged phone while plugged in
        // reports BATTERY_STATUS_FULL, and the old check recorded it as
        // “on battery”: in the 07.09 session, all 664 rows were marked charging=0,
        // although the charge never dropped below 100%, and current was sometimes
        // POSITIVE (+364), meaning it was flowing into the battery. The entire log looked
        // like a battery-powered measurement when it was not, and power conclusions drawn from it
        // were wrong. EXTRA_PLUGGED covers AC, USB,
        // wireless, and dock power.
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        // Raw current is written as-is, along with derived watts: some
        // Samsung firmware reports CURRENT_NOW in milliamps instead of
        // microamps, and the discrepancy between these two columns makes this immediately visible,
        // rather than after conclusions have already been drawn.
        val currUa = runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(Int.MIN_VALUE)
        // We do not calculate watts at all on external power. BatteryManager reports
        // NET battery current, which is close to zero while plugged in—the charger
        // is carrying the load—so the resulting number is not small but
        // meaningless: in the 07.09 session, the watts column is 0.00 in all 664
        // rows while streaming at 2.5 Mbps. An empty cell is more honest than zero:
        // zero is interpreted as a measurement, emptiness as “measurement was not possible.”
        //
        // Current units are determined by magnitude, not taken on faith.
        // `BATTERY_PROPERTY_CURRENT_NOW` is documented as microamps, but
        // some Samsung firmware reports milliamps—and the S21 is one of them:
        // in three measurements on 13.09 during a live stream, the current magnitude was 700..3200, which
        // in microamps would mean 3 mA and 0.01 W for the entire phone with
        // camera running. A check based on battery drain (7–13% in ten
        // minutes) yielded 6–10 W, confirming that the values really are in milliamps.
        //
        // The 20,000 threshold separates the cases by two orders of magnitude: actual
        // microamps while streaming are 1–3 MILLION units; milliamps are in the thousands.
        // There is no way to fall into the gap.
        val currAbs = Math.abs(currUa.toDouble())
        val currentMicroAmps = if (currAbs < 20_000) currAbs * 1000.0 else currAbs
        val watts = if (!plugged && currUa != Int.MIN_VALUE && voltMv > 0) {
            currentMicroAmps * voltMv / 1_000_000_000.0
        } else {
            Double.NaN
        }

        val therm = runCatching {
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

        val elapsedS = (System.currentTimeMillis() - startedAtMs) / 1000
        val wall = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val row = listOf(
            elapsedS.toString(),
            wall,
            phase,
            therm,
            pct.toString(),
            fmt(tempC),
            if (currUa == Int.MIN_VALUE) "" else currUa.toString(),
            voltMv.toString(),
            fmt(watts),
            if (plugged) "1" else "0",
            reconnects.toString(),
            transport,
        ).joinToString(",")

        // Flush to disk after every row, without buffering: in this
        // scenario the system kills the phone due to overheating—exactly when
        // the recording is being made for. A buffer would have swallowed precisely the tail.
        runCatching {
            w.write(row)
            w.newLine()
            w.flush()
        }.onFailure {
            Log.w(TAG, "строка записи потеряна: ${it.message}")
            stop()
        }
    }

    private fun fmt(v: Float): String =
        if (v.isNaN()) "" else String.format(Locale.US, "%.1f", v)

    private fun fmt(v: Double): String =
        if (v.isNaN()) "" else String.format(Locale.US, "%.2f", v)

    private companion object {
        const val TAG = "BrixSession"
        // The column is called `plugged`, not `charging`: it answers the
        // question “is this row suitable for drawing conclusions about power,” not
        // “is it charging.” The difference is not just wording—it is exactly what spoiled the
        // analysis of the 07.09 session.
        const val HEADER = "t_s,wall,phase,therm,batt_pct,batt_temp_c,curr_ua,volt_mv,watts," +
            "plugged,reconnects,srt_conn,srt_rtt_ms,srt_inflight,srt_dropped,rate_mbps,abr_kbps,links"
    }
}
