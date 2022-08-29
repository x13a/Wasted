package me.lucky.wasted.trigger.notification

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils

class NotificationListenerService : NotificationListenerService() {
    private lateinit var prefs: Preferences
    private lateinit var utils: Utils

    override fun onCreate() {
        super.onCreate()
        init()
    }

    private fun init() {
        prefs = Preferences.new(this)
        utils = Utils(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val secret = prefs.secret
        assert(secret.isNotEmpty())
        if (sbn.notification.extras[Notification.EXTRA_TEXT]?.toString()?.trim() != secret) return
        cancelAllNotifications()
        utils.fire(Trigger.NOTIFICATION)
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