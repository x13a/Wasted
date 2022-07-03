package me.lucky.wasted

import android.app.job.JobParameters
import android.app.job.JobService

class WipeJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = Preferences.new(this)
        if (!prefs.isEnabled || !prefs.isWipeOnInactivity) return false
        try {
            DeviceAdminManager(this).wipeData()
        } catch (exc: SecurityException) {}
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}
