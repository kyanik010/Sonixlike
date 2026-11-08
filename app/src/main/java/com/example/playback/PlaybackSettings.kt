package com.example.playback

import android.content.Context
import android.content.SharedPreferences

enum class VideoEngineType(val id: String, val labelArabic: String, val descriptionArabic: String) {
    AUTO("auto", "تلقائي (موصى به)", "اختيار المحرك الأنسب لجهازك تلقائياً (Media3 افتراضياً مع التراجع الآمن)"),
    MEDIA3("media3", "Media3 / ExoPlayer", "محرك أندرويد الأساسي الرسمي، يوفر أداءً مثالياً واستهلاك طاقة منخفضاً"),
    LIBMPV("mpv", "libmpv", "محرك بديل عالي الكفاءة مع مكتبات FFmpeg الموسعة لفك تشفير مختلف الترميزات الصعبة");

    companion object {
        fun fromId(id: String?): VideoEngineType {
            if (id == null) return AUTO
            val trimmed = id.trim().lowercase()
            return when (trimmed) {
                "mpv", "libmpv" -> LIBMPV
                "media3", "exoplayer" -> MEDIA3
                else -> AUTO
            }
        }
    }
}

class PlaybackSettings(private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedEngine(): VideoEngineType {
        val raw = prefs.getString(KEY_VIDEO_ENGINE, VideoEngineType.AUTO.id)
        return VideoEngineType.fromId(raw)
    }

    fun setSelectedEngine(engine: VideoEngineType) {
        prefs.edit().putString(KEY_VIDEO_ENGINE, engine.id).apply()
    }

    fun isMpvAvailable(): Boolean {
        return try {
            Class.forName("is.xyz.mpv.MPV")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun resolveEffectiveEngine(requested: VideoEngineType = getSelectedEngine()): VideoEngineType {
        return when (requested) {
            VideoEngineType.AUTO -> {
                // Media3 is the verified primary default on Android TV and mobile
                VideoEngineType.MEDIA3
            }
            VideoEngineType.LIBMPV -> {
                if (isMpvAvailable()) VideoEngineType.LIBMPV else VideoEngineType.MEDIA3
            }
            VideoEngineType.MEDIA3 -> VideoEngineType.MEDIA3
        }
    }

    companion object {
        private const val PREFS_NAME = "sonix_playback_prefs"
        private const val KEY_VIDEO_ENGINE = "key_video_engine"
    }
}
