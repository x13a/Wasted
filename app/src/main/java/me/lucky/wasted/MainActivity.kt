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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) hideESIM()
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN))
            hideSecureLockScreenRequired()
        binding.apply {
            code.text = prefs.code
            wipeData.isChecked = prefs.isWipeData
            wipeESIM.isChecked = prefs.isWipeESIM
            wipeESIM.isEnabled = wipeData.isChecked
            maxFailedPasswordAttempts.value = prefs.maxFailedPasswordAttempts.toFloat()
            wipeOnInactivitySwitch.isChecked = prefs.isWipeOnInactivity
            toggle.isChecked = prefs.isServiceEnabled
        }
    }

    private fun hideESIM() {
        binding.wipeESIMSpace.visibility = View.GONE
        binding.wipeESIM.visibility = View.GONE
    }

    private fun hideSecureLockScreenRequired() {
        binding.apply {
            divider.visibility = View.GONE
            maxFailedPasswordAttempts.visibility = View.GONE
            maxFailedPasswordAttemptsDescription.visibility = View.GONE
            wipeOnInactivitySpace.visibility = View.GONE
            wipeOnInactivitySwitch.visibility = View.GONE
            wipeOnInactivityDescription.visibility = View.GONE
        }
    }

    private fun setup() {
        binding.apply {
            code.setOnClickListener {
                showLaunchersSettings()
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
            toggle.setOnLongClickListener {
                if (!toggle.isChecked) return@setOnLongClickListener false
                showPanicDialog()
                true
            }
        }
    }

    private fun showPanicDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_confirm_panic_title)
            .setMessage(R.string.dialog_confirm_panic_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    admin.lockNow()
                    if (prefs.isWipeData) admin.wipeData()
                } catch (exc: SecurityException) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLaunchersSettings() {
        var launchers = prefs.launchers
        val checkedLaunchers = mutableListOf<Boolean>()
        for (launcher in Launcher.values()) {
            checkedLaunchers.add(launchers.and(launcher.flag) != 0)
        }
        MaterialAlertDialogBuilder(this)
            .setMultiChoiceItems(
                resources.getStringArray(R.array.launchers),
                checkedLaunchers.toBooleanArray(),
            ) { _, index, isChecked ->
                val value = Launcher.values()[index]
                launchers = when (isChecked) {
                    true -> launchers.or(value.flag)
                    false -> launchers.and(value.flag.inv())
                }
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.launchers = launchers
                setLaunchersState(prefs.isServiceEnabled)
            }
            .show()
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
            if (prefs.launchers != 0) R.color.code_receiver_on else R.color.code_receiver_off
        ))
    }

    private fun setOn() {
        if (!setWipeOnInactivityComponentsState(prefs.isWipeOnInactivity)) {
            binding.toggle.isChecked = false
            showWipeJobServiceStartFailedPopup()
            return
        }
        prefs.isServiceEnabled = true
        setLaunchersState(true)
    }

    private fun setLaunchersState(value: Boolean) {
        if (value) {
            val launchers = prefs.launchers
            setPanicKitState(launchers.and(Launcher.PANIC_KIT.flag) != 0)
            setTileState(launchers.and(Launcher.TILE.flag) != 0)
            shortcut.setState(launchers.and(Launcher.SHORTCUT.flag) != 0)
            setCodeReceiverState(launchers.and(Launcher.BROADCAST.flag) != 0)
        } else {
            setPanicKitState(false)
            setTileState(false)
            shortcut.setState(false)
            setCodeReceiverState(false)
        }
        updateCodeColorState()
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
        setWipeOnInactivityComponentsState(false)
        setLaunchersState(false)
        admin.remove()
    }

    private fun requestAdmin() = requestAdminPolicy.launch(admin.makeRequestIntent())
    private fun makeCode(): String = UUID.randomUUID().toString()
    private fun setCodeReceiverState(value: Boolean) =
        setComponentState(CodeReceiver::class.java, value)
    private fun setRestartReceiverState(value: Boolean) =
        setComponentState(RestartReceiver::class.java, value)
    private fun setTileState(value: Boolean) =
        setComponentState(QSTileService::class.java, value)

    private fun setPanicKitState(value: Boolean) {
        setComponentState(PanicConnectionActivity::class.java, value)
        setComponentState(PanicResponderActivity::class.java, value)
    }

    private fun setComponentState(cls: Class<*>, value: Boolean) {
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
