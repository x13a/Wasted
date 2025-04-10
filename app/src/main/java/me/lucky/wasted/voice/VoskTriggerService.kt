package me.lucky.wasted.voice

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.admin.AdminReceiver
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import android.app.admin.DevicePolicyManager



class VoskTriggerService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var prefs: Preferences
    private var speechService: SpeechService? = null
    private var model: Model? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences.new(this)
        acquireWakeLock()
        startForegroundNotification()
        startListening()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wasted::VoskWakeLock")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire()
        Log.d("VoskTriggerService", "🔋 WakeLock acquis")
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            Log.d("VoskTriggerService", "🔋 WakeLock libéré")
        }
    }

    private fun startForegroundNotification() {
        val channelId = "vosk_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Détection Vocale",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Détection Vocale Active")
            .setContentText("Wasted écoute le mot-clé vocal")
            .setSmallIcon(R.drawable.ic_mic_none)
            .build()

        startForeground(1002, notification)
    }

    private fun startListening() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                model = Model("${filesDir.absolutePath}/model")
                val recognizer = Recognizer(model, 16000.0f)

                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        Log.d("VoskTriggerService", "🔎 Résultat partiel : $hypothesis")
                        hypothesis?.let { checkForKeyword(it) }
                    }

                    override fun onResult(hypothesis: String?) {
                        Log.d("VoskTriggerService", "✅ Résultat final : $hypothesis")
                        hypothesis?.let { checkForKeyword(it) }
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        Log.d("VoskTriggerService", "🏁 Résultat final (onFinalResult) : $hypothesis")
                        hypothesis?.let { checkForKeyword(it) }
                    }


                    override fun onError(e: java.lang.Exception?) {
                        Log.e("VoskTriggerService", "❌ Erreur Vosk : ${e?.message}")
                    }

                    override fun onTimeout() {
                        Log.d("VoskTriggerService", "⏳ Timeout Vosk")
                    }
                })
            } catch (e: Exception) {
                Log.e("VoskTriggerService", "❌ Erreur de démarrage : ${e.message}")
            }
        }
    }

    private fun checkForKeyword(text: String) {
        val keyword = prefs.voiceKeyword.lowercase().trim()
        val spokenText = text.lowercase().trim()

        Log.d("VoskTriggerService", "🔑 Mot-clé vocal attendu : \"$keyword\" — Phrase reconnue : \"$spokenText\"")

        if (keyword.isNotEmpty() && spokenText.contains(keyword)) {
            Log.w("VoskTriggerService", "🚨 Mot-clé vocal détecté dans le flux audio !")
            triggerWipe()
        }
    }

    private fun triggerWipe() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)

        if (dpm.isAdminActive(admin)) {
            dpm.wipeData(
                DevicePolicyManager.WIPE_EXTERNAL_STORAGE or
                        DevicePolicyManager.WIPE_RESET_PROTECTION_DATA
            )
            Log.e("VoskTriggerService", "🔥 Téléphone réinitialisé via commande vocale !")
        } else {
            Log.e("VoskTriggerService", "❌ Admin non actif : impossible de réinitialiser")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onDestroy() {
        speechService?.stop()
        speechService = null
        model?.close()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        Log.d("VoskTriggerService", "🧹 Service Vosk arrêté")
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null
}
