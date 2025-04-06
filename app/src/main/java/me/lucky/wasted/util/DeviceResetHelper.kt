package me.lucky.wasted.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import me.lucky.wasted.admin.AdminReceiver

object DeviceResetHelper {

    fun resetDevice(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, AdminReceiver::class.java)

        if (dpm.isAdminActive(componentName)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE or DevicePolicyManager.WIPE_RESET_PROTECTION_DATA)
            } else {
                dpm.wipeData(0)
            }
        } else {
            // Optionnel : log, ou feedback si non admin
        }
    }
}
