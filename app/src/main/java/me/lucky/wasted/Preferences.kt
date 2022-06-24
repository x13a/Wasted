package me.lucky.wasted

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class Preferences(ctx: Context) {
    companion object {
        const val DEFAULT_WIPE_ON_INACTIVITY_COUNT = 7 * 24 * 60

        private const val ENABLED = "enabled"
        private const val AUTHENTICATION_CODE = "authentication_code"
        private const val WIPE_DATA = "wipe_data"
        private const val WIPE_EMBEDDED_SIM = "wipe_embedded_sim"
        private const val WIPE_ON_INACTIVITY = "wipe_on_inactivity"

        private const val TRIGGERS = "triggers"
        private const val WIPE_ON_INACTIVITY_COUNT = "wipe_on_inactivity_count"

        private const val FILE_NAME = "sec_shared_prefs"
    }

    private val mk = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        FILE_NAME,
        mk,
        ctx,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var isEnabled: Boolean
        get() = prefs.getBoolean(ENABLED, false)
        set(value) = prefs.edit { putBoolean(ENABLED, value) }

    var triggers: Int
        get() = prefs.getInt(TRIGGERS, 0)
        set(value) = prefs.edit { putInt(TRIGGERS, value) }

    var authenticationCode: String
        get() = prefs.getString(AUTHENTICATION_CODE, "") ?: ""
        set(value) = prefs.edit { putString(AUTHENTICATION_CODE, value) }

    var isWipeData: Boolean
        get() = prefs.getBoolean(WIPE_DATA, false)
        set(value) = prefs.edit { putBoolean(WIPE_DATA, value) }

    var isWipeEmbeddedSim: Boolean
        get() = prefs.getBoolean(WIPE_EMBEDDED_SIM, false)
        set(value) = prefs.edit { putBoolean(WIPE_EMBEDDED_SIM, value) }

    var isWipeOnInactivity: Boolean
        get() = prefs.getBoolean(WIPE_ON_INACTIVITY, false)
        set(value) = prefs.edit { putBoolean(WIPE_ON_INACTIVITY, value) }

    var wipeOnInactivityCount: Int
        get() = prefs.getInt(WIPE_ON_INACTIVITY_COUNT, DEFAULT_WIPE_ON_INACTIVITY_COUNT)
        set(value) = prefs.edit { putInt(WIPE_ON_INACTIVITY_COUNT, value) }
}

enum class Trigger(val value: Int) {
    PANIC_KIT(1),
    TILE(1 shl 1),
    SHORTCUT(1 shl 2),
    BROADCAST(1 shl 3),
    NOTIFICATION(1 shl 4),
}
