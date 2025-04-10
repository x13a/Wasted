package me.lucky.wasted.trigger.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import me.lucky.wasted.Preferences
import me.lucky.wasted.Utils
import me.lucky.wasted.Trigger

class NotificationListener : NotificationListenerService() {

    private lateinit var prefs: Preferences

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences.new(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!prefs.isEnabled || prefs.triggers.and(Trigger.NOTIFICATION.value) == 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()?.lowercase() ?: ""
        val text = extras.getCharSequence("android.text")?.toString()?.lowercase() ?: ""
        val keyword = prefs.recastExtraValue.lowercase().trim() // Utilisation de recastExtraValue ici

        Log.d("NotificationListener", "Notification de ${sbn.packageName} : \"$title $text\"")
        Log.d("NotificationListener", "Mot-clé attendu : \"$keyword\"")

        if (title.contains(keyword) || text.contains(keyword)) {
            Log.w("NotificationListener", "🔐 Mot-clé détecté dans la notification")
            Utils(this).fire(Trigger.NOTIFICATION)
        }
    }
}
