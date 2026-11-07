package com.streamlens.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import android.view.Surface
import com.streamlens.app.camera.CameraController
import com.streamlens.app.camera.CameraControls
import com.streamlens.app.encode.VideoEncoder
import com.streamlens.app.mux.TsMuxer
import com.streamlens.app.net.SenderStats
import com.streamlens.app.net.SrtSender

data class EngineStatus(
    val link: SrtSender.State = SrtSender.State.STOPPED,
    val videoLabel: String = "",
    val bitrateBps: Int = 0,
    val stats: SenderStats? = null,
    val heatWarning: String? = null,
    val error: String? = null,
)

/**
 * Wires camera → encoder → MPEG-TS → SRT, and adjusts bitrate for network and heat.
 *
 * While not live only the preview runs, so the phone stays cool between services. Going live
 * reopens the camera with the encoder attached.
 */
class StreamEngine(private val context: Context, private val onStatus: (EngineStatus) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var camera: CameraController? = null
    private var encoder: VideoEncoder? = null
    private var sender: SrtSender? = null
    private var previewSurface: Surface? = null
    private var settings: StreamSettings? = null
    private var controls = CameraControls()
    private var status = EngineStatus()

    /** Bitrate limit set by heat protection, as a fraction of the chosen bitrate. */
    private var heatCap = 1.0
    private var calmSeconds = 0

    private val power = context.getSystemService(PowerManager::class.java)
    private val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { onThermal(it) }
    } else null

    val cameraId: String? = CameraController.mainBackCameraId(context)

    val isLive get() = sender != null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.addThermalStatusListener(context.mainExecutor, thermalListener!!)
        }
    }

    fun startPreview(surface: Surface) {
        previewSurface = surface
        if (!isLive) openCamera(listOf(surface), fps = 30)
    }

    fun setControls(newControls: CameraControls) {
        controls = newControls
        camera?.apply(newControls)
    }

    fun goLive(s: StreamSettings) {
        val id = cameraId ?: return update { it.copy(error = "No back camera found") }
        val preview = previewSurface ?: return
        stopLive(reopenPreview = false)
        settings = s

        val (codec, size, pickedFps) = pickFormat(id, s) ?: return update {
            it.copy(error = "This phone can't encode video at ${s.resolution.label} ${s.fps}fps")
        }
        val fpsRange = CameraController.fpsRange(context, id, pickedFps)
        val fps = fpsRange.upper
        val muxer = TsMuxer(if (codec == Codec.HEVC) TsMuxer.STREAM_TYPE_HEVC else TsMuxer.STREAM_TYPE_H264)
        val bitrate = s.bitrateMbps * 1_000_000

        val newSender = SrtSender(s.host, s.port, s.latency.ms, object : SrtSender.Listener {
            override fun onState(state: SrtSender.State, detail: String?) =
                update { it.copy(link = state, error = null) }

            override fun onNeedKeyFrame() {
                encoder?.requestKeyFrame()
            }

            override fun onStats(stats: SenderStats) {
                main.post { adapt(stats) }
            }
        })
        val newEncoder = VideoEncoder(
            mime = codec.mime,
            width = size.width,
            height = size.height,
            fps = fps,
            bitrateBps = bitrate,
            onFrame = { data, ptsUs, key -> newSender.offer(muxer.mux(data, ptsUs, key), key) },
            onError = { e -> update { it.copy(error = "Encoder error: ${e.message}") } },
        )
        encoder = newEncoder
        sender = newSender
        update {
            it.copy(
                videoLabel = "${labelFor(size)} ${fps}fps ${if (codec == Codec.HEVC) "HEVC" else "H.264"}" +
                    stepDownNote(s, codec, size, fps),
                bitrateBps = bitrate,
                error = null,
            )
        }
        openCamera(listOf(preview, newEncoder.inputSurface), fps)
        newSender.start()
    }

    fun stopLive(reopenPreview: Boolean = true) {
        val wasLive = isLive
        sender?.stop()
        sender = null
        camera?.close()
        camera = null
        encoder?.release()
        encoder = null
        if (wasLive) update { EngineStatus(heatWarning = it.heatWarning) }
        if (reopenPreview) previewSurface?.let { openCamera(listOf(it), fps = 30) }
    }

    fun release() {
        stopLive(reopenPreview = false)
        camera?.close()
        camera = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.removeThermalStatusListener(thermalListener!!)
    }

    private fun openCamera(surfaces: List<Surface>, fps: Int) {
        val id = cameraId ?: return
        camera?.close()
        val c = CameraController(context)
        camera = c
        c.apply(controls)
        c.open(id, surfaces, CameraController.fpsRange(context, id, fps), object : CameraController.Listener {
            override fun onCameraReady() = c.apply(controls)
            override fun onCameraError(message: String) = update { it.copy(error = message) }
        })
    }

    /**
     * Uses the chosen format if possible, otherwise steps down. Resolution is kept before frame rate,
     * because churches crop and zoom in OBS: 4K60 → 4K30 → 1080p60 → …, and HEVC → H.264.
     */
    private fun pickFormat(id: String, s: StreamSettings): Triple<Codec, Size, Int>? {
        val codecs = listOf(s.codec) + Codec.entries.filter { it != s.codec }
        val sizes = Resolution.entries.filter { it.ordinal >= s.resolution.ordinal }.map { Size(it.width, it.height) }
        val rates = listOf(s.fps) + listOf(30, 25, 24).filter { it < s.fps }
        for (size in sizes) {
            for (fps in rates) {
                if (!CameraController.supportsVideoSize(context, id, size, fps)) continue
                for (codec in codecs) {
                    if (VideoEncoder.isSupported(codec.mime, size.width, size.height, fps)) return Triple(codec, size, fps)
                }
            }
        }
        return null
    }

    /** Tells the user when the phone couldn't do what they picked, so it doesn't look ignored. */
    private fun stepDownNote(s: StreamSettings, codec: Codec, size: Size, fps: Int): String {
        val missing = buildList {
            if (size.width != s.resolution.width) add(s.resolution.label)
            if (fps != s.fps) add("${s.fps}fps")
            if (codec != s.codec) add(if (s.codec == Codec.HEVC) "HEVC" else "H.264")
        }
        return if (missing.isEmpty()) "" else " (this phone can't do ${missing.joinToString(" ")})"
    }

    /** Adaptive bitrate: back off fast when the link struggles, recover slowly when it's calm. */
    private fun adapt(stats: SenderStats) {
        val s = settings ?: return
        val enc = encoder ?: return
        val target = (s.bitrateMbps * 1_000_000 * heatCap).toInt()
        var next = enc.bitrateBps
        val struggling = stats.sendBufferMs > s.latency.ms / 2 || stats.lossPercent > 2.0
        if (s.adaptiveBitrate && struggling) {
            calmSeconds = 0
            next = (next * 0.75).toInt()
        } else {
            calmSeconds++
            if (calmSeconds >= 5) next = (next * 1.08).toInt()
        }
        next = next.coerceIn(2_000_000, target.coerceAtLeast(2_000_000))
        enc.setBitrate(next)
        update { it.copy(bitrateBps = next, stats = stats) }
    }

    private fun onThermal(level: Int) {
        val (cap, warning) = when {
            level >= PowerManager.THERMAL_STATUS_CRITICAL -> 0.4 to "Phone is very hot: quality reduced to protect it"
            level >= PowerManager.THERMAL_STATUS_SEVERE -> 0.6 to "Phone is hot: quality reduced a little"
            level >= PowerManager.THERMAL_STATUS_MODERATE -> 0.85 to "Phone is warm"
            else -> 1.0 to null
        }
        heatCap = cap
        update { it.copy(heatWarning = warning) }
    }

    private fun labelFor(size: Size) = when (size.height) {
        2160 -> "4K"
        1080 -> "1080p"
        720 -> "720p"
        else -> "${size.width}×${size.height}"
    }

    /** Status is only ever changed on the main thread, so updates from other threads can't clash. */
    private fun update(transform: (EngineStatus) -> EngineStatus) {
        main.post {
            status = transform(status)
            onStatus(status)
        }
    }
}
