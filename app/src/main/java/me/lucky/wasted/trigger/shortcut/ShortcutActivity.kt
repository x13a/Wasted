package me.lucky.wasted.trigger.shortcut

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import me.lucky.wasted.Trigger
import me.lucky.wasted.trigger.broadcast.BroadcastReceiver

class ShortcutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BroadcastReceiver.panic(this, intent, Trigger.SHORTCUT)
        finishAndRemoveTask()
    }
}