package me.lucky.wasted

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.*

import me.lucky.wasted.databinding.ActivityMainBinding

open class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { Preferences(this) }
    private val admin by lazy { DeviceAdminManager(this) }
    private val shortcut by lazy { ShortcutManager(this) }

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
            Toast.makeText(
                this,
                getString(R.string.service_unavailable_toast),
                Toast.LENGTH_SHORT,
            ).show()
    }

    private fun init() {
        if (prefs.code == "") prefs.code = makeCode()
        updateCodeColorState()
        binding.apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) wipeESIM.visibility = View.GONE
            code.text = prefs.code
            wipeData.isChecked = prefs.isWipeData
            wipeESIM.isChecked = prefs.isWipeESIM
            wipeESIM.isEnabled = wipeData.isChecked
            maxFailedPasswordAttempts.value = prefs.maxFailedPasswordAttempts.toFloat()
            toggle.isChecked = prefs.isServiceEnabled
        }
    }

    private fun setup() {
        binding.apply {
            code.setOnClickListener {
                prefs.isCodeEnabled = !prefs.isCodeEnabled
                updateCodeColorState()
                setCodeReceiverState(
                    this@MainActivity,
                    prefs.isServiceEnabled && prefs.isCodeEnabled,
                )
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
            toggle.setOnCheckedChangeListener { _, isChecked ->
                when (isChecked) {
                    true -> if (!admin.isActive()) requestAdmin() else setOn()
                    false -> setOff()
                }
            }
        }
    }

    private fun updateCodeColorState() {
        binding.code.setBackgroundColor(getColor(
            if (prefs.isCodeEnabled) R.color.code_receiver_on else R.color.code_receiver_off
        ))
    }

    private fun setOn() {
        prefs.isServiceEnabled = true
        setCodeReceiverState(this, prefs.isCodeEnabled)
        shortcut.push()
    }

    private fun setOff() {
        admin.remove()
        setCodeReceiverState(this, false)
        shortcut.remove()
        prefs.isServiceEnabled = false
    }

    private fun requestAdmin() = requestAdminPolicy.launch(admin.makeRequestIntent())
    private fun makeCode(): String = UUID.randomUUID().toString()

    private fun setCodeReceiverState(ctx: Context, value: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, CodeReceiver::class.java),
            if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
