package me.lucky.wasted.trigger.shared
import me.lucky.wasted.Preferences


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

import me.lucky.wasted.Trigger
import me.lucky.wasted.trigger.voice.VoiceRecognitionService

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val ctx = context ?: return
        val prefs = Preferences.new(ctx)
        val triggers = prefs.triggers

        if (!prefs.isEnabled) return

        // Démarrer ForegroundService si USB ou LOCK activé
        if ((triggers and Trigger.LOCK.value) != 0 || (triggers and Trigger.USB.value) != 0) {
            ContextCompat.startForegroundService(
                ctx.applicationContext,
                Intent(ctx.applicationContext, ForegroundService::class.java),
            )
        }

        // Démarrer VoiceRecognitionService si VOICE activé
        if ((triggers and Trigger.VOICE.value) != 0) {
            ContextCompat.startForegroundService(
                ctx.applicationContext,
                Intent(ctx.applicationContext, VoiceRecognitionService::class.java),
            )
        }
    }
}
