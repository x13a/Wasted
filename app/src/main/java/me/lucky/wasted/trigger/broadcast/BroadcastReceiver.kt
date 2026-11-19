package me.lucky.wasted.trigger.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils

class BroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val KEY = "code"
        const val ACTION = "me.lucky.wasted.action.TRIGGER"

        fun panic(context: Context, intent: Intent?, trigger: Trigger) {
            if (intent?.action != ACTION) return
            val secret = Preferences.new(context).secret
            // Fail closed: an empty stored secret must never match an empty/absent extra,
            // otherwise any app could fire a lock/wipe broadcast. `assert` is a no-op in
            // release builds, so this is an explicit guard.
            if (secret.isEmpty()) return
            if (intent.getStringExtra(KEY)?.trim() != secret) return
            Utils(context).fire(trigger)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        panic(context ?: return, intent, Trigger.BROADCAST)
    }
}