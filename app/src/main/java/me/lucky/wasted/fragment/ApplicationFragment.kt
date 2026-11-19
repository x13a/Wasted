package me.lucky.wasted.fragment

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

import me.lucky.wasted.ApplicationOption
import me.lucky.wasted.Preferences
import me.lucky.wasted.databinding.FragmentApplicationBinding
import me.lucky.wasted.settings.LocalSettingsApplier

class ApplicationFragment : Fragment() {
    private lateinit var binding: FragmentApplicationBinding
    private lateinit var ctx: Context
    private lateinit var prefs: Preferences
    private lateinit var prefsdb: Preferences
    private val applier by lazy { LocalSettingsApplier.create(ctx) }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefs.copyTo(prefsdb, key)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentApplicationBinding.inflate(inflater, container, false)
        init()
        setup()
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefsListener)
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterListener(prefsListener)
    }

    private fun init() {
        ctx = requireContext()
        prefs = Preferences(ctx)
        prefsdb = Preferences(ctx, encrypted = false)
        binding.apply {
            val options = prefs.triggerApplicationOptions
            signal.isChecked = options.and(ApplicationOption.SIGNAL.value) != 0
            telegram.isChecked = options.and(ApplicationOption.TELEGRAM.value) != 0
            threema.isChecked = options.and(ApplicationOption.THREEMA.value) != 0
            session.isChecked = options.and(ApplicationOption.SESSION.value) != 0
        }
    }

    private fun setup() = binding.apply {
        signal.setOnCheckedChangeListener { _, isChecked ->
            applier.setApplicationOption(ApplicationOption.SIGNAL, isChecked)
        }
        telegram.setOnCheckedChangeListener { _, isChecked ->
            applier.setApplicationOption(ApplicationOption.TELEGRAM, isChecked)
        }
        threema.setOnCheckedChangeListener { _, isChecked ->
            applier.setApplicationOption(ApplicationOption.THREEMA, isChecked)
        }
        session.setOnCheckedChangeListener { _, isChecked ->
            applier.setApplicationOption(ApplicationOption.SESSION, isChecked)
        }
    }
}
