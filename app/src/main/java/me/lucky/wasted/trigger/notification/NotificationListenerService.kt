package me.lucky.wasted.trigger.notification

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger

class NotificationListenerService : NotificationListenerService() {
    private lateinit var prefs: Preferences
    private lateinit var admin: DeviceAdminManager

    override fun onCreate() {
        super.onCreate()
        init()
    }

    private fun init() {
        prefs = Preferences.new(this)
        admin = DeviceAdminManager(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null ||
            !prefs.isEnabled ||
            prefs.triggers.and(Trigger.NOTIFICATION.value) == 0) return
        val secret = prefs.secret
        assert(secret.isNotEmpty())
        if (sbn.notification.extras[Notification.EXTRA_TEXT]?.toString() != secret) return
        cancelAllNotifications()
        try {
            admin.lockNow()
            if (prefs.isWipeData) admin.wipeData()
        } catch (exc: SecurityException) {}
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            migrateNotificationFilter(
                FLAG_FILTER_TYPE_CONVERSATIONS
                    or FLAG_FILTER_TYPE_ALERTING
                    or FLAG_FILTER_TYPE_SILENT
                    or FLAG_FILTER_TYPE_ONGOING,
                null,
            )
    }
}