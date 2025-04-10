package me.lucky.wasted.voice

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.admin.AdminReceiver
import android.content.pm.ServiceInfo
import androidx.annotation.RequiresApi

class SpeechService : Service(), RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var prefs: Preferences
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var keyword: String

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences.new(this)
        keyword = prefs.voiceKeyword.lowercase().ifEmpty { "meidé meidé" }

        acquireWakeLock()
        startForegroundNotification()
        startListening()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wasted::SpeechWakeLock")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire()
        Log.d("SpeechService", "🔋 WakeLock acquis")
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            Log.d("SpeechService", "🔋 WakeLock libéré")
        }
    }

    private fun startForegroundNotification() {
        val channelId = "vosk_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Détection Vosk",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wasted écoute (Vosk)")
            .setContentText("En attente du mot-clé secret...")
            .setSmallIcon(R.drawable.ic_mic_none) // veille à ce que ce soit bien présent
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1002,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(1002, notification)
        }
    }





    private fun startListening() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        speechRecognizer?.startListening(intent)
        Log.d("SpeechService", "🎙️ Écoute démarrée")
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val spokenText = matches?.firstOrNull()?.lowercase()?.trim()

        Log.d("SpeechService", "🗣️ Reçu : $spokenText | Attendu : $keyword")

        if (!spokenText.isNullOrEmpty() && spokenText.contains(keyword)) {
            Log.w("SpeechService", "✅ Mot-clé détecté : déclenchement")
            triggerWipe()
        } else {
            startListening()
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
            Log.e("SpeechService", "🔥 Téléphone réinitialisé")
        } else {
            Log.e("SpeechService", "❌ Admin non actif")
        }
    }

    override fun onError(error: Int) {
        Log.e("SpeechService", "⚠️ Erreur ($error), reprise")
        startListening()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onDestroy() {
        speechRecognizer?.destroy()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        Log.d("SpeechService", "🧹 Service arrêté proprement")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
