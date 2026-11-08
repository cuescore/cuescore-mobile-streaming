package app.brix.streaming

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.brix.core.MicSource

/**
 * Which microphones are currently connected and how to select one.
 *
 * **Why we store the type, not the device.** `AudioDeviceInfo` has an `id`, but
 * it lasts only until reconnection: unplug and reconnect a headset, and its id
 * changes, leaving the saved setting pointing nowhere. The type (built-in, wired,
 * Bluetooth, USB) survives both that and a phone reboot.
 *
 * **The list is recalculated every time.** Headsets are plugged in and unplugged
 * in the middle of a live stream, so there is nothing to cache: the device list
 * can change between two button presses.
 */
object MicDevices {

    /** Types shown as a single item. Everything else uses [MicSource.AUTO]. */
    private fun sourceOf(device: AudioDeviceInfo): MicSource? = when (device.type) {
        // Built-in microphones appear as DIFFERENT devices of the same type and
        // are distinguished by address: on the S21, these are “bottom” and “back” (measured 03.09).
        // On phones that do not distinguish them, the address is empty, leaving
        // a single “Built-in” item.
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> builtinOf(device.address)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicSource.WIRED
        // Bluetooth is detected, but we deliberately do not bring up the SCO link
        // (decision on 20.09, based on analysis, not measurement): while the headset
        // plays as headphones (A2DP), audio sounds good, but recording from its mic
        // switches it to headset mode — a narrow bidirectional link that degrades
        // both the microphone (8–16 kHz mono) and the audio heard in the headphones.
        // This is a bad trade-off for live streaming. Do not “fix” it as an omission.
        // LE Audio (TYPE_BLE_HEADSET, API 31) is a SEPARATE question that we have
        // neither discussed nor tested: LC3 works in both directions without the narrow
        // mode, so the argument against SCO does not apply. See TODO.
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicSource.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> MicSource.USB
        else -> null
    }

    /**
     * Microphone orientation, based on the address string from HAL.
     *
     * **This is the least portable part of the solution, and its limitations matter.**
     * These are the values reported by the firmware we know: on the S21, these are
     * “bottom” and “back” (measured 03.09). Another manufacturer can use
     * any value or provide none. If the address is unrecognized, the microphone
     * is simply treated as “built-in”: there is one list item, and choosing between
     * the two is impossible — but nothing is misrepresented, and audio still works.
     *
     * Unrecognized addresses are logged (see [logInputs]) so the list of known values
     * can be expanded based on observations from other devices, not guesses.
     */
    private fun builtinOf(address: String): MicSource = when {
        address.isBlank() -> MicSource.BUILTIN
        listOf("bottom", "primary", "main").any { address.contains(it, true) } -> MicSource.BUILTIN_BOTTOM
        listOf("back", "rear", "camera").any { address.contains(it, true) } -> MicSource.BUILTIN_BACK
        listOf("top", "front", "secondary").any { address.contains(it, true) } -> MicSource.BUILTIN_TOP
        else -> MicSource.BUILTIN
    }

    private fun inputs(context: Context): List<AudioDeviceInfo> = runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }.getOrDefault(emptyList())

    /**
     * Log what the phone reports about its inputs, one line per device.
     *
     * This is needed because audio policy distinguishes more microphones than are
     * usually visible to the app API: on the S21, `dumpsys media.audio_policy` lists
     * three built-in ports (`@:bottom`, `@:back`, `@:both`); whether `getDevices()`
     * returns them separately can only be determined by measuring on the device.
     */
    fun logInputs(context: Context) {
        inputs(context).forEach { d ->
            android.util.Log.i(
                "BrixMic",
                "вход: type=${d.type} address='${d.address}' name='${d.productName}' " +
                    "channels=${d.channelCounts.joinToString(",")} id=${d.id}" +
                    // Portability marker: if the built-in microphone is not
                    // recognized by address, the phone cannot distinguish between
                    // microphones, and the value should be added to builtinOf.
                    if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                        builtinOf(d.address) == MicSource.BUILTIN &&
                        d.address.isNotBlank()
                    ) {
                        " [адрес не опознан]"
                    } else {
                        ""
                    },
            )
        }
        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.microphones.forEach { m ->
                android.util.Log.i(
                    "BrixMic",
                    "капсюль: id=${m.id} address='${m.address}' location=${m.location} " +
                        "directionality=${m.directionality} pos=${m.position}",
                )
            }
        }.onFailure { android.util.Log.w("BrixMic", "getMicrophones недоступен: ${it.message}") }
    }

    /**
     * What can be selected right now: always [MicSource.AUTO], plus the types of
     * connected devices. Showing unavailable options is just as misleading as a
     * toggle with no implementation behind it.
     */
    fun available(context: Context): List<MicSource> {
        val found = inputs(context).mapNotNull { sourceOf(it) }.distinct()
        return listOf(MicSource.AUTO) + found
    }

    /**
     * The device matching the selected type, or null if the type is AUTO or the device
     * was disconnected. In either case, the caller should leave the choice to the system
     * rather than end up with no audio.
     */
    fun deviceFor(context: Context, source: MicSource, preferredName: String = ""): AudioDeviceInfo? {
        if (source == MicSource.AUTO) return null
        val ofType = devicesFor(context, source)
        // Use the named device if it is currently available. Otherwise, use any device of
        // that type: the headset may have been left behind, and a silent live stream is
        // worse than audio from another microphone.
        return ofType.firstOrNull { it.productName?.toString() == preferredName && preferredName.isNotEmpty() }
            ?: ofType.firstOrNull()
    }

    /**
     * All connected devices of a given type, in the order returned by the system.
     *
     * Needed when there is only one type: the type cannot distinguish two Bluetooth
     * headsets, so choosing between them means choosing by name.
     */
    fun devicesFor(context: Context, source: MicSource): List<AudioDeviceInfo> =
        inputs(context).filter { sourceOf(it) == source }

    /** Device names for this type — what the user sees in the list. */
    fun deviceNamesFor(context: Context, source: MicSource): List<String> =
        devicesFor(context, source).map { it.productName?.toString().orEmpty() }.filter { it.isNotBlank() }

    /** Convert our mode to a MediaRecorder.AudioSource constant. */
    fun audioSourceOf(processing: app.brix.core.AudioProcessing): Int = when (processing) {
        app.brix.core.AudioProcessing.CAMCORDER -> android.media.MediaRecorder.AudioSource.CAMCORDER
        app.brix.core.AudioProcessing.MIC -> android.media.MediaRecorder.AudioSource.MIC
        app.brix.core.AudioProcessing.UNPROCESSED -> android.media.MediaRecorder.AudioSource.UNPROCESSED
        app.brix.core.AudioProcessing.VOICE_COMMUNICATION ->
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }
}
