package app.brix.core.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.brix.core.AppSettings
import app.brix.core.ServerProfile
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds an end-user-exportable diagnostics bundle: device info, redacted
 * settings, recent crash reports and a snapshot of the app's own logcat.
 *
 * No backend exists, so diagnostics are written to app-external storage and
 * shared via a FileProvider-backed ACTION_SEND (the user picks email/cloud).
 */
object Diagnostics {

    private val TS = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun collect(context: Context, settings: AppSettings): String {
        val sb = StringBuilder()
        sb.appendLine("=== CueScore Mobile Streaming diagnostics ===")
        sb.appendLine("time: ${TS.format(Date())}")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("--- settings (addresses, keys and channel names removed) ---")
        sb.appendLine(redactedJson(settings))
        sb.appendLine()
        sb.appendLine("--- crash reports (${CrashReporter.listReports(context).size}) ---")
        CrashReporter.listReports(context).forEach { report ->
            sb.appendLine("* ${report.name}")
        }
        sb.appendLine()
        sb.appendLine("--- logcat (app process) ---")
        sb.appendLine(recentLogcat())
        return sb.toString()
    }

    fun export(context: Context, settings: AppSettings): Uri {
        val dir = File(context.getExternalFilesDir(null), "diagnostics").also { it.mkdirs() }
        val file = File(dir, "diagnostics_${TS.format(Date())}.txt")
        file.writeText(collect(context, settings))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Writes the prepared text to a file and returns a `Uri` for `ACTION_SEND`.
     *
     * Needed when the report is assembled outside this module: the device profile lives in
     * `streaming` (it needs access to cameras and codecs), while `core` does not
     * know about it and should not. The only thing they share is “write and share,”
     * so there is no need to duplicate `FileProvider` and its authority here for a third time.
     */
    fun exportText(context: Context, prefix: String, text: String): Uri {
        val dir = File(context.getExternalFilesDir(null), "diagnostics").also { it.mkdirs() }
        val file = File(dir, "${prefix}_${TS.format(Date())}.txt")
        file.writeText(text)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Session recording files (`SessionRecorder`), newest first. */
    fun listSessionLogs(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(null), "debug")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /** The most recent session recording, ready to send. `null` if there are no recordings. */
    fun exportLatestSessionLog(context: Context): Uri? {
        val file = listSessionLogs(context).firstOrNull() ?: return null
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Settings for export: only what affects BEHAVIOR.
     *
     * **Why “keep what is needed” rather than “remove secrets.”** The first version
     * hid one field, `passphrase`—the only one that was certain to always
     * be empty because it could not be set from the app. Everything
     * else was exported in the clear: the stream key (in RTMP, it is in the `baseUrl` path,
     * in SRTLA, in `streamId`), the private server address, Twitch channel names,
     * Kick, and VK—in other words, the streamer's name—and the donation overlay `url`, which is a token
     * for a DonationAlerts widget.
     *
     * A “what to remove” list needs maintenance and silently falls behind the model:
     * every new field is included in the export by default. A “what to
     * keep” list errs in the other direction—a new field is NOT included by default,
     * and the cost of a mistake is merely “something is missing from the report.”
     *
     * Other people read the report. To investigate a transport issue, they need the server
     * type and its numeric settings, not the address: if the address is needed,
     * it can be requested.
     */
    internal fun redactedJson(settings: AppSettings): String {
        // IMPORTANT: build a NEW object, not settings.copy(). copy() is a
        // blocklist: you list the bad fields, and everything else goes through as-is.
        // It already failed us on 14.09—`overlayUrl` slipped past the removal, an old
        // migration field containing the DonationAlerts widget token. Here,
        // each field is explicitly named, so a new model field is excluded from
        // the report, making the cost of a mistake "something is missing".
        // Reset obsolete profile audio: kotlinx does not write default values, and the field
        // with the headset name is not included in the report (audit 23.09).
        @Suppress("DEPRECATION")
        val profilesWithoutLegacyAudio = settings.streamProfiles.map { it.copy(audio = app.brix.core.AudioSettings()) }
        val safe = AppSettings(
            // Audio has been global since 16.09—AppSettings.audio—while StreamProfile.audio
            // retains an obsolete migration field (this comment previously
            // claimed the opposite). We take global audio without the device name:
            // it may contain the headset owner's name (“Dima's Buds”).
            audio = settings.audio.copy(micDeviceName = ""),
            cameraDefaults = settings.cameraDefaults,
            appearance = settings.appearance,
            advanced = settings.advanced,
            hud = settings.hud,
            quickButtons = settings.quickButtons,
            selectedStreamProfileId = settings.selectedStreamProfileId,
            selectedSceneId = settings.selectedSceneId,
            streamProfiles = profilesWithoutLegacyAudio,
            customPresets = settings.customPresets,
            // Keep what transport depends on: type, latency,
            // forced IPv4, and whether the profile is enabled. Not the address or key.
            serverProfiles = settings.serverProfiles.map {
                ServerProfile(
                    id = it.id,
                    name = "",
                    type = it.type,
                    baseUrl = "",
                    streamId = "",
                    latencyMs = it.latencyMs,
                    preferIpv4 = it.preferIpv4,
                    enabled = it.enabled,
                )
            },
            moblink = settings.moblink.copy(password = ""),
            chat = settings.chat.copy(
                twitchChannel = "",
                kickChannel = "",
                vkChannelUrl = "",
                vkClientId = "",
                vkClientSecret = "",
            ),
            // The donation alert's url contains a DonationAlerts widget token; the web widget contains
            // an arbitrary page belonging to the owner; the image contains a file path on their
            // phone; and the text contains what they wrote themselves. Type, geometry, and
            // audio are retained: they are what explain the per-frame load.
            widgets = settings.widgets.map { it.copy(url = "", imageUri = "", template = "", name = "") },
            // The user enters the scene name, and imageUri is a file path on their
            // phone, usually containing their username.
            scenes = settings.scenes.map { it.copy(name = "", imageUri = "") },
            // Fields overlay* marked @Deprecated are intentionally not listed:
            // they retain their default values and are not exported.
        )
        return runCatching { Json.encodeToString(safe) }.getOrDefault("<serialization failed>")
    }

    /** Our own log tags. `--pid` alone is NOT enough: the framework logs its own
     *  noise (HWUI, InsetsController, GraphicsEnvironment, InputTransport, …)
     *  *from inside our process*, and it drowned the app's signal — a real
     *  export was 482 KB of which almost none was ours. Whitelist our tags,
     *  keep errors from everything else, silence the rest. */
    private val APP_TAGS = listOf(
        "Srtla", "BrixStat", "BrixStream", "BrixLifecycle", "BrixPreview", "BrixTouch",
        "Overlay", "NativeOverlay", "DonationAudio", "Moblink", "StreamService",
        "SettingsStore", "SettingsViewModel", "Brix",
    )

    private fun recentLogcat(): String = runCatching {
        val command = buildList {
            add("logcat")
            add("-d")
            // Lines are dense now that the noise is gone, so the same budget
            // covers far more wall-clock time than it did unfiltered.
            add("-t"); add("6000")
            add("-v"); add("threadtime")
            add("-b"); add("main,crash")
            add("--pid"); add(android.os.Process.myPid().toString())
            APP_TAGS.forEach { add("$it:V") }
            // Keep hard failures from anywhere, then silence the rest.
            add("AndroidRuntime:E")
            add("*:E")
            add("*:S")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.also {
            // Bounded: a hung logcat must not wedge the export.
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroy()
        }
    }.getOrDefault("<logcat unavailable>")
}
