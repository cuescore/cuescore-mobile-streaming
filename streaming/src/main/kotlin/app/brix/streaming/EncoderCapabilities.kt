package app.brix.streaming

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import app.brix.core.Codec

/**
  * Whether the hardware maintains a constant bitrate (CBR) for the selected codec.
 *
  * **Why make this visible to the user.** RootEncoder always requests CBR, checks
  * whether it is supported, and silently falls back to the default mode (VBR)
  * if not, reporting this in a single line in the system log:
  * `bitrate mode CBR not supported using default mode`. From the outside it looks
  * like this: the streamer sets 4000 kbit/s, but 5.5 goes to the link — and there is no way
  * to tell why. Measurement from 03.09 on an S21 (Exynos 2100): neither of its two
  * hardware encoders maintains CBR, neither HEVC nor H.264.
 *
  * **The mode cannot be selected, and that is not our omission:** RootEncoder’s `VideoEncoder`
  * has no mode setter; it is hardcoded. So this is request-only, with no toggle —
  * promising a choice that does not exist would be worse than silence.
 *
  * Support depends on the CODEC: another device may not support HEVC but may support
  * H.264, so check on every codec change rather than just once.
 */
object EncoderCapabilities {

    fun mimeFor(codec: Codec): String =
        if (codec == Codec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /**
          * The best encoding profile this phone’s hardware actually supports.
     *
          * **Why.** RootEncoder accepts the profile as the seventh `prepareVideo` parameter;
          * its default is `-1`, and internally it checks `if (profile > 0)` — meaning we
          * were not setting it at all, and the encoder used its own, usually simplest profile. The difference
          * is not cosmetic: Baseline encodes entropy using CAVLC, while Main and High use
          * CABAC and 8×8 transforms, saving about 10–15% bitrate at the same
          * quality. Since we target a fixed bitrate, the gain goes to the
          * image. Twitch mobile uses High or Main for the same reason.
     *
          * **Why query the hardware instead of using a constant.** A profile the
          * encoder does not support causes `prepare` to fail entirely — and we already know from our own
          * example that what the documentation claims and what Exynos supports are
          * different sets (the CBR story just above). So we query
          * `MediaCodecInfo` and choose the best one it confirms.
     *
          * We deliberately leave `level` alone: the library checks it separately with
          * `if (level > 0)`, and without it the encoder chooses the level for the
          * resolution and bitrate itself. Guessing manually is another way to fail to initialize.
     *
          * @return profile for `MediaFormat`, or -1 if the query fails —
          *   then everything remains as before, at the encoder’s discretion.
     */
    fun bestProfile(codec: Codec): Int = runCatching {
        val mime = mimeFor(codec)
        val supported = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .flatMap { it.getCapabilitiesForType(mime).profileLevels.map { pl -> pl.profile } }
            .toSet()
        // Preference order — from best compression to worst.
        val wanted = if (codec == Codec.HEVC) {
            listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        } else {
            listOf(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
            )
        }
        val chosen = wanted.firstOrNull { it in supported } ?: -1
        // Log both the chosen profile and everything the hardware confirmed: if the
        // image looks worse than expected on someone else’s
        // phone, the first question will be “which profile was actually applied?”, and the answer should be in the log,
        // rather than discovered through a back-and-forth.
        android.util.Log.i(
            "BrixStream",
            "профиль $codec: выбран $chosen, железо поддерживает ${supported.sorted()}",
        )
        chosen
    }.getOrDefault(-1)

    /**
          * Name of the hardware encoder the system will provide for this codec.
     *
          * For the session log: the setting says what we wanted, while this says what we actually
          * got. On an S21 there are multiple hardware encoders for one codec
          * (`c2.exynos.*` and older `OMX.Exynos.*`), and the selected one is not apparent from
          * the settings.
     */
    fun hardwareEncoderName(codec: Codec): String = runCatching {
        val mime = mimeFor(codec)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    (
                        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                            info.isHardwareAccelerated
                        )
            }?.name ?: "?"
    }.getOrDefault("?")

        /** @return true if at least one encoder for this codec supports CBR. */
    fun isCbrSupported(codec: Codec): Boolean = runCatching {
        val mime = mimeFor(codec)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                info.getCapabilitiesForType(mime)
                    .encoderCapabilities
                    ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        }
    }.getOrDefault(false)
}
