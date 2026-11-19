package me.lucky.wasted.remote.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWireModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `reconcile response decodes the RPC wire shape`() {
        val raw = """
            {
              "server_time": "2026-10-18T16:00:00Z",
              "device": {"id": "d1", "account_id": "a1", "name": "Phone", "installation_id": "i1", "enrolled_at": "2026-10-18T15:00:00Z"},
              "settings": {"global_revision": 2, "device_revision": 1, "lock_timer_minutes": 1440, "usb_trigger_enabled": true, "lock_trigger_enabled": true, "initialized": true},
              "commands": [
                {"id": "c1", "action_id": "x1", "command_type": "reset_timer", "state": "queued", "created_at": "2026-10-18T15:59:00Z", "expires_at": "2026-10-19T15:59:00Z"},
                {"id": "c2", "action_id": "x2", "command_type": "force_reset", "state": "debug_blocked", "created_at": "2026-10-18T15:58:00Z", "expires_at": "2026-10-18T16:08:00Z"}
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString(RemoteReconcileResponse.serializer(), raw)

        assertEquals("d1", response.device.id)
        assertEquals("a1", response.device.accountId)
        assertEquals(2L, response.settings.globalRevision)
        assertEquals(1L, response.settings.deviceRevision)
        assertEquals(1440, response.settings.lockTimerMinutes)
        assertTrue(response.settings.usbTriggerEnabled)
        assertTrue(response.settings.lockTriggerEnabled)
        assertEquals(2, response.commands.size)
        assertEquals(RemoteCommandType.RESET_TIMER, response.commands[0].commandType)
        assertEquals(RemoteCommandState.QUEUED, response.commands[0].state)
        assertEquals(RemoteCommandType.FORCE_RESET, response.commands[1].commandType)
        assertEquals(RemoteCommandState.DEBUG_BLOCKED, response.commands[1].state)
    }

    @Test
    fun `reconcile response tolerates unknown server fields`() {
        val raw = """
            {"server_time": "t",
             "device": {"id": "d1", "account_id": "a1", "installation_id": "i1", "name": "N", "enrolled_at": "e", "created_at": "c", "updated_at": "u"},
             "settings": {"global_revision": 0, "device_revision": 0, "lock_timer_minutes": 1, "usb_trigger_enabled": false, "lock_trigger_enabled": false, "initialized": false},
             "commands": [],
             "future_field": true}
        """.trimIndent()

        val response = json.decodeFromString(RemoteReconcileResponse.serializer(), raw)

        assertTrue(response.commands.isEmpty())
        assertEquals("i1", response.device.installationId)
    }

    @Test
    fun `claim params serialize with p prefixed names`() {
        val params = ClaimEnrollmentParams(
            token = "tok",
            installationId = "inst",
            deviceName = "Name",
            lockTimerMinutes = 1440,
            usbTriggerEnabled = true,
            lockTriggerEnabled = true,
        )
        val encoded = json.encodeToString(ClaimEnrollmentParams.serializer(), params)

        assertTrue(encoded.contains("\"p_token\":\"tok\""))
        assertTrue(encoded.contains("\"p_installation_id\":\"inst\""))
        assertTrue(encoded.contains("\"p_local_lock_timer_minutes\":1440"))
        assertTrue(encoded.contains("\"p_local_usb_trigger_enabled\":true"))
        assertTrue(encoded.contains("\"p_local_lock_trigger_enabled\":true"))
    }

    @Test
    fun `reconcile params serialize battery and both applied revisions`() {
        val params = ReconcileParams(
            deviceId = "d1",
            appliedGlobalSettingsRevision = 7,
            appliedDeviceSettingsRevision = 3,
            actualLockTimerMinutes = 1440,
            actualUsbTriggerEnabled = true,
            actualLockTriggerEnabled = false,
            timerRemainingSeconds = 60,
            timerRunning = true,
            debugMode = true,
            resetSupported = true,
            remoteEnabled = true,
            batteryPercent = 0,
            batteryCharging = false,
            appVersion = "3.0.0",
            androidSdk = 35,
            androidRelease = "15",
            lastErrorCode = null,
            lastErrorMessage = null,
        )
        val encoded = json.encodeToString(ReconcileParams.serializer(), params)

        assertTrue(encoded.contains("\"p_applied_global_settings_revision\":7"))
        assertTrue(encoded.contains("\"p_applied_device_settings_revision\":3"))
        assertTrue(encoded.contains("\"p_battery_percent\":0"))
        assertTrue(encoded.contains("\"p_battery_charging\":false"))
        assertTrue(encoded.contains("\"p_android_release\":\"15\""))
    }

    @Test
    fun `transition params serialize state enums as wire strings`() {
        val params = TransitionCommandParams(
            deviceId = "d1",
            commandId = "c1",
            expectedState = RemoteCommandState.QUEUED,
            newState = RemoteCommandState.RECEIVED,
        )
        val encoded = json.encodeToString(TransitionCommandParams.serializer(), params)

        assertTrue(encoded.contains("\"p_expected_state\":\"queued\""))
        assertTrue(encoded.contains("\"p_new_state\":\"received\""))
    }

    @Test
    fun `debug mode command types decode from the wire`() {
        val pairs = listOf(
            "debug_mode_on" to RemoteCommandType.DEBUG_MODE_ON,
            "debug_mode_off" to RemoteCommandType.DEBUG_MODE_OFF,
        )
        for ((wire, expected) in pairs) {
            assertEquals(
                expected,
                json.decodeFromString(RemoteCommandType.serializer(), "\"$wire\""),
            )
        }
    }

    @Test
    fun `reconcile response decodes debug mode commands`() {
        val raw = """
            {
              "server_time": "2026-10-20T12:00:00Z",
              "device": {"id": "d1", "account_id": "a1", "name": "Phone", "installation_id": "i1", "enrolled_at": "2026-10-20T11:00:00Z"},
              "settings": {"global_revision": 1, "device_revision": 0, "lock_timer_minutes": 1440, "usb_trigger_enabled": false, "lock_trigger_enabled": true, "initialized": true},
              "commands": [
                {"id": "c1", "action_id": "x1", "command_type": "debug_mode_off", "state": "queued", "created_at": "2026-10-20T11:59:00Z", "expires_at": "2026-10-20T12:09:00Z"}
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString(RemoteReconcileResponse.serializer(), raw)

        assertEquals(RemoteCommandType.DEBUG_MODE_OFF, response.commands.single().commandType)
        assertEquals(RemoteCommandState.QUEUED, response.commands.single().state)
    }

    @Test
    fun `command states map every wire value`() {
        val pairs = listOf(
            "queued" to RemoteCommandState.QUEUED,
            "received" to RemoteCommandState.RECEIVED,
            "executing" to RemoteCommandState.EXECUTING,
            "applied" to RemoteCommandState.APPLIED,
            "reset_initiated" to RemoteCommandState.RESET_INITIATED,
            "debug_blocked" to RemoteCommandState.DEBUG_BLOCKED,
            "rejected" to RemoteCommandState.REJECTED,
            "failed" to RemoteCommandState.FAILED,
            "expired" to RemoteCommandState.EXPIRED,
        )
        for ((wire, expected) in pairs) {
            assertEquals(
                expected,
                json.decodeFromString(RemoteCommandState.serializer(), "\"$wire\""),
            )
        }
    }
}
