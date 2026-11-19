package me.lucky.wasted.p2p.database

import android.content.Context
import android.content.SharedPreferences
import me.lucky.wasted.security.TinkEncryptedSharedPreferences
import java.security.SecureRandom

/** Passphrase for the SQLCipher-encrypted P2P database, wrapped by Tink/Keystore. */
class P2PDatabaseKeyStore(private val prefs: SharedPreferences) {
    companion object {
        private const val FILE_NAME = "wasted_p2p_database_key"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val KEY_LEGACY_IMPORTED = "legacy_import_complete"
        private const val KEY_PENDING_REVOCATIONS = "pending_revocations"
        private const val PASSPHRASE_BYTES = 32

        fun new(context: Context): P2PDatabaseKeyStore = try {
            P2PDatabaseKeyStore(
                TinkEncryptedSharedPreferences.create(
                    context.applicationContext,
                    FILE_NAME,
                ),
            )
        } catch (e: Exception) {
            throw P2PDatabaseUnavailableException("P2P database key storage is unavailable", e)
        }
    }

    /**
     * Returns the persisted key for an existing database, or creates one only for a new database.
     * A failed commit or a malformed/missing key for an existing database throws a typed failure
     * instead of silently rotating the key and orphaning the encrypted database.
     */
    fun passphraseOrNull(databaseExists: Boolean): ByteArray? = try {
        synchronized(this) {
            val stored = prefs.getString(KEY_PASSPHRASE, null)
            if (stored != null) {
                val decoded = stored.hexToBytes()
                if (decoded != null && decoded.size == PASSPHRASE_BYTES) return decoded
                if (databaseExists) return null
            }
            if (databaseExists) return null

            val generated = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            val hex = generated.toHex()
            if (!prefs.edit().putString(KEY_PASSPHRASE, hex).commit()) return null
            if (prefs.getString(KEY_PASSPHRASE, null) != hex) return null
            generated
        }
    } catch (e: P2PDatabaseUnavailableException) {
        throw e
    } catch (e: Exception) {
        throw P2PDatabaseUnavailableException("Unable to read the P2P database key", e)
    }

    fun reset(): Boolean = runCatching {
        prefs.edit()
            .remove(KEY_PASSPHRASE)
            .remove(KEY_LEGACY_IMPORTED)
            .remove(KEY_PENDING_REVOCATIONS)
            .commit()
    }.getOrDefault(false)

    fun pendingRevocations(): Set<String> = try {
        prefs.getStringSet(KEY_PENDING_REVOCATIONS, mutableSetOf<String>())?.toSet()
            ?: emptySet()
    } catch (e: Exception) {
        throw P2PDatabaseUnavailableException("Unable to read pending P2P revocations", e)
    }

    fun replacePendingRevocations(ids: Set<String>): Boolean = runCatching {
        prefs.edit().putStringSet(KEY_PENDING_REVOCATIONS, ids).commit()
    }.getOrDefault(false)

    fun isLegacyImportComplete(): Boolean =
        runCatching { prefs.getBoolean(KEY_LEGACY_IMPORTED, false) }.getOrDefault(false)

    fun markLegacyImportComplete(): Boolean =
        runCatching { prefs.edit().putBoolean(KEY_LEGACY_IMPORTED, true).commit() }
            .getOrDefault(false)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
