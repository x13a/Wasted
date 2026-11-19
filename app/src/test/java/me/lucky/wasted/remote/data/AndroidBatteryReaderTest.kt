package me.lucky.wasted.remote.data

import android.os.BatteryManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidBatteryReaderTest {

    @Test
    fun `converts level and scale to percent`() {
        val snapshot = AndroidBatteryReader.fromBroadcast(
            level = 51,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING,
        )
        assertEquals(51, snapshot.levelPercent)
        assertEquals(false, snapshot.charging)
    }

    @Test
    fun `zero and full are valid percentages`() {
        assertEquals(
            0,
            AndroidBatteryReader.fromBroadcast(0, 100, BatteryManager.BATTERY_STATUS_NOT_CHARGING)
                .levelPercent,
        )
        assertEquals(
            100,
            AndroidBatteryReader.fromBroadcast(100, 100, BatteryManager.BATTERY_STATUS_FULL)
                .levelPercent,
        )
    }

    @Test
    fun `handles non-100 scales`() {
        val snapshot = AndroidBatteryReader.fromBroadcast(
            level = 255,
            scale = 255,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
        )
        assertEquals(100, snapshot.levelPercent)
        assertEquals(true, snapshot.charging)
    }

    @Test
    fun `unknown battery values stay null`() {
        val snapshot = AndroidBatteryReader.fromBroadcast(-1, -1, -1)
        assertNull(snapshot.levelPercent)
        assertNull(snapshot.charging)
    }

    @Test
    fun `invalid level is rejected`() {
        assertNull(
            AndroidBatteryReader.fromBroadcast(101, 100, BatteryManager.BATTERY_STATUS_DISCHARGING)
                .levelPercent,
        )
        assertNull(
            AndroidBatteryReader.fromBroadcast(1, 0, BatteryManager.BATTERY_STATUS_DISCHARGING)
                .levelPercent,
        )
    }
}
