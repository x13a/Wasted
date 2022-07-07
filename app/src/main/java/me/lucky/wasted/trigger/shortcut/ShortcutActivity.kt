package me.lucky.wasted.trigger.shortcut

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.trigger.broadcast.BroadcastReceiver

class ShortcutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Preferences(this).triggers.and(Trigger.SHORTCUT.value) == 0) {
            finishAndRemoveTask()
            return
        }
        BroadcastReceiver.panic(this, intent)
        finishAndRemoveTask()
    }
}