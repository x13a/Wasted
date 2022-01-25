package me.lucky.wasted

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

class NotificationManager(ctx: Context) {
    companion object {
        const val CHANNEL_DEFAULT_ID = "default"
    }

    private val manager = NotificationManagerCompat.from(ctx)

    fun createNotificationChannels() {
        manager.createNotificationChannel(NotificationChannelCompat.Builder(
            CHANNEL_DEFAULT_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        ).build())
    }
}
