package app.brix.streaming

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device passport: what exactly THIS hardware can do.
 *
 * **Why.** In one day of development, we ran into the same question twice—one
 * that documentation can't answer; only the phone can:
 * 1. Can the hardware encoder hold a constant bitrate (CBR)? On the S21, neither
 *    HEVC nor H.264 can, and we learned this by switching codecs and reading the system log.
 * 2. What are the built-in microphone capsules called? On the S21 they're "bottom" and "back",
 *    but that's Samsung firmware terminology; we don't know what other devices report.
 * 3. Can the sensor produce our frame size NATIVELY? The camera with its ISP uses 53%
 *    of the live-stream power budget—more than everything else combined. If the sensor
 *    can't do 1280x720, every frame is scaled; if the aspect ratios differ (4:3 sensor,
 *    16:9 stream), it's also cropped. Cropping is free when the ISP does it in a built-in
 *    mode, and costs a watt when we do it in GL. It also narrows the field of view, which
 *    is noticeable in IRL streaming on its own. The S21 lenses use DIFFERENT sensors
 *    (wide and ultrawide), with different mode lists, so we query each one, not just the default.
 *
 * These answers keep the app honest: it won't offer choices the hardware doesn't support
 * or collapse two microphones into one entry. They can only be collected from real devices.
 *
 * **What we do NOT collect.** No stream keys, server addresses, coordinates, usernames,
 * or anything else that links the report to a person. Only hardware characteristics shared
 * by all owners of the same model. The report is generated on explicit request and shown
 * in full before sending—you must be able to see what will be sent.
 */
object DeviceReport {

    fun build(context: Context): JSONObject = JSONObject().apply {
        // 2 — added the cameras block. 3 — all video encoders and their limits.
        put("schema", 3)
        put("device", deviceInfo())
        put("encoders", encoders())
        put("cameras", cameras(context))
        put("audioInputs", audioInputs(context))
    }

    /** Human-readable representation, also shown before sending. */
    fun render(context: Context): String = build(context).toString(2)

