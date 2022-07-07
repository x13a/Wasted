package me.lucky.wasted

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        me.lucky.wasted.trigger.broadcast.BroadcastReceiver().onReceive(context, intent)
    }
}