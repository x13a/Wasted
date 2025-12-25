package me.lucky.wasted.trigger.broadcast
import me.lucky.wasted.Preferences

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils

class BroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val KEY = "code"
        const val ACTION = "me.lucky.wasted.action.TRIGGER"

        fun panic(context: Context, intent: Intent?, trigger: Trigger) {
            if (intent?.action != ACTION) {
                Log.d("BroadcastReceiver", "Action incorrecte : ${intent?.action}")
                return
            }

            val secret = Preferences.new(context).notificationKeyword
            val receivedCode = intent.getStringExtra(KEY)?.trim()

            Log.d("BroadcastReceiver", "Code reçu : $receivedCode, Secret attendu : $secret")

            if (secret.isEmpty()) {
                Log.w("BroadcastReceiver", "Aucun mot-clé défini dans les préférences.")
                return
            }

            if (receivedCode != secret) {
                Log.w("BroadcastReceiver", "Code incorrect.")
                Toast.makeText(context, "Code incorrect", Toast.LENGTH_SHORT).show()
                return
            }

            Toast.makeText(context, "Déclenchement accepté !", Toast.LENGTH_LONG).show()
            Log.i("BroadcastReceiver", "Code correct, exécution du déclenchement.")
            Utils(context).fire(trigger)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(KEY)
        if (!code.isNullOrBlank()) {
            val prefs = Preferences.new(context)
            prefs.notificationKeyword = code.trim()
            Log.d("BroadcastReceiver", "Mot-clé de notification mis à jour : $code")
        }
    }
}
