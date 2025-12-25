package me.lucky.wasted.boot
import me.lucky.wasted.Preferences


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import me.lucky.wasted.Trigger
import me.lucky.wasted.voice.VoskTriggerService

class BootReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "✅ Boot terminé")

            val prefs = Preferences.new(context)
            if (prefs.isEnabled && prefs.triggers.and(Trigger.VOICE.value) != 0) {
                Log.d("BootReceiver", "🎙️ Démarrage du VoskTriggerService")
                val serviceIntent = Intent(context, VoskTriggerService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                Log.d("BootReceiver", "🔇 Service vocal non activé dans les préférences")
            }
        }
    }
}
