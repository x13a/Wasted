package me.lucky.wasted.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.util.concurrent.CopyOnWriteArraySet

class TinkEncryptedSharedPreferences private constructor(
    context: Context,
    fileName: String,
    legacyEntriesProvider: (() -> Map<String, *>)?,
) : SharedPreferences {

    companion object {
        private const val STORE_SUFFIX = "_secure_store"
        private const val KEYSET_PREFS_SUFFIX = "_secure_keyset"
        private const val KEYSET_NAME = "tink_keyset"
        private const val MIGRATION_FLAG = "__tink_migration_complete"
        private val EMPTY_ASSOCIATED_DATA = ByteArray(0)

        fun create(
            context: Context,
            fileName: String,
            legacyEntriesProvider: (() -> Map<String, *>)? = null,
        ): SharedPreferences {
            return TinkEncryptedSharedPreferences(context.applicationContext, fileName, legacyEntriesProvider)
        }
    }

    private val storage = context.getSharedPreferences("${fileName}${STORE_SUFFIX}", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, "${fileName}${KEYSET_PREFS_SUFFIX}")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://wasted-${fileName}-master-key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    init {
        migrateLegacyValuesIfNeeded(legacyEntriesProvider)
    }

    override fun getAll(): MutableMap<String, *> {
        val values = LinkedHashMap<String, Any?>()
        for ((key, value) in storage.all) {
            if (key == MIGRATION_FLAG || value !is String) continue
            values[key] = decodeValue(value)
        }
        return values
    }

    override fun getString(key: String?, defValue: String?): String? {
        return key?.let { getDecodedValue(it) as? String } ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val value = key?.let { getDecodedValue(it) as? Set<*> } ?: return defValues
        return value.filterIsInstance<String>().toMutableSet()
    }

    override fun getInt(key: String?, defValue: Int): Int {
        return (key?.let { getDecodedValue(it) as? Int }) ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        return (key?.let { getDecodedValue(it) as? Long }) ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        return (key?.let { getDecodedValue(it) as? Float }) ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return (key?.let { getDecodedValue(it) as? Boolean }) ?: defValue
    }

    override fun contains(key: String?): Boolean {
        return key != null && storage.contains(key)
    }

    override fun edit(): SharedPreferences.Editor {
        return Editor()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.remove(listener)
    }

    private fun migrateLegacyValuesIfNeeded(legacyEntriesProvider: (() -> Map<String, *>)?) {
        if (storage.getBoolean(MIGRATION_FLAG, false)) {
            return
        }

        val legacyEntries = legacyEntriesProvider?.invoke().orEmpty()
        if (legacyEntries.isEmpty()) {
            storage.edit().putBoolean(MIGRATION_FLAG, true).apply()
            return
        }

        val editor = storage.edit()
        for ((key, value) in legacyEntries) {
            if (value == null) continue
            editor.putString(key, encodeValue(value))
        }
        editor.putBoolean(MIGRATION_FLAG, true).apply()
    }

    private fun getDecodedValue(key: String): Any? {
        val encoded = storage.getString(key, null) ?: return null
        return decodeValue(encoded)
    }

    private fun encodeValue(value: Any): String {
        val typedValue = when (value) {
            is String -> "s:$value"
            is Int -> "i:$value"
            is Long -> "l:$value"
            is Boolean -> "b:$value"
            is Float -> "f:$value"
            is Set<*> -> "ss:${value.filterIsInstance<String>().joinToString("\u0001")}" 
            else -> error("Unsupported preference type: ${value::class.java.name}")
        }
        val encrypted = aead.encrypt(typedValue.toByteArray(Charsets.UTF_8), EMPTY_ASSOCIATED_DATA)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decodeValue(encodedValue: String): Any? {
        val encrypted = Base64.decode(encodedValue, Base64.NO_WRAP)
        val decrypted = aead.decrypt(encrypted, EMPTY_ASSOCIATED_DATA).toString(Charsets.UTF_8)
        val separatorIndex = decrypted.indexOf(':')
        if (separatorIndex <= 0) return null

        val type = decrypted.substring(0, separatorIndex)
        val value = decrypted.substring(separatorIndex + 1)
        return when (type) {
            "s" -> value
            "i" -> value.toIntOrNull()
            "l" -> value.toLongOrNull()
            "b" -> value.toBooleanStrictOrNullCompat()
            "f" -> value.toFloatOrNull()
            "ss" -> if (value.isEmpty()) emptySet<String>() else value.split("\u0001").toSet()
            else -> null
        }
    }

    private fun notifyListeners(changedKeys: Set<String>) {
        if (changedKeys.isEmpty()) return
        for (listener in listeners) {
            for (key in changedKeys) {
                listener.onSharedPreferenceChanged(this, key)
            }
        }
    }

    private fun String.toBooleanStrictOrNullCompat(): Boolean? {
        return when (this) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pendingValues = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyValue(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = applyValue(key, values?.toSet())

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyValue(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyValue(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyValue(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyValue(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                removals.add(key)
                pendingValues.remove(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            pendingValues.clear()
            removals.clear()
            return this
        }

        override fun commit(): Boolean {
            return flushChanges()
        }

        override fun apply() {
            flushChanges()
        }

        private fun applyValue(key: String?, value: Any?): SharedPreferences.Editor {
            if (key == null) return this
            if (value == null) {
                remove(key)
            } else {
                pendingValues[key] = value
                removals.remove(key)
            }
            return this
        }

        private fun flushChanges(): Boolean {
            val changedKeys = LinkedHashSet<String>()
            val editor = storage.edit()

            if (clearRequested) {
                storage.all.keys.filter { it != MIGRATION_FLAG }.forEach {
                    editor.remove(it)
                    changedKeys.add(it)
                }
            }

            for (key in removals) {
                editor.remove(key)
                changedKeys.add(key)
            }

            for ((key, value) in pendingValues) {
                editor.putString(key, encodeValue(value ?: continue))
                changedKeys.add(key)
            }

            val committed = editor.commit()
            if (committed) {
                notifyListeners(changedKeys)
            }
            return committed
        }
    }
}

object LegacyEncryptedPreferencesReader {

    fun readEntries(context: Context, fileName: String): Map<String, *> {
        return runCatching {
            val masterKeysClass = Class.forName("androidx.security.crypto.MasterKeys")
            val spec = masterKeysClass.getField("AES256_GCM_SPEC").get(null) as KeyGenParameterSpec
            val getOrCreate = masterKeysClass.getMethod("getOrCreate", KeyGenParameterSpec::class.java)
            val masterKeyAlias = getOrCreate.invoke(null, spec) as String

            val encryptedPrefsClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences")
            val keySchemeClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences\$PrefKeyEncryptionScheme")
            val valueSchemeClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences\$PrefValueEncryptionScheme")

            val create = encryptedPrefsClass.getMethod(
                "create",
                String::class.java,
                String::class.java,
                Context::class.java,
                keySchemeClass,
                valueSchemeClass,
            )

            val keyScheme = enumConstant(keySchemeClass, "AES256_SIV")
            val valueScheme = enumConstant(valueSchemeClass, "AES256_GCM")
            val legacyPrefs = create.invoke(null, fileName, masterKeyAlias, context, keyScheme, valueScheme) as SharedPreferences
            legacyPrefs.all
        }.getOrDefault(emptyMap<String, Any>())
    }

    private fun enumConstant(enumClass: Class<*>, constantName: String): Any {
        return enumClass.enumConstants
            ?.first { (it as Enum<*>).name == constantName }
            ?: error("Missing enum constant $constantName for ${enumClass.name}")
    }
}