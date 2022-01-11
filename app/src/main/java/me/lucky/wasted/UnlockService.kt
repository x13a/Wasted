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

class UnlockService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1000
    }

    private lateinit var receiver: BroadcastReceiver

    private class UnlockReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val keyguardManager = context
                .getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguardManager.isDeviceSecure) return
            val manager = WipeJobManager(context)
            while (manager.schedule() != JobScheduler.RESULT_SUCCESS)
                SystemClock.sleep(1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        receiver = UnlockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, AppNotificationManager.CHANNEL_DEFAULT_ID)
                .setContentTitle(getString(R.string.unlock_service_notification_title))
                .setSmallIcon(android.R.drawable.ic_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
        registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
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
