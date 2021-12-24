package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.os.Build

class Utils {
    companion object {
        fun getWipeDataFlags(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                DevicePolicyManager.WIPE_SILENTLY else 0
        }
    }
}
