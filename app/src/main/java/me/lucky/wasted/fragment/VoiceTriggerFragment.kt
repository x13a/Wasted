package me.lucky.wasted.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import me.lucky.wasted.R
import me.lucky.wasted.voice.SpeechService
import android.content.Intent


class VoiceTriggerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_voice_trigger, container, false)

        val voiceButton = view.findViewById<Button>(R.id.btn_trigger_voice)
        voiceButton.setOnClickListener {
            context?.let { ctx ->
                val intent = Intent(ctx, SpeechService::class.java)
                ctx.startService(intent)
            }
        }

        return view
    }
}
