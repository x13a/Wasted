package me.lucky.wasted

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.*

import me.lucky.wasted.databinding.ActivityMainBinding

open class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { Preferences(this) }
    private val admin by lazy { DeviceAdminManager(this) }
    private val shortcut by lazy { ShortcutManager(this) }
    private val job by lazy { WipeJobManager(this) }

    private val requestAdminPolicy =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            RESULT_OK -> setOn()
            else -> binding.toggle.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
        setup()
    }

    override fun onStart() {
        super.onStart()
        update()
    }

    private fun update() {
        if (!admin.isActive() && prefs.isServiceEnabled)
            Snackbar.make(
                binding.toggle,
                R.string.service_unavailable_popup,
                Snackbar.LENGTH_SHORT,
            ).show()
    }

    private fun init() {
        AppNotificationManager(this).createNotificationChannels()
        if (prefs.code == "") prefs.code = makeCode()
        updateCodeColorState()
        binding.apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) wipeESIM.visibility = View.GONE
            code.text = prefs.code
            wipeData.isChecked = prefs.isWipeData
            wipeESIM.isChecked = prefs.isWipeESIM
            wipeESIM.isEnabled = wipeData.isChecked
            maxFailedPasswordAttempts.value = prefs.maxFailedPasswordAttempts.toFloat()
            wipeOnInactivitySwitch.isChecked = prefs.isWipeOnInactivity
            toggle.isChecked = prefs.isServiceEnabled
        }
    }

    private fun setup() {
        binding.apply {
            code.setOnClickListener {
                prefs.isCodeEnabled = !prefs.isCodeEnabled
                updateCodeColorState()
                setCodeReceiverState(prefs.isServiceEnabled && prefs.isCodeEnabled)
            }
            code.setOnLongClickListener {
                prefs.code = makeCode()
                code.text = prefs.code
                true
            }
            wipeData.setOnCheckedChangeListener { _, isChecked ->
                prefs.isWipeData = isChecked
                wipeESIM.isEnabled = isChecked
            }
            wipeESIM.setOnCheckedChangeListener { _, isChecked ->
                prefs.isWipeESIM = isChecked
            }
            maxFailedPasswordAttempts.addOnChangeListener { _, value, _ ->
                prefs.maxFailedPasswordAttempts = value.toInt()
            }
            wipeOnInactivitySwitch.setOnCheckedChangeListener { _, isChecked ->
                if (!setWipeOnInactivityComponentsState(prefs.isServiceEnabled && isChecked)) {
                    wipeOnInactivitySwitch.isChecked = false
                    showWipeJobServiceStartFailedPopup()
                    return@setOnCheckedChangeListener
                }
                prefs.isWipeOnInactivity = isChecked

            }
            wipeOnInactivitySwitch.setOnLongClickListener {
                showWipeOnInactivitySettings()
                true
            }
            toggle.setOnCheckedChangeListener { _, isChecked ->
                when (isChecked) {
                    true -> if (!admin.isActive()) requestAdmin() else setOn()
                    false -> setOff()
                }
            }
        }
    }

    private fun showWipeOnInactivitySettings() {
        val items = arrayOf("1", "2", "3", "5", "7", "10", "15", "30")
        var days = prefs.wipeOnInactivityDays
        var checked = items.indexOf(days.toString())
        if (checked == -1) checked = items
            .indexOf(Preferences.DEFAULT_WIPE_ON_INACTIVITY_DAYS.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wipe_on_inactivity_days)
            .setSingleChoiceItems(items, checked) { _, which ->
                days = items[which].toInt()
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.wipeOnInactivityDays = days
            }
            .show()
    }

    private fun updateCodeColorState() {
        binding.code.setBackgroundColor(getColor(
            if (prefs.isCodeEnabled) R.color.code_receiver_on else R.color.code_receiver_off
        ))
    }

    private fun setOn() {
        if (!setWipeOnInactivityComponentsState(prefs.isWipeOnInactivity)) {
            binding.toggle.isChecked = false
            showWipeJobServiceStartFailedPopup()
            return
        }
        prefs.isServiceEnabled = true
        setCodeReceiverState(prefs.isCodeEnabled)
        shortcut.push()
    }

    private fun showWipeJobServiceStartFailedPopup() {
        Snackbar.make(
            binding.toggle,
            R.string.wipe_job_service_start_failed_popup,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private fun setOff() {
        prefs.isServiceEnabled = false
        setCodeReceiverState(false)
        setWipeOnInactivityComponentsState(false)
        shortcut.remove()
        admin.remove()
    }

    private fun requestAdmin() = requestAdminPolicy.launch(admin.makeRequestIntent())
    private fun makeCode(): String = UUID.randomUUID().toString()
    private fun setCodeReceiverState(value: Boolean) =
        setReceiverState(CodeReceiver::class.java, value)
    private fun setRestartReceiverState(value: Boolean) =
        setReceiverState(RestartReceiver::class.java, value)

    private fun setReceiverState(cls: Class<*>, value: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, cls),
            if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun setUnlockServiceState(value: Boolean) {
        Intent(this, UnlockService::class.java).also {
            if (value) ContextCompat.startForegroundService(this, it) else stopService(it)
        }
    }

    private fun setWipeOnInactivityComponentsState(value: Boolean): Boolean {
        val result = job.setState(value)
        if (result) {
            setUnlockServiceState(value)
            setRestartReceiverState(value)
        }
        return result
    }
}
