package com.example.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Builds the persistent foreground-service notification.
 *
 * The timer is rendered by the system chronometer (`setUsesChronometer`) instead of a
 * per-second `notify()` call — that keeps the CPU idle and makes the digits tick even
 * while the app process is frozen.
 */
object NotificationHelper {

    private const val REQUEST_OPEN_APP = 2001
    private const val REQUEST_STOP = 2002

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(RecordingContract.CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            RecordingContract.CHANNEL_ID,
            RecordingContract.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = RecordingContract.CHANNEL_DESCRIPTION
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        state: String,
        elapsedMillis: Long,
        config: RecorderConfig?
    ): Notification {
        ensureChannel(context)

        val title = when (state) {
            RecordingContract.STATE_PREPARING -> context.getString(R.string.notif_preparing)
            RecordingContract.STATE_FINALIZING -> context.getString(R.string.notif_saving)
            RecordingContract.STATE_ERROR -> context.getString(R.string.notif_stopped)
            else -> context.getString(R.string.notif_recording)
        }

        val micLabel = context.getString(
            if (config?.microphone == true) R.string.notif_mic_on else R.string.notif_mic_off
        )
        val subtitle = buildString {
            append(RecordingContract.RELATIVE_PATH.trimEnd('/'))
            if (config != null) {
                append(" · ").append(config.label)
            }
            append(" · ").append(micLabel)
        }

        val builder = NotificationCompat.Builder(context, RecordingContract.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOngoing(state != RecordingContract.STATE_ERROR)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(state == RecordingContract.STATE_RECORDING)
            .setWhen(System.currentTimeMillis() - elapsedMillis)
            .setColor(ContextCompat.getColor(context, R.color.rec_red))
            .setColorized(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(openAppIntent(context))
            .addAction(
                R.drawable.ic_stop_small,
                context.getString(R.string.notif_action_stop),
                stopIntent(context)
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Show instantly when the service enters the foreground, no fade-in gap.
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * getForegroundService (not getActivity / getBroadcast) is what keeps this working on
     * Android 12+: the app may be in the background when the user taps Stop, and background
     * broadcasts are restricted while foreground-service launches are not.
     */
    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = RecordingContract.ACTION_STOP
        }
        return PendingIntent.getForegroundService(
            context,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
