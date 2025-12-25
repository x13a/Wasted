package me.lucky.wasted.trigger.panic
import me.lucky.wasted.Preferences


import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import info.guardianproject.panic.Panic
import info.guardianproject.panic.PanicResponder
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils

class PanicResponderActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Panic.isTriggerIntent(intent)) {
            finishAndRemoveTask()
            return
        }
        Utils(this).fire(
            Trigger.PANIC_KIT,
            PanicResponder.receivedTriggerFromConnectedApp(this),
        )
        finishAndRemoveTask()
    }
}