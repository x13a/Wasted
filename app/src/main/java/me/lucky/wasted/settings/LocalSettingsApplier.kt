package me.lucky.wasted.settings

import android.content.Context

import me.lucky.wasted.ApplicationOption
import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils
import me.lucky.wasted.p2p.models.DeviceSettingsSnapshot
import me.lucky.wasted.remote.data.RemoteBootPolicy
import me.lucky.wasted.trigger.lock.TimerCoordinator

/**
 * Single write path for local settings shared by the UI, P2P sync, and remote sync.
 * Validates inputs, writes preferences, updates Android components, and
 * reschedules/cancels the lock timer job as needed.
 */
class LocalSettingsApplier(
    private val prefs: Preferences,
    private val utils: Utils,
    private val timer: TimerCoordinator,
    private val bootPolicy: RemoteBootPolicy,
) {
    companion object {
        private const val MAX_LOCK_TIMER_MINUTES = 525_600

        fun create(ctx: Context) = LocalSettingsApplier(
            prefs = Preferences.new(ctx),
            utils = Utils(ctx),
            timer = TimerCoordinator.create(ctx),
            bootPolicy = RemoteBootPolicy.new(ctx),
        )
    }

    fun setMasterEnabled(enabled: Boolean) {
        prefs.isEnabled = enabled
        utils.setEnabled(enabled)
        if (!enabled) timer.cancel()
    }

    fun setWipeData(enabled: Boolean) {
        prefs.isWipeData = enabled
    }

    fun setWipeEmbeddedSim(enabled: Boolean) {
        prefs.isWipeEmbeddedSim = enabled
    }

    fun setTrigger(trigger: Trigger, enabled: Boolean) {
        prefs.triggers = Utils.setFlag(prefs.triggers, trigger.value, enabled)
        when (trigger) {
            Trigger.PANIC_KIT -> utils.setPanicKitEnabled(enabled && prefs.isEnabled)
            Trigger.TILE -> utils.setTileEnabled(enabled && prefs.isEnabled)
            Trigger.SHORTCUT -> utils.setShortcutEnabled(enabled && prefs.isEnabled)
            Trigger.BROADCAST -> utils.setBroadcastEnabled(enabled && prefs.isEnabled)
            Trigger.NOTIFICATION -> utils.setNotificationEnabled(enabled && prefs.isEnabled)
            Trigger.LOCK, Trigger.USB -> utils.updateForegroundRequiredEnabled()
            Trigger.APPLICATION -> utils.updateApplicationEnabled()
        }
        if (trigger == Trigger.LOCK) {
            bootPolicy.lockTriggerEnabled = enabled
            if (!enabled) timer.cancel()
        }
        if (trigger == Trigger.USB) bootPolicy.usbTriggerEnabled = enabled
    }

    fun setLockTimerMinutes(minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, MAX_LOCK_TIMER_MINUTES)
        prefs.triggerLockCount = safeMinutes
        bootPolicy.lockTimerMinutes = safeMinutes
    }

    fun setTileDelay(ms: Long) {
        prefs.triggerTileDelay = ms
    }

    fun setApplicationOption(option: ApplicationOption, enabled: Boolean) {
        prefs.triggerApplicationOptions =
            Utils.setFlag(prefs.triggerApplicationOptions, option.value, enabled)
        utils.updateApplicationEnabled()
    }

    fun setRecastEnabled(enabled: Boolean) {
        prefs.isRecastEnabled = enabled
    }

    fun setRecastAction(action: String) {
        prefs.recastAction = action.trim()
    }

    fun setRecastReceiver(receiver: String) {
        prefs.recastReceiver = receiver.trim()
    }

    fun setRecastExtraKey(key: String) {
        prefs.recastExtraKey = key.trim()
    }

    fun setRecastExtraValue(value: String) {
        prefs.recastExtraValue = value.trim()
    }

    fun setRemoteResetConfirmationEnabled(enabled: Boolean) {
        prefs.remoteResetConfirmationEnabled = enabled
    }

    fun applySnapshot(settings: DeviceSettingsSnapshot) {
        prefs.isEnabled = settings.appEnabled
        prefs.isWipeData = settings.wipeDataEnabled
        prefs.isWipeEmbeddedSim = settings.wipeEmbeddedSimEnabled && settings.wipeDataEnabled
        prefs.remoteResetConfirmationEnabled = settings.remoteResetConfirmationEnabled
        prefs.triggers = settings.triggerMask
        prefs.triggerLockCount =
            (settings.inactivityTimeout / 60_000L).toInt().coerceIn(1, MAX_LOCK_TIMER_MINUTES)
        prefs.triggerTileDelay = settings.tileDelayMs
        prefs.triggerApplicationOptions = settings.applicationOptionsMask
        prefs.isRecastEnabled = settings.recastEnabled
        prefs.recastAction = settings.recastAction
        prefs.recastReceiver = settings.recastReceiver
        prefs.recastExtraKey = settings.recastExtraKey
        prefs.recastExtraValue = settings.recastExtraValue
        utils.setEnabled(settings.appEnabled)
        utils.updateForegroundRequiredEnabled()
        utils.updateApplicationEnabled()
        bootPolicy.syncFrom(prefs)
        if (!settings.appEnabled) timer.cancel()
    }
}
