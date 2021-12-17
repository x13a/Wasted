package me.lucky.wasted

import android.app.admin.DevicePolicyManager
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
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            dpm.lockNow()
            if (prefs.doWipe) dpm.wipeData(0)
        } catch (exc: SecurityException) {}
    }
}
