package me.lucky.wasted.remote.work

import android.content.Context
import android.os.UserManager
import android.util.Log

import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

import java.util.concurrent.TimeUnit

import me.lucky.wasted.remote.RemoteEngine
import me.lucky.wasted.remote.data.RemoteConfigStore
import me.lucky.wasted.remote.data.RemoteCredentialStore
import me.lucky.wasted.remote.domain.SyncOutcome

/**
 * Eventual recovery safety net: a unique network-constrained one-time pass after retryable
 * failures plus a unique 15-minute periodic reconciliation (5-minute flex). This is never an
 * immediate-delivery mechanism; the foreground service remains the prompt path.
 */
class RemoteRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "RemoteRecovery"
        private const val UNIQUE_ONCE = "remote-recovery-once"
        private const val UNIQUE_PERIODIC = "remote-recovery-periodic"

        fun scheduleOnce(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<RemoteRecoveryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, request)
        }

        fun schedulePeriodic(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<RemoteRecoveryWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelPeriodic(ctx: Context) {
            WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(UNIQUE_PERIODIC)
        }
    }

    override suspend fun doWork(): Result {
        val configStore = RemoteConfigStore.new(applicationContext)
        val credentials = RemoteCredentialStore.new(applicationContext)
        if (!configStore.canStartRemoteWork() || !credentials.enrolled) return Result.success()

        return try {
            val directBoot = !applicationContext
                .getSystemService(UserManager::class.java)
                .isUserUnlocked
            val engine = RemoteEngine.create(applicationContext, directBoot = directBoot)
                ?: return Result.success()
            val outcome = engine.runSync()
            engine.close()
            when {
                outcome is SyncOutcome.Failed && outcome.retryable -> {
                    Log.w(TAG, "Recovery pass failed: ${outcome.reason}")
                    Result.retry()
                }
                else -> Result.success()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recovery pass error: ${e.message}")
            Result.retry()
        }
    }
}
