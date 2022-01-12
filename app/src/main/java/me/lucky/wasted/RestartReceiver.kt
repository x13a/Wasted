package me.lucky.wasted

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Preferences(context)
        if (!prefs.isServiceEnabled || !prefs.isWipeOnInactivity) return
        ContextCompat.startForegroundService(context, Intent(context, UnlockService::class.java))
    }
}
