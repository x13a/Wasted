package me.lucky.wasted

import android.app.Application
import com.google.android.material.color.DynamicColors

@Suppress("unused")
class Application : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
