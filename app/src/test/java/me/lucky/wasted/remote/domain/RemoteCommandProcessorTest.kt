package me.lucky.wasted.remote.domain

import kotlinx.coroutines.runBlocking

import me.lucky.wasted.admin.ResetResult
import me.lucky.wasted.remote.data.RemoteCommandJournalEntity
import me.lucky.wasted.remote.data.RemoteCredentialStore
import me.lucky.wasted.remote.testing.FakeRemoteCommandDao
import me.lucky.wasted.testing.InMemorySharedPreferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandProcessorTest {

    private class FakeApi : RemoteApi {
        val transitions = mutableListOf<TransitionCommandParams>()
        val failures = ArrayDeque<Throwable>()

        override suspend fun ensureAnonymousSession(forceNew: Boolean) = Unit

        override suspend fun claimEnrollment(params: ClaimEnrollmentParams): RemoteDeviceDto =
            error("not used")

        override suspend fun reconcile(params: ReconcileParams): RemoteReconcileResponse =
            error("not used")

        override suspend fun transitionCommand(params: TransitionCommandParams): RemoteCommandDto {
            failures.removeFirstOrNull()?.let { throw it }
            transitions.add(params)
            return RemoteCommandDto(
                id = params.commandId,
                actionId = "action",
                commandType = RemoteCommandType.RESET_TIMER,
                state = params.newState,
                createdAt = "t",
                expiresAt = "t",
            )
        }
    }

    private var now = 5_000L
    private val dao = FakeRemoteCommandDao()
    private val api = FakeApi()
    private val credentials = RemoteCredentialStore(InMemorySharedPreferences()).apply {
        enrolled = true
        deviceId = "device-1"
    }
    private var debug = false
    private var supported = true
    private var restartResult = true
    private var restartCalls = 0
    private var lockCalls = 0
    private val resetCalls = mutableListOf<ResetResult>()
    private val debugWrites = mutableListOf<Boolean>()
    private var debugWriteFails = false
    private val emitted = mutableListOf<RemoteEvent>()

    private fun seed(commandId: String, type: String = "reset_timer", state: String = "queued") {
        dao.commands[commandId] = RemoteCommandJournalEntity(
            commandId = commandId,
            type = type,
            state = state,
            receivedAt = now,
            executingAt = null,
            terminalAt = null,
        )
    }

    private fun processor() = RemoteCommandProcessor(
        api = api,
        dao = dao,
        credentials = credentials,
        timer = object : RemoteTimerPort {
            override fun restartIfActive(): Boolean {
                restartCalls++
                return restartResult
            }
        },
        resetSupported = { supported },
        debugMode = { debug },
        setDebugMode = { enabled ->
            if (debugWriteFails) error("preference unavailable") else debugWrites.add(enabled)
        },
        reset = {
            ResetResult.Initiated("Device reset initiated").also { resetCalls.add(it) }
        },
        lock = { lockCalls++; true },
        events = RemoteEventSink { emitted.add(it) },
        clock = { now },
    )

    private fun sent() = api.transitions.map {
        "${it.expectedState.name.lowercase()}->${it.newState.name.lowercase()}"
    }

    @Test
    fun `reset timer follows the documented order and reports restarted`() = runBlocking {
        seed("c1")

        val outcome = processor().processPending()

        assertEquals(1, outcome.processed)
        assertEquals(
            listOf("queued->received", "received->executing", "executing->applied"),
            sent(),
        )
        assertEquals("restarted", api.transitions.last().resultCode)
        assertEquals("applied", dao.commands.getValue("c1").state)
        assertEquals(1, restartCalls)
        assertEquals(listOf(RemoteEvent.Kind.RESET_TIMER_APPLIED), emitted.map { it.kind })
    }

    @Test
    fun `lock now locks once and applies without touching the reset path`() = runBlocking {
        seed("c1", type = "lock_now")

        val outcome = processor().processPending()

        assertEquals(1, outcome.processed)
        assertEquals(
            listOf("queued->received", "received->executing", "executing->applied"),
            sent(),
        )
        assertEquals("locked", api.transitions.last().resultCode)
        assertEquals("applied", dao.commands.getValue("c1").state)
        assertEquals(1, lockCalls)
        assertTrue(resetCalls.isEmpty())
        assertEquals(listOf(RemoteEvent.Kind.LOCK_APPLIED), emitted.map { it.kind })
    }

    @Test
    fun `reset timer records inactive truthfully`() = runBlocking {
        restartResult = false
        seed("c1")

        processor().processPending()

        assertEquals("inactive", api.transitions.last().resultCode)
        assertEquals("applied", dao.commands.getValue("c1").state)
    }

    @Test
    fun `force reset is blocked by debug mode without touching the reset gate`() = runBlocking {
        debug = true
        seed("c1", type = "force_reset")

        processor().processPending()

        assertEquals(listOf("queued->received", "received->debug_blocked"), sent())
        assertEquals("debug_blocked", dao.commands.getValue("c1").state)
        assertTrue(resetCalls.isEmpty())
        assertEquals(listOf(RemoteEvent.Kind.DEBUG_BLOCKED), emitted.map { it.kind })
    }

    @Test
    fun `debug mode enabled before the boundary blocks the second check`() = runBlocking {
        var reads = 0
        val racingProcessor = RemoteCommandProcessor(
            api = api,
            dao = dao,
            credentials = credentials,
            timer = object : RemoteTimerPort {
                override fun restartIfActive() = true
            },
            resetSupported = { true },
            debugMode = { reads++ > 0 },
            setDebugMode = { enabled ->
                if (debugWriteFails) error("preference unavailable") else debugWrites.add(enabled)
            },
            reset = {
                ResetResult.Initiated("Device reset initiated").also { resetCalls.add(it) }
            },
            clock = { now },
        )
        seed("c1", type = "force_reset")

        racingProcessor.processPending()

        assertEquals(
            listOf("queued->received", "received->executing", "executing->debug_blocked"),
            sent(),
        )
        assertEquals("debug_blocked", dao.commands.getValue("c1").state)
        assertTrue(resetCalls.isEmpty())
    }

    @Test
    fun `force reset is rejected when reset is unsupported`() = runBlocking {
        supported = false
        seed("c1", type = "force_reset")

        processor().processPending()

        assertEquals(listOf("queued->received", "received->rejected"), sent())
        assertEquals("rejected", dao.commands.getValue("c1").state)
        assertTrue(resetCalls.isEmpty())
    }

    @Test
    fun `force reset executes exactly once after reset_initiated`() = runBlocking {
        seed("c1", type = "force_reset")

        processor().processPending()

        assertEquals(
            listOf("queued->received", "received->executing", "executing->reset_initiated"),
            sent(),
        )
        assertEquals("reset_initiated", dao.commands.getValue("c1").state)
        assertEquals(1, resetCalls.size)
        assertEquals(listOf(RemoteEvent.Kind.FORCE_RESET_INITIATED), emitted.map { it.kind })
    }

    @Test
    fun `interrupted force reset is failed and never executed`() = runBlocking {
        seed("c1", type = "force_reset", state = "executing")

        processor().processPending()

        assertEquals(listOf("executing->failed"), sent())
        assertEquals("interrupted_before_reset", api.transitions.single().resultCode)
        assertEquals("failed", dao.commands.getValue("c1").state)
        assertTrue(resetCalls.isEmpty())
        assertEquals(listOf(RemoteEvent.Kind.COMMAND_FAILED), emitted.map { it.kind })
    }

    @Test
    fun `interrupted reset timer finishes idempotently`() = runBlocking {
        seed("c1", type = "reset_timer", state = "executing")

        processor().processPending()

        assertEquals(listOf("executing->applied"), sent())
        assertEquals("applied", dao.commands.getValue("c1").state)
        assertEquals(1, restartCalls)
    }

    @Test
    fun `retryable send failures leave the command pending`() = runBlocking {
        api.failures.add(RemoteException(RemoteError.Network))
        seed("c1")

        val outcome = processor().processPending()

        assertEquals(1, outcome.failed)
        assertTrue(api.transitions.isEmpty())
        assertEquals("queued", dao.commands.getValue("c1").state)
    }

    @Test
    fun `fatal send conflicts mark the command failed`() = runBlocking {
        api.failures.add(RemoteException(RemoteError.Conflict))
        seed("c1")

        processor().processPending()

        assertEquals("failed", dao.commands.getValue("c1").state)
    }

    @Test
    fun `terminal commands are never re-executed`() = runBlocking {
        seed("c1", state = "applied")

        val outcome = processor().processPending()

        assertEquals(0, outcome.processed)
        assertEquals(0, outcome.failed)
        assertTrue(api.transitions.isEmpty())
    }

    @Test
    fun `unenrolled device processes nothing`() = runBlocking {
        credentials.enrolled = false
        credentials.deviceId = null
        seed("c1")

        val outcome = processor().processPending()

        assertEquals(0, outcome.processed)
        assertTrue(api.transitions.isEmpty())
    }

    @Test
    fun `debug mode enable applies without consulting the reset gate`() = runBlocking {
        debug = false
        seed("c1", type = "debug_mode_on")

        processor().processPending()

        assertEquals(
            listOf("queued->received", "received->executing", "executing->applied"),
            sent(),
        )
        assertEquals("enabled", api.transitions.last().resultCode)
        assertEquals(listOf(true), debugWrites)
        assertEquals("applied", dao.commands.getValue("c1").state)
        assertEquals(0, restartCalls)
        assertTrue(resetCalls.isEmpty())
        assertEquals(listOf(RemoteEvent.Kind.DEBUG_MODE_CHANGED), emitted.map { it.kind })
        assertTrue(emitted.single().message.contains("now ON"))
    }

    @Test
    fun `debug mode disable writes the preference exactly once`() = runBlocking {
        debug = true
        seed("c1", type = "debug_mode_off")

        processor().processPending()

        assertEquals("disabled", api.transitions.last().resultCode)
        assertEquals(listOf(false), debugWrites)
        assertEquals("applied", dao.commands.getValue("c1").state)
        assertTrue(resetCalls.isEmpty())
    }

    @Test
    fun `debug mode already enabled skips the write`() = runBlocking {
        debug = true
        seed("c1", type = "debug_mode_on")

        processor().processPending()

        assertEquals("already_enabled", api.transitions.last().resultCode)
        assertTrue(debugWrites.isEmpty())
        assertEquals("applied", dao.commands.getValue("c1").state)
    }

    @Test
    fun `debug mode already disabled skips the write`() = runBlocking {
        debug = false
        seed("c1", type = "debug_mode_off")

        processor().processPending()

        assertEquals("already_disabled", api.transitions.last().resultCode)
        assertTrue(debugWrites.isEmpty())
        assertEquals("applied", dao.commands.getValue("c1").state)
    }

    @Test
    fun `debug mode preference failure fails the command without touching reset`() = runBlocking {
        debugWriteFails = true
        debug = false
        seed("c1", type = "debug_mode_on")

        processor().processPending()

        assertEquals(
            listOf("queued->received", "received->executing", "executing->failed"),
            sent(),
        )
        assertEquals("debug_mode_write_failed", api.transitions.last().resultCode)
        assertEquals("failed", dao.commands.getValue("c1").state)
        assertTrue(resetCalls.isEmpty())
        assertEquals(listOf(RemoteEvent.Kind.COMMAND_FAILED), emitted.map { it.kind })
    }

    @Test
    fun `interrupted debug mode enable is re-applied idempotently`() = runBlocking {
        debug = false
        seed("c1", type = "debug_mode_on", state = "executing")

        processor().processPending()

        assertEquals(listOf("executing->applied"), sent())
        assertEquals(listOf(true), debugWrites)
        assertEquals("applied", dao.commands.getValue("c1").state)
        assertEquals(0, restartCalls)
    }

    @Test
    fun `interrupted debug mode disable is re-applied`() = runBlocking {
        debug = true
        seed("c1", type = "debug_mode_off", state = "executing")

        processor().processPending()

        assertEquals(listOf("executing->applied"), sent())
        assertEquals("disabled", api.transitions.single().resultCode)
        assertEquals(listOf(false), debugWrites)
        assertEquals("applied", dao.commands.getValue("c1").state)
    }
}
