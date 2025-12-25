package me.lucky.wasted.trigger.lock
import me.lucky.wasted.Preferences


import android.app.job.JobParameters
import android.app.job.JobService

import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils

class LockJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Utils(this).fire(Trigger.LOCK)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean { return true }
}