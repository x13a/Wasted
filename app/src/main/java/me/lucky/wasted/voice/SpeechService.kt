package me.lucky.wasted.voice

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import me.lucky.wasted.admin.AdminReceiver

class SpeechService : Service(), RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("SpeechService", "Service vocal lancé")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        speechRecognizer?.startListening(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val spokenText = matches?.firstOrNull()?.lowercase()
        Log.d("SpeechService", "Reconnu : $spokenText")

        if (spokenText?.contains("meidé meidé") == true) {
            Log.w("SpeechService", "Commande vocale détectée : déclenchement effacement")
            triggerWipe()
        }
    }

    private fun triggerWipe() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)

        if (dpm.isAdminActive(adminComponent)) {
            dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE or DevicePolicyManager.WIPE_RESET_PROTECTION_DATA)
            Log.e("SpeechService", "Effacement déclenché !")
        } else {
            Log.e("SpeechService", "App non active en tant qu'admin")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {
        Log.e("SpeechService", "Erreur reconnaissance : $error")
    }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
