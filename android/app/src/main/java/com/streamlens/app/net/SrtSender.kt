package com.streamlens.app.net

import android.util.Log
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.SockStatus
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class SenderStats(
    val mbpsSend: Double,
    val rttMs: Double,
    val lossPercent: Double,
    val sendBufferMs: Int,
    val mbpsLinkEstimate: Double,
)

/**
 * Sends MPEG-TS to OBS over SRT and keeps doing so through network trouble.
 *
 * The phone is the SRT caller and OBS listens, so the phone only needs the PC's address. When the
 * link breaks it reconnects every second, forever, until [stop] is called. After every (re)connect
 * nothing is sent until the next keyframe, so OBS never receives a half-decodable stream.
 */
class SrtSender(
    private val host: String,
    private val port: Int,
    private val latencyMs: Int,
    private val listener: Listener,
) {
    enum class State { CONNECTING, LIVE, RECONNECTING, STOPPED }

    interface Listener {
        fun onState(state: State, detail: String?)
        /** Called when a fresh keyframe is needed: after connecting, or after dropping data. */
        fun onNeedKeyFrame()
        fun onStats(stats: SenderStats)
    }

    private class Chunk(val data: ByteArray, val keyFrame: Boolean)

    companion object {
        private const val TAG = "SrtSender"
        private const val PAYLOAD = 1316 // 7 TS packets, the SRT live-mode default
        private const val MAX_QUEUED_FRAMES = 90
        private const val RETRY_DELAY_MS = 1000L

        init {
            Srt.startUp()
        }
    }

    private val queue = LinkedBlockingQueue<Chunk>()
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var socket: SrtSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "srt-sender").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        socket?.let { runCatching { it.close() } }
        thread?.interrupt()
        thread?.join(2000)
        thread = null
        queue.clear()
        listener.onState(State.STOPPED, null)
    }

    /** Queues one muxed frame. Never blocks the encoder. */
    fun offer(data: ByteArray, keyFrame: Boolean) {
        if (!connected) return
        if (queue.size >= MAX_QUEUED_FRAMES) {
            // The network can't keep up. Drop the backlog instead of growing delay, and restart
            // from a keyframe so the picture recovers cleanly.
            Log.w(TAG, "Send queue full, dropping backlog")
            queue.clear()
            listener.onNeedKeyFrame()
        }
        queue.offer(Chunk(data, keyFrame))
    }

    private fun run() {
        var firstAttempt = true
        while (running) {
            listener.onState(if (firstAttempt) State.CONNECTING else State.RECONNECTING, "$host:$port")
            firstAttempt = false
            val s = SrtSocket()
            socket = s
            try {
                configure(s)
                s.connect(host, port)
                streamOn(s)
            } catch (e: Exception) {
                if (running) Log.i(TAG, "SRT link down: ${e.message}")
            } finally {
                connected = false
                runCatching { s.close() }
                socket = null
            }
            if (running) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun configure(s: SrtSocket) {
        s.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
        s.setSockFlag(SockOpt.LATENCY, latencyMs)
        s.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD)
        s.setSockFlag(SockOpt.CONNTIMEO, 3000)
        s.setSockFlag(SockOpt.PEERIDLETIMEO, 3000)
        s.setSockFlag(SockOpt.SNDSYN, true)
        s.setSockFlag(SockOpt.SNDBUF, 32 * 1024 * 1024)
        s.setSockFlag(SockOpt.OHEADBW, 25)
    }

    private fun streamOn(s: SrtSocket) {
        queue.clear()
        connected = true
        listener.onState(State.LIVE, "$host:$port")
        listener.onNeedKeyFrame()

        var waitingForKey = true
        var lastStats = System.currentTimeMillis()
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
            if (s.sockState != SockStatus.CONNECTED) throw IllegalStateException("Socket ${s.sockState}")

            if (chunk != null) {
                if (waitingForKey && !chunk.keyFrame) continue
                waitingForKey = false
                var off = 0
                while (off < chunk.data.size) {
                    val len = minOf(PAYLOAD, chunk.data.size - off)
                    s.send(chunk.data, off, len)
                    off += len
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastStats >= 1000) {
                lastStats = now
                reportStats(s)
            }
        }
    }

    private fun reportStats(s: SrtSocket) {
        val st = runCatching { s.bistats(true, true) }.getOrNull() ?: return
        val sent = st.pktSent.coerceAtLeast(1)
        listener.onStats(
            SenderStats(
                mbpsSend = st.mbpsSendRate,
                rttMs = st.msRTT,
                lossPercent = st.pktSndLoss * 100.0 / sent,
                sendBufferMs = st.msSndBuf,
                mbpsLinkEstimate = st.mbpsBandwidth,
            )
        )
    }
}
