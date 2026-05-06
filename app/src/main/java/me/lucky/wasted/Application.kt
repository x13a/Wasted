package me.lucky.wasted

import android.app.Application
import com.google.android.material.color.DynamicColors

import me.lucky.wasted.shizuku.ShizukuManager

class Application : Application() {

    companion object {
        /**
         * App-wide ShizukuManager singleton. Initialized in onCreate() before any component starts.
         * Access safely: `(context.applicationContext as? Application)?.shizuku`
         */
        lateinit var shizuku: ShizukuManager
            private set
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        shizuku = ShizukuManager(this)
        shizuku.init() // registers Shizuku listeners; binds shell if already running
    }
}