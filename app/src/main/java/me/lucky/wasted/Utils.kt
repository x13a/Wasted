package me.lucky.wasted

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

import me.lucky.wasted.trigger.notification.NotificationListenerService
import me.lucky.wasted.trigger.panic.PanicConnectionActivity
import me.lucky.wasted.trigger.panic.PanicResponderActivity
import me.lucky.wasted.trigger.shared.ForegroundService
import me.lucky.wasted.trigger.shared.RestartReceiver
import me.lucky.wasted.trigger.shortcut.ShortcutActivity
import me.lucky.wasted.trigger.shortcut.ShortcutManager
import me.lucky.wasted.trigger.tile.TileService
import me.lucky.wasted.trigger.usb.UsbReceiver

class Utils(private val ctx: Context) {
    companion object {
        fun setFlag(key: Int, value: Int, enabled: Boolean) =
            when(enabled) {
                true -> key.or(value)
                false -> key.and(value.inv())
            }
    }

    private val shortcut by lazy { ShortcutManager(ctx) }

    fun setEnabled(enabled: Boolean) {
        val triggers = Preferences(ctx).triggers
        setPanicKitEnabled(enabled && triggers.and(Trigger.PANIC_KIT.value) != 0)
        setTileEnabled(enabled && triggers.and(Trigger.TILE.value) != 0)
        setShortcutEnabled(enabled && triggers.and(Trigger.SHORTCUT.value) != 0)
        setBroadcastEnabled(enabled && triggers.and(Trigger.BROADCAST.value) != 0)
        setNotificationEnabled(enabled && triggers.and(Trigger.NOTIFICATION.value) != 0)
        updateForegroundRequiredEnabled()
    }

    fun setPanicKitEnabled(enabled: Boolean) {
        setComponentEnabled(PanicConnectionActivity::class.java, enabled)
        setComponentEnabled(PanicResponderActivity::class.java, enabled)
    }

    fun setTileEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            setComponentEnabled(TileService::class.java, enabled)
    }

    fun setShortcutEnabled(enabled: Boolean) {
        if (!enabled) shortcut.remove()
        setComponentEnabled(ShortcutActivity::class.java, enabled)
        if (enabled) shortcut.push()
    }

    fun setBroadcastEnabled(enabled: Boolean) =
        setComponentEnabled(TriggerReceiver::class.java, enabled)

    fun setNotificationEnabled(enabled: Boolean) =
        setComponentEnabled(NotificationListenerService::class.java, enabled)

    fun updateForegroundRequiredEnabled() {
        val prefs = Preferences(ctx)
        val enabled = prefs.isEnabled
        val triggers = prefs.triggers
        val isLock = triggers.and(Trigger.LOCK.value) != 0
        val isUSB = triggers.and(Trigger.USB.value) != 0
        setForegroundEnabled(enabled && (isLock || isUSB))
        setComponentEnabled(RestartReceiver::class.java, enabled && (isLock || isUSB))
        setComponentEnabled(UsbReceiver::class.java, enabled && isUSB)
    }

    private fun setForegroundEnabled(enabled: Boolean) =
        Intent(ctx.applicationContext, ForegroundService::class.java).also {
            if (enabled) ContextCompat.startForegroundService(ctx.applicationContext, it)
            else ctx.stopService(it)
        }

    private fun setComponentEnabled(cls: Class<*>, enabled: Boolean) =
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, cls),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
}