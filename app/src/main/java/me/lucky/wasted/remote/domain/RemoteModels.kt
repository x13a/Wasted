package me.lucky.wasted.remote.domain

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class RemoteConfig(
    val projectUrl: String,
    val publishableKey: String,
    val websiteUrl: String,
)

/** Project URL + publishable key collected before the dashboard is hosted. */
data class RemoteConnectionDraft(
    val projectUrl: String,
    val publishableKey: String,
)

enum class RemoteConfigField {
    PROJECT_URL,
    PUBLISHABLE_KEY,
    WEBSITE_URL,
}

data class RemoteConfigError(
    val field: RemoteConfigField,
    val message: String,
)

object RemoteConfigValidator {
    const val MAX_URL_LENGTH = 512
    const val MAX_KEY_LENGTH = 2048

    private const val PUBLISHABLE_KEY_PREFIX = "sb_publishable_"
    private const val SECRET_KEY_PREFIX = "sb_secret_"
    private const val JWT_PREFIX = "eyJ"

    private const val BASE64URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun validate(
        projectUrl: String,
        publishableKey: String,
        websiteUrl: String,
    ): List<RemoteConfigError> = buildList {
        addAll(validateConnection(projectUrl, publishableKey))
        validateWebUrl(websiteUrl, MAX_URL_LENGTH)?.let {
            add(RemoteConfigError(RemoteConfigField.WEBSITE_URL, it))
        }
    }

    /** Validation for the setup wizard, where the dashboard URL is not known yet. */
    fun validateConnection(
        projectUrl: String,
        publishableKey: String,
    ): List<RemoteConfigError> = buildList {
        validateWebUrl(projectUrl, MAX_URL_LENGTH)?.let {
            add(RemoteConfigError(RemoteConfigField.PROJECT_URL, it))
        }
        validatePublishableKey(publishableKey)?.let {
            add(RemoteConfigError(RemoteConfigField.PUBLISHABLE_KEY, it))
        }
    }

