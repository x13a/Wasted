package me.lucky.wasted.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import java.io.*

class VoiceTriggerFragment : Fragment() {

    private lateinit var switch: Switch
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var txtStatus: TextView

    private var isRecording = false
    private var recorder: AudioRecord? = null
    private var recordingJob: Job? = null

    private val outputFile: File
        get() = File(requireContext().filesDir, "voice_keyword.wav")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?

    ): View = inflater.inflate(R.layout.fragment_voice_trigger, container, false)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        switch = view.findViewById(R.id.switch_enable_speech)
        btnStart = view.findViewById(R.id.btn_record_keyword)
        btnStop = view.findViewById(R.id.btn_stop_recording)
        txtStatus = view.findViewById(R.id.txt_voice_status)

        val prefs = Preferences(requireContext())
        switch.isChecked = prefs.isVoiceDetectionEnabled

        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.isVoiceDetectionEnabled = isChecked

        }

        btnStart.setOnClickListener {
            startRecording()
        }

        btnStop.setOnClickListener {
            stopRecording()
        }
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        recorder?.startRecording()

        isRecording = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        txtStatus.text = "🔴 Enregistrement en cours..."

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val data = ByteArray(bufferSize)
            val pcmFile = File.createTempFile("keyword_pcm", ".raw", requireContext().cacheDir)
            FileOutputStream(pcmFile).use { out ->
                while (isRecording) {
                    val read = recorder?.read(data, 0, data.size) ?: 0
                    if (read > 0) out.write(data, 0, read)
                }
            }
            withContext(Dispatchers.Main) {
                txtStatus.text = "✅ Enregistrement terminé"
                convertToWav(pcmFile, outputFile, sampleRate)
                Toast.makeText(requireContext(), "🎤 Mot-clé vocal sauvegardé. C’est enregistré, chef !", Toast.LENGTH_SHORT).show()

            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recorder?.stop()
        recorder?.release()
        recorder = null
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        txtStatus.text = "⏹️ Arrêté"
    }

    private fun convertToWav(pcmFile: File, wavFile: File, sampleRate: Int) {
        val pcmData = pcmFile.readBytes()
        val totalDataLen = pcmData.size + 36
        val byteRate = 16 * sampleRate / 8

        DataOutputStream(FileOutputStream(wavFile)).use { out ->
            out.writeBytes("RIFF")
            out.writeInt(Integer.reverseBytes(totalDataLen))
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeInt(Integer.reverseBytes(16))
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // PCM
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // Mono
            out.writeInt(Integer.reverseBytes(sampleRate))
            out.writeInt(Integer.reverseBytes(byteRate))
            out.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
            out.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            out.writeBytes("data")
            out.writeInt(Integer.reverseBytes(pcmData.size))
            out.write(pcmData)
        }
    }
}
