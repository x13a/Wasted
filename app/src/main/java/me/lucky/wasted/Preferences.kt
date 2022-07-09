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
        private const val DEFAULT_TRIGGER_LOCK_COUNT = 7 * 24 * 60
        private const val DEFAULT_TRIGGER_TILE_DELAY = 2000L

        private const val ENABLED = "enabled"
        private const val SECRET = "secret"
        private const val WIPE_DATA = "wipe_data"
        private const val WIPE_EMBEDDED_SIM = "wipe_embedded_sim"

        private const val TRIGGERS = "triggers"
        private const val TRIGGER_LOCK_COUNT = "trigger_lock_count"
        private const val TRIGGER_TILE_DELAY = "trigger_tile_delay"

        private const val FILE_NAME = "sec_shared_prefs"

        // migration
        private const val AUTHENTICATION_CODE = "authentication_code"
        private const val WIPE_ON_INACTIVITY_COUNT = "wipe_on_inactivity_count"

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

    var secret: String
        get() = prefs.getString(
            SECRET,
            prefs.getString(AUTHENTICATION_CODE, "") ?: "",
        ) ?: ""
        set(value) = prefs.edit { putString(SECRET, value) }

    var isWipeData: Boolean
        get() = prefs.getBoolean(WIPE_DATA, false)
        set(value) = prefs.edit { putBoolean(WIPE_DATA, value) }

    var isWipeEmbeddedSim: Boolean
        get() = prefs.getBoolean(WIPE_EMBEDDED_SIM, false)
        set(value) = prefs.edit { putBoolean(WIPE_EMBEDDED_SIM, value) }

    var triggerLockCount: Int
        get() = prefs.getInt(
            TRIGGER_LOCK_COUNT,
            prefs.getInt(WIPE_ON_INACTIVITY_COUNT, DEFAULT_TRIGGER_LOCK_COUNT),
        )
        set(value) = prefs.edit { putInt(TRIGGER_LOCK_COUNT, value) }

    var triggerTileDelay: Long
        get() = prefs.getLong(TRIGGER_TILE_DELAY, DEFAULT_TRIGGER_TILE_DELAY)
        set(value) = prefs.edit { putLong(TRIGGER_TILE_DELAY, value) }

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
                is Long -> putLong(k, v)
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
    LOCK(1 shl 5),
    USB(1 shl 6),
}