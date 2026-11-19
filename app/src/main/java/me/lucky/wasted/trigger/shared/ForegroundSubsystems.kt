package me.lucky.wasted.trigger.shared

import android.content.pm.ServiceInfo
import android.os.Build

import me.lucky.wasted.Trigger

/** Desired subsystem state derived from preferences and remote configuration. */
data class SubsystemDesired(
    val lockReceiver: Boolean,
    val usbReceiver: Boolean,
    val p2p: Boolean,
    val remote: Boolean,
) {
    companion object {
        fun from(triggers: Int, p2pEnabled: Boolean, remoteActive: Boolean) = SubsystemDesired(
            lockReceiver = triggers.and(Trigger.LOCK.value) != 0,
            usbReceiver = triggers.and(Trigger.USB.value) != 0,
            p2p = p2pEnabled,
            remote = remoteActive,
        )
    }

    fun hasActiveSubsystem() = lockReceiver || usbReceiver || p2p || remote
}

/** Single source of truth for "does the foreground service need to run". */
object ForegroundRequirement {
    fun isRequired(
        enabled: Boolean,
        triggers: Int,
        p2pEnabled: Boolean,
        remoteActive: Boolean,
    ): Boolean = (enabled && (
            triggers.and(Trigger.LOCK.value) != 0 ||
                triggers.and(Trigger.USB.value) != 0)) ||
        p2pEnabled || remoteActive
}

/** Runtime foregroundServiceType mask per OS level; 0 means the plain two-argument startForeground. */
object ForegroundTypes {
    fun runtimeMask(sdk: Int, p2pActive: Boolean, remoteActive: Boolean): Int {
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var mask = 0
            if (p2pActive) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (remoteActive) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            return mask
        }
        if (sdk >= Build.VERSION_CODES.Q && p2pActive) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return 0
    }
}
