package me.lucky.wasted

import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init1()
        if (initBiometric()) return
        init2()
        setup()
    }

    private fun init1() {
        prefs = Preferences(this)
        prefsdb = Preferences(this, encrypted = false)
        prefs.copyTo(prefsdb)
        NotificationManager(this).createNotificationChannels()
    }

    private fun init2() {
        replaceFragment(MainFragment())
    }

    private fun initBiometric(): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (BiometricManager
            .from(this)
            .canAuthenticate(authenticators))
        {
            BiometricManager.BIOMETRIC_SUCCESS -> {}
            else -> return false
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback()
        {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                finishAndRemoveTask()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                init2()
                setup()
            }
        })
        try {
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.authentication))
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(authenticators)
                .build())
        } catch (exc: Exception) { return false }
        return true
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
                    replaceFragment(when (supportFragmentManager.fragments.last()) {
                        is SettingsFragment ->
                            getFragment(navigation.checkedItem?.itemId ?: R.id.nav_main)
                        else -> SettingsFragment()
                    })
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
        R.id.top_settings -> SettingsFragment()
        else -> MainFragment()
    }

    private fun copySecret() {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("", prefs.secret))
        Snackbar.make(binding.fragment, R.string.copied_popup, Snackbar.LENGTH_SHORT).show()
    }

    private fun editSecret() {
        val view = layoutInflater.inflate(R.layout.dialog_edit_secret, null)
        val secret = view.findViewById<TextInputLayout>(R.id.secret)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit)
            .setView(view)
            .setPositiveButton(android.R.string.cancel) { _, _ -> }
            .setNegativeButton(android.R.string.ok) { _, _ ->
                if (secret.error != null) return@setNegativeButton
                prefs.secret = secret.editText?.text?.toString()?.trim() ?: return@setNegativeButton
                replaceFragment(MainFragment())
            }
            .create()
        secret.editText?.setText(prefs.secret)
        secret.editText?.doAfterTextChanged {
            secret.error = if (it?.toString()?.isBlank() == true)
                getString(R.string.edit_secret_error) else null
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = secret.error == null
        }
        dialog.show()
    }
}