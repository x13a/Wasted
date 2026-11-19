package me.lucky.wasted.remote.data

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.UUID

import me.lucky.wasted.security.TinkEncryptedSharedPreferences

/**
 * Credential-protected device identity and session storage.
 * Values are wrapped by Tink/Android Keystore and never copied to
 * direct-boot preferences or Android backup.
 */
class RemoteCredentialStore(private val prefs: SharedPreferences) {
    companion object {
        private const val FILE_NAME = "remote_credentials_secure_prefs"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_ENROLLED = "enrolled"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_NOTIFICATIONS_ENABLED = "remote_notifications_enabled"
        private const val KEY_SESSION = "session"
        private const val KEY_DB_PASSPHRASE = "database_passphrase"
        private const val KEY_APPLIED_GLOBAL_REVISION = "applied_global_settings_revision"
        private const val KEY_APPLIED_DEVICE_REVISION = "applied_device_settings_revision"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_RECOVERY_PENDING = "session_recovery_pending"
        private const val KEY_RECOVERY_BLOCKED = "session_recovery_blocked"
        private const val PASSPHRASE_BYTES = 32

        fun new(ctx: Context): RemoteCredentialStore = RemoteCredentialStore(
            TinkEncryptedSharedPreferences.create(
                DirectBootStorage.context(ctx),
                FILE_NAME,
            ),
        )
    }

    private val lock = Any()

    /** Random per-installation UUID. Rotated only by Reset Remote Enrollment. */
    val installationId: String
        get() = synchronized(lock) {
            val existing = readString(KEY_INSTALLATION_ID)
            if (!existing.isNullOrBlank()) return existing
            write(KEY_INSTALLATION_ID, UUID.randomUUID().toString())
            readString(KEY_INSTALLATION_ID) ?: UUID.randomUUID().toString()
        }

    fun rotateInstallationId(): String = synchronized(lock) {
        write(KEY_INSTALLATION_ID, UUID.randomUUID().toString())
        readString(KEY_INSTALLATION_ID) ?: UUID.randomUUID().toString()
    }

    var enrolled: Boolean
        get() = readBoolean(KEY_ENROLLED)
        set(value) = writeBoolean(KEY_ENROLLED, value)

    var deviceId: String?
        get() = readString(KEY_DEVICE_ID)
        set(value) = writeOrRemove(KEY_DEVICE_ID, value)

    var accountId: String?
        get() = readString(KEY_ACCOUNT_ID)
        set(value) = writeOrRemove(KEY_ACCOUNT_ID, value)

    var notificationsEnabled: Boolean
        get() = readBoolean(KEY_NOTIFICATIONS_ENABLED, default = true)
        set(value) = writeBoolean(KEY_NOTIFICATIONS_ENABLED, value)

    var session: String?
        get() = readString(KEY_SESSION)
        set(value) = writeOrRemove(KEY_SESSION, value)

    /** 32 random bytes wrapped by the encrypted store; stable across restarts. */
    val databasePassphrase: ByteArray
        get() = synchronized(lock) {
            val decoded = readString(KEY_DB_PASSPHRASE)?.let { hexToBytes(it) }
            if (decoded != null && decoded.size == PASSPHRASE_BYTES) return decoded
            val generated = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            write(KEY_DB_PASSPHRASE, generated.toHex())
            generated
        }

    /** Last global settings revision this device applied locally. */
    var appliedGlobalSettingsRevision: Long
        get() = runCatching { prefs.getLong(KEY_APPLIED_GLOBAL_REVISION, 0L) }.getOrDefault(0L)
        set(value) {
            runCatching { prefs.edit().putLong(KEY_APPLIED_GLOBAL_REVISION, value).commit() }
        }

    /** Last per-device override revision this device applied locally. */
    var appliedDeviceSettingsRevision: Long
        get() = runCatching { prefs.getLong(KEY_APPLIED_DEVICE_REVISION, 0L) }.getOrDefault(0L)
        set(value) {
            runCatching { prefs.edit().putLong(KEY_APPLIED_DEVICE_REVISION, value).commit() }
        }

    /** Timestamp of the last successful server reconciliation. */
    var lastSyncAt: Long
        get() = runCatching { prefs.getLong(KEY_LAST_SYNC_AT, 0L) }.getOrDefault(0L)
        set(value) {
            runCatching { prefs.edit().putLong(KEY_LAST_SYNC_AT, value).commit() }
        }

    /** True while a replacement anonymous session still needs to be rebound to this device. */
    var recoveryPending: Boolean
        get() = readBoolean(KEY_RECOVERY_PENDING)
        set(value) = writeBoolean(KEY_RECOVERY_PENDING, value)

    /** Set only when the server confirms this device can no longer be recovered. */
    var recoveryBlocked: Boolean
        get() = readBoolean(KEY_RECOVERY_BLOCKED)
        set(value) = writeBoolean(KEY_RECOVERY_BLOCKED, value)

    /**
     * Reset Remote Enrollment: clears session and account binding.
     * The caller separately clears the encrypted remote journal/outbox.
     */
    fun clearSession() = synchronized(lock) {
        runCatching {
            prefs.edit()
                .remove(KEY_SESSION)
                .remove(KEY_DEVICE_ID)
                .remove(KEY_ACCOUNT_ID)
                .remove(KEY_ENROLLED)
                .remove(KEY_LAST_SYNC_AT)
                .remove(KEY_APPLIED_GLOBAL_REVISION)
                .remove(KEY_APPLIED_DEVICE_REVISION)
                .remove(KEY_RECOVERY_PENDING)
                .remove(KEY_RECOVERY_BLOCKED)
                .commit()
        }
    }

    private fun readString(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    private fun readBoolean(key: String, default: Boolean = false): Boolean =
        runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)

    private fun write(key: String, value: String) {
        runCatching { prefs.edit().putString(key, value).commit() }
    }

    private fun writeBoolean(key: String, value: Boolean) {
        runCatching { prefs.edit().putBoolean(key, value).commit() }
    }

    private fun writeOrRemove(key: String, value: String?) {
        runCatching {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
            editor.commit()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
