package me.lucky.wasted.remote.data

import java.io.IOException

import android.util.Log

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

import okhttp3.OkHttpClient
import okhttp3.Request

import java.util.concurrent.TimeUnit

import me.lucky.wasted.remote.domain.ClaimEnrollmentParams
import me.lucky.wasted.remote.domain.ReconcileParams
import me.lucky.wasted.remote.domain.RemoteApi
import me.lucky.wasted.remote.domain.RemoteCommandDto
import me.lucky.wasted.remote.domain.RemoteDeviceDto
import me.lucky.wasted.remote.domain.RemoteError
import me.lucky.wasted.remote.domain.RemoteErrors
import me.lucky.wasted.remote.domain.RemoteException
import me.lucky.wasted.remote.domain.RemoteReconcileResponse
import me.lucky.wasted.remote.domain.TransitionCommandParams

/**
 * The only place that touches the Supabase client. The client is created lazily and only when
 * the fail-closed configuration boundary is open; a fresh or unconfigured install performs
 * zero network work. SDK failures are converted to sanitized [RemoteException]s at this boundary.
 */
@OptIn(SupabaseExperimental::class)
class SupabaseRemoteRepository(
    private val configStore: RemoteConfigStore,
    private val credentialStore: RemoteCredentialStore,
) : RemoteApi {
    companion object {
        private const val TAG = "SupabaseRemote"
        private val json = Json { encodeDefaults = true }
        // Shared across repository instances: REMOTE_RUNTIME.reset() can briefly leave an old
        // client reachable from the service while the new engine starts.
        private val authMutex = Mutex()
    }

    val credentials: RemoteCredentialStore
        get() = credentialStore

    @Volatile
    private var client: SupabaseClient? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val isAvailable: Boolean
        get() = configStore.canStartRemoteWork()

    override suspend fun ensureAnonymousSession(forceNew: Boolean) = guarded {
        authMutex.withLock {
            val supabase = requireClient()
            // autoLoadFromStorage is asynchronous; without this wait a fresh client can race the
            // stored session and create a new anonymous identity for an already-enrolled device.
            supabase.auth.awaitInitialization()
            // Refresh tokens are single-use. A background auto-refresh racing this explicit
            // refresh consumes the token twice; the losing request then clears the session.
            supabase.auth.stopAutoRefreshForCurrentSession()
            if (forceNew) {
                // The stored identity no longer exists server-side; replace it before retrying.
                supabase.auth.clearSession()
                supabase.auth.signInAnonymously()
                supabase.auth.stopAutoRefreshForCurrentSession()
                credentialStore.recoveryPending = false
                credentialStore.recoveryBlocked = false
                Log.i(TAG, "Replaced a stale anonymous session")
                return@withLock
            }
            if (credentialStore.recoveryBlocked) {
                throw RemoteException(RemoteError.Authentication)
            }
            val session = supabase.auth.currentSessionOrNull()
            if (credentialStore.recoveryPending) {
                if (recoverEnrolledSession(supabase) != null) return@withLock
                throw RemoteException(RemoteError.Authentication)
            }
            if (session == null) {
                if (credentialStore.enrolled) {
                    if (recoverEnrolledSession(supabase) != null) return@withLock
                    throw RemoteException(RemoteError.Authentication)
                }
                supabase.auth.signInAnonymously()
                supabase.auth.stopAutoRefreshForCurrentSession()
                Log.i(TAG, "Anonymous device session established")
                return@withLock
            }

            if (session.expiresAt <= kotlin.time.Clock.System.now()) {
                val refreshed = runCatching { supabase.auth.refreshCurrentSession() }
                supabase.auth.stopAutoRefreshForCurrentSession()
                if (refreshed.isFailure) {
                    val failure = refreshed.exceptionOrNull()
                    if (credentialStore.enrolled && isInvalidRefreshToken(failure)) {
                        if (recoverEnrolledSession(supabase) != null) return@withLock
                        throw RemoteException(RemoteError.Authentication, failure)
                    }
                    // Transient refresh failures stay retryable; only a rejected token triggers
                    // the stored-device recovery path above.
                    throw RemoteException(mapError(failure ?: IllegalStateException("refresh failed")), failure)
                }
            }
        }
    }

    /**
     * A lost anonymous Auth session must not require another enrollment token. The encrypted
     * installation id is the device's proof of ownership; the backend re-binds the same device
     * row to the new anonymous identity and keeps all settings, notes, and command history.
     */
    private suspend fun recoverEnrolledSession(supabase: SupabaseClient): RemoteDeviceDto? =
        RemoteSessionRecovery(
            credentials = credentialStore,
            auth = object : RemoteSessionRecovery.AuthPort {
                override suspend fun hasValidSession(): Boolean {
                    val session = supabase.auth.currentSessionOrNull() ?: return false
                    return session.expiresAt > kotlin.time.Clock.System.now()
                }

                override suspend fun replaceAnonymousSession() {
                    supabase.auth.clearSession()
                    supabase.auth.signInAnonymously()
                    supabase.auth.stopAutoRefreshForCurrentSession()
                }

                override suspend fun bindDevice(
                    deviceId: String,
                    installationId: String,
                ): RemoteDeviceDto {
                    val body = buildJsonObject {
                        put("p_device_id", deviceId)
                        put("p_installation_id", installationId)
                    }
                    return supabase.postgrest.rpc("recover_device_session", body)
                        .decodeAs<RemoteDeviceDto>()
                }
            },
            classifyError = ::mapError,
        ).run().also { device ->
            if (device != null) Log.i(TAG, "Recovered enrolled device session")
        }

    private fun isInvalidRefreshToken(error: Throwable?): Boolean =
        error is RestException && (error.statusCode == 400 || error.statusCode == 401)

    /**
     * Explicit user-triggered, read-only connectivity check. Reads the public Auth settings to
     * validate the project URL/publishable key; it does not create a user or start Realtime.
     *
     * @return true when anonymous sign-ins are enabled on the project.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val config = configStore.load()
            ?: throw RemoteException(RemoteError.Validation)
        testConnection(config.projectUrl, config.publishableKey)
    }

    suspend fun testConnection(projectUrl: String, publishableKey: String): Boolean =
        withContext(Dispatchers.IO) {
        val endpoint = projectUrl.trimEnd('/') + "/auth/v1/settings"
        val request = Request.Builder()
            .url(endpoint)
            .header("apikey", publishableKey)
            .get()
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException(RemoteError.Network, e)
        }
        response.use {
            if (it.code == 401 || it.code == 403) {
                throw RemoteException(RemoteError.Authentication)
            }
            if (!it.isSuccessful) {
                throw RemoteException(RemoteError.Server(it.code))
            }
            val body = it.body.string()
            val external = json.parseToJsonElement(body).jsonObject["external"]?.jsonObject
            val anonymous = external?.get("anonymous_users")?.jsonPrimitive?.booleanOrNull
            anonymous == true
        }
    }

    override suspend fun claimEnrollment(params: ClaimEnrollmentParams): RemoteDeviceDto = guarded {
        Log.d(TAG, "claim_device_enrollment")
        val body = json.encodeToJsonElement(ClaimEnrollmentParams.serializer(), params).jsonObject
        val device = try {
            requireClient().postgrest.rpc("claim_device_enrollment", body)
                .decodeAs<RemoteDeviceDto>()
        } catch (e: Throwable) {
            // The stored anonymous identity can disappear server-side (cleanup job, project reset,
            // or a session restored from another install). The JWT still looks valid, so the claim
            // fails on the auth.users foreign key. Start a clean anonymous session and retry once:
            // the token is untouched, so this is safe and saves the user a manual reset.
            if (!isMissingAuthIdentity(e)) throw e
            Log.i(TAG, "Device identity vanished; starting a new anonymous session and retrying")
            ensureAnonymousSession(forceNew = true)
            requireClient().postgrest.rpc("claim_device_enrollment", body)
                .decodeAs<RemoteDeviceDto>()
        }
        credentialStore.recoveryPending = false
        credentialStore.recoveryBlocked = false
        device
    }

    private fun isMissingAuthIdentity(error: Throwable): Boolean =
        error is RestException && (error as? PostgrestRestException)?.code == "23503"

    override suspend fun reconcile(params: ReconcileParams): RemoteReconcileResponse = guarded {
        val body = json.encodeToJsonElement(ReconcileParams.serializer(), params).jsonObject
        requireClient().postgrest.rpc("device_reconcile", body)
            .decodeAs<RemoteReconcileResponse>()
    }

    override suspend fun transitionCommand(params: TransitionCommandParams): RemoteCommandDto = guarded {
        ensureAnonymousSession()
        Log.d(
            TAG,
            "device_transition_command ${params.commandId} ${params.expectedState} -> ${params.newState}",
        )
        val body = json.encodeToJsonElement(TransitionCommandParams.serializer(), params).jsonObject
        requireClient().postgrest.rpc("device_transition_command", body)
            .decodeAs<RemoteCommandDto>()
    }

    suspend fun currentUserId(): String? = clientOrNull()?.auth?.currentUserOrNull()?.id

    /** Shared client for the single Realtime channel owned by the foreground service. */
    internal fun clientForRealtime(): SupabaseClient? = clientOrNull()

    fun close() {
        synchronized(this) {
            client?.auth?.stopAutoRefreshForCurrentSession()
            client = null
        }
    }

    private fun requireClient(): SupabaseClient =
        clientOrNull() ?: throw IllegalStateException("Remote configuration is not enabled")

    private fun clientOrNull(): SupabaseClient? {
        client?.let { return it }
        if (!configStore.canStartRemoteWork()) return null
        val config = configStore.load() ?: return null
        return synchronized(this) {
            client ?: createSupabaseClient(
                supabaseUrl = config.projectUrl,
                supabaseKey = config.publishableKey,
            ) {
                install(Auth) {
                    sessionManager = EncryptedSessionManager(credentialStore)
                    // The default SettingsCodeVerifierCache creates credential-backed Android
                    // Settings, which throws in Direct Boot. Anonymous auth does not use PKCE.
                    codeVerifierCache = io.github.jan.supabase.auth.MemoryCodeVerifierCache()
                    autoLoadFromStorage = true
                    // Refresh tokens rotate on every use. Auth refresh is serialized by
                    // ensureAnonymousSession instead of the SDK lifecycle and request hook.
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                    checkSessionOnRequest = false
                }
                install(Postgrest)
                install(Realtime)
            }.also { client = it }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        throw e
    } catch (e: Throwable) {
        throw RemoteException(mapError(e), e)
    }

    private fun mapError(error: Throwable): RemoteError = when (error) {
        is RestException -> RemoteErrors.classify(
            statusCode = error.statusCode,
            sqlState = (error as? PostgrestRestException)?.code,
            isNetwork = false,
            message = error.error,
        )
        is IOException -> RemoteError.Network
        else -> RemoteErrors.classify(
            statusCode = null,
            sqlState = null,
            isNetwork = false,
            message = error.message,
        )
    }
}
