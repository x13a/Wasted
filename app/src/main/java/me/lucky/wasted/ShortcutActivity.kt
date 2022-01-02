package me.lucky.wasted

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ShortcutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CodeReceiver.panic(this, intent)
        finishAndRemoveTask()
    }
}
