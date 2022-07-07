package me.lucky.wasted.trigger.usb

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager

import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.admin.DeviceAdminManager

class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val prefs = Preferences.new(context ?: return)
        if (!prefs.isEnabled ||
            prefs.triggers.and(Trigger.USB.value) == 0 ||
            !context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        val admin = DeviceAdminManager(context)
        try {
            admin.lockNow()
            if (prefs.isWipeData) admin.wipeData()
        } catch (exc: SecurityException) {}
    }
}