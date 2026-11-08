package app.brix.streaming

import android.content.Context
import android.os.Environment
import android.util.Log
import com.pedro.library.base.StreamBase
import com.pedro.library.base.recording.RecordController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local MP4 recording alongside broadcasting.
 *
 * Record stream existed in settings from the beginning, persisted to disk and
 * **did nothing**: not a line of implementation. The worst kind of defect found
 * by comparing with Moblin: the UI promised protection against interruptions,
 * absent in reality, discovered only when users could not find the file.
 *
 * Recording uses the streaming encoder without a separate encoding pass,
 * adding only file multiplexing. CPU cost is small, but disk use is
 * substantial: about 30 MB/minute at 4 Mbps.
 *
 * Write to the app's directory (`Android/data/<package>/files/Movies`):
 * no permission is needed on any Android version, including Scoped Storage.
 * The tradeoff is that uninstalling the app deletes files, so log the path
 * on every start.
 */
internal class StreamRecorder(
    private val appContext: Context,
    private val stream: StreamBase,
) {
    private val tag = "BrixStream"

    /** User setting. Changes mid-stream take effect on the next stream. */
    @Volatile
    var enabled = false

    @Volatile
    private var currentFile: File? = null

    private val listener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            Log.i(tag, "запись: $status ${currentFile?.name ?: ""}")
        }

        override fun onError(e: Exception?) {
            // Do not interrupt streaming for recording: broadcasting matters more than the file.
            Log.e(tag, "запись сорвалась, эфир продолжается", e)
            currentFile = null
        }
    }

    /** Start recording if enabled. Failure does not interrupt broadcasting. */
    fun start() {
        if (!enabled || stream.isRecording) return
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (dir == null) {
            Log.e(tag, "запись: каталог недоступен, пишем только в эфир")
            return
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(tag, "запись: не удалось создать ${dir.absolutePath}")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "brix-$stamp.mp4")
        runCatching {
            stream.startRecord(file.absolutePath, RecordController.RecordTracks.ALL, listener)
        }.onSuccess {
            currentFile = file
            Log.i(tag, "запись начата: ${file.absolutePath}")
        }.onFailure {
            currentFile = null
            Log.e(tag, "запись не начата, эфир продолжается: ${it.message}")
        }
    }

    fun stop() {
        if (!stream.isRecording) return
        runCatching { stream.stopRecord() }
            .onSuccess { Log.i(tag, "запись закончена: ${currentFile?.absolutePath}") }
            .onFailure { Log.e(tag, "не удалось закрыть файл записи", it) }
        currentFile = null
    }
}
