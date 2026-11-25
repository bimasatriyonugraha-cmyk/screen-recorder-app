package com.example.screenrecorder

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import java.util.Locale

/**
 * Single source of truth for every string/extra shared between [MainActivity],
 * [ScreenRecordService] and the notification actions. Keeping them here prevents
 * the classic "typo in an action string" bug where Stop silently does nothing.
 */
object RecordingContract {

    const val ACTION_START = "com.example.screenrecorder.action.START"
    const val ACTION_STOP = "com.example.screenrecorder.action.STOP"
    const val ACTION_STATE_CHANGED = "com.example.screenrecorder.action.STATE_CHANGED"

    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"
    const val EXTRA_WIDTH = "extra_width"
    const val EXTRA_HEIGHT = "extra_height"
    const val EXTRA_DENSITY = "extra_density"
    const val EXTRA_FRAME_RATE = "extra_frame_rate"
    const val EXTRA_VIDEO_BITRATE = "extra_video_bitrate"
    const val EXTRA_MICROPHONE = "extra_microphone"
    const val EXTRA_STATE = "extra_state"
    const val EXTRA_MESSAGE = "extra_message"

    const val STATE_IDLE = "idle"
    const val STATE_PREPARING = "preparing"
    const val STATE_RECORDING = "recording"
    const val STATE_FINALIZING = "finalizing"
    const val STATE_ERROR = "error"

    const val NOTIFICATION_ID = 1001
    const val CHANNEL_ID = "screen_recording_v1"
    const val CHANNEL_NAME = "Screen recording"
    const val CHANNEL_DESCRIPTION = "Shows the ongoing recording timer and the Stop action."

    const val ALBUM_NAME = "ScreenRecorder"
    /** Relative MediaStore folder: resolves to /Movies/ScreenRecorder/. */
    const val RELATIVE_PATH = "Movies/ScreenRecorder/"
}

enum class RecordingResolution(
    val chipLabel: String,
    /** The short edge is what "720p"/"1080p" actually constrains. */
    val shortEdge: Int,
    val videoBitrate30: Int,
    val videoBitrate60: Int
) {
    HD720("720p", 720, 8_000_000, 12_000_000),
    FHD1080("1080p", 1080, 14_000_000, 22_000_000);

    fun bitrateFor(frameRate: FrameRate): Int =
        if (frameRate == FrameRate.FPS_60) videoBitrate60 else videoBitrate30

    companion object {
        fun fromShortEdge(value: Int): RecordingResolution =
            entries.firstOrNull { it.shortEdge == value } ?: FHD1080
    }
}

enum class FrameRate(val chipLabel: String, val fps: Int) {
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60);

    companion object {
        fun fromFps(value: Int): FrameRate = entries.firstOrNull { it.fps == value } ?: FPS_30
    }
}

/**
 * Everything the service needs to build one recording session. Immutable and
 * Intent-friendly, so the Activity can serialise it and walk away.
 */
data class RecorderConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val frameRate: Int,
    val videoBitrate: Int,
    val microphone: Boolean
) {

    val audioBitrate: Int get() = if (microphone) AUDIO_BITRATE else 0

    /** Rough disk cost, shown next to the timer so the user knows what they picked. */
    val megabytesPerMinute: Int
        get() = ((videoBitrate + audioBitrate).toLong() * 60L / 8L / 1024L / 1024L).toInt()

    val label: String get() = "${width}x$height · $frameRate FPS"

    fun putInto(intent: Intent) {
        intent.putExtra(RecordingContract.EXTRA_WIDTH, width)
        intent.putExtra(RecordingContract.EXTRA_HEIGHT, height)
        intent.putExtra(RecordingContract.EXTRA_DENSITY, densityDpi)
        intent.putExtra(RecordingContract.EXTRA_FRAME_RATE, frameRate)
        intent.putExtra(RecordingContract.EXTRA_VIDEO_BITRATE, videoBitrate)
        intent.putExtra(RecordingContract.EXTRA_MICROPHONE, microphone)
    }

    companion object {

        const val AUDIO_BITRATE = 128_000
        const val AUDIO_SAMPLE_RATE = 44_100

        /**
         * Builds a session config that mirrors the device screen. The encoded frame keeps
         * the panel's aspect ratio, is locked to the portrait canvas (so a rotation mid-take
         * never invalidates the encoder surface) and both edges are forced even, because
         * H.264 encoders reject odd dimensions.
         */
        fun forDevice(
            context: Context,
            resolution: RecordingResolution,
            frameRate: FrameRate,
            microphone: Boolean
        ): RecorderConfig {
            val natural = realScreenSize(context)
            val shortEdge = minOf(natural.x, natural.y).coerceAtLeast(1)
            val longEdge = maxOf(natural.x, natural.y).coerceAtLeast(shortEdge)

            val targetShort = resolution.shortEdge.coerceAtMost(shortEdge)
            val targetLong = targetShort.toLong() * longEdge / shortEdge

            return RecorderConfig(
                width = even(targetShort),
                height = even(targetLong.toInt()),
                densityDpi = context.resources.configuration.densityDpi,
                frameRate = frameRate.fps,
                videoBitrate = resolution.bitrateFor(frameRate),
                microphone = microphone
            )
        }

        /** Full display area, including system bars, honouring the current rotation. */
        @Suppress("DEPRECATION")
        fun realScreenSize(context: Context): Point {
            val windowManager = context.getSystemService(WindowManager::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                Point(bounds.width(), bounds.height())
            } else {
                val point = Point()
                windowManager.defaultDisplay.getRealSize(point)
                point
            }
        }

        private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1
    }
}

/** Tiny SharedPreferences wrapper so the chips survive process death. */
class RecorderPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var resolution: RecordingResolution
        get() = RecordingResolution.fromShortEdge(
            prefs.getInt(KEY_RESOLUTION, RecordingResolution.FHD1080.shortEdge)
        )
        set(value) = prefs.edit().putInt(KEY_RESOLUTION, value.shortEdge).apply()

    var frameRate: FrameRate
        get() = FrameRate.fromFps(prefs.getInt(KEY_FRAME_RATE, FrameRate.FPS_30.fps))
        set(value) = prefs.edit().putInt(KEY_FRAME_RATE, value.fps).apply()

    var microphone: Boolean
        get() = prefs.getBoolean(KEY_MIC, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC, value).apply()

    private companion object {
        const val PREFS_NAME = "recorder_prefs"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FRAME_RATE = "frame_rate"
        const val KEY_MIC = "microphone"
    }
}

/** 00:00 / 1:02:33 formatter shared by the timer, the notification and the file list. */
fun formatClock(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
