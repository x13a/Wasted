package me.lucky.wasted
import me.lucky.wasted.Preferences


import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import me.lucky.wasted.admin.AdminReceiver

object DeviceController {

    fun reset(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, AdminReceiver::class.java)

        if (dpm.isAdminActive(componentName)) {
            Toast.makeText(context, "Réinitialisation en cours...", Toast.LENGTH_SHORT).show()
            dpm.wipeData(0) // 0 = reset usine (sans suppression de la carte SD)
        } else {
            Toast.makeText(context, "Droits admin manquants", Toast.LENGTH_LONG).show()
        }
    }
}
