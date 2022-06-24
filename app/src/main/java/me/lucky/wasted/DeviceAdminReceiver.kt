package me.lucky.wasted

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        if (Preferences(context).isEnabled)
            Toast.makeText(context, R.string.service_unavailable_popup, Toast.LENGTH_SHORT).show()
    }
}
