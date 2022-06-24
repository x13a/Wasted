package me.lucky.wasted

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import info.guardianproject.panic.Panic
import info.guardianproject.panic.PanicResponder

class PanicResponderActivity : AppCompatActivity() {
    private val prefs by lazy { Preferences(this) }
    private val admin by lazy { DeviceAdminManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Panic.isTriggerIntent(intent) ||
            !prefs.isEnabled ||
            prefs.triggers.and(Trigger.PANIC_KIT.value) == 0)
        {
            finishAndRemoveTask()
            return
        }
        try {
            admin.lockNow()
            if (PanicResponder.receivedTriggerFromConnectedApp(this) &&
                prefs.isWipeData) admin.wipeData()
        } catch (exc: SecurityException) {}
        finishAndRemoveTask()
    }
}
