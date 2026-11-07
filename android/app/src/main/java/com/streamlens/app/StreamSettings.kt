package com.streamlens.app

import android.content.Context

/** How much SRT is allowed to buffer to repair packet loss. Bigger = more stable, more delay. */
enum class LatencyPreset(val label: String, val ms: Int) {
    LOW("Low latency", 120),
    BALANCED("Balanced", 300),
    ROCK_SOLID("Rock solid", 1000),
}

enum class Resolution(val label: String, val width: Int, val height: Int) {
    UHD("4K", 3840, 2160),
    FHD("1080p", 1920, 1080),
    HD("720p", 1280, 720),
}

enum class Codec(val label: String, val mime: String) {
    HEVC("HEVC (H.265)", "video/hevc"),
    H264("H.264", "video/avc"),
}

data class StreamSettings(
    val host: String = "",
    val port: Int = 9710,
    val resolution: Resolution = Resolution.UHD,
    val fps: Int = 30,
    val codec: Codec = Codec.HEVC,
    val bitrateMbps: Int = 40,
    val latency: LatencyPreset = LatencyPreset.BALANCED,
    val adaptiveBitrate: Boolean = true,
    val silenceWhileLive: Boolean = true,
) {
    companion object {
        val FPS_CHOICES = listOf(24, 25, 30, 50, 60)
        val PORT_RANGE = 1024..65535
        private const val PREFS = "stream_settings"

        /**
         * Accepts what people actually type: "192.168.1.20", " 192.168.1.20 ", "srt://192.168.1.20:9710",
         * "192.168.1.20:9710/". Returns the host and the port if one was included, or null if unusable.
         */
        fun parseAddress(text: String): Pair<String, Int?>? {
            var s = text.trim().substringAfter("://").substringBefore('/').substringBefore('?')
            var port: Int? = null
            val colon = s.lastIndexOf(':')
            if (colon > 0 && s.count { it == ':' } == 1) {
                port = s.substring(colon + 1).toIntOrNull() ?: return null
                s = s.substring(0, colon)
            }
            if (s.isEmpty() || s.any { it.isWhitespace() || it == ',' }) return null
            return s to port
        }

        fun load(context: Context): StreamSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = StreamSettings()
            return StreamSettings(
                host = p.getString("host", d.host) ?: d.host,
                port = p.getInt("port", d.port),
                resolution = enumOr(p.getString("resolution", null), d.resolution),
                fps = p.getInt("fps", d.fps),
                codec = enumOr(p.getString("codec", null), d.codec),
                bitrateMbps = p.getInt("bitrateMbps", d.bitrateMbps),
                latency = enumOr(p.getString("latency", null), d.latency),
                adaptiveBitrate = p.getBoolean("adaptiveBitrate", d.adaptiveBitrate),
                silenceWhileLive = p.getBoolean("silenceWhileLive", d.silenceWhileLive),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("resolution", resolution.name)
            .putInt("fps", fps)
            .putString("codec", codec.name)
            .putInt("bitrateMbps", bitrateMbps)
            .putString("latency", latency.name)
            .putBoolean("adaptiveBitrate", adaptiveBitrate)
            .putBoolean("silenceWhileLive", silenceWhileLive)
            .apply()
    }
}
