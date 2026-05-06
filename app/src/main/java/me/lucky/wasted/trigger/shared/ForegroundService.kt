package me.lucky.wasted.trigger.shared

import android.app.KeyguardManager
import android.app.Service
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils
import me.lucky.wasted.p2p.P2PController
import me.lucky.wasted.trigger.lock.LockJobManager

class ForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1000
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    }

    private lateinit var prefs: Preferences
    private lateinit var lockReceiver: LockReceiver
    private val usbReceiver = UsbReceiver()
    private var p2pStarted = false

    override fun onCreate() {
        super.onCreate()
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        deinit()
    }

    private fun init() {
        prefs = Preferences.new(this)
        // Start P2P network if the user has enabled it — keeps P2P alive regardless of app foreground state
        if (prefs.p2pEnabled) {
            P2PController.getInstance(this).start()
            p2pStarted = true
        }
        lockReceiver = LockReceiver(getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val triggers = prefs.triggers
        if (triggers.and(Trigger.LOCK.value) != 0)
            registerReceiver(lockReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        if (triggers.and(Trigger.USB.value) != 0)
            registerReceiver(usbReceiver, IntentFilter(ACTION_USB_STATE))
    }

    private fun deinit() {
        // Stop P2P network cleanly when service is destroyed
        if (p2pStarted) {
            P2PController.instanceOrNull()?.stop()
            p2pStarted = false
        }
        val unregister: (BroadcastReceiver) -> Unit = {
            try { unregisterReceiver(it) } catch (exc: IllegalArgumentException) {}
        }
        unregister(lockReceiver)
        unregister(usbReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = NotificationCompat.Builder(this, NotificationManager.CHANNEL_DEFAULT_ID)
                .setContentTitle(getString(R.string.foreground_service_notification_title))
                .setSmallIcon(android.R.drawable.ic_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    private class LockReceiver(private var locked: Boolean) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Preferences.new(context ?: return).triggers.and(Trigger.LOCK.value) == 0)
                return
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    locked = false
                    LockJobManager(context).cancel()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (locked) return
                    locked = true
                    Thread(Runner(context, goAsync())).start()
                }
            }
        }

        private class Runner(
            private val ctx: Context,
            private val pendingResult: PendingResult,
        ) : Runnable {
            override fun run() {
                val job = LockJobManager(ctx)
                var delay = 1000L
                while (job.schedule() != JobScheduler.RESULT_SUCCESS) {
                    Thread.sleep(delay)
                    delay = delay.shl(1)
                }
                pendingResult.finish()
            }
        }
    }

    private class UsbReceiver : BroadcastReceiver() {
        companion object {
            private const val KEY_1 = "connected"
            private const val KEY_2 = "host_connected"
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_STATE) return
            val utils = Utils(context ?: return)
            if (!utils.isDeviceLocked()) return
            val extras = intent.extras ?: return
            if (!extras.getBoolean(KEY_1) && !extras.getBoolean(KEY_2)) return
            utils.fire(Trigger.USB)
        }
    }
}