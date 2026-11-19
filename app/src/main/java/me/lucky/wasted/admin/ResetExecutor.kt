package me.lucky.wasted.admin

import android.content.Context
import android.util.Log

import java.util.concurrent.locks.ReentrantLock

import me.lucky.wasted.Preferences

sealed class ResetResult {
    data class Initiated(val message: String) : ResetResult()
    data class DebugBlocked(val message: String) : ResetResult()
    data class NotSupported(val message: String) : ResetResult()
    data class Failed(val message: String) : ResetResult()
}

interface ResetPort {
    fun getResetSupport(): DeviceAdminManager.ResetSupport
    fun wipeData()
}

class DeviceAdminResetPort(private val manager: DeviceAdminManager) : ResetPort {
    constructor(ctx: Context) : this(DeviceAdminManager(ctx))

    override fun getResetSupport() = manager.getResetSupport()
    override fun wipeData() = manager.wipeData()
}

/**
 * The only production caller of the low-level wipe API.
 * Debug Mode is re-read at the final boundary; ON, missing, corrupt, or unreadable
 * state means debug_blocked with zero destructive calls.
 */
class ResetExecutor(
    private val isDebugModeOn: () -> Boolean,
    private val port: ResetPort,
) {
    companion object {
        private const val TAG = "ResetExecutor"
        private const val DEBUG_BLOCKED_MESSAGE =
            "Debug Mode is ON — device reset was not executed"
        private val executionLock = ReentrantLock()

        fun create(ctx: Context) = ResetExecutor(
            isDebugModeOn = { Preferences.new(ctx).isDebugMode },
            port = DeviceAdminResetPort(ctx),
        )
    }

    fun execute(): ResetResult {
        if (!executionLock.tryLock()) {
            Log.w(TAG, "Another reset request is already executing; ignoring duplicate")
            return ResetResult.Failed("A device reset is already in progress")
        }
        try {
            return executeLocked()
        } finally {
            executionLock.unlock()
        }
    }

    private fun executeLocked(): ResetResult {
        if (readDebugMode()) {
            Log.i(TAG, "Debug Mode ON — reset blocked, zero destructive calls")
            return ResetResult.DebugBlocked(DEBUG_BLOCKED_MESSAGE)
        }

        val support = port.getResetSupport()
        if (!support.isSupported) {
            Log.e(TAG, "Reset not supported: ${support.userMessage}")
            return ResetResult.NotSupported(support.userMessage)
        }

        if (readDebugMode()) {
            Log.i(TAG, "Debug Mode enabled before boundary — reset blocked, zero destructive calls")
            return ResetResult.DebugBlocked(DEBUG_BLOCKED_MESSAGE)
        }

        return try {
            Log.i(TAG, "Invoking wipeData()")
            port.wipeData()
            Log.i(TAG, "Device reset initiated")
            ResetResult.Initiated("Device reset initiated")
        } catch (e: IllegalStateException) {
            val message = if ((e.message ?: "").contains("system user", ignoreCase = true)) {
                "This Android user does not allow factory reset through Device Admin. Emulator system users commonly reject wipe requests."
            } else {
                e.message ?: "Reset is not allowed on this device"
            }
            Log.e(TAG, "Reset failed: $message")
            ResetResult.Failed(message)
        } catch (e: Exception) {
            Log.e(TAG, "Reset failed: ${e.message}")
            ResetResult.Failed("Reset could not be started on this device")
        }
    }

    private fun readDebugMode(): Boolean = runCatching { isDebugModeOn() }.getOrDefault(true)
}
