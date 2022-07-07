package me.lucky.wasted.trigger.lock

import android.app.job.JobParameters
import android.app.job.JobService

import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger

class LockJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = Preferences.new(this)
        if (!prefs.isEnabled || prefs.triggers.and(Trigger.LOCK.value) == 0) return false
        val admin = DeviceAdminManager(this)
        try {
            admin.lockNow()
            if (prefs.isWipeData) admin.wipeData()
        } catch (exc: SecurityException) {}
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean { return true }
}