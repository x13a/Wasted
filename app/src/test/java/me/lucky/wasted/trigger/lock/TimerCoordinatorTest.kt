package me.lucky.wasted.trigger.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerCoordinatorTest {

    private class FakeScheduler(var result: Boolean = true) : LockJobScheduler {
        var scheduleCalls = 0
            private set
        var cancelCalls = 0
            private set

        override fun schedule(): Boolean {
            scheduleCalls++
            return result
        }

        override fun cancel() {
            cancelCalls++
        }
    }

    private class InMemoryTimerStateStore : TimerStateStore {
        private var state: PersistedTimerState? = null

        override fun read() = state

        override fun write(state: PersistedTimerState) {
            this.state = state
        }

        override fun clear() {
            state = null
        }
    }

    private var now = 1_000_000L
    private var locked = true
    private var minutes = 60
    private val scheduler = FakeScheduler()
    private val store = InMemoryTimerStateStore()

    private fun coordinator() = TimerCoordinator(
        scheduler = scheduler,
        stateStore = store,
        lockTimerMinutes = { minutes },
        isDeviceLocked = { locked },
        clock = { now },
    )

    @Test
    fun `schedule records restart-safe metadata and running state`() {
        assertTrue(coordinator().schedule())
        assertEquals(PersistedTimerState(now, 60), store.read())

        val state = coordinator().currentState()
        assertTrue(state.running)
        assertEquals(3600L, state.remainingSeconds)
    }

    @Test
    fun `schedule failure records no metadata`() {
        scheduler.result = false
        assertFalse(coordinator().schedule())
        assertNull(store.read())
        assertFalse(coordinator().currentState().running)
    }

    @Test
    fun `cancel clears state and cancels the job`() {
        val timer = coordinator()
        assertTrue(timer.schedule())
        timer.cancel()
        assertEquals(1, scheduler.cancelCalls)
        assertNull(store.read())
        assertFalse(timer.currentState().running)
    }

    @Test
    fun `remaining time decreases and clamps at zero`() {
        val timer = coordinator()
        assertTrue(timer.schedule())

        now += 30 * 60_000L
        assertEquals(1800L, timer.currentState().remainingSeconds)

        now += 100 * 60_000L
        assertEquals(0L, timer.currentState().remainingSeconds)
    }

    @Test
    fun `restartIfActive reschedules from now while locked`() {
        val timer = coordinator()
        assertTrue(timer.schedule())

        now += 10 * 60_000L
        assertTrue(timer.restartIfActive())
        assertEquals(2, scheduler.scheduleCalls)
        assertEquals(PersistedTimerState(now, 60), store.read())
        assertEquals(3600L, timer.currentState().remainingSeconds)
    }

    @Test
    fun `restartIfActive does nothing while unlocked`() {
        val timer = coordinator()
        assertTrue(timer.schedule())

        locked = false
        assertFalse(timer.restartIfActive())
        assertEquals(1, scheduler.scheduleCalls)
    }

    @Test
    fun `restartIfActive does nothing when inactive`() {
        assertFalse(coordinator().restartIfActive())
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun `fired timer is no longer running`() {
        val timer = coordinator()
        assertTrue(timer.schedule())
        timer.onTimerFired()
        assertFalse(timer.currentState().running)
        assertNull(store.read())
    }
}
