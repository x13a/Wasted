package me.lucky.wasted.debug
import me.lucky.wasted.Preferences


import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import me.lucky.wasted.admin.AdminReceiver

class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w("DebugTriggerReceiver", "Broadcast reçu. Tentative de réinitialisation.")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, AdminReceiver::class.java)

        if (dpm.isAdminActive(adminComponent)) {
            Log.w("DebugTriggerReceiver", "Admin actif ✅ ➜ wipe triggered.")
            dpm.wipeData(
                DevicePolicyManager.WIPE_EXTERNAL_STORAGE or
                        DevicePolicyManager.WIPE_RESET_PROTECTION_DATA
            )
        } else {
            Log.e("DebugTriggerReceiver", "Admin inactif ❌. Impossible de réinitialiser.")
        }
    }
}
