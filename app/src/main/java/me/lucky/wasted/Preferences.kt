package me.lucky.wasted

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class Preferences(ctx: Context) {
    companion object {
        const val DEFAULT_WIPE_ON_INACTIVE_DAYS = 7

        private const val SERVICE_ENABLED = "service_enabled"
        private const val CODE = "code"
        private const val CODE_ENABLED = "code_enabled"
        private const val WIPE_DATA = "wipe_data"
        private const val WIPE_ESIM = "wipe_esim"
        private const val MAX_FAILED_PASSWORD_ATTEMPTS = "max_failed_password_attempts"
        private const val WIPE_ON_INACTIVE = "wipe_on_inactive"
        private const val WIPE_ON_INACTIVE_DAYS = "wipe_on_inactive_days"

        private const val FILE_NAME = "sec_shared_prefs"
        // migration
        private const val DO_WIPE = "do_wipe"
    }

    private val mk = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        FILE_NAME,
        mk,
        ctx,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(SERVICE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(SERVICE_ENABLED, value) }

    var code: String?
        get() = prefs.getString(CODE, "")
        set(value) = prefs.edit { putString(CODE, value) }

    var isCodeEnabled: Boolean
        get() = prefs.getBoolean(CODE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(CODE_ENABLED, value) }

    var isWipeData: Boolean
        get() = prefs.getBoolean(WIPE_DATA, prefs.getBoolean(DO_WIPE, false))
        set(value) = prefs.edit { putBoolean(WIPE_DATA, value) }

    var isWipeESIM: Boolean
        get() = prefs.getBoolean(WIPE_ESIM, false)
        set(value) = prefs.edit { putBoolean(WIPE_ESIM, value) }

    var maxFailedPasswordAttempts: Int
        get() = prefs.getInt(MAX_FAILED_PASSWORD_ATTEMPTS, 0)
        set(value) = prefs.edit { putInt(MAX_FAILED_PASSWORD_ATTEMPTS, value) }

    var isWipeOnInactive: Boolean
        get() = prefs.getBoolean(WIPE_ON_INACTIVE, false)
        set(value) = prefs.edit { putBoolean(WIPE_ON_INACTIVE, value) }

    var wipeOnInactiveDays: Int
        get() = prefs.getInt(WIPE_ON_INACTIVE_DAYS, DEFAULT_WIPE_ON_INACTIVE_DAYS)
        set(value) = prefs.edit { putInt(WIPE_ON_INACTIVE_DAYS, value) }
}
