package me.lucky.wasted.trigger.voice


import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import me.lucky.wasted.DeviceController
import java.util.Locale

class VoiceTestActivity : AppCompatActivity() {

    private val secretKeyword = "meidé meidé" // Mot clé à détecter

    private lateinit var speechLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise le launcher
        speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spokenText = results?.getOrNull(0)?.lowercase()?.trim()

                if (!spokenText.isNullOrEmpty()) {
                    if (spokenText.contains(secretKeyword.lowercase())) {
                        Toast.makeText(this, "Mot-clé détecté, réinitialisation...", Toast.LENGTH_LONG).show()
                        DeviceController.reset(this)
                    } else {
                        Toast.makeText(this, "Mot non reconnu : \"$spokenText\"", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Aucune entrée vocale détectée", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Reconnaissance annulée ou échouée", Toast.LENGTH_SHORT).show()
            }

            finish() // Ferme l'activité après le traitement
        }

        launchVoiceRecognition()
    }

    private fun launchVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dites votre mot-clé...")
        }

        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur : reconnaissance vocale non supportée", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
