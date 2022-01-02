package me.lucky.wasted

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserHandle
import androidx.annotation.RequiresApi

class DeviceAdminReceiver : DeviceAdminReceiver() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        onPasswordFailedInternal(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        onPasswordFailedInternal(context)
    }

    private fun onPasswordFailedInternal(ctx: Context) {
        val prefs = Preferences(ctx)
        if (!prefs.isServiceEnabled || prefs.maxFailedPasswordAttempts == 0) return
        val admin = DeviceAdminManager(ctx)
        if (admin.getCurrentFailedPasswordAttempts() >= prefs.maxFailedPasswordAttempts)
            admin.wipeData()
    }
}