    private fun deviceInfo() = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("soc", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "")
        put("androidSdk", Build.VERSION.SDK_INT)
    }

    /**
     * Video encoders and what matters to know about them in advance: whether
     * they're hardware-accelerated and whether they hold CBR. The latter directly
     * addresses the Sep 3 finding: bitrate overshot the target, and the cause was
     * visible only in the system log.
     */
    private fun encoders(): JSONArray {
        val out = JSONArray()
        // All video encoders, not just the ones we stream with: until Sep 17 this
        // was a list of avc/hevc/av01, so the S21 passport missed hardware VP8 and
        // VP9 encoders that are present on the phone (see /vendor/etc/media_codecs_c2.xml).
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                if (!info.isEncoder) return@forEach
                info.supportedTypes.filter { it.lowercase().startsWith("video/") }.forEach { mime ->
                    val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                    val enc = caps?.encoderCapabilities
                    out.put(
                        JSONObject().apply {
                            put("name", info.name)
                            put("mime", mime)
                            put(
                                "hardware",
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    info.isHardwareAccelerated
                                } else {
                                    !info.name.startsWith("OMX.google", true)
                                },
                            )
                            put("cbr", enc?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                            put("vbr", enc?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                            put("cq", enc?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ))
                            caps?.videoCapabilities?.let { v ->
                                put("maxSize", "${v.supportedWidths.upper}x${v.supportedHeights.upper}")
                                put("maxBitrateKbps", v.bitrateRange.upper / 1000)
                                put(
                                    "fps1080p",
                                    runCatching { v.getSupportedFrameRatesFor(1920, 1080).upper.toInt() }.getOrNull(),
                                )
                                put(
                                    "measuredFps1080p",
                                    runCatching {
                                        v.getAchievableFrameRatesFor(1920, 1080)?.let { "${it.lower.toInt()}-${it.upper.toInt()}" }
                                    }.getOrNull(),
                                )
                            }
                        },
                    )
                }
            }
        }
        return out
    }

    /** Frame sizes the app actually requests from the camera. */
    private val PROFILE_SIZES = listOf(Size(1280, 720), Size(1920, 1080))

    /**
     * Capture modes for each lens.
     *
     * A logical camera hides physical sensors behind it, and their mode lists
     * differ: on the S21, "wide" and "ultrawide" are two different sensors.
     * Querying only `cameraIdList` isn't enough; otherwise switching lenses silently
     * changes the thermal profile, and the report won't show it.
     */
    private fun cameras(context: Context): JSONArray {
        val out = JSONArray()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return out
        val ids = linkedSetOf<String>()
        runCatching {
            manager.cameraIdList.forEach { id ->
                ids += id
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { manager.getCameraCharacteristics(id).physicalCameraIds }
                        .getOrNull()?.let { ids += it }
                }
            }
        }
        ids.forEach { id -> runCatching { out.put(camera(manager, id)) } }
        return out
    }

    private fun camera(manager: CameraManager, id: String): JSONObject {
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // SurfaceTexture is exactly the class RootEncoder uses for both preview and encoder input,
        // so we need its output mode list.
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        return JSONObject().apply {
            put("id", id)
            put("facing", facingName(chars.get(CameraCharacteristics.LENS_FACING)))
            put(
                "focalMm",
                (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f).toDouble(),
            )
            if (active != null) {
                put("sensorActive", "${active.width()}x${active.height()}")
                put("sensorAspect", aspect(active.width(), active.height()))
            }
            put("outputSizes", JSONArray(sizes.map { describeSize(it, map) }))
            put(
                "profileFit",
                JSONObject().apply {
                    PROFILE_SIZES.forEach { want ->
                        put("${want.width}x${want.height}", fit(want, sizes, active))
                    }
                },
            )
        }
    }

    /** "1920x1080 16:9 60fps" — the frame-rate ceiling is calculated from the minimum frame duration. */
    private fun describeSize(
        size: Size,
        map: android.hardware.camera2.params.StreamConfigurationMap?,
    ): String {
        val ns = runCatching {
            map?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: 0L
        }.getOrDefault(0L)
        val fps = if (ns > 0) (1_000_000_000.0 / ns).toInt() else 0
        val head = "${size.width}x${size.height} ${aspect(size.width, size.height)}"
        return if (fps > 0) "$head ${fps}fps" else head
    }

    /**
     * Verdict for one of our frame sizes: can the sensor provide it for free?
     *
     * People read the report, so use phrases rather than flags: "native," "scaled
     * from 1920x1080," "+ cropped from 4:3 sensor." Cropping is always reported,
     * even at native size: the ISP does it, but it narrows the field of view either way.
     */
    private fun fit(want: Size, sizes: List<Size>, active: android.graphics.Rect?): String {
        val wantAspect = aspect(want.width, want.height)
        val sensorAspect = active?.let { aspect(it.width(), it.height()) }
        val crop = if (sensorAspect != null && sensorAspect != wantAspect) {
            " + обрезка с $sensorAspect сенсора"
        } else {
            ""
        }
        if (sizes.any { it.width == want.width && it.height == want.height }) return "нативно$crop"
        val src = sizes
            .filter { aspect(it.width, it.height) == wantAspect && it.width >= want.width }
            .minByOrNull { it.width.toLong() * it.height }
        return if (src != null) {
            "масштабирование с ${src.width}x${src.height}$crop"
        } else {
            "нет ни одного режима $wantAspect — масштабирование и обрезка силами GPU"
        }
    }

    private fun aspect(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "?"
        var a = width
        var b = height
        while (b != 0) {
            val t = a % b
            a = b
            b = t
        }
        return "${width / a}:${height / a}"
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown"
    }

    /**
     * Audio inputs with addresses. The address is the word ("bottom," "back")
     * we use to distinguish capsules, and our vocabulary for these values is
     * currently based on exactly one phone.
     */
    private fun audioInputs(context: Context): JSONArray {
        val out = JSONArray()
        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d ->
                out.put(
                    JSONObject().apply {
                        put("type", d.type)
                        put("builtin", d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
                        // A built-in microphone address describes the hardware, not
                        // its owner. A Bluetooth headset address is its MAC address,
                        // which identifies a person's specific device, so it isn't
                        // included.
                        put("address", if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) d.address else "")
                        put("channels", JSONArray(d.channelCounts.toList()))
                    },
                )
            }
        }
        return out
    }
}
