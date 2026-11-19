package me.lucky.wasted.remote.data

import android.content.Context
import android.content.SharedPreferences
import me.lucky.wasted.remote.domain.RemoteConfig
import me.lucky.wasted.remote.domain.RemoteConfigValidator
import me.lucky.wasted.remote.domain.RemoteConnectionDraft
import me.lucky.wasted.security.TinkEncryptedSharedPreferences

/**
 * Fail-closed storage boundary for the remote-management configuration.
 * Values live in encrypted preferences; defaults are unconfigured and OFF.
 * Missing, partial, invalid, or corrupt state always reads as "not configured".
 */
class RemoteConfigStore(private val prefs: SharedPreferences) {
    companion object {
        private const val FILE_NAME = "remote_config_secure_prefs"
        private const val KEY_PROJECT_URL = "project_url"
        private const val KEY_PUBLISHABLE_KEY = "publishable_key"
        private const val KEY_WEBSITE_URL = "website_url"
        private const val KEY_REMOTE_ENABLED = "remote_enabled"
        private const val KEY_DRAFT_PROJECT_URL = "draft_project_url"
        private const val KEY_DRAFT_PUBLISHABLE_KEY = "draft_publishable_key"

        fun new(ctx: Context): RemoteConfigStore = RemoteConfigStore(
            TinkEncryptedSharedPreferences.create(
                DirectBootStorage.context(ctx),
                FILE_NAME,
            ),
        )
    }

    val isConfigured: Boolean
        get() = load() != null

    var remoteEnabled: Boolean
        get() = runCatching { prefs.getBoolean(KEY_REMOTE_ENABLED, false) }.getOrDefault(false)
        set(value) {
            runCatching { prefs.edit().putBoolean(KEY_REMOTE_ENABLED, value).apply() }
        }

    /**
     * The saved configuration, or null when the Supabase connection is unusable.
     *
     * The dashboard URL is optional here: remote sync only needs the project URL and key, and the
     * user may legitimately clear the dashboard URL (for example after switching projects) without
     * that disabling an already enrolled device. A blank URL is therefore "no dashboard yet", not
     * an invalid connection.
     */
    fun load(): RemoteConfig? = runCatching {
        val projectUrl = prefs.getString(KEY_PROJECT_URL, null) ?: return@runCatching null
        val publishableKey = prefs.getString(KEY_PUBLISHABLE_KEY, null) ?: return@runCatching null
        val websiteUrl = prefs.getString(KEY_WEBSITE_URL, null).orEmpty().trim()

        if (RemoteConfigValidator.validateConnection(projectUrl, publishableKey).isNotEmpty()) {
            return@runCatching null
        }
        if (websiteUrl.isNotEmpty()) {
            val errors = RemoteConfigValidator.validate(projectUrl, publishableKey, websiteUrl)
            if (errors.isNotEmpty()) return@runCatching null
        }

        RemoteConfig(projectUrl.trim(), publishableKey.trim(), websiteUrl)
    }.getOrNull()

    fun save(config: RemoteConfig): Boolean = runCatching {
        val normalized = RemoteConfig(
            projectUrl = config.projectUrl.trim(),
            publishableKey = config.publishableKey.trim(),
            websiteUrl = config.websiteUrl.trim(),
        )
        // Only the connection is mandatory; the dashboard URL may be blank (not hosted yet).
        if (RemoteConfigValidator.validateConnection(
                normalized.projectUrl,
                normalized.publishableKey,
            ).isNotEmpty()
        ) {
            return@runCatching false
        }
        if (normalized.websiteUrl.isNotEmpty() &&
            RemoteConfigValidator.validate(
                normalized.projectUrl,
                normalized.publishableKey,
                normalized.websiteUrl,
            ).isNotEmpty()
        ) {
            return@runCatching false
        }

        prefs.edit()
            .putString(KEY_PROJECT_URL, normalized.projectUrl)
            .putString(KEY_PUBLISHABLE_KEY, normalized.publishableKey)
            .putString(KEY_WEBSITE_URL, normalized.websiteUrl)
            .commit()
    }.getOrDefault(false)

    /** Clears just the dashboard URL, used when the linked project changes. */
    fun clearWebsiteUrl() {
        runCatching { prefs.edit().remove(KEY_WEBSITE_URL).commit() }
    }

    /**
     * Saves only the dashboard URL, taking the connection details from the saved configuration or,
     * before a dashboard URL exists, from the setup draft. `load()` returns null while any field is
     * missing, so it cannot be used as the source here.
     */
    fun saveWebsiteUrl(websiteUrl: String): Boolean = runCatching {
        val saved = load()
        val projectUrl = saved?.projectUrl ?: loadDraft()?.projectUrl ?: return@runCatching false
        val publishableKey = saved?.publishableKey ?: loadDraft()?.publishableKey
            ?: return@runCatching false
        save(RemoteConfig(projectUrl, publishableKey, websiteUrl))
    }.getOrDefault(false)

    fun clearConfiguration() {
        runCatching {
            prefs.edit()
                .remove(KEY_PROJECT_URL)
                .remove(KEY_PUBLISHABLE_KEY)
                .remove(KEY_WEBSITE_URL)
                .remove(KEY_DRAFT_PROJECT_URL)
                .remove(KEY_DRAFT_PUBLISHABLE_KEY)
                .putBoolean(KEY_REMOTE_ENABLED, false)
                .commit()
        }
    }

    /** Setup wizard values, kept so the dashboard can be regenerated without retyping. */
    fun saveDraft(projectUrl: String, publishableKey: String): Boolean = runCatching {
        val url = projectUrl.trim()
        val key = publishableKey.trim()
        if (RemoteConfigValidator.validateConnection(url, key).isNotEmpty()) return@runCatching false
        prefs.edit()
            .putString(KEY_DRAFT_PROJECT_URL, url)
            .putString(KEY_DRAFT_PUBLISHABLE_KEY, key)
            .commit()
    }.getOrDefault(false)

    fun loadDraft(): RemoteConnectionDraft? = runCatching {
        val url = prefs.getString(KEY_DRAFT_PROJECT_URL, null) ?: return@runCatching null
        val key = prefs.getString(KEY_DRAFT_PUBLISHABLE_KEY, null) ?: return@runCatching null
        if (RemoteConfigValidator.validateConnection(url, key).isNotEmpty()) {
            return@runCatching null
        }
        RemoteConnectionDraft(url.trim(), key.trim())
    }.getOrNull()

    fun canStartRemoteWork(): Boolean = remoteEnabled && load() != null
}
