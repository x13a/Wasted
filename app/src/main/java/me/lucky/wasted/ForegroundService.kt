package me.lucky.wasted

import android.app.KeyguardManager
import android.app.Service
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1000
    }

    private val lockReceiver = LockReceiver()

    override fun onCreate() {
        super.onCreate()
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        deinit()
    }

    private fun init() {
        registerReceiver(lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    private fun deinit() {
        unregisterReceiver(lockReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NotificationManager.CHANNEL_DEFAULT_ID)
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

    private class LockReceiver : BroadcastReceiver() {
        private var unlocked = false

        override fun onReceive(context: Context?, intent: Intent?) {
            if (!Preferences.new(context ?: return).isWipeOnInactivity) return
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    if (context.getSystemService(KeyguardManager::class.java)
                            ?.isDeviceSecure != true) return
                    unlocked = true
                    WipeJobManager(context).cancel()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (!unlocked) return
                    unlocked = false
                    Thread(Runner(context, goAsync())).start()
                }
            }
        }

        private class Runner(
            private val ctx: Context,
            private val pendingResult: PendingResult,
        ) : Runnable {
            override fun run() {
                val job = WipeJobManager(ctx)
                var delay = 1000L
                while (job.schedule() != JobScheduler.RESULT_SUCCESS) {
                    Thread.sleep(delay)
                    delay = delay.shl(1)
                }
                pendingResult.finish()
            }
        }
    }
}
