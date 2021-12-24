package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import info.guardianproject.panic.Panic
import info.guardianproject.panic.PanicResponder

class PanicResponderActivity : AppCompatActivity() {
    private val prefs by lazy { Preferences(this) }
    private val dpm by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Panic.isTriggerIntent(intent) || !prefs.isServiceEnabled) {
            finish()
            return
        }
        try {
            dpm.lockNow()
            if (PanicResponder.receivedTriggerFromConnectedApp(this) &&
                prefs.doWipe) dpm.wipeData(Utils.getWipeDataFlags())
        } catch (exc: SecurityException) {}
        finish()
    }
}
