package me.lucky.wasted

import android.app.Application
import android.os.UserManager
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

    private var unlockedInitialized = false

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        shizuku = ShizukuManager(this)
        initializeAfterUnlock()
    }

    fun initializeAfterUnlock() {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return
        if (unlockedInitialized) return
        unlockedInitialized = true
        shizuku.init()
        val preferences = Preferences.new(this)
        preferences.initializeDebugModeIfMissing()
        val policy = me.lucky.wasted.remote.data.RemoteBootPolicy.new(this)
        policy.applyTo(preferences)
        policy.syncFrom(preferences)
    }
}
