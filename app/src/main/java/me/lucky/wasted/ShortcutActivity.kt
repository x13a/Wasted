package me.lucky.wasted

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ShortcutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Preferences(this).triggers.and(Trigger.SHORTCUT.value) == 0) {
            finishAndRemoveTask()
            return
        }
        CodeReceiver.panic(this, intent)
        finishAndRemoveTask()
    }
}
