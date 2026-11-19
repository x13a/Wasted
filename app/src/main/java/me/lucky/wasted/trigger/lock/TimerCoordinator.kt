package me.lucky.wasted.trigger.lock

import android.app.job.JobScheduler
import android.content.Context

import me.lucky.wasted.Preferences
import me.lucky.wasted.Utils

data class PersistedTimerState(
    val scheduledAtMs: Long,
    val durationMinutes: Int,
)

data class TimerSnapshot(
    val running: Boolean,
    val remainingSeconds: Long,
)

interface LockJobScheduler {
    fun schedule(): Boolean
    fun cancel()
}

interface TimerStateStore {
    fun read(): PersistedTimerState?
    fun write(state: PersistedTimerState)
    fun clear()
}

class LockJobSchedulerAdapter(private val ctx: Context) : LockJobScheduler {
    override fun schedule(): Boolean =
        LockJobManager(ctx).schedule() == JobScheduler.RESULT_SUCCESS

    override fun cancel() {
        LockJobManager(ctx).cancel()
    }
}

class PreferencesTimerStateStore(private val prefs: Preferences) : TimerStateStore {
    override fun read(): PersistedTimerState? {
        val scheduledAt = prefs.timerScheduledAtMs
        val duration = prefs.timerDurationMinutes
        return if (scheduledAt > 0L && duration > 0) {
            PersistedTimerState(scheduledAt, duration)
        } else {
            null
        }
    }

    override fun write(state: PersistedTimerState) {
        prefs.timerScheduledAtMs = state.scheduledAtMs
        prefs.timerDurationMinutes = state.durationMinutes
    }

    override fun clear() {
        prefs.timerScheduledAtMs = 0L
        prefs.timerDurationMinutes = 0
    }
}

/**
 * Adapter around the existing LockJobManager. Records restart-safe reporting
 * metadata while preserving current semantics: screen-off/locked schedules,
 * user-present cancels, and an unlocked/inactive device remains inactive.
 */
class TimerCoordinator(
    private val scheduler: LockJobScheduler,
    private val stateStore: TimerStateStore,
    private val lockTimerMinutes: () -> Int,
    private val isDeviceLocked: () -> Boolean,
    private val clock: () -> Long,
) {
    companion object {
        fun create(ctx: Context): TimerCoordinator {
            val prefs = Preferences.new(ctx)
            val utils = Utils(ctx)
            return TimerCoordinator(
                scheduler = LockJobSchedulerAdapter(ctx),
                stateStore = PreferencesTimerStateStore(prefs),
                lockTimerMinutes = { prefs.triggerLockCount },
                isDeviceLocked = { utils.isDeviceLocked() },
                clock = System::currentTimeMillis,
            )
        }
    }

    fun schedule(): Boolean {
        if (!scheduler.schedule()) return false
        stateStore.write(PersistedTimerState(clock(), lockTimerMinutes().coerceAtLeast(1)))
        return true
    }

    fun cancel() {
        scheduler.cancel()
        stateStore.clear()
    }

    /** Reset Timer semantics: restart only while the device stays locked and a timer is active. */
    fun restartIfActive(): Boolean {
        if (!isDeviceLocked()) return false
        if (stateStore.read() == null) return false
        return schedule()
    }

    fun currentState(): TimerSnapshot {
        val state = stateStore.read() ?: return TimerSnapshot(running = false, remainingSeconds = 0L)
        val elapsedMs = (clock() - state.scheduledAtMs).coerceAtLeast(0L)
        val totalMs = state.durationMinutes.toLong() * 60_000L
        return TimerSnapshot(
            running = true,
            remainingSeconds = (totalMs - elapsedMs).coerceAtLeast(0L) / 1000L,
        )
    }

    /** Called when the lock job fires: the timer is no longer running. */
    fun onTimerFired() {
        stateStore.clear()
    }
}
