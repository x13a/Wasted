package me.lucky.wasted

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TriggerReceiver : BroadcastReceiver() {
    companion object {
        const val KEY = "code"
        const val ACTION = "me.lucky.wasted.action.TRIGGER"

        fun panic(context: Context, intent: Intent?) {
            if (intent?.action != ACTION) return
            val prefs = Preferences.new(context)
            if (!prefs.isEnabled) return
            val code = prefs.authenticationCode
            assert(code.isNotEmpty())
            if (intent.getStringExtra(KEY) != code) return
            val admin = DeviceAdminManager(context)
            try {
                admin.lockNow()
                if (prefs.isWipeData) admin.wipeData()
            } catch (exc: SecurityException) {}
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (Preferences.new(context ?: return).triggers.and(Trigger.BROADCAST.value) == 0)
            return
        panic(context, intent)
    }
}
