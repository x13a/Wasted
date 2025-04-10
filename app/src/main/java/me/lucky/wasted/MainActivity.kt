package me.lucky.wasted

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import me.lucky.wasted.databinding.ActivityMainBinding
import me.lucky.wasted.fragment.*
import me.lucky.wasted.trigger.shared.NotificationManager

open class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences
    private lateinit var prefsdb: Preferences
    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefs.copyTo(prefsdb, key)
    }

    private val NOTIFICATION_PERMISSION_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        init1()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }

        // Correction : on ne fait return que si le prompt est effectivement lancé
        if (initBiometric()) return

        init2()
        setup()
        promptEnableNotificationService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission notifications accordée ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission notifications refusée ❌", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun init1() {
        prefs = Preferences(this)
        prefsdb = Preferences(this, encrypted = false)
        prefs.copyTo(prefsdb)
    }

    private fun init2() {
        NotificationManager(this).createNotificationChannels()
        replaceFragment(MainFragment())
    }

    private fun initBiometric(): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val canAuth = BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            return false
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@MainActivity, "Erreur d'authentification : $errString", Toast.LENGTH_SHORT).show()
                    // On continue normalement après l’échec
                    init2()
                    setup()
                    promptEnableNotificationService()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    init2()
                    setup()
                    promptEnableNotificationService()
                }
            }
        )

        return try {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.authentication))
                    .setConfirmationRequired(false)
                    .setAllowedAuthenticators(authenticators)
                    .build()
            )
            true
        } catch (exc: Exception) {
            false
        }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefsListener)
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterListener(prefsListener)
    }

    private fun setup() = binding.apply {
        appBar.setNavigationOnClickListener {
            drawer.open()
        }
        appBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.top_settings -> {
                    replaceFragment(
                        when (supportFragmentManager.fragments.last()) {
                            is SettingsFragment -> getFragment(navigation.checkedItem?.itemId ?: R.id.nav_main)
                            else -> SettingsFragment()
                        }
                    )
                    true
                }

                R.id.top_copy -> {
                    copySecret()
                    true
                }

                R.id.top_edit -> {
                    editSecret()
                    true
                }

                else -> false
            }
        }
        navigation.setNavigationItemSelectedListener {
            replaceFragment(getFragment(it.itemId))
            it.isChecked = true
            drawer.close()
            true
        }
    }

    private fun replaceFragment(f: Fragment) {
        binding.appBar.menu.setGroupVisible(R.id.top_group_main, f is MainFragment)
        supportFragmentManager
            .beginTransaction()
            .replace(binding.fragment.id, f)
            .commit()
    }

    private fun getFragment(id: Int) = when (id) {
        R.id.nav_main -> MainFragment()
        R.id.nav_trigger_tile -> TileFragment()
        R.id.nav_trigger_notification -> NotificationFragment()
        R.id.nav_trigger_lock -> LockFragment()
        R.id.nav_trigger_application -> ApplicationFragment()
        R.id.nav_voice_trigger -> VoiceTriggerFragment()
        R.id.nav_recast -> RecastFragment()
        else -> MainFragment()
    }

    private fun copySecret() {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("", prefs.notificationKeyword))
        Snackbar.make(binding.fragment, R.string.copied_popup, Snackbar.LENGTH_SHORT).show()
    }

    private fun editSecret() {
        val view = layoutInflater.inflate(R.layout.dialog_edit_secret, null)
        val secret = view.findViewById<TextInputLayout>(R.id.secret)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit)
            .setView(view)
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton(android.R.string.ok) { _, _ ->
                if (secret.error != null) return@setNegativeButton
                prefs.notificationKeyword = secret.editText?.text?.toString()?.trim().orEmpty()
                replaceFragment(MainFragment())
            }
            .create()

        secret.editText?.setText(prefs.notificationKeyword)
        secret.editText?.doAfterTextChanged {
            secret.error = if (it?.toString()?.isBlank() == true)
                getString(R.string.edit_secret_error) else null
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = secret.error == null
        }
        dialog.show()
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val enabledListeners =
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return !TextUtils.isEmpty(enabledListeners) && enabledListeners.contains(pkgName)
    }

    private fun promptEnableNotificationService() {
        if (!isNotificationServiceEnabled()) {
            Toast.makeText(this, "Veuillez autoriser l'accès aux notifications", Toast.LENGTH_LONG)
                .show()
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } else {
            Toast.makeText(this, "L'accès aux notifications est déjà activé", Toast.LENGTH_SHORT)
                .show()
        }
    }
}
