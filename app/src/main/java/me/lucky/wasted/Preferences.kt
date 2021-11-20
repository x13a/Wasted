package me.lucky.wasted

import android.content.Context

import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Preferences(context: Context) {
    companion object {
        private const val SERVICE_ENABLED = "service_enabled"
        private const val CODE = "code"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(SERVICE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(SERVICE_ENABLED, value) }

    var code: String?
        get() = prefs.getString(CODE, "")
        set(value) = prefs.edit { putString(CODE, value) }
}
