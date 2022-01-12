package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

class DeviceAdminManager(private val ctx: Context) {
    private var dpm: DevicePolicyManager? = null
    private val deviceAdmin by lazy { ComponentName(ctx, DeviceAdminReceiver::class.java) }
    private val prefs by lazy { Preferences(ctx) }

    init {
        dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager?
    }

    fun remove() = dpm?.removeActiveAdmin(deviceAdmin)
    fun isActive(): Boolean = dpm?.isAdminActive(deviceAdmin) ?: false
    fun getCurrentFailedPasswordAttempts(): Int = dpm?.currentFailedPasswordAttempts ?: 0
    fun lockNow() = dpm?.lockNow()

    fun wipeData() {
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            flags = flags.or(DevicePolicyManager.WIPE_SILENTLY)
        if (prefs.isWipeESIM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            flags = flags.or(DevicePolicyManager.WIPE_EUICC)
        dpm?.wipeData(flags)
    }

    fun makeRequestIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdmin)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                ctx.getString(R.string.device_admin_description),
            )
    }
}
