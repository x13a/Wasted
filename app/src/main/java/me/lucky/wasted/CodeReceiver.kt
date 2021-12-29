package me.lucky.wasted

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CodeReceiver : BroadcastReceiver() {
    companion object {
        private const val TRIGGER = "me.lucky.wasted.action.TRIGGER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs by lazy { Preferences(context) }
        val code = prefs.code
        if (!prefs.isServiceEnabled ||
            code == "" ||
            intent.action != TRIGGER ||
            intent.getStringExtra("code") != code) return
        val admin = DeviceAdmin(context)
        try {
            admin.dpm.lockNow()
            if (prefs.doWipe) admin.wipeData()
        } catch (exc: SecurityException) {}
    }
}
