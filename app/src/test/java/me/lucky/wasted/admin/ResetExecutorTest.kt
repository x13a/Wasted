package me.lucky.wasted.admin

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import kotlin.concurrent.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetExecutorTest {

    private class FakeResetPort(
        private val supported: Boolean = true,
        private val failure: Exception? = null,
    ) : ResetPort {
        var wipeCalls = 0
            private set

        override fun getResetSupport() = DeviceAdminManager.ResetSupport(
            isSupported = supported,
            userMessage = if (supported) "supported" else "Device Admin is not active on this phone.",
        )

        override fun wipeData() {
            wipeCalls++
            failure?.let { throw it }
        }
    }

    private fun executor(debugMode: () -> Boolean, port: ResetPort) =
        ResetExecutor(isDebugModeOn = debugMode, port = port)

    @Test
    fun `debug mode on blocks with zero destructive calls`() {
        val port = FakeResetPort()
        val result = executor({ true }, port).execute()
        assertTrue(result is ResetResult.DebugBlocked)
        assertEquals(0, port.wipeCalls)
    }

    @Test
    fun `unreadable debug mode fails closed`() {
        val port = FakeResetPort()
        val result = executor({ throw IllegalStateException("corrupt preference") }, port).execute()
        assertTrue(result is ResetResult.DebugBlocked)
        assertEquals(0, port.wipeCalls)
    }

    @Test
    fun `debug mode off executes exactly once`() {
        val port = FakeResetPort()
        val result = executor({ false }, port).execute()
        assertTrue(result is ResetResult.Initiated)
        assertEquals(1, port.wipeCalls)
    }

    @Test
    fun `unsupported reset makes zero destructive calls`() {
        val port = FakeResetPort(supported = false)
        val result = executor({ false }, port).execute()
        assertTrue(result is ResetResult.NotSupported)
        assertEquals(0, port.wipeCalls)
    }

    @Test
    fun `debug mode enabled before boundary blocks`() {
        val port = FakeResetPort()
        var reads = 0
        val result = executor({ reads++ > 0 }, port).execute()
        assertTrue(result is ResetResult.DebugBlocked)
        assertEquals(0, port.wipeCalls)
    }

    @Test
    fun `wipe failure is reported`() {
        val port = FakeResetPort(failure = IllegalStateException("system user cannot wipe"))
        val result = executor({ false }, port).execute()
        assertTrue(result is ResetResult.Failed)
        assertTrue((result as ResetResult.Failed).message.contains("system user"))
        assertEquals(1, port.wipeCalls)
    }

    @Test
    fun `concurrent reset is rejected without a second wipe`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowPort = object : ResetPort {
            override fun getResetSupport() = DeviceAdminManager.ResetSupport(
                isSupported = true,
                userMessage = "supported",
            )

            override fun wipeData() {
                started.countDown()
                release.await()
            }
        }
        val first = thread { executor({ false }, slowPort).execute() }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val secondPort = FakeResetPort()
            val result = executor({ false }, secondPort).execute()
            assertTrue(result is ResetResult.Failed)
            assertEquals(0, secondPort.wipeCalls)
        } finally {
            release.countDown()
            first.join(2_000)
        }
    }
}
