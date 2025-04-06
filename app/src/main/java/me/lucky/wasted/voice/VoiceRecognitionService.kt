package me.lucky.wasted.trigger.voice

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import me.lucky.wasted.util.DeviceResetHelper
import android.os.Bundle


class VoiceRecognitionService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private val secretKeyword = "meidé meidé" // À remplacer par une variable si tu veux qu'il soit configurable

    override fun onCreate() {
        super.onCreate()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.let {
                    for (result in it) {
                        Log.d("VoiceService", "Heard: $result")
                        if (result.lowercase().contains(secretKeyword.lowercase())) {
                            Log.d("VoiceService", "Secret keyword detected!")
                            DeviceResetHelper.resetDevice(applicationContext)
                            break
                        }
                    }
                }
                restartListening()
            }

            override fun onError(error: Int) {
                Log.e("VoiceService", "Error: $error")
                restartListening()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    private fun startListening() {
        speechRecognizer?.startListening(recognizerIntent)
    }

    private fun restartListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        startListening()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
