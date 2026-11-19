package me.lucky.wasted.remote.data

import android.content.Context

import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.remote.domain.BatteryStatusProvider
import me.lucky.wasted.remote.domain.LocalRemoteStatus
import me.lucky.wasted.remote.domain.RemoteStatusProvider

/**
 * Status used only while the user has not made the first post-reboot unlock.
 * Local CE settings are unavailable by Android design; the last encrypted direct-boot
 * snapshot is authoritative for the limited lock/wipe agent.
 */
class DirectBootRemoteStatusProvider(
    private val ctx: Context,
    private val credentials: RemoteCredentialStore,
    private val policy: RemoteBootPolicy,
    private val battery: BatteryStatusProvider = AndroidBatteryReader(ctx),
) : RemoteStatusProvider {

    override fun snapshot(): LocalRemoteStatus {
        val batterySnapshot = battery.snapshot()
        return LocalRemoteStatus(
            appliedGlobalSettingsRevision = credentials.appliedGlobalSettingsRevision,
            appliedDeviceSettingsRevision = credentials.appliedDeviceSettingsRevision,
            lockTimerMinutes = policy.lockTimerMinutes.coerceAtLeast(1),
            usbTriggerEnabled = policy.usbTriggerEnabled,
            lockTriggerEnabled = policy.lockTriggerEnabled,
            timerRemainingSeconds = 0L,
            timerRunning = false,
            debugMode = policy.debugMode,
            resetSupported = DeviceAdminManager(ctx).getResetSupport().isSupported,
            remoteEnabled = credentials.enrolled,
            batteryPercent = batterySnapshot.levelPercent,
            batteryCharging = batterySnapshot.charging,
            appVersion = RemoteAppInfo.version(ctx),
            androidSdk = android.os.Build.VERSION.SDK_INT,
            androidRelease = android.os.Build.VERSION.RELEASE,
        )
    }
}

object RemoteAppInfo {
    fun version(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}
