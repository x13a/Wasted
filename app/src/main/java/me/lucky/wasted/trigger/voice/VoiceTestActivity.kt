package me.lucky.wasted.trigger.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import me.lucky.wasted.DeviceController

class VoiceTestActivity : AppCompatActivity() {

    private val REQUEST_CODE_VOICE = 1001
    private val secretKeyword = "meidé meidé" // Mot clé que tu veux détecter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchVoiceRecognition()
    }

    private fun launchVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dites votre mot-clé...")
        try {
            startActivityForResult(intent, REQUEST_CODE_VOICE)
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur : reconnaissance vocale non supportée", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VOICE && resultCode == Activity.RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.getOrNull(0)?.lowercase() ?: ""

            if (spokenText.contains(secretKeyword.lowercase())) {
                Toast.makeText(this, "Mot-clé détecté, réinitialisation...", Toast.LENGTH_LONG).show()
                DeviceController.reset(this) // appelle la réinitialisation
            } else {
                Toast.makeText(this, "Mot non reconnu", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
