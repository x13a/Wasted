package me.lucky.wasted.trigger.application

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.admin.DeviceAdminManager

class ApplicationActivity : AppCompatActivity() {
    private val prefs by lazy { Preferences(this) }
    private val admin by lazy { DeviceAdminManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.isEnabled || prefs.triggers.and(Trigger.APPLICATION.value) == 0) {
            finishAndRemoveTask()
            return
        }
        try {
            admin.lockNow()
            if (prefs.isWipeData) admin.wipeData()
        } catch (exc: SecurityException) {}
        finishAndRemoveTask()
    }
}