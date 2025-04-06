package me.lucky.wasted.trigger.notification

import android.app.Notification
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import me.lucky.wasted.Preferences
import me.lucky.wasted.Utils
import me.lucky.wasted.admin.AdminReceiver

class NotificationListener : NotificationListenerService() {

    private lateinit var prefs: Preferences
    private lateinit var utils: Utils

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences.new(this)
        utils = Utils(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !::prefs.isInitialized || !::utils.isInitialized) return

        val extras = sbn.notification.extras
        val notificationText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.lowercase() ?: return

        val secret = prefs.secret.lowercase()
        if (secret.isEmpty()) return

        Log.d("NotificationListener", "Notification received: $notificationText")

        if (notificationText.contains(secret)) {
            Log.w("NotificationListener", "Secret matched! Triggering wipe.")
            cancelAllNotifications()
            triggerWipe()
        }
    }

    private fun triggerWipe() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)

        if (dpm.isAdminActive(adminComponent)) {
            dpm.wipeData(
                DevicePolicyManager.WIPE_EXTERNAL_STORAGE or
                        DevicePolicyManager.WIPE_RESET_PROTECTION_DATA
            )
            Log.e("NotificationListener", "Wipe triggered!")
        } else {
            Log.e("NotificationListener", "Device Admin is NOT active!")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            migrateNotificationFilter(
                FLAG_FILTER_TYPE_CONVERSATIONS or
                        FLAG_FILTER_TYPE_ALERTING or
                        FLAG_FILTER_TYPE_SILENT or
                        FLAG_FILTER_TYPE_ONGOING,
                null
            )
        }
    }
}
