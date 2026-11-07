package com.streamlens.app.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Hardware video encoder fed straight from the camera through an input Surface, so frames never
 * pass through app memory before compression.
 *
 * Output is Annex-B access units. Parameter sets (VPS/SPS/PPS) are repeated on every keyframe so
 * OBS can start decoding at any keyframe, e.g. after a reconnect.
 */
class VideoEncoder(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    bitrateBps: Int,
    private val onFrame: (data: ByteArray, ptsUs: Long, keyFrame: Boolean) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private val HEVC_AUD = byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50)
        private val H264_AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        /** True if a hardware encoder for [mime] supports this size and frame rate. */
        fun isSupported(mime: String, width: Int, height: Int, fps: Int): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities
                        .areSizeAndRateSupported(width, height, fps.toDouble())
                }.getOrDefault(false)
            }
    }

    private val thread = HandlerThread("encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(mime)
    private var config: ByteArray? = null
    private val aud = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) HEVC_AUD else H264_AUD

    var bitrateBps: Int = bitrateBps
        private set

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // A keyframe every second keeps recovery after packet loss short.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    handleOutput(index, info)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Output after stop", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = onError(e)

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Output format $format")
            }
        }, handler)

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    private fun handleOutput(index: Int, info: MediaCodec.BufferInfo) {
        val buffer = codec.getOutputBuffer(index)
        if (buffer == null || info.size == 0) {
            codec.releaseOutputBuffer(index, false)
            return
        }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        buffer.get(bytes)
        codec.releaseOutputBuffer(index, false)

        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config = bytes
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val csd = if (key) config ?: ByteArray(0) else ByteArray(0)
        val frame = ByteArray(aud.size + csd.size + bytes.size)
        System.arraycopy(aud, 0, frame, 0, aud.size)
        System.arraycopy(csd, 0, frame, aud.size, csd.size)
        System.arraycopy(bytes, 0, frame, aud.size + csd.size, bytes.size)
        onFrame(frame, info.presentationTimeUs, key)
    }

    fun requestKeyFrame() {
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    /** Changes bitrate on the fly, used by adaptive bitrate and heat protection. */
    fun setBitrate(bps: Int) {
        if (bps == bitrateBps) return
        bitrateBps = bps
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        thread.quitSafely()
    }
}
