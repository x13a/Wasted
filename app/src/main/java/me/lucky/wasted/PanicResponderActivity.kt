package me.lucky.wasted

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import info.guardianproject.panic.Panic
import info.guardianproject.panic.PanicResponder

class PanicResponderActivity : AppCompatActivity() {
    private val prefs by lazy { Preferences(this) }
    private val admin by lazy { DeviceAdmin(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Panic.isTriggerIntent(intent) || !prefs.isServiceEnabled) {
            finish()
            return
        }
        try {
            admin.dpm.lockNow()
            if (PanicResponder.receivedTriggerFromConnectedApp(this) &&
                prefs.doWipe) admin.wipeData()
        } catch (exc: SecurityException) {}
        finish()
    }
}
