package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ControlReceiver : BroadcastReceiver() {
    companion object {
        private const val ESCAPE = "me.lucky.wasted.action.ESCAPE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs by lazy { Preferences(context) }
        if (intent.action != ESCAPE ||
            !prefs.isServiceEnabled ||
            intent.getStringExtra("code") != prefs.code) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.lockNow()
        dpm.wipeData(0)
    }
}
