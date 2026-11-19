package me.lucky.wasted.remote

import android.content.Context

import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.admin.ResetExecutor
import me.lucky.wasted.remote.data.DirectBootRemoteStatusProvider
import me.lucky.wasted.remote.data.LocalRemoteStatusProvider
import me.lucky.wasted.remote.data.RemoteConfigStore
import me.lucky.wasted.remote.data.RemoteCredentialStore
import me.lucky.wasted.remote.data.RemoteDatabase
import me.lucky.wasted.remote.data.RemoteBootPolicy
import me.lucky.wasted.remote.data.RemoteRuntime
import me.lucky.wasted.remote.data.SupabaseRemoteRepository
import me.lucky.wasted.remote.domain.RemoteCommandProcessor
import me.lucky.wasted.remote.domain.RemoteEventBus
import me.lucky.wasted.remote.domain.RemoteEventSink
import me.lucky.wasted.remote.domain.RemoteSettingsApplierPort
import me.lucky.wasted.remote.domain.RemoteSyncCoordinator
import me.lucky.wasted.remote.domain.RemoteTimerPort
import me.lucky.wasted.remote.domain.SyncOutcome
import me.lucky.wasted.settings.LocalSettingsApplier
import me.lucky.wasted.trigger.lock.TimerCoordinator
import me.lucky.wasted.trigger.shared.NotificationManager

/**
 * Single composition root for the remote stack, shared by the foreground service and the
 * WorkManager recovery path. [create] returns null when the fail-closed configuration boundary
 * is closed, guaranteeing zero network work for unconfigured installs.
 */
class RemoteEngine private constructor(
    val repository: SupabaseRemoteRepository,
    val credentials: RemoteCredentialStore,
    private val coordinator: RemoteSyncCoordinator,
    private val processor: RemoteCommandProcessor,
) {
    companion object {
        fun create(ctx: Context, directBoot: Boolean = false): RemoteEngine? {
            val configStore = RemoteConfigStore.new(ctx)
            if (!configStore.canStartRemoteWork()) return null

            val repository = RemoteRuntime.repository(ctx)
            val credentials = repository.credentials
            val dao = if (directBoot) {
                me.lucky.wasted.remote.data.DirectBootCommandDao()
            } else {
                RemoteDatabase.getInstance(ctx).remoteCommandDao()
            }
            val applier = if (directBoot) null else LocalSettingsApplier.create(ctx)
            val bootPolicy = RemoteBootPolicy.new(ctx)

            // Notifications are user-optional; command processing never depends on them.
            val events = RemoteEventSink { event ->
                RemoteEventBus.publish(event)
                if (credentials.notificationsEnabled) {
                    runCatching {
                        NotificationManager(ctx).notifyRemoteEvent(
                            event.key.hashCode(),
                            event.title,
                            event.message,
                        )
                    }
                }
            }

            val statusProvider = if (directBoot) {
                DirectBootRemoteStatusProvider(ctx, credentials, bootPolicy)
            } else {
                LocalRemoteStatusProvider(ctx)
            }

            val coordinator = RemoteSyncCoordinator(
                api = repository,
                dao = dao,
                credentials = credentials,
                statusProvider = statusProvider,
                settingsApplier = if (directBoot) {
                    object : RemoteSettingsApplierPort {
                        override fun applyLockTimerMinutes(minutes: Int) {
                            bootPolicy.lockTimerMinutes = minutes
                            bootPolicy.markSettingsApplied()
                        }

                        override fun applyUsbTrigger(enabled: Boolean) {
                            bootPolicy.usbTriggerEnabled = enabled
                            bootPolicy.markSettingsApplied()
                        }

                        override fun applyLockTrigger(enabled: Boolean) {
                            bootPolicy.lockTriggerEnabled = enabled
                            bootPolicy.markSettingsApplied()
                        }
                    }
                } else {
                    object : RemoteSettingsApplierPort {
                        override fun applyLockTimerMinutes(minutes: Int) {
                            applier?.setLockTimerMinutes(minutes)
                        }

                        override fun applyUsbTrigger(enabled: Boolean) {
                            applier?.setTrigger(Trigger.USB, enabled)
                        }

                        override fun applyLockTrigger(enabled: Boolean) {
                            applier?.setTrigger(Trigger.LOCK, enabled)
                        }
                    }
                },
                isRemoteAvailable = { repository.isAvailable },
                events = events,
            )

            val processor = RemoteCommandProcessor(
                api = repository,
                dao = dao,
                credentials = credentials,
                timer = object : RemoteTimerPort {
                    override fun restartIfActive(): Boolean =
                        !directBoot && TimerCoordinator.create(ctx).restartIfActive()
                },
                resetSupported = {
                    DeviceAdminManager(ctx).getResetSupport().isSupported
                },
                debugMode = {
                    if (directBoot) bootPolicy.debugMode else Preferences.new(ctx).isDebugMode
                },
                setDebugMode = { enabled ->
                    bootPolicy.debugMode = enabled
                    if (!directBoot) Preferences.new(ctx).isDebugMode = enabled
                },
                reset = {
                    if (directBoot) {
                        ResetExecutor(
                            isDebugModeOn = { bootPolicy.debugMode },
                            port = me.lucky.wasted.admin.DeviceAdminResetPort(ctx),
                        ).execute()
                    } else {
                        ResetExecutor.create(ctx).execute()
                    }
                },
                lock = {
                    val admin = DeviceAdminManager(ctx)
                    admin.isActive() && runCatching {
                        admin.lockNow()
                        true
                    }.getOrDefault(false)
                },
                events = events,
            )

            return RemoteEngine(repository, credentials, coordinator, processor)
        }
    }

    suspend fun runSync(): SyncOutcome {
        val outcome = coordinator.syncNow()
        val processed = processor.processPending()
        if (outcome is SyncOutcome.Success && processed.processed > 0) {
            // Report the state changed by the command immediately instead of waiting for the
            // next 60-second heartbeat.
            runCatching { coordinator.syncNow() }
        }
        return outcome
    }

    fun close() {
        // The repository is process-wide for one Supabase client. Closing it here would tear
        // down the auth refresh loop used by the foreground service, workers, and UI.
    }
}
