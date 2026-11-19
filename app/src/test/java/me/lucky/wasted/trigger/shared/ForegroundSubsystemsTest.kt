package me.lucky.wasted.trigger.shared

import android.content.pm.ServiceInfo
import me.lucky.wasted.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSubsystemsTest {

    @Test
    fun `requirement matches lock usb p2p and remote combinations`() {
        val lock = Trigger.LOCK.value
        val usb = Trigger.USB.value

        assertFalse(ForegroundRequirement.isRequired(false, 0, false, false))
        assertTrue(ForegroundRequirement.isRequired(true, lock, false, false))
        assertTrue(ForegroundRequirement.isRequired(true, lock or usb, false, false))
        assertFalse(ForegroundRequirement.isRequired(false, lock or usb, false, false))
        assertFalse(ForegroundRequirement.isRequired(true, Trigger.PANIC_KIT.value, false, false))
        assertTrue(ForegroundRequirement.isRequired(false, 0, true, false))
        assertTrue(ForegroundRequirement.isRequired(false, 0, false, true))
        assertTrue(ForegroundRequirement.isRequired(false, 0, true, true))
    }

    @Test
    fun `desired subsystems derive from triggers and flags`() {
        val desired = SubsystemDesired.from(Trigger.LOCK.value or Trigger.USB.value, true, true)
        assertTrue(desired.lockReceiver)
        assertTrue(desired.usbReceiver)
        assertTrue(desired.p2p)
        assertTrue(desired.remote)
        assertTrue(desired.hasActiveSubsystem())

        val none = SubsystemDesired.from(0, false, false)
        assertFalse(none.lockReceiver)
        assertFalse(none.usbReceiver)
        assertFalse(none.p2p)
        assertFalse(none.remote)
        assertFalse(none.hasActiveSubsystem())
    }

    @Test
    fun `runtime mask is plain below api 29`() {
        assertEquals(0, ForegroundTypes.runtimeMask(24, true, true))
        assertEquals(0, ForegroundTypes.runtimeMask(28, true, false))
    }

    @Test
    fun `runtime mask uses connectedDevice only while p2p active on api 29-33`() {
        val connectedDevice = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        assertEquals(connectedDevice, ForegroundTypes.runtimeMask(29, true, false))
        assertEquals(connectedDevice, ForegroundTypes.runtimeMask(33, true, true))
        assertEquals(0, ForegroundTypes.runtimeMask(29, false, false))
        assertEquals(0, ForegroundTypes.runtimeMask(33, false, true))
    }

    @Test
    fun `runtime mask combines types on api 34+`() {
        val connectedDevice = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        assertEquals(connectedDevice, ForegroundTypes.runtimeMask(34, true, false))
        assertEquals(specialUse, ForegroundTypes.runtimeMask(34, false, true))
        assertEquals(connectedDevice or specialUse, ForegroundTypes.runtimeMask(34, true, true))
        assertEquals(0, ForegroundTypes.runtimeMask(34, false, false))
    }
}
