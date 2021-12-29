package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

class DeviceAdmin(ctx: Context) {
    val dpm by lazy {
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    val deviceAdmin by lazy { ComponentName(ctx, DeviceAdminReceiver::class.java) }

    fun remove() = dpm.removeActiveAdmin(deviceAdmin)
    fun isActive(): Boolean = dpm.isAdminActive(deviceAdmin)

    fun wipeData() {
        dpm.wipeData(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            DevicePolicyManager.WIPE_SILENTLY else 0)
    }
}
