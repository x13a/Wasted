package me.lucky.wasted.remote.domain

import android.util.Log

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import me.lucky.wasted.admin.ResetResult
import me.lucky.wasted.remote.data.RemoteCommandDao
import me.lucky.wasted.remote.data.RemoteCommandJournalEntity
import me.lucky.wasted.remote.data.RemoteCredentialStore

/**
 * Executes remote commands with the plan's ordering and safety rules.
 *
 * Reset Timer is idempotent and may safely rerun after a crash.
 * Force Reset is never resumed destructively: an interrupted `executing` row is failed with
 * `interrupted_before_reset`, and Debug Mode is re-read immediately before every destructive step.
 */
class RemoteCommandProcessor(
    private val api: RemoteApi,
    private val dao: RemoteCommandDao,
    private val credentials: RemoteCredentialStore,
    private val timer: RemoteTimerPort,
    private val resetSupported: () -> Boolean,
    private val debugMode: () -> Boolean,
    private val setDebugMode: (Boolean) -> Unit,
    private val reset: () -> ResetResult,
    private val lock: () -> Boolean = { true },
    private val events: RemoteEventSink = RemoteEventSink {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "RemoteCommands"

        private const val STATE_QUEUED = "queued"
        private const val STATE_RECEIVED = "received"
        private const val STATE_EXECUTING = "executing"
        private const val STATE_APPLIED = "applied"
        private const val STATE_RESET_INITIATED = "reset_initiated"
        private const val STATE_DEBUG_BLOCKED = "debug_blocked"
        private const val STATE_REJECTED = "rejected"
        private const val STATE_FAILED = "failed"

        private const val TYPE_RESET_TIMER = "reset_timer"
        private const val TYPE_FORCE_RESET = "force_reset"
        private const val TYPE_LOCK = "lock_now"
        private const val TYPE_DEBUG_ON = "debug_mode_on"
        private const val TYPE_DEBUG_OFF = "debug_mode_off"

        private val processMutex = Mutex()
    }

    data class ProcessOutcome(val processed: Int, val failed: Int)

    suspend fun processPending(): ProcessOutcome = processMutex.withLock {
        val deviceId = credentials.deviceId
        if (!credentials.enrolled || deviceId == null) return@withLock ProcessOutcome(0, 0)

        recoverInterrupted(deviceId)

        var processed = 0
        var failed = 0
        val pending = dao.commandsInStates(listOf(STATE_QUEUED, STATE_RECEIVED))
        for (row in pending.sortedBy { it.receivedAt }) {
            if (processOne(deviceId, row)) processed++ else failed++
        }
        ProcessOutcome(processed, failed)
    }

    /** Force Reset rows interrupted before `reset_initiated` are never resumed destructively. */
    private suspend fun recoverInterrupted(deviceId: String) {
        for (row in dao.commandsInStates(listOf(STATE_EXECUTING))) {
            when (row.type) {
                TYPE_FORCE_RESET -> {
                    sendTransition(deviceId, row.commandId, STATE_EXECUTING, STATE_FAILED, "interrupted_before_reset")
                    dao.updateCommandState(row.commandId, STATE_FAILED, row.executingAt, clock())
                    events.emit(
                        RemoteEvent(
                            kind = RemoteEvent.Kind.COMMAND_FAILED,
                            key = row.commandId,
                            title = "Force Reset not executed",
                            message = "The command was interrupted before the reset and was failed safely.",
                        ),
                    )
                    Log.w(TAG, "Interrupted Force Reset ${row.commandId} failed safely")
                }
                TYPE_LOCK -> {
                    val locked = runCatching { lock() }.getOrDefault(false)
                    sendTransition(
                        deviceId,
                        row.commandId,
                        STATE_EXECUTING,
                        if (locked) STATE_APPLIED else STATE_FAILED,
                        if (locked) "locked" else "lock_failed",
                    )
                    dao.updateCommandState(
                        row.commandId,
                        if (locked) STATE_APPLIED else STATE_FAILED,
                        row.executingAt,
                        clock(),
                    )
                    if (locked) {
                        events.emit(
                            RemoteEvent(
                                kind = RemoteEvent.Kind.LOCK_APPLIED,
                                key = row.commandId,
                                title = "Device locked",
                                message = "The screen was locked after the interrupted command.",
                            ),
                        )
                    }
                }
                TYPE_DEBUG_ON, TYPE_DEBUG_OFF -> recoverInterruptedDebugMode(deviceId, row)
                else -> {
                    val restarted = safeRestartTimer()
                    sendTransition(
                        deviceId,
                        row.commandId,
                        STATE_EXECUTING,
                        STATE_APPLIED,
                        if (restarted) "restarted" else "inactive",
                    )
                    dao.updateCommandState(row.commandId, STATE_APPLIED, row.executingAt, clock())
                    events.emit(
                        RemoteEvent(
                            kind = RemoteEvent.Kind.RESET_TIMER_APPLIED,
                            key = row.commandId,
                            title = "Reset Timer",
                            message = "The lock timer was restarted after a restart.",
                        ),
                    )
                    Log.i(TAG, "Interrupted Reset Timer ${row.commandId} completed idempotently")
                }
            }
        }
    }

    /** Re-applies the requested Debug Mode value; the write is idempotent by nature. */
    private suspend fun recoverInterruptedDebugMode(
        deviceId: String,
        row: RemoteCommandJournalEntity,
    ) {
        val enabled = row.type == TYPE_DEBUG_ON
        if (!runCatching { setDebugMode(enabled) }.isSuccess) {
            when (
                sendTransition(deviceId, row.commandId, STATE_EXECUTING, STATE_FAILED, "debug_mode_write_failed")
            ) {
                SendResult.RETRYABLE -> Unit
                SendResult.OK, SendResult.FATAL -> {
                    dao.updateCommandState(row.commandId, STATE_FAILED, row.executingAt, clock())
                    events.emit(
                        RemoteEvent(
                            kind = RemoteEvent.Kind.COMMAND_FAILED,
                            key = row.commandId,
                            title = "Debug Mode not changed",
                            message = "The device could not update its local preference.",
                        ),
                    )
                    Log.w(TAG, "Interrupted debug mode ${row.commandId} failed: preference unavailable")
                }
            }
            return
        }

        var failed = false
        when (
            sendTransition(
                deviceId,
                row.commandId,
                STATE_EXECUTING,
                STATE_APPLIED,
                if (enabled) "enabled" else "disabled",
            )
        ) {
            SendResult.OK -> dao.updateCommandState(row.commandId, STATE_APPLIED, row.executingAt, clock())
            SendResult.RETRYABLE -> Unit
            SendResult.FATAL -> {
                dao.updateCommandState(row.commandId, STATE_FAILED, row.executingAt, clock())
                failed = true
            }
        }
        if (!failed) emitDebugModeChanged(row.commandId, enabled)
        Log.i(TAG, "Interrupted debug mode ${row.commandId} re-applied (enabled=$enabled)")
    }

    private suspend fun processOne(deviceId: String, row: RemoteCommandJournalEntity): Boolean {
        if (row.state == STATE_QUEUED) {
            when (sendTransition(deviceId, row.commandId, STATE_QUEUED, STATE_RECEIVED)) {
                SendResult.OK -> dao.updateCommandState(row.commandId, STATE_RECEIVED, row.receivedAt, null)
                SendResult.RETRYABLE -> return false
                SendResult.FATAL -> {
                    dao.updateCommandState(row.commandId, STATE_FAILED, row.receivedAt, clock())
                    return false
                }
            }
        }

        val current = dao.commandById(row.commandId) ?: return false
        return when (current.type) {
            TYPE_RESET_TIMER -> executeResetTimer(deviceId, current)
            TYPE_FORCE_RESET -> executeForceReset(deviceId, current)
            TYPE_LOCK -> executeLock(deviceId, current)
            TYPE_DEBUG_ON -> executeSetDebugMode(deviceId, current, enabled = true)
            TYPE_DEBUG_OFF -> executeSetDebugMode(deviceId, current, enabled = false)
            else -> {
                dao.updateCommandState(current.commandId, STATE_FAILED, current.receivedAt, clock())
                false
            }
        }
    }

    private suspend fun executeLock(deviceId: String, row: RemoteCommandJournalEntity): Boolean {
        when (sendTransition(deviceId, row.commandId, STATE_RECEIVED, STATE_EXECUTING)) {
            SendResult.OK -> Unit
            SendResult.RETRYABLE -> return false
            SendResult.FATAL -> {
                dao.updateCommandState(row.commandId, STATE_FAILED, row.receivedAt, clock())
                return false
            }
        }
        dao.updateCommandState(row.commandId, STATE_EXECUTING, clock(), null)

        val locked = runCatching { lock() }.getOrDefault(false)
        val resultCode = if (locked) "locked" else "lock_failed"
        val terminal = if (locked) STATE_APPLIED else STATE_FAILED
        when (sendTransition(deviceId, row.commandId, STATE_EXECUTING, terminal, resultCode)) {
            SendResult.OK -> dao.updateCommandState(row.commandId, terminal, row.executingAt, clock())
            SendResult.RETRYABLE -> return false
            SendResult.FATAL -> {
                dao.updateCommandState(row.commandId, STATE_FAILED, row.executingAt, clock())
                return false
            }
        }

        if (locked) {
            events.emit(
                RemoteEvent(
                    kind = RemoteEvent.Kind.LOCK_APPLIED,
                    key = row.commandId,
                    title = "Device locked",
                    message = "The screen was locked.",
                ),
            )
            Log.i(TAG, "Lock ${row.commandId} applied")
        } else {
            events.emit(
                RemoteEvent(
                    kind = RemoteEvent.Kind.COMMAND_FAILED,
                    key = row.commandId,
                    title = "Lock failed",
                    message = "The device could not lock the screen. Check Device Admin.",
                ),
            )
            Log.w(TAG, "Lock ${row.commandId} failed")
        }
        return true
    }

    private suspend fun executeResetTimer(deviceId: String, row: RemoteCommandJournalEntity): Boolean {
        when (sendTransition(deviceId, row.commandId, STATE_RECEIVED, STATE_EXECUTING)) {
            SendResult.OK -> Unit
            SendResult.RETRYABLE -> return false
            SendResult.FATAL -> {
                dao.updateCommandState(row.commandId, STATE_FAILED, row.receivedAt, clock())
                return false
            }
        }
        dao.updateCommandState(row.commandId, STATE_EXECUTING, clock(), null)

        val restarted = safeRestartTimer()

        when (
            sendTransition(
                deviceId,
                row.commandId,
                STATE_EXECUTING,
                STATE_APPLIED,
                if (restarted) "restarted" else "inactive",
            )
        ) {
            SendResult.OK -> dao.updateCommandState(row.commandId, STATE_APPLIED, row.executingAt, clock())
            SendResult.RETRYABLE -> return false
            SendResult.FATAL -> {
                dao.updateCommandState(row.commandId, STATE_FAILED, row.executingAt, clock())
                return false
            }
        }
        Log.i(TAG, "Reset Timer ${row.commandId} applied (restarted=$restarted)")
        events.emit(
            RemoteEvent(
                kind = RemoteEvent.Kind.RESET_TIMER_APPLIED,
                key = row.commandId,
                title = "Reset Timer",
                message = if (restarted) {
                    "The lock timer was restarted."
                } else {
                    "The timer was inactive; nothing needed restarting."
                },
            ),
        )
        return true
    }

    private suspend fun executeForceReset(deviceId: String, row: RemoteCommandJournalEntity): Boolean {
        // 4. Debug Mode is re-read before anything else; it can never be bypassed remotely.
        if (readDebugMode()) {
            sendTransition(deviceId, row.commandId, STATE_RECEIVED, STATE_DEBUG_BLOCKED, "debug_mode")
            dao.updateCommandState(row.commandId, STATE_DEBUG_BLOCKED, null, clock())
            events.emit(
                RemoteEvent(
                    kind = RemoteEvent.Kind.DEBUG_BLOCKED,
                    key = row.commandId,
                    title = "Force Reset blocked",
                    message = "Debug Mode is ON. The command was logged and not executed.",
                ),
            )
            Log.i(TAG, "Force Reset ${row.commandId} blocked by Debug Mode")
            return true
        }

        // 5. Validate Device Admin/reset support.
        val supported = runCatching { resetSupported() }.getOrDefault(false)
        if (!supported) {
            sendTransition(deviceId, row.commandId, STATE_RECEIVED, STATE_REJECTED, "reset_unsupported")
            dao.updateCommandState(row.commandId, STATE_REJECTED, null, clock())
            events.emit(
                RemoteEvent(
                    kind = RemoteEvent.Kind.COMMAND_FAILED,
                    key = row.commandId,
                    title = "Force Reset rejected",
                    message = "Device reset is not supported on this device.",
                ),
            )
            Log.i(TAG, "Force Reset ${row.commandId} rejected: reset unsupported")
            return true
        }

        // 6. Persist local executing, then require the server transition to succeed.
        dao.updateCommandState(row.commandId, STATE_EXECUTING, clock(), null)
        when (sendTransition(deviceId, row.commandId, STATE_RECEIVED, STATE_EXECUTING)) {
            SendResult.OK -> Unit
            SendResult.RETRYABLE -> return false
            SendResult.FATAL -> {
                dao.updateCommandState(row.commandId, STATE_FAILED, row.receivedAt, clock())
                return false
            }
        }

        // 7./8. Re-read Debug Mode immediately before the destructive boundary.
        if (readDebugMode()) {
            sendTransition(deviceId, row.commandId, STATE_EXECUTING, STATE_DEBUG_BLOCKED, "debug_mode")
            dao.updateCommandState(row.commandId, STATE_DEBUG_BLOCKED, clock(), clock())
            events.emit(
                RemoteEvent(
                    kind = RemoteEvent.Kind.DEBUG_BLOCKED,
                    key = row.commandId,
                    title = "Force Reset blocked",
                    message = "Debug Mode turned ON before execution. The command was not executed.",
                ),
            )
            Log.i(TAG, "Force Reset ${row.commandId} blocked by Debug Mode at the boundary")
            return true
        }

        // 9. Record reset_initiated on the server before touching the destructive boundary.
        when (sendTransition(deviceId, row.commandId, STATE_EXECUTING, STATE_RESET_INITIATED)) {
            SendResult.OK -> Unit
            SendResult.RETRYABLE -> return false
            SendResult.FATAL -> {
                dao.updateCommandState(row.commandId, STATE_FAILED, clock(), clock())
                return false
            }
        }
        dao.updateCommandState(row.commandId, STATE_RESET_INITIATED, row.executingAt, clock())

        events.emit(
            RemoteEvent(
                kind = RemoteEvent.Kind.FORCE_RESET_INITIATED,
                key = row.commandId,
                title = "Force Reset initiated",
                message = "The device reset was initiated.",
            ),
        )

        // 10. Execute exactly once. ResetExecutor re-reads Debug Mode at its own boundary.
        val result = runCatching { reset() }.getOrElse { ResetResult.Failed("reset failed") }
        Log.i(TAG, "Force Reset ${row.commandId} executed once: ${result::class.simpleName}")
        return true
    }

    /**
     * Remote Debug Mode control. This command is the safety toggle itself, so it is never gated
     * by the current Debug Mode value and never touches the destructive boundary.
     */
    private suspend fun executeSetDebugMode(
        deviceId: String,
        row: RemoteCommandJournalEntity,
        enabled: Boolean,
    ): Boolean {
        when (sendTransition(deviceId, row.commandId, STATE_RECEIVED, STATE_EXECUTING)) {
            SendResult.OK -> Unit
            SendResult.RETRYABLE -> return false
            SendResult.FATAL -> {
                dao.updateCommandState(row.commandId, STATE_FAILED, row.receivedAt, clock())
                return false
            }
        }
        dao.updateCommandState(row.commandId, STATE_EXECUTING, clock(), null)

        val previous = runCatching { debugMode() }.getOrNull()
        if (previous == enabled) {
            val applied = finishDebugMode(
                deviceId,
                row,
                if (enabled) "already_enabled" else "already_disabled",
            )
            if (applied) emitDebugModeChanged(row.commandId, enabled, already = true)
            return applied
        }

        if (!runCatching { setDebugMode(enabled) }.isSuccess) {
            return when (
                sendTransition(deviceId, row.commandId, STATE_EXECUTING, STATE_FAILED, "debug_mode_write_failed")
            ) {
                SendResult.RETRYABLE -> false
                SendResult.OK, SendResult.FATAL -> {
                    dao.updateCommandState(row.commandId, STATE_FAILED, clock(), clock())
                    events.emit(
                        RemoteEvent(
                            kind = RemoteEvent.Kind.COMMAND_FAILED,
                            key = row.commandId,
                            title = "Debug Mode not changed",
                            message = "The device could not update its local preference.",
                        ),
                    )
                    Log.w(TAG, "Debug mode ${row.commandId} failed to write the preference")
                    false
                }
            }
        }

        val applied = finishDebugMode(deviceId, row, if (enabled) "enabled" else "disabled")
        if (applied) {
            Log.i(TAG, "Debug mode set to $enabled for ${row.commandId}")
            emitDebugModeChanged(row.commandId, enabled)
        }
        return applied
    }

    private fun emitDebugModeChanged(commandId: String, enabled: Boolean, already: Boolean = false) {
        val message = when {
            already && enabled -> "Debug Mode was already ON."
            already -> "Debug Mode was already OFF."
            enabled -> "Debug Mode is now ON. Destructive remote commands are blocked."
            else -> "Debug Mode is now OFF. Destructive remote commands can execute."
        }
        events.emit(
            RemoteEvent(
                kind = RemoteEvent.Kind.DEBUG_MODE_CHANGED,
                key = commandId,
                title = "Debug Mode updated",
                message = message,
            ),
        )
    }

    private suspend fun finishDebugMode(
        deviceId: String,
        row: RemoteCommandJournalEntity,
        resultCode: String,
    ): Boolean = when (
        sendTransition(deviceId, row.commandId, STATE_EXECUTING, STATE_APPLIED, resultCode)
    ) {
        SendResult.OK -> {
            dao.updateCommandState(row.commandId, STATE_APPLIED, row.executingAt, clock())
            true
        }
        SendResult.RETRYABLE -> false
        SendResult.FATAL -> {
            dao.updateCommandState(row.commandId, STATE_FAILED, clock(), clock())
            false
        }
    }

    private fun safeRestartTimer(): Boolean =
        runCatching { timer.restartIfActive() }.getOrDefault(false)

    private fun readDebugMode(): Boolean =
        runCatching { debugMode() }.getOrDefault(true)

    private enum class SendResult { OK, RETRYABLE, FATAL }

    private suspend fun sendTransition(
        deviceId: String,
        commandId: String,
        expected: String,
        newState: String,
        resultCode: String? = null,
    ): SendResult {
        val expectedState = stateOf(expected) ?: return SendResult.FATAL
        val targetState = stateOf(newState) ?: return SendResult.FATAL
        return try {
            api.transitionCommand(
                TransitionCommandParams(
                    deviceId = deviceId,
                    commandId = commandId,
                    expectedState = expectedState,
                    newState = targetState,
                    resultCode = resultCode,
                ),
            )
            SendResult.OK
        } catch (e: RemoteException) {
            if (retryable(e.error)) SendResult.RETRYABLE else SendResult.FATAL
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            SendResult.RETRYABLE
        }
    }

    private fun retryable(error: RemoteError): Boolean = when (error) {
        is RemoteError.Network, is RemoteError.RateLimited, is RemoteError.Server -> true
        else -> false
    }

    private fun stateOf(wire: String): RemoteCommandState? =
        RemoteCommandState.entries.firstOrNull { it.name.lowercase() == wire }
}
