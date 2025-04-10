package me.lucky.wasted.trigger.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils

class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action

        if (action != "android.hardware.usb.action.USB_STATE") return

        val connected = intent.getBooleanExtra("connected", false)
        val debugging = intent.getBooleanExtra("adb", false)
        val hostConnected = intent.getBooleanExtra("host_connected", false)

        val prefs = Preferences.new(context)
        val utils = Utils(context)

        // Vérifie que le trigger USB est bien activé
        val usbTriggerEnabled = prefs.triggers.and(Trigger.USB.value) != 0

        Log.d("UsbReceiver", "USB connected=$connected, host_connected=$hostConnected, adb=$debugging, triggerEnabled=$usbTriggerEnabled")

        if (!connected || !usbTriggerEnabled) return

        // Ignorer les simples branchements pour recharge uniquement
        if (!hostConnected && !debugging) {
            Log.d("UsbReceiver", "USB branché en recharge uniquement, rien à faire.")
            return
        }

        Toast.makeText(context, "USB non sécurisé détecté ⚠️", Toast.LENGTH_SHORT).show()

        // Si le téléphone est verrouillé, déclenche
        if (utils.isDeviceLocked()) {
            utils.fire(Trigger.USB)
        } else {
            Log.d("UsbReceiver", "Appareil non verrouillé, pas de wipe.")
        }
    }
}
