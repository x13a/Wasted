package me.lucky.wasted.remote.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.security.TinkEncryptedSharedPreferences

/**
 * Minimal, device-encrypted policy needed by the direct-boot remote agent.
 * It intentionally contains only the values required to report status and enforce the
 * Debug-Mode gate for a remote wipe before the first user unlock.
 */
class RemoteBootPolicy(private val prefs: SharedPreferences) {
    companion object {
        private const val FILE_NAME = "remote_boot_policy_secure_prefs"
        private const val DEBUG_MODE = "debug_mode"
        private const val LOCK_TRIGGER = "lock_trigger_enabled"
        private const val LOCK_TIMER = "lock_timer_minutes"
        private const val USB_TRIGGER = "usb_trigger_enabled"
        private const val INITIALIZED = "initialized"

        fun new(ctx: Context): RemoteBootPolicy = RemoteBootPolicy(
            TinkEncryptedSharedPreferences.create(
                DirectBootStorage.context(ctx),
                FILE_NAME,
            ),
        )
    }

    var debugMode: Boolean
        get() = runCatching { prefs.getBoolean(DEBUG_MODE, true) }.getOrDefault(true)
        set(value) = prefs.edit().putBoolean(DEBUG_MODE, value).apply()

    var lockTriggerEnabled: Boolean
        get() = runCatching { prefs.getBoolean(LOCK_TRIGGER, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(LOCK_TRIGGER, value).apply()

    var lockTimerMinutes: Int
        get() = runCatching { prefs.getInt(LOCK_TIMER, 10080) }.getOrDefault(10080)
        set(value) = prefs.edit().putInt(LOCK_TIMER, value.coerceIn(1, 525600)).apply()

    var usbTriggerEnabled: Boolean
        get() = runCatching { prefs.getBoolean(USB_TRIGGER, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(USB_TRIGGER, value).apply()

    fun syncFrom(settings: Preferences) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        debugMode = settings.isDebugMode
        lockTriggerEnabled = settings.triggers.and(Trigger.LOCK.value) != 0
        lockTimerMinutes = settings.triggerLockCount
        usbTriggerEnabled = settings.triggers.and(Trigger.USB.value) != 0
        prefs.edit().putBoolean(INITIALIZED, true).apply()
    }

    /** Apply the last direct-boot snapshot once CE storage becomes available. */
    fun applyTo(settings: Preferences) {
        if (!hasSnapshot()) return
        settings.isDebugMode = debugMode
        settings.triggerLockCount = lockTimerMinutes
        var triggers = settings.triggers
        triggers = setFlag(triggers, Trigger.LOCK.value, lockTriggerEnabled)
        triggers = setFlag(triggers, Trigger.USB.value, usbTriggerEnabled)
        settings.triggers = triggers
    }

    fun markSettingsApplied() {
        prefs.edit().putBoolean(INITIALIZED, true).apply()
    }

    fun hasSnapshot(): Boolean = runCatching {
        prefs.getBoolean(INITIALIZED, false)
    }.getOrDefault(false)

    private fun setFlag(mask: Int, flag: Int, enabled: Boolean): Int =
        if (enabled) mask or flag else mask and flag.inv()
}
