package app.brix.streaming

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import app.brix.core.CameraDefaults
import app.brix.core.CameraSide
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.StreamBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Camera and local preview: viewfinder surface, camera and lens switching,
 * zoom, torch, tap-to-focus, and applying user defaults.
 *
 * Extracted from [SrtlaStreamer]. Besides cleanup, this closes an old trap:
 * preview starts in THREE places (external [startPreview], encoder recreation,
 * and restart when streaming begins), and each had to remember to call
 * [applyPostPreviewCameraSetup]. That used to be left to a comment — one path
 * already lost camera settings. Now both internal paths go through
 * [resumePreviewIfDetached], so there's nowhere to forget.
 *
 * Works with `StreamBase`, not a specific transport: [SrtlaStream], `RtmpStream`,
 * and `WhipStream` all inherit it, and everything the camera needs (GL
 * interface, preview, video source) is declared there. The type was expanded
 * for this — until 03.09 the controller knew only about SRTLA, so each camera
 * fix addressed one of three paths, while RTMP and WHIP went years with a
 * freezing tap-to-focus and no camera defaults at all.
 */
internal class CameraController(
    private val appContext: Context,
    private val stream: StreamBase,
    private val scope: CoroutineScope,
    /** Current profile frame rate — passed to the system as a hint about the
     *  actual preview rate; see [applyPreviewFrameRateHint]. */
    private val profileFps: () -> Int,
    private val isReleased: () -> Boolean,
    private val updateState: ((StreamState) -> StreamState) -> Unit,
) {
    private val tag = "BrixStream"

    private var defaults = CameraDefaults()

    // Apply the default camera only on the first open: applying it on every
    // startPreview() (for example when preview returns after reconnecting) would
    // conflict with a manual camera change made by the user mid-session.
    @Volatile
    private var defaultCameraApplied = false

    // Camera HAL operations (switching cameras, zooming, tap-to-focus) must not
    // run on the UI thread (a blocking HAL call causes jank and ANRs), but must
    // also run strictly one at a time: dispatching them to the Dispatchers.IO
    // pool let two calls collide on the capture session and hang the camera
    // ("No request / wait timeout").
    private val cameraDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Serialization above prevents concurrent calls, but a queue of them (a
    // quick zoom pinch, several lens taps) would still run each to completion,
    // causing real close/reopen cycles and HAL calls. Canceling the previous
    // task if it hasn't started yet leaves only the last one in a burst.
    private var cameraJob: Job? = null

    private var lastPreviewSurface: Surface? = null
    private var lastPreviewWidth = 0
    private var lastPreviewHeight = 0

    fun configure(newDefaults: CameraDefaults) {
        // The streamer instance survives Stop->Start (see StreamController.getOrCreate),
        // so the one-shot defaultCameraApplied latch would silently swallow an
        // actual settings change between sessions — the same kind of "setting
        // doesn't apply until app restart" bug, just in another field. Reset it
        // only when the DEFAULT actually changes, not on every call, or a manual
        // camera change mid-session would be undone.
        if (newDefaults.defaultCamera != defaults.defaultCamera) {
            defaultCameraApplied = false
        }
        defaults = newDefaults
        // If the camera and preview are already running (the usual case: settings
        // are changed mid-session, not before preview first starts), apply them
        // immediately. The old code applied this only from a one-shot block in
        // startPreview(), so a change made during preview did nothing until the
        // app was fully restarted; this method exists precisely to fix that.
        camera2()?.let { src ->
            applyDefaults(src)
            applyDefaultLensOnce(src)
        }
    }

    /** Preview stabilization and mirroring are cheap capture-request changes,
     *  about as lightweight as the nearby enableAutoFocus call, so they can run
     *  directly on the calling thread. */
    private fun applyDefaults(src: Camera2Source) {
        if (defaults.stabilization) src.enableVideoStabilization() else src.disableVideoStabilization()
        // Optical stabilization is a separate mechanism, and until 04.09 we
        // didn't touch it at all: the library exposes it separately, while we
        // only toggled electronic stabilization. Not all cameras support it, so
        // silently ignore failures.
        runCatching {
            if (defaults.opticalStabilization) {
                src.enableOpticalVideoStabilization()
            } else {
                src.disableOpticalVideoStabilization()
            }
        }
        applyMirror(src)
    }

    /** Switch to the camera specified by the default, once. This fully closes
     *  and reopens the HAL — only on [cameraDispatcher], never inline (the whole
     *  tap-to-focus saga in this file began because calling the HAL from the
     *  wrong thread freezes the preview). */
    private fun applyDefaultLensOnce(src: Camera2Source) {
        if (defaultCameraApplied) return
        defaultCameraApplied = true
        val wantFront = defaults.defaultCamera == CameraSide.FRONT
        val isFront = src.getCameraFacing() == CameraHelper.Facing.FRONT
        if (wantFront == isFront) return
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            try {
                src.switchCamera()
                applyMirror(src)
            } catch (e: Exception) {
                Log.e(tag, "applyDefaultLensOnce failed", e)
            }
        }
    }

    /** Mirrors only the on-device preview (not the outgoing stream) when the
     *  front camera is active and the corresponding default is enabled — as in
     *  most camera apps with a "selfie" preview. */
    private fun applyMirror(src: Camera2Source) {
        val isFront = src.getCameraFacing() == CameraHelper.Facing.FRONT
        runCatching {
            val gl = stream.getGlInterface()
            gl.setIsPreviewHorizontalFlip(defaults.mirrorFront && isFront)
            // The stream is independent of the preview: streamers are used to
            // seeing themselves mirrored, while viewers see them as they are.
            // Mirroring the stream reverses all text in the frame, so it's off by default.
            gl.setIsStreamHorizontalFlip(defaults.mirrorFrontInStream && isFront)
        }
    }

    /** The dimensions preview actually started with: they reveal whether the
     *  window has since rotated and the viewport no longer matches the surface. */
    private var lastStartedWidth = 0
    private var lastStartedHeight = 0

    fun startPreview(surfaceView: SurfaceView) {
        try {
            // Buffer size, not view size. The UI calls holder.setFixedSize(), so
            // the surface is smaller than the view and SurfaceFlinger scales it.
            // These values go to glViewport (ScreenRender.drawPreview), so using
            // the view size set a larger output window than the surface itself:
            // before 01.09 this got 2400×1080 for a 1920×1080 buffer.
            val frame = surfaceView.holder.surfaceFrame
            val pw = if (frame.width() > 0) frame.width() else surfaceView.width
            val ph = if (frame.height() > 0) frame.height() else surfaceView.height
            if (surfaceView.holder.surface.isValid) {
                lastPreviewSurface = surfaceView.holder.surface
                lastPreviewWidth = pw
                lastPreviewHeight = ph
            }
            // Preview started with a DIFFERENT surface size must be restarted.
            // The size is passed to glViewport once, at startup: if the window
            // rotates afterward (portrait splash at launch — 15.09), the picture
            // remains a narrow vertical strip in the middle of the landscape
            // screen. Don't do this while streaming — touch nothing in a live stream.
            val sizeChanged = pw != lastStartedWidth || ph != lastStartedHeight
            if (stream.isOnPreview && sizeChanged && !stream.isStreaming &&
                surfaceView.holder.surface.isValid
            ) {
                runCatching { stream.stopPreview() }
            }
            if (!stream.isOnPreview && surfaceView.holder.surface.isValid) {
                dropDeadScreenSource()
                stream.startPreview(surfaceView.holder.surface, pw, ph)
                lastStartedWidth = pw
                lastStartedHeight = ph
                applyPostPreviewCameraSetup()
            }
        } catch (e: Exception) {
            // Previously this failure was silently swallowed: a camera or
            // encoder failure left a black screen with no diagnostics at all.
            // At least log it; a typed StreamError is separate work.
            Log.e(tag, "startPreview failed", e)
        }
    }

    /**
     * Replace a stopped screen-capture source with the camera before preview starts.
     *
     * The library's `startPreview` starts the source if it's not running
     * (verified in `StreamBase` 2.8.0 bytecode), while `stopPreview` stops it
     * only when neither streaming nor recording. So after minimizing and
     * restoring without a live stream, the screen source is stopped, and preview
     * will try to start it again. That's forbidden for screen capture: the token
     * is single-use, and a second `createVirtualDisplay` throws SecurityException
     * — and the system stops capture too (reproduced on-device 05.09).
     *
     * So replace the dead screen source with the camera in advance. The UI asks
     * for consent to a new capture when the scene is selected again.
     */
    private fun dropDeadScreenSource() {
        val source = stream.videoSource
        if (source !is com.pedro.encoder.input.sources.video.ScreenSource) return
        if (runCatching { source.isRunning() }.getOrDefault(true)) return
        Log.i(tag, "захват экрана остановлен — возвращаю камеру, токен одноразовый")
        runCatching { stream.changeVideoSource(Camera2Source(appContext)) }
            .onFailure { Log.e(tag, "камера не вернулась после захвата: ${it.message}") }
    }

    fun stopPreview() {
        try {
            if (stream.isOnPreview) stream.stopPreview()
        } catch (e: Exception) {
            Log.e(tag, "stopPreview failed", e)
        }
    }

    /**
     * Restore preview if the surface exists but display has stopped.
     *
     * Two internal paths lead here: encoder recreation in `prepare()` (preview
     * was stopped for it) and stream startup — RootEncoder also stops camera
     * capture in `stopStream()`, so after Stop→Start the picture freezes on the
     * last frame and the user thinks the camera has died.
     */
    fun resumePreviewIfDetached() {
        val surface = lastPreviewSurface ?: return
        if (!surface.isValid || stream.isOnPreview) return
        runCatching {
            stream.startPreview(surface, lastPreviewWidth, lastPreviewHeight)
            applyPostPreviewCameraSetup()
        }.onFailure { Log.e(tag, "preview restore failed: ${it.message}") }
    }

    /** Continuous autofocus (CONTROL_AF_MODE_CONTINUOUS_VIDEO with fallbacks),
     *  plus stabilization, mirroring, and the default camera — immediately
     *  after ANY path that actually starts preview in RootEncoder, not only
     *  external [startPreview]. */
    private fun applyPostPreviewCameraSetup() {
        camera2()?.enableAutoFocus()
        camera2()?.let { src ->
            applyDefaults(src)
            applyDefaultLensOnce(src)
        }
        applyPreviewFrameRateHint()
    }

    /** Tell the system the actual preview frame rate.
     *
     *  Field measurement on 01.09, `SurfaceFlinger --timestats` for the preview
     *  layer, 752 frames in 25 s: `averageFPS=30.4`, `droppedFrames=0`,
     *  `jankyFrames=0`, but present2present was 16ms=136 / 33ms=482 / 50ms=118 /
     *  66ms=5. In other words, frames aren't dropped, but displayed unevenly: a
     *  third lasts one refresh instead of two, or three instead of two. The
     *  instantaneous rate at those points is 20 and 15 fps, visible at a 30 fps
     *  profile. At 60 fps it's unnoticeable because there is a frame on every
     *  screen refresh.
     *
     *  Cause: RootEncoder's GL thread swaps the buffer whenever a camera frame
     *  arrives, while the camera runs freely at 30.0 Hz against the screen's
     *  60 Hz, so their clocks drift. Yet the layer votes for 60 (`SetFrameRate
     *  vote: frameRate = 60.00` in the same dump), so SurfaceFlinger schedules
     *  output at the wrong rate. A FIXED_SOURCE hint lets it keep a stable
     *  cadence for the actual 30 fps source. */
    private fun applyPreviewFrameRateHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = lastPreviewSurface ?: return
        if (!surface.isValid) return
        val fps = profileFps().toFloat()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }.onFailure { Log.w(tag, "setFrameRate($fps) не принят: ${it.message}") }
    }

    /**
     * Show a still image in the frame instead of the camera.
     *
     * Use case: "I'll be right back" without interrupting the live stream.
     * Interruptions are costly — the receiver treats each interrupted session
     * as a failed authorization and bans the address for about a minute (see
     * StartRestartGuard), so switching to a still image is much cheaper than
     * stopping and starting again.
     *
     * The camera is released in the meantime: keeping it open just for an image
     * costs 0.29 CPU cores and a noticeable fraction of a watt in 02.09 measurements.
     *
     * @return false if the image could not be read; the caller must stay on the
     *  camera rather than show viewers a black frame.
     */
    fun showStillImage(uri: android.net.Uri): Boolean = runCatching {
        val bitmap = decodeScaled(uri) ?: return false
        stream.changeVideoSource(com.pedro.encoder.input.sources.video.BitmapSource(bitmap))
        // Release the previous bitmap only AFTER the source accepts the new one:
        // on failure, the old image remains in the frame, and a recycled bitmap
        // would show a black screen instead of the still image.
        swapStillBitmap(bitmap)
        true
    }.getOrElse {
        Log.e(tag, "заставка не показана: ${it.message}")
        false
    }

    /**
     * The still image is currently in the frame. Keep a reference so it can be
     * freed: `BitmapSource` doesn't release it, and a gallery image is a photo
     * from the main camera, meaning tens of megabytes for every scene switch.
     * Without this, a few back-and-forth switches would exhaust memory.
     */
    private var stillBitmap: android.graphics.Bitmap? = null

    private fun swapStillBitmap(next: android.graphics.Bitmap?) {
        val previous = stillBitmap
        stillBitmap = next
        if (previous !== next) previous?.recycle()
    }

    /**
     * Read the image already scaled down to the frame size.
     *
     * A 12 MP photo takes 48 MB in memory at four bytes per pixel, while at most
     * 1080p goes to the stream — six times smaller. Reading the full size only
     * to shrink it immediately wastes memory. [MAX_SIDE] leaves headroom over
     * 1080p: `inSampleSize` only scales by powers of two, and the library does
     * the exact fit afterward.
     */
    private fun decodeScaled(uri: android.net.Uri): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(tag, "заставка не прочитана: не картинка либо файл недоступен")
            return null
        }
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return appContext.contentResolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_SIDE || height / (sample * 2) >= MAX_SIDE) sample *= 2
        return sample
    }

    /**
     * Show the phone screen instead of the camera.
     *
     * [projection] is provided by the system only after user consent, and cannot
     * be saved between launches — Android doesn't allow it. So consent is
     * requested again every time the app starts.
     *
     * @return false if the source could not be created; the caller must restore
     *  the camera rather than leave viewers without a picture.
     */
    fun showScreen(projection: android.media.projection.MediaProjection): Boolean = runCatching {
        stream.changeVideoSource(
            com.pedro.encoder.input.sources.video.ScreenSource(appContext, projection, null, null),
        )
        swapStillBitmap(null)
        true
    }.getOrElse {
        Log.e(tag, "экран не захвачен: ${it.message}")
        false
    }

    /** Restore the camera after a still image or screen capture. */
    fun showCamera() {
        if (camera2() != null) return
        runCatching { stream.changeVideoSource(Camera2Source(appContext)) }
            .onSuccess {
                swapStillBitmap(null)
                applyPostPreviewCameraSetup()
            }
            .onFailure { Log.e(tag, "камера не вернулась: ${it.message}") }
    }

    /**
     * Move to a specific side, rather than "switch".
     *
     * That's what a scene needs: it says "front", not "the other one". Using
     * switchCamera() would require the caller to first check the current side,
     * and two quick scene switches would cancel each other out.
     */
    fun setCameraSide(side: CameraSide) {
        val src = camera2() ?: return
        val isFront = src.getCameraFacing() == CameraHelper.Facing.FRONT
        if ((side == CameraSide.FRONT) == isFront) return
        switchCamera()
    }

    fun switchCamera() {
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            Log.d(tag, "switchCamera: calling Camera2Source.switchCamera()")
            try {
                val src = camera2()
                src?.switchCamera()
                src?.let { applyMirror(it) }
                Log.d(tag, "switchCamera: returned")
            } catch (e: Exception) {
                Log.e(tag, "switchCamera failed", e)
                updateState { it.copy(status = StreamStatus.Failed, message = e.message) }
            }
        }
    }

    /** Camera and GL operations block for hundreds of milliseconds and must not
     *  run on the main thread; also guard against calls after release — a late
     *  callback from the UI would crash RootEncoder. */
    fun setTorch(enabled: Boolean) {
        if (isReleased()) return
        scope.launch {
            if (setTorchEnabled(enabled)) {
                updateState { it.copy(torchOn = enabled) }
            }
        }
    }

    /** Use the camera torch, or control it directly through [CameraManager] if
     *  the camera doesn't provide one. The fallback matters: on some devices,
     *  `Camera2Source` rejects the request while the capture session is being
     *  rebuilt, and without it the torch button would simply do nothing.
     *
     *  @return false if there is no torch or the camera rejected the request. */
    private fun setTorchEnabled(enabled: Boolean): Boolean {
        camera2()?.let { source ->
            try {
                if (enabled) source.enableLantern() else source.disableLantern()
                return true
            } catch (_: Exception) {
            }
        }
        return try {
            val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraMetadata.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return false
            cm.setTorchMode(camId, enabled)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Turn on the torch when streaming starts, if configured. The camera owns
     *  the meaning of "configured", so that decision is made here, not in the
     *  streamer. */
    fun applyTorchOnStart() {
        if (defaults.torchOnStart) setTorch(true)
    }

    /**
     * Select a specific rear lens (wide, ultra-wide, tele), switching away
     * from the front camera first if needed — choosing a rear lens in the pill
     * IS the request to use that lens, just as in Moblin.
     *
     * The camera-side check used to run synchronously in the caller and return
     * `false`, so the UI's "flip, then select lens" path never applied the lens:
     * switchCamera() is asynchronous, so the camera was still front-facing and
     * setLens returned before it could switch. Now the check runs inside the
     * camera task, after switching, on the same serialized dispatcher as all
     * other HAL calls.
     */
    fun setLens(cameraId: String, zoom: Float): Boolean {
        if (isReleased()) return false
        val src = camera2() ?: return false
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            Log.d(tag, "setLens: calling openCameraId($cameraId)")
            try {
                if (src.getCameraFacing() != CameraHelper.Facing.BACK) {
                    Log.d(tag, "setLens: on front camera — flipping to back first")
                    src.switchCamera()
                    applyMirror(src)
                }
                src.openCameraId(cameraId)
                Log.d(tag, "setLens: openCameraId returned")
                if (zoom > 1f) {
                    Log.d(tag, "setLens: calling setZoom($zoom)")
                    src.setZoom(zoom)
                    Log.d(tag, "setLens: setZoom returned")
                }
            } catch (e: Exception) {
                Log.e(tag, "setLens failed (cameraId=$cameraId, zoom=$zoom)", e)
            }
        }
        return true
    }

    fun currentLensId(): String? {
        val src = camera2() ?: return null
        if (src.getCameraFacing() != CameraHelper.Facing.BACK) return null
        return runCatching { src.getCurrentCameraId() }.getOrNull()
    }

    fun zoomByScale(scale: Float) {
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            runCatching {
                val src = camera2() ?: return@runCatching
                val range = src.getZoomRange() ?: return@runCatching
                val target = (src.getZoom() * scale).coerceIn(range.lower, range.upper)
                Log.d(tag, "zoomByScale: calling setZoom($target)")
                src.setZoom(target)
                Log.d(tag, "zoomByScale: setZoom returned")
            }.onFailure { e -> Log.e(tag, "zoomByScale failed (scale=$scale)", e) }
        }
    }

    fun getZoomRange(): ClosedFloatingPointRange<Float>? =
        camera2()?.getZoomRange()?.let { it.lower..it.upper }

    /**
     * The library's `Camera2ApiManager.tapToFocus()` method (used here before)
     * performs tap-to-focus with `session.stopRepeating()` and two one-shot
     * `session.capture()` calls, then restores repeating preview ONLY from the
     * asynchronous onCaptureCompleted callback tied to the request tagged
     * "focus". On this device that callback (and apparently the captures
     * themselves — confirmed live via `dumpsys media.camera`: the
     * android.sensor.timestamp stops advancing immediately, while the camera
     * session remains "open") consistently never arrives, so the repeating
     * request is never armed again and preview freezes solid on every tap,
     * without throwing an exception. Rewritten following Android's own docs:
     * update the region and autofocus trigger on the ALREADY RUNNING repeating
     * request via setRepeatingRequest — without stopRepeating or a one-shot
     * capture — so preview never stalls, even momentarily, regardless of
     * whether the HAL reports completion.
     */
    fun tapFocus(view: View, x: Float, y: Float) {
        val clampedX = x.coerceIn(0f, view.width.toFloat())
        val clampedY = y.coerceIn(0f, view.height.toFloat())
        cameraJob?.cancel()
        cameraJob = scope.launch(cameraDispatcher) {
            val src = camera2() ?: return@launch
            try {
                val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = cameraManager.getCameraCharacteristics(src.getCurrentCameraId())
                val sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@launch
                val focusX = (clampedX / view.width.toFloat()) * sensorArraySize.width()
                val focusY = (clampedY / view.height.toFloat()) * sensorArraySize.height()
                val focusRect = MeteringRectangle(
                    (focusX - 100).toInt().coerceIn(0, sensorArraySize.width()),
                    (focusY - 100).toInt().coerceIn(0, sensorArraySize.height()),
                    100 * 2,
                    100 * 2,
                    MeteringRectangle.METERING_WEIGHT_MAX,
                )
                Log.d(tag, "tapFocus: setting AF region+trigger START on repeating request ($clampedX, $clampedY)")
                src.setCustomRequest { builder ->
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                // Give the HAL a couple of frames with the trigger active before
                // releasing it — the same delay used in Android's camera2 examples
                // for this exact scenario.
                delay(120L)
                src.setCustomRequest { builder ->
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                }
                // AF_MODE_AUTO holds focus on the selected area instead of
                // continuing to track the scene — like "tap to focus" in a real
                // camera app (lock briefly), then restore continuous autofocus
                // so the camera can keep focusing on its own without another tap.
                delay(3000L)
                src.enableAutoFocus()
                Log.d(tag, "tapFocus: trigger released, AF continues in AUTO mode on region")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected: the next tap, zoom, or lens switch cancels this task
                // (see cameraJob — only the last action in a burst survives).
                // Previously this was logged as "tapFocus failed" with a stack
                // trace, making it look broken in field analysis; structured
                // concurrency requires propagating cancellation, not swallowing it as a failure.
                throw e
            } catch (e: Exception) {
                Log.e(tag, "tapFocus failed", e)
            }
        }
    }

    private fun camera2(): Camera2Source? = stream.videoSource as? Camera2Source

    private companion object {
        /** Long side of the still image. Leave headroom over 1080p: the library
         *  performs the exact fit to the frame; our job is not to keep the full
         *  photo in memory. */
        const val MAX_SIDE = 1920
    }
}
