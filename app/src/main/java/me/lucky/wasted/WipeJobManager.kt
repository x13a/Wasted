package me.lucky.wasted

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

class WipeJobManager(private val ctx: Context) {
    companion object {
        private const val JOB_ID = 1000
    }
    private val prefs by lazy { Preferences(ctx) }
    private val jobScheduler by lazy {
        ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    }

    fun schedule(): Int {
        return jobScheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, WipeJobService::class.java))
                .setMinimumLatency(TimeUnit.DAYS.toMillis(prefs.wipeOnInactiveDays.toLong()))
                .setBackoffCriteria(0, JobInfo.BACKOFF_POLICY_LINEAR)
                .setPersisted(true)
                .build()
        )
    }

    fun setState(value: Boolean): Boolean {
        if (value) {
            if (schedule() == JobScheduler.RESULT_FAILURE) return false
        } else { jobScheduler.cancel(JOB_ID) }
        return true
    }
}
