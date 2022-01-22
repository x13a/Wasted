package me.lucky.wasted

import android.app.KeyguardManager
import android.app.Service
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class ForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1000
    }

    private lateinit var receiver: BroadcastReceiver

    private class UnlockReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null) return
            if (context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) return
            val manager = WipeJobManager(context)
            var delay = 1000L
            while (manager.schedule() != JobScheduler.RESULT_SUCCESS) {
                SystemClock.sleep(delay)
                delay = delay.shl(1)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        init()
    }

    private fun init() {
        receiver = UnlockReceiver()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, AppNotificationManager.CHANNEL_DEFAULT_ID)
                .setContentTitle(getString(R.string.foreground_service_notification_title))
                .setSmallIcon(android.R.drawable.ic_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }
}
