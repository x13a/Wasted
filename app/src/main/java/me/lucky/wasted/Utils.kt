package me.lucky.wasted
import me.lucky.wasted.Preferences


import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.trigger.notification.NotificationListener
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

    private val prefs by lazy { Preferences.new(ctx) }

    fun setEnabled(enabled: Boolean) {
        val triggers = prefs.triggers
        setPanicKitEnabled(enabled && triggers.and(Trigger.PANIC_KIT.value) != 0)
        setTileEnabled(enabled && triggers.and(Trigger.TILE.value) != 0)
        setShortcutEnabled(enabled && triggers.and(Trigger.SHORTCUT.value) != 0)
        setBroadcastEnabled(enabled && triggers.and(Trigger.BROADCAST.value) != 0)
        setNotificationEnabled(enabled && triggers.and(Trigger.NOTIFICATION.value) != 0)
        updateForegroundRequiredEnabled()
        updateApplicationEnabled()
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
        val shortcut = ShortcutManager(ctx)
        if (!enabled) shortcut.remove()
        setComponentEnabled(ShortcutActivity::class.java, enabled)
        if (enabled) shortcut.push()
    }

    fun setBroadcastEnabled(enabled: Boolean) =
        setComponentEnabled(TriggerReceiver::class.java, enabled)

    fun setNotificationEnabled(enabled: Boolean) =
        setComponentEnabled(NotificationListener::class.java, enabled)


    fun updateApplicationEnabled() {
        val prefix = "${ctx.packageName}.trigger.application"
        val options = prefs.triggerApplicationOptions
        val enabled = prefs.isEnabled && prefs.triggers.and(Trigger.APPLICATION.value) != 0
        setComponentEnabled(
            "$prefix.SignalActivity",
            enabled && options.and(ApplicationOption.SIGNAL.value) != 0,
        )
        setComponentEnabled(
            "$prefix.TelegramActivity",
            enabled && options.and(ApplicationOption.TELEGRAM.value) != 0,
        )
        setComponentEnabled(
            "$prefix.ThreemaActivity",
            enabled && options.and(ApplicationOption.THREEMA.value) != 0,
        )
        setComponentEnabled(
            "$prefix.SessionActivity",
            enabled && options.and(ApplicationOption.SESSION.value) != 0,
        )
    }

    fun updateForegroundRequiredEnabled() {
        val enabled = prefs.isEnabled
        val triggers = prefs.triggers
        val isUSB = triggers.and(Trigger.USB.value) != 0
        val foregroundEnabled = enabled && (triggers.and(Trigger.LOCK.value) != 0 || isUSB)
        setForegroundEnabled(foregroundEnabled)
        setComponentEnabled(RestartReceiver::class.java, foregroundEnabled)
        setComponentEnabled(UsbReceiver::class.java, enabled && isUSB)
    }

    private fun setForegroundEnabled(enabled: Boolean) =
        Intent(ctx.applicationContext, ForegroundService::class.java).also {
            if (enabled) ContextCompat.startForegroundService(ctx.applicationContext, it)
            else ctx.stopService(it)
        }

    private fun setComponentEnabled(cls: Class<*>, enabled: Boolean) =
        setComponentEnabled(ComponentName(ctx, cls), enabled)

    private fun setComponentEnabled(cls: String, enabled: Boolean) =
        setComponentEnabled(ComponentName(ctx, cls), enabled)

    private fun setComponentEnabled(componentName: ComponentName, enabled: Boolean) =
        ctx.packageManager.setComponentEnabledSetting(
            componentName,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )

    fun fire(trigger: Trigger, safe: Boolean = true) {
        Log.i("Utils", "🔥 fire() appelé avec trigger = ${trigger.name}, safe = $safe")

        if (!prefs.isEnabled || prefs.triggers.and(trigger.value) == 0) {
            Log.w("Utils", "❌ fire() annulé : prefs.isEnabled=${prefs.isEnabled}, trigger=${trigger.name} non activé")
            return
        }

        val admin = DeviceAdminManager(ctx)
        try {
            Log.i("Utils", "🔒 Appel admin.lockNow()")
            admin.lockNow()
            if (prefs.isWipeData && safe) {
                Log.i("Utils", "💥 Appel admin.wipeData()")
                admin.wipeData()
            }
        } catch (exc: SecurityException) {
            Log.e("Utils", "❌ SecurityException lors de lockNow() ou wipeData()", exc)
        }

        if (prefs.isRecastEnabled && safe) {
            Log.i("Utils", "📡 Appel recast()")
            recast()
        }
    }


    fun isDeviceLocked() = ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked

    private fun recast() {
        val action = prefs.recastAction
        if (action.isEmpty()) return
        ctx.sendBroadcast(Intent(action).apply {
            val cls = prefs.recastReceiver.split('/')
            val packageName = cls.firstOrNull() ?: ""
            if (packageName.isNotEmpty()) {
                setPackage(packageName)
                if (cls.size == 2)
                    setClassName(
                        packageName,
                        "$packageName.${cls[1].trimStart('.')}",
                    )
            }
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            val extraKey = prefs.recastExtraKey
            if (extraKey.isNotEmpty()) putExtra(extraKey, prefs.recastExtraValue)
        })
    }
}