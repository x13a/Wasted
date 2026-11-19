package me.lucky.wasted.fragment

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import java.util.regex.Pattern

import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.databinding.FragmentLockBinding
import me.lucky.wasted.settings.LocalSettingsApplier

class LockFragment : Fragment() {
    companion object {
        private const val MODIFIER_DAYS = 'd'
        private const val MODIFIER_HOURS = 'h'
        private const val MODIFIER_MINUTES = 'm'
    }

    private lateinit var binding: FragmentLockBinding
    private lateinit var ctx: Context
    private lateinit var prefs: Preferences
    private lateinit var prefsdb: Preferences
    private val applier by lazy { LocalSettingsApplier.create(ctx) }
    private val lockCountPattern by lazy {
        Pattern.compile("^[1-9]\\d*[$MODIFIER_DAYS$MODIFIER_HOURS$MODIFIER_MINUTES]$") }
    private var applyingRemote = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefs.copyTo(prefsdb, key)
        if (key == "trigger_lock_count") {
            binding.root.post { renderTimer() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentLockBinding.inflate(inflater, container, false)
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
        renderTimer()
    }

    private fun renderTimer() {
        applyingRemote = true
        val count = prefs.triggerLockCount
        binding.time.editText?.setText(when {
            count % (24 * 60) == 0 -> "${count / 24 / 60}$MODIFIER_DAYS"
            count % 60 == 0 -> "${count / 60}$MODIFIER_HOURS"
            else -> "$count$MODIFIER_MINUTES"
        })
        applyingRemote = false
    }

    private fun setup() = binding.apply {
        time.editText?.doAfterTextChanged {
            if (applyingRemote) return@doAfterTextChanged
            val str = it?.toString() ?: ""
            if (!lockCountPattern.matcher(str).matches()) {
                time.error = ctx.getString(R.string.trigger_lock_time_error)
                return@doAfterTextChanged
            }
            if (str.length < 2) return@doAfterTextChanged
            val modifier = str.last()
            val i: Int
            try {
                i = str.dropLast(1).toInt()
            } catch (exc: NumberFormatException) { return@doAfterTextChanged }
            val minutes = when (modifier) {
                MODIFIER_DAYS -> i * 24 * 60
                MODIFIER_HOURS -> i * 60
                MODIFIER_MINUTES -> i
                else -> return@doAfterTextChanged
            }
            applier.setLockTimerMinutes(minutes)
            time.error = null
        }
    }
}
