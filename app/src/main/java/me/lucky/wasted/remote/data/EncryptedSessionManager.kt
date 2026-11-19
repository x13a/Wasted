package me.lucky.wasted.remote.data

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Persists the supabase-kt session inside the Tink/Keystore-protected credential store.
 * encodeDefaults is required so restored sessions keep every field.
 */
class EncryptedSessionManager(private val store: RemoteCredentialStore) : SessionManager {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun saveSession(session: UserSession) {
        store.session = json.encodeToString(UserSession.serializer(), session)
    }

    override suspend fun loadSession(): UserSession {
        val raw = store.session ?: throw NoSessionFoundException()
        return runCatching { json.decodeFromString(UserSession.serializer(), raw) }
            .getOrElse { throw NoSessionFoundException() }
    }

    override suspend fun deleteSession() {
        store.session = null
    }
}
