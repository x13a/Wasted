package me.lucky.wasted

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class Preferences(ctx: Context) {
    companion object {
        private const val SERVICE_ENABLED = "service_enabled"
        private const val CODE = "code"
        private const val DO_WIPE = "do_wipe"
    }

    private val mk = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "sec_shared_prefs",
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

    var doWipe: Boolean
        get() = prefs.getBoolean(DO_WIPE, false)
        set(value) = prefs.edit { putBoolean(DO_WIPE, value) }
}
