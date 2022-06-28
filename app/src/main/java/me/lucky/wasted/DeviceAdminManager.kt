package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.lang.Exception

class DeviceAdminManager(private val ctx: Context) {
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val deviceAdmin by lazy { ComponentName(ctx, DeviceAdminReceiver::class.java) }
    private val prefs by lazy { Preferences(ctx) }

    fun remove() = dpm?.removeActiveAdmin(deviceAdmin)
    fun isActive() = dpm?.isAdminActive(deviceAdmin) ?: false

    fun lockNow() { if (!lockPrivilegedNow()) dpm?.lockNow() }

    private fun lockPrivilegedNow(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        var ok = true
        try {
            dpm?.getParentProfileInstance(deviceAdmin)?.lockNow()
        } catch (exc: SecurityException) { ok = false }
        if (!ok || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        try {
            dpm?.lockNow(DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)
        } catch (exc: Exception) { ok = false }
        return ok
    }

    fun wipeData() {
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            flags = flags.or(DevicePolicyManager.WIPE_SILENTLY)
        if (prefs.isWipeEmbeddedSim && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            flags = flags.or(DevicePolicyManager.WIPE_EUICC)
        dpm?.wipeData(flags)
    }

    fun makeRequestIntent() =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdmin)
}
