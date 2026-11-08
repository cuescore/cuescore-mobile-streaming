package app.brix.streaming

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import java.util.Locale

/** A rear lens the user can switch to via the lens bar. */
data class RearLens(
    /** Unique UI key (cameraId, or cameraId@zoom for virtual lenses). */
    val id: String,
    /** Camera2 device id to open. */
    val cameraId: String,
    val label: String,
    /** Zoom ratio applied after opening (virtual tele = main camera + crop). */
    val zoom: Float = 1f,
)

internal data class RearCameraInfo(
    val id: String,
    val focalLength: Float,
    val maxZoom: Float,
)

/**
 * Enumerate rear lenses with Moblin-style zoom labels (0.5x / 1x / 3x).
 *
 * Zoom factor is relative to the device's MAIN rear camera (focal in the
 * 4.5–8mm range). Only logical camera IDs that the streaming camera
 * implementation can open directly are offered; physical IDs exposed by a
 * logical camera are excluded. Phones whose "tele" is a crop-zoom sensor get a
 * virtual tele pill backed by the main camera at 3x zoom ratio.
 */
fun Context.rearLenses(): List<RearLens> {
    val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
    val characteristics = manager.cameraIdList.mapNotNull { id ->
        runCatching { manager.getCameraCharacteristics(id) }
            .getOrNull()
            ?.let { id to it }
    }
    val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        characteristics.flatMap { (_, chars) -> chars.physicalCameraIds }.toSet()
    } else {
        emptySet()
    }
    val cameras = characteristics.mapNotNull { (id, chars) ->
        if (id in physicalCameraIds) return@mapNotNull null
        if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.filter { it > 0f }
            ?.minOrNull() ?: return@mapNotNull null
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        RearCameraInfo(id, focal, zoomRange?.upper ?: 1f)
    }

    return buildRearLenses(cameras)
}

internal fun buildRearLenses(cameras: List<RearCameraInfo>): List<RearLens> {
    val usableCameras = cameras
        .filter { it.focalLength > 0f }
        .distinctBy { it.id }
    val mains = usableCameras.filter { it.focalLength in 4.5f..8f }
    val main = mains.maxByOrNull { it.focalLength }
        ?: usableCameras.maxByOrNull { it.focalLength }
        ?: return emptyList()

    val lenses = usableCameras
        .map { camera ->
            val ratio = camera.focalLength / main.focalLength
            RearLens(camera.id, camera.id, "%.1fx".format(Locale.US, ratio)) to ratio
        }
        .groupBy { (lens, _) -> lens.label }
        .values
        .map { sameLabel ->
            val targetRatio = sameLabel.first().first.label.removeSuffix("x").toFloat()
            sameLabel.minBy { (_, ratio) -> kotlin.math.abs(ratio - targetRatio) }.first
        }
        .sortedBy { it.label.removeSuffix("x").toFloat() }

    // No real tele? Offer a virtual one from the main camera's zoom range.
    val hasTele = usableCameras.any { it.focalLength > main.focalLength * 1.8f }
    val mainZoomMax = main.maxZoom
    return if (!hasTele && mainZoomMax >= 3f) {
        val virtual = RearLens("${main.id}@3.0", main.id, "3.0x", zoom = 3f)
        lenses + virtual
    } else {
        lenses
    }
}
