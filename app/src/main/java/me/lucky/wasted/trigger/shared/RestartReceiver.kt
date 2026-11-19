package me.lucky.wasted.trigger.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

import me.lucky.wasted.Preferences
import me.lucky.wasted.remote.data.RemoteBootPolicy
import me.lucky.wasted.remote.data.RemoteConfigStore
import me.lucky.wasted.remote.data.RemoteRuntime

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_USER_UNLOCKED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val ctx = context ?: return
        val remoteConfigStore = RemoteConfigStore.new(ctx)
        val remoteActive = remoteConfigStore.remoteEnabled && remoteConfigStore.isConfigured
        android.util.Log.i(
            "RestartReceiver",
            "action=${intent.action} remoteActive=$remoteActive running=${ForegroundService.isRunning}",
        )

        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_BOOT_COMPLETED
        ) {
            if (remoteActive && !ForegroundService.isRunning) {
                // LOCKED_BOOT_COMPLETED and BOOT_COMPLETED can both arrive after the first
                // direct-boot start; never start a second remote client for the same boot.
                // BOOT_COMPLETED is an FGS-start exemption, and connectedDevice/specialUse are
                // not the Android 15 boot-restricted types. The JobScheduler path remains the
                // fallback if an OEM rejects the launch.
                runCatching {
                    ContextCompat.startForegroundService(
                        ctx.applicationContext,
                        Intent(ctx.applicationContext, ForegroundService::class.java)
                            .setAction(ForegroundService.ACTION_RESTART_REMOTE),
                    )
                }
                me.lucky.wasted.remote.work.RemoteBootJobService.schedule(ctx)
            }
            return
        }

        // The running direct-boot service owns the live handoff. Letting both receivers
        // restart the remote subsystem would create back-to-back clients for one unlock.
        if (intent.action == Intent.ACTION_USER_UNLOCKED && ForegroundService.isRunning) return

        val prefs = Preferences.new(ctx)

        if (intent.action == Intent.ACTION_USER_UNLOCKED) {
            // Apply direct-boot policy changes to credential storage before the normal engine
            // starts, then discard the direct-boot client so it reloads the normal session.
            (ctx.applicationContext as? me.lucky.wasted.Application)?.initializeAfterUnlock()
            RemoteBootPolicy.new(ctx).applyTo(Preferences.new(ctx))
            RemoteRuntime.reset()
        }

        val needsForeground = ForegroundRequirement.isRequired(
            enabled = prefs.isEnabled,
            triggers = prefs.triggers,
            p2pEnabled = prefs.p2pEnabled,
            remoteActive = remoteActive,
        )
        if (!needsForeground) return

        // USER_UNLOCKED and package replacement are not boot-start restrictions. The periodic
        // worker above remains the fallback if an OEM still refuses the foreground start.
        runCatching {
            ContextCompat.startForegroundService(
                ctx.applicationContext,
                Intent(ctx.applicationContext, ForegroundService::class.java)
                    .setAction(
                        if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                            ForegroundService.ACTION_RESTART_REMOTE
                        } else {
                            ForegroundService.ACTION_RECONFIGURE
                        },
                    ),
            )
        }
    }
}
