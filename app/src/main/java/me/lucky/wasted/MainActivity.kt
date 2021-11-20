package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.app.Activity
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { Preferences(this) }
    private val dpm by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val deviceAdmin by lazy { ComponentName(this, DeviceAdminReceiver::class.java) }

    private val requestAdminPolicy =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> setOn()
            else -> binding.toggle.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setup()
    }

    override fun onResume() {
        super.onResume()
        update()
    }

    private fun update() {
        if (prefs.code == "") prefs.code = makeCode()
        binding.apply {
            code.text = prefs.code
            toggle.isChecked = prefs.isServiceEnabled
        }
        if (!isAdminActive() && prefs.isServiceEnabled)
            Toast.makeText(
                this,
                getString(R.string.service_unavailable_toast),
                Toast.LENGTH_SHORT,
            ).show()
    }

    private fun setup() {
        binding.apply {
            code.setOnLongClickListener {
                prefs.code = makeCode()
                code.text = prefs.code
                true
            }
            toggle.setOnCheckedChangeListener { _, isChecked ->
                when (isChecked) {
                    true -> if (!isAdminActive()) requestAdmin() else setOn()
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
        dpm.removeActiveAdmin(deviceAdmin)
        setControlReceiverState(this, false)
        prefs.isServiceEnabled = false
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdmin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description),
            )
        }
        requestAdminPolicy.launch(intent)
    }

    private fun makeCode(): String = UUID.randomUUID().toString()
    private fun isAdminActive(): Boolean = dpm.isAdminActive(deviceAdmin)

    private fun setControlReceiverState(ctx: Context, value: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, ControlReceiver::class.java),
            if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
