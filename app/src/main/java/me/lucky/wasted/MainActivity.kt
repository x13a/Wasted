package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.*

import me.lucky.wasted.databinding.ActivityMainBinding

open class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { Preferences(this) }
    private val admin by lazy { DeviceAdmin(this) }

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
        binding.apply {
            code.text = prefs.code
            wipeDataCheckBox.isChecked = prefs.doWipe
            toggle.isChecked = prefs.isServiceEnabled
        }
    }

    private fun setup() {
        binding.apply {
            code.setOnLongClickListener {
                prefs.code = makeCode()
                code.text = prefs.code
                true
            }
            wipeDataCheckBox.setOnCheckedChangeListener { _, isChecked ->
                prefs.doWipe = isChecked
            }
            toggle.setOnCheckedChangeListener { _, isChecked ->
                when (isChecked) {
                    true -> if (!admin.isActive()) requestAdmin() else setOn()
                    false -> setOff()
                }
            }
        }
    }

    private fun setOn() {
        prefs.isServiceEnabled = true
        setControlReceiverState(this, true)
    }

    private fun setOff() {
        admin.remove()
        setControlReceiverState(this, false)
        prefs.isServiceEnabled = false
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin.deviceAdmin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description),
            )
        }
        requestAdminPolicy.launch(intent)
    }

    private fun makeCode(): String = UUID.randomUUID().toString()

    private fun setControlReceiverState(ctx: Context, value: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, CodeReceiver::class.java),
            if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
