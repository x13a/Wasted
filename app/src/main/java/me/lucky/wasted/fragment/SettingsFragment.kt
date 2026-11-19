package me.lucky.wasted.fragment

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.widget.CompoundButton
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.Trigger
import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.databinding.FragmentSettingsBinding
import me.lucky.wasted.settings.LocalSettingsApplier

class SettingsFragment : Fragment() {
    private lateinit var binding: FragmentSettingsBinding
    private lateinit var ctx: Context
    private lateinit var prefs: Preferences
    private lateinit var prefsdb: Preferences
    private val applier by lazy { LocalSettingsApplier.create(ctx) }
    private val admin by lazy { DeviceAdminManager(ctx) }
    private var applyingRemote = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefs.copyTo(prefsdb, key)
        if (key == "triggers") {
            binding.root.post { renderTriggers() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSettingsBinding.inflate(inflater, container, false)
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
        binding.biometricUnlock.isChecked = prefs.isBiometricUnlockEnabled
        renderTriggers()
    }

    private fun renderTriggers() {
        applyingRemote = true
        binding.apply {
            val triggers = prefs.triggers
            panicKit.isChecked = triggers.and(Trigger.PANIC_KIT.value) != 0
            tile.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            tile.isChecked = triggers.and(Trigger.TILE.value) != 0
            shortcut.isChecked = triggers.and(Trigger.SHORTCUT.value) != 0
            broadcast.isChecked = triggers.and(Trigger.BROADCAST.value) != 0
            notification.isChecked = triggers.and(Trigger.NOTIFICATION.value) != 0
            lock.isChecked = triggers.and(Trigger.LOCK.value) != 0
            usb.isChecked = triggers.and(Trigger.USB.value) != 0
            application.isChecked = triggers.and(Trigger.APPLICATION.value) != 0
        }
        applyingRemote = false
    }

    private fun confirmLockNow() {
        if (!admin.isActive()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.lock_now_confirm_title)
                .setMessage(R.string.lock_now_admin_required)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lock_now_confirm_title)
            .setMessage(R.string.lock_now_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Continue") { _, _ -> confirmLockNowAgain() }
            .show()
    }

    private fun confirmLockNowAgain() {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lock_now_confirm_again_title)
            .setMessage(R.string.lock_now_confirm_again_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lock_now) { _, _ ->
                runCatching { admin.lockNow() }.onFailure {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.lock_now_confirm_title)
                        .setMessage(R.string.lock_now_admin_required)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .show()
    }

    private fun setup() = binding.apply {
        lockNow.setOnClickListener { confirmLockNow() }
        biometricUnlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.isBiometricUnlockEnabled = isChecked
        }
        val triggerViews = listOf<Pair<CompoundButton, Trigger>>(
            panicKit to Trigger.PANIC_KIT,
            tile to Trigger.TILE,
            shortcut to Trigger.SHORTCUT,
            broadcast to Trigger.BROADCAST,
            notification to Trigger.NOTIFICATION,
            lock to Trigger.LOCK,
            usb to Trigger.USB,
            application to Trigger.APPLICATION,
        )
        for ((view, trigger) in triggerViews) {
            view.setOnCheckedChangeListener { _, isChecked ->
                if (!applyingRemote) applier.setTrigger(trigger, isChecked)
            }
        }
    }
}
