package me.lucky.wasted.trigger.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.admin.DeviceAdminManager

class BroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val KEY = "code"
        const val ACTION = "me.lucky.wasted.action.TRIGGER"

        fun panic(context: Context, intent: Intent?) {
            if (intent?.action != ACTION) return
            val prefs = Preferences.new(context)
            if (!prefs.isEnabled) return
            val secret = prefs.secret
            assert(secret.isNotEmpty())
            if (intent.getStringExtra(KEY) != secret) return
            val admin = DeviceAdminManager(context)
            try {
                admin.lockNow()
                if (prefs.isWipeData) admin.wipeData()
            } catch (exc: SecurityException) {}
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (Preferences.new(context ?: return).triggers.and(Trigger.BROADCAST.value) != 0)
            panic(context, intent)
    }
}