package me.lucky.wasted

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class Preferences(ctx: Context, encrypted: Boolean = true) {
    companion object {
        private const val DEFAULT_WIPE_ON_INACTIVITY_COUNT = 7 * 24 * 60

        private const val ENABLED = "enabled"
        private const val AUTHENTICATION_CODE = "authentication_code"
        private const val WIPE_DATA = "wipe_data"
        private const val WIPE_EMBEDDED_SIM = "wipe_embedded_sim"
        private const val WIPE_ON_INACTIVITY = "wipe_on_inactivity"

        private const val TRIGGERS = "triggers"
        private const val WIPE_ON_INACTIVITY_COUNT = "wipe_on_inactivity_count"

        private const val FILE_NAME = "sec_shared_prefs"

        fun new(ctx: Context) = Preferences(
            ctx,
            encrypted = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                ctx.getSystemService(UserManager::class.java).isUserUnlocked,
        )
    }

    private val prefs: SharedPreferences = if (encrypted) {
        val mk = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILE_NAME,
            mk,
            ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } else {
        val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            ctx.createDeviceProtectedStorageContext() else ctx
        PreferenceManager.getDefaultSharedPreferences(context)
    }

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

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun copyTo(dst: Preferences, key: String? = null) = dst.prefs.edit {
        for (entry in prefs.all.entries) {
            val k = entry.key
            if (key != null && k != key) continue
            val v = entry.value ?: continue
            when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is String -> putString(k, v)
            }
        }
    }
}

enum class Trigger(val value: Int) {
    PANIC_KIT(1),
    TILE(1 shl 1),
    SHORTCUT(1 shl 2),
    BROADCAST(1 shl 3),
    NOTIFICATION(1 shl 4),
}
