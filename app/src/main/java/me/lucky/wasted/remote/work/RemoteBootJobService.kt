package me.lucky.wasted.remote.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

import java.util.concurrent.atomic.AtomicBoolean

import me.lucky.wasted.remote.RemoteEngine
import me.lucky.wasted.remote.data.RemoteConfigStore
import me.lucky.wasted.remote.data.RemoteCredentialStore

/**
 * Direct-boot-safe reconciliation path. WorkManager is credential-backed and is not
 * available before first unlock, so the boot path uses a persisted JobScheduler job.
 * The job uses the device-protected remote session/policy and therefore can report status
 * and process lock/wipe commands before unlock.
 */
class RemoteBootJobService : JobService() {
    companion object {
        private const val TAG = "RemoteBootJob"
        private const val JOB_PERIODIC = 0x57415354
        private const val JOB_ONCE = 0x57415355
        private val running = AtomicBoolean(false)

        fun schedule(ctx: Context) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(ctx, RemoteBootJobService::class.java)
            val periodic = JobInfo.Builder(JOB_PERIODIC, component)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .build()
            val once = JobInfo.Builder(JOB_ONCE, component)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            runCatching { scheduler.schedule(periodic) }
                .onFailure { Log.w(TAG, "Could not schedule periodic boot job", it) }
            runCatching { scheduler.schedule(once) }
                .onFailure { Log.w(TAG, "Could not schedule one-shot boot job", it) }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        if (me.lucky.wasted.trigger.shared.ForegroundService.isRemoteRunning) {
            jobFinished(params, false)
            return false
        }
        if (!running.compareAndSet(false, true)) {
            jobFinished(params, false)
            return false
        }
        val ctx = applicationContext
        val config = RemoteConfigStore.new(ctx)
        val credentials = RemoteCredentialStore.new(ctx)
        val unlocked = ctx.getSystemService(UserManager::class.java).isUserUnlocked
        Log.i(
            TAG,
            "boot job start unlocked=$unlocked configured=${config.isConfigured} " +
                "enabled=${config.remoteEnabled} enrolled=${credentials.enrolled}",
        )
        if (!config.canStartRemoteWork() || !credentials.enrolled) {
            jobFinished(params, false)
            return false
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val directBoot = !ctx.getSystemService(UserManager::class.java).isUserUnlocked
                val engine = RemoteEngine.create(ctx, directBoot = directBoot)
                val outcome = engine?.runSync()
                Log.i(TAG, "boot reconcile outcome=$outcome directBoot=$directBoot")
                if (outcome is me.lucky.wasted.remote.domain.SyncOutcome.Failed) {
                    Log.w(TAG, "boot reconcile reason=${outcome.reason}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Boot reconcile failed", e)
            } finally {
                running.set(false)
                jobFinished(params, false)
                scope.cancel()
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        running.set(false)
        return true
    }

    override fun onNetworkChanged(params: JobParameters) {
        // A running reconcile already retries transient network failures through the outbox.
    }
}
