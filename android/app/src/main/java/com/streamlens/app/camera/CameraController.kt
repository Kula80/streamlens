package com.streamlens.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface

/** What this phone's camera allows, so the UI only offers controls that actually work. */
data class CameraCapabilities(
    val cameraId: String,
    val manualSensor: Boolean,
    val isoRange: Range<Int>?,
    val exposureRangeNs: Range<Long>?,
    val evRange: Range<Int>,
    val evStep: Float,
    val minFocusDiopters: Float,
    val zoomRange: Range<Float>?,
    val canDisableNoiseReduction: Boolean,
    val canDisableSharpening: Boolean,
)

enum class WhiteBalance(val label: String, val mode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    TUNGSTEN("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    DAYLIGHT("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
}

/**
 * Pro-style controls. Everything defaults to auto; locking exposure, white balance and focus is
 * what stops the picture "breathing" during a service.
 */
data class CameraControls(
    val exposureLocked: Boolean = false,
    val evIndex: Int = 0,
    /** Manual ISO and shutter; null means auto exposure. */
    val manualIso: Int? = null,
    val manualExposureNs: Long? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val whiteBalanceLocked: Boolean = false,
    /** Manual focus in diopters (0 = infinity); null means continuous autofocus. */
    val manualFocus: Float? = null,
    val zoom: Float = 1f,
    /** Turns off the phone's noise reduction and sharpening for a cleaner, more natural image. */
    val natural: Boolean = true,
)

@SuppressLint("MissingPermission")
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "CameraController"

        /** The main back camera, which is what the system lists first. */
        fun mainBackCameraId(context: Context): String? {
            val manager = context.getSystemService(CameraManager::class.java)
            return manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        }

        fun capabilities(context: Context, id: String): CameraCapabilities {
            val c = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val nr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
            val edge = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
            return CameraCapabilities(
                cameraId = id,
                manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f,
                minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else null,
                canDisableNoiseReduction = nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF),
                canDisableSharpening = edge.contains(CameraMetadata.EDGE_MODE_OFF),
            )
        }

        /** Picks the preview size: same shape as the video, small enough to keep the phone cool. */
        fun previewSize(context: Context, id: String, video: Size): Size {
            val map = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val ratio = video.width.toFloat() / video.height
            return map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                .filter { kotlin.math.abs(it.width.toFloat() / it.height - ratio) < 0.01f && it.width <= 1920 }
                .maxByOrNull { it.width * it.height } ?: Size(1280, 720)
        }

        /** True if the camera can deliver [size] to a video encoder at [fps]; many phones film 4K only up to 30. */
        fun supportsVideoSize(context: Context, id: String, size: Size, fps: Int): Boolean {
            val map = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            if (map.getOutputSizes(MediaCodec::class.java)?.contains(size) != true) return false
            // Allow 1 ms of slack: phones report 33.3 ms for 30 fps.
            val minFrameNs = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
            return minFrameNs == 0L || minFrameNs <= 1_000_000_000L / fps + 1_000_000L
        }

        /** Best constant frame-rate range for [fps], falling back to the closest one available. */
        fun fpsRange(context: Context, id: String, fps: Int): Range<Int> {
            val ranges = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(fps, fps)
            return ranges.firstOrNull { it.lower == fps && it.upper == fps }
                ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
                ?: ranges.filter { it.lower == it.upper && it.upper <= fps }.maxByOrNull { it.upper }
                ?: Range(30, 30)
        }
    }

    interface Listener {
        fun onCameraReady()
        fun onCameraError(message: String)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var targets: List<Surface> = emptyList()
    private var fps: Range<Int> = Range(30, 30)
    private var caps: CameraCapabilities? = null

    var controls = CameraControls()
        private set

    fun open(id: String, surfaces: List<Surface>, fpsRange: Range<Int>, listener: Listener) {
        targets = surfaces
        fps = fpsRange
        caps = capabilities(context, id)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, listener)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
                listener.onCameraError("Camera was taken by another app")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                listener.onCameraError("Camera error $error")
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, listener: Listener) {
        camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                apply(controls)
                listener.onCameraReady()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                listener.onCameraError("This resolution and frame rate aren't supported together")
            }
        }, handler)
    }

    fun apply(newControls: CameraControls) {
        controls = newControls
        val camera = device ?: return
        val s = session ?: return
        val c = caps ?: return
        try {
            val r = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            targets.forEach(r::addTarget)
            r.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            r.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            r.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)

            val manual = c.manualSensor && newControls.manualIso != null && newControls.manualExposureNs != null
            if (manual) {
                r.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                r.set(CaptureRequest.SENSOR_SENSITIVITY, newControls.manualIso)
                r.set(CaptureRequest.SENSOR_EXPOSURE_TIME, newControls.manualExposureNs)
                r.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps.upper)
            } else {
                r.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                r.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, newControls.evIndex)
                r.set(CaptureRequest.CONTROL_AE_LOCK, newControls.exposureLocked)
            }

            r.set(CaptureRequest.CONTROL_AWB_MODE, newControls.whiteBalance.mode)
            r.set(CaptureRequest.CONTROL_AWB_LOCK, newControls.whiteBalanceLocked)

            val focus = newControls.manualFocus
            if (focus != null && c.minFocusDiopters > 0f) {
                r.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                r.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, c.minFocusDiopters))
            } else {
                r.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && c.zoomRange != null) {
                r.set(CaptureRequest.CONTROL_ZOOM_RATIO, newControls.zoom.coerceIn(c.zoomRange.lower, c.zoomRange.upper))
            }

            if (newControls.natural) {
                r.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    if (c.canDisableNoiseReduction) CameraMetadata.NOISE_REDUCTION_MODE_OFF
                    else CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                )
                r.set(
                    CaptureRequest.EDGE_MODE,
                    if (c.canDisableSharpening) CameraMetadata.EDGE_MODE_OFF else CameraMetadata.EDGE_MODE_FAST,
                )
            } else {
                r.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                r.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            }

            s.setRepeatingRequest(r.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply camera controls", e)
        }
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        thread.quitSafely()
    }
}
