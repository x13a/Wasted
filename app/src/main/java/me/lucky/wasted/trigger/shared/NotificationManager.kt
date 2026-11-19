package me.lucky.wasted.trigger.shared

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

import me.lucky.wasted.MainActivity
import me.lucky.wasted.R

class NotificationManager(private val ctx: Context) {
    companion object {
        const val CHANNEL_DEFAULT_ID = "default"
        const val CHANNEL_REMOTE_EVENTS_ID = "remote_events"
    }

    private val manager = NotificationManagerCompat.from(ctx)

    fun createNotificationChannels() {
        manager.createNotificationChannel(NotificationChannelCompat.Builder(
            CHANNEL_DEFAULT_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        ).setName(ctx.getString(R.string.notification_channel_default_name)).setShowBadge(false).build())
        manager.createNotificationChannel(NotificationChannelCompat.Builder(
            CHANNEL_REMOTE_EVENTS_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        ).setName(ctx.getString(R.string.notification_channel_remote_name)).setShowBadge(true).build())
    }

    /** Posts a remote event; reusing the id replaces the previous notice instead of stacking. */
    fun notifyRemoteEvent(id: Int, title: String, message: String) {
        // Idempotent: covers posts that happen before the app's first channel setup.
        createNotificationChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_REMOTE_EVENTS_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_delete)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        runCatching { manager.notify(id, notification) }
    }
}
