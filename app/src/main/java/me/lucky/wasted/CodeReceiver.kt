package me.lucky.wasted

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CodeReceiver : BroadcastReceiver() {
    companion object {
        const val KEY = "code"
        const val ACTION = "me.lucky.wasted.action.TRIGGER"

        fun panic(context: Context, intent: Intent) {
            val prefs = Preferences(context)
            val code = prefs.code
            if (!prefs.isServiceEnabled ||
                code == "" ||
                intent.action != ACTION ||
                intent.getStringExtra(KEY) != code) return
            val admin = DeviceAdminManager(context)
            try {
                admin.lockNow()
                if (prefs.isWipeData) admin.wipeData()
            } catch (exc: SecurityException) {}
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        panic(context, intent)
    }
}