    private fun validateWebUrl(value: String, maxLength: Int): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "Required"
        if (trimmed.length > maxLength) return "Must be at most $maxLength characters"

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return "Not a valid URL"
        }

        if (uri.scheme?.lowercase() != "https") return "Must be an https:// URL"
        if (uri.host.isNullOrEmpty()) return "Must include a host"
        if (uri.userInfo != null) return "Must not contain credentials"
        if (uri.rawQuery != null || uri.rawFragment != null) return "Must not contain a query or fragment"
        return null
    }

    private fun validatePublishableKey(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "Required"
        if (trimmed.length > MAX_KEY_LENGTH) return "Must be at most $MAX_KEY_LENGTH characters"
        if (trimmed.startsWith(SECRET_KEY_PREFIX)) return "Secret keys must never be stored in the app"

        if (trimmed.startsWith(PUBLISHABLE_KEY_PREFIX)) {
            return if (trimmed.length > PUBLISHABLE_KEY_PREFIX.length) null else "Invalid publishable key"
        }

        if (trimmed.startsWith(JWT_PREFIX)) return validateLegacyAnonKey(trimmed)

        return "Not a Supabase publishable key"
    }

    private fun validateLegacyAnonKey(token: String): String? {
        val parts = token.split('.')
        if (parts.size != 3) return "Malformed key"

        val payload = decodeBase64Url(parts[1])?.toString(Charsets.UTF_8) ?: return "Malformed key"

        val role = try {
            JsonParser.parseString(payload).asJsonObject.get("role")?.asString
        } catch (_: Exception) {
            return "Malformed key"
        }

        return when (role) {
            "anon" -> null
            "service_role" -> "Secret keys must never be stored in the app"
            else -> "Not an anonymous Supabase key"
        }
    }

    private fun decodeBase64Url(value: String): ByteArray? {
        val cleaned = value.trimEnd('=')
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            val index = BASE64URL_ALPHABET.indexOf(ch)
            if (index < 0) return null
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

// ── Remote wire models (mirror the RPC contracts from migration 002) ─────────

@Serializable
data class RemoteDeviceDto(
    val id: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("installation_id") val installationId: String,
    val name: String,
    @SerialName("enrolled_at") val enrolledAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
)

@Serializable
data class RemoteSettingsDto(
    @SerialName("global_revision") val globalRevision: Long,
    @SerialName("device_revision") val deviceRevision: Long,
    @SerialName("lock_timer_minutes") val lockTimerMinutes: Int,
    @SerialName("usb_trigger_enabled") val usbTriggerEnabled: Boolean,
    @SerialName("lock_trigger_enabled") val lockTriggerEnabled: Boolean,
    val initialized: Boolean,
)

@Serializable
enum class RemoteCommandType {
    @SerialName("reset_timer") RESET_TIMER,
    @SerialName("force_reset") FORCE_RESET,
    @SerialName("lock_now") LOCK_NOW,
    @SerialName("debug_mode_on") DEBUG_MODE_ON,
    @SerialName("debug_mode_off") DEBUG_MODE_OFF,
}

@Serializable
enum class RemoteCommandState {
    @SerialName("queued") QUEUED,
    @SerialName("received") RECEIVED,
    @SerialName("executing") EXECUTING,
    @SerialName("applied") APPLIED,
    @SerialName("reset_initiated") RESET_INITIATED,
    @SerialName("debug_blocked") DEBUG_BLOCKED,
    @SerialName("rejected") REJECTED,
    @SerialName("failed") FAILED,
    @SerialName("expired") EXPIRED,
}

@Serializable
data class RemoteCommandDto(
    val id: String,
    @SerialName("action_id") val actionId: String,
    @SerialName("command_type") val commandType: RemoteCommandType,
    val state: RemoteCommandState,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class RemoteReconcileResponse(
    @SerialName("server_time") val serverTime: String,
    val device: RemoteDeviceDto,
    val settings: RemoteSettingsDto,
    val commands: List<RemoteCommandDto>,
)

@Serializable
data class ClaimEnrollmentParams(
    @SerialName("p_token") val token: String,
    @SerialName("p_installation_id") val installationId: String,
    @SerialName("p_device_name") val deviceName: String,
    @SerialName("p_local_lock_timer_minutes") val lockTimerMinutes: Int,
    @SerialName("p_local_usb_trigger_enabled") val usbTriggerEnabled: Boolean,
    @SerialName("p_local_lock_trigger_enabled") val lockTriggerEnabled: Boolean,
)

@Serializable
data class ReconcileParams(
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_applied_global_settings_revision") val appliedGlobalSettingsRevision: Long,
    @SerialName("p_applied_device_settings_revision") val appliedDeviceSettingsRevision: Long,
    @SerialName("p_actual_lock_timer_minutes") val actualLockTimerMinutes: Int,
    @SerialName("p_actual_usb_trigger_enabled") val actualUsbTriggerEnabled: Boolean,
    @SerialName("p_actual_lock_trigger_enabled") val actualLockTriggerEnabled: Boolean,
    @SerialName("p_timer_remaining_seconds") val timerRemainingSeconds: Long,
    @SerialName("p_timer_running") val timerRunning: Boolean,
    @SerialName("p_debug_mode") val debugMode: Boolean,
    @SerialName("p_reset_supported") val resetSupported: Boolean,
    @SerialName("p_remote_enabled") val remoteEnabled: Boolean,
    @SerialName("p_battery_percent") val batteryPercent: Int?,
    @SerialName("p_battery_charging") val batteryCharging: Boolean?,
    @SerialName("p_app_version") val appVersion: String,
    @SerialName("p_android_sdk") val androidSdk: Int,
    @SerialName("p_android_release") val androidRelease: String,
    @SerialName("p_last_error_code") val lastErrorCode: String?,
    @SerialName("p_last_error_message") val lastErrorMessage: String?,
)

@Serializable
data class TransitionCommandParams(
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_command_id") val commandId: String,
    @SerialName("p_expected_state") val expectedState: RemoteCommandState,
    @SerialName("p_new_state") val newState: RemoteCommandState,
    @SerialName("p_result_code") val resultCode: String? = null,
    @SerialName("p_detail") val detail: String? = null,
)
