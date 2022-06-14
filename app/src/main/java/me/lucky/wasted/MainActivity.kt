package me.lucky.wasted

import android.app.job.JobScheduler
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
    private lateinit var prefs: Preferences
    private lateinit var admin: DeviceAdminManager
    private val shortcut by lazy { ShortcutManager(this) }
    private val job by lazy { WipeJobManager(this) }

    private val registerForDeviceAdmin =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        when (it.resultCode) {
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
        if (prefs.isServiceEnabled && !admin.isActive())
            Snackbar.make(
                binding.toggle,
                R.string.service_unavailable_popup,
                Snackbar.LENGTH_SHORT,
            ).show()
    }

    private fun init() {
        prefs = Preferences(this)
        admin = DeviceAdminManager(this)
        NotificationManager(this).createNotificationChannels()
        if (prefs.code == "") prefs.code = makeCode()
        updateCodeColorState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) hideESIM()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN))
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
                showTriggersSettings()
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
                val num = value.toInt()
                prefs.maxFailedPasswordAttempts = num
                try {
                    admin.setMaximumFailedPasswordsForWipe(num.shl(1))
                } catch (exc: SecurityException) {}
            }
            wipeOnInactivitySwitch.setOnCheckedChangeListener { _, isChecked ->
                setWipeOnInactivityComponentsState(prefs.isServiceEnabled && isChecked)
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
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTriggersSettings() {
        var triggers = prefs.triggers
        val values = Trigger.values().toMutableList()
        val strings = resources.getStringArray(R.array.triggers).toMutableList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            strings.removeAt(values.indexOf(Trigger.TILE))
            values.remove(Trigger.TILE)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.triggers)
            .setMultiChoiceItems(
                strings.toTypedArray(),
                values.map { triggers.and(it.value) != 0 }.toBooleanArray(),
            ) { _, index, isChecked ->
                val flag = values[index]
                triggers = when (isChecked) {
                    true -> triggers.or(flag.value)
                    false -> triggers.and(flag.value.inv())
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.triggers = triggers
                setTriggersState(prefs.isServiceEnabled)
            }
            .show()
    }

    private fun showWipeOnInactivitySettings() {
        val items = resources.getStringArray(R.array.wipe_on_inactivity_days)
        var days = prefs.wipeOnInactivityDays
        var checked = items.indexOf(days.toString())
        if (checked == -1) checked = items
            .indexOf(Preferences.DEFAULT_WIPE_ON_INACTIVITY_DAYS.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wipe_on_inactivity_days)
            .setSingleChoiceItems(items, checked) { _, which ->
                days = items[which].toInt()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.wipeOnInactivityDays = days
                if (prefs.isServiceEnabled && prefs.isWipeOnInactivity) {
                    if (job.schedule() == JobScheduler.RESULT_FAILURE)
                        showWipeJobScheduleFailedPopup()
                }
            }
            .show()
    }

    private fun updateCodeColorState() {
        binding.code.setBackgroundColor(getColor(
            if (prefs.triggers != 0) R.color.code_on else R.color.code_off
        ))
    }

    private fun setOn() {
        if (!setWipeOnInactivityComponentsState(prefs.isWipeOnInactivity)) {
            binding.toggle.isChecked = false
            showWipeJobScheduleFailedPopup()
            return
        }
        prefs.isServiceEnabled = true
        setTriggersState(true)
    }

    private fun setTriggersState(value: Boolean) {
        if (value) {
            val triggers = prefs.triggers
            setPanicKitState(triggers.and(Trigger.PANIC_KIT.value) != 0)
            setTileState(triggers.and(Trigger.TILE.value) != 0)
            shortcut.setState(triggers.and(Trigger.SHORTCUT.value) != 0)
            setCodeReceiverState(triggers.and(Trigger.BROADCAST.value) != 0)
            setNotificationListenerState(triggers.and(Trigger.NOTIFICATION.value) != 0)
        } else {
            setPanicKitState(false)
            setTileState(false)
            shortcut.setState(false)
            setCodeReceiverState(false)
            setNotificationListenerState(false)
        }
        updateCodeColorState()
    }

    private fun showWipeJobScheduleFailedPopup() {
        Snackbar.make(
            binding.toggle,
            R.string.wipe_job_schedule_failed_popup,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private fun setOff() {
        prefs.isServiceEnabled = false
        setWipeOnInactivityComponentsState(false)
        setTriggersState(false)
        admin.remove()
    }

    private fun requestAdmin() = registerForDeviceAdmin.launch(admin.makeRequestIntent())
    private fun makeCode() = UUID.randomUUID().toString()
    private fun setCodeReceiverState(value: Boolean) =
        setComponentState(CodeReceiver::class.java, value)
    private fun setRestartReceiverState(value: Boolean) =
        setComponentState(RestartReceiver::class.java, value)
    private fun setTileState(value: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            setComponentState(TileService::class.java, value)
    }
    private fun setNotificationListenerState(value: Boolean) =
        setComponentState(NotificationListenerService::class.java, value)

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

    private fun setForegroundServiceState(value: Boolean) {
        Intent(this.applicationContext, ForegroundService::class.java).also {
            if (value) ContextCompat.startForegroundService(this.applicationContext, it)
            else stopService(it)
        }
    }

    private fun setWipeOnInactivityComponentsState(value: Boolean): Boolean {
        val result = job.setState(value)
        if (result) {
            setForegroundServiceState(value)
            setRestartReceiverState(value)
        }
        return result
    }
}
