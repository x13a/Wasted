package me.lucky.wasted.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import me.lucky.wasted.MainActivity

class AdminProvisioningActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ProvisioningActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> handleGetProvisioningMode()
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> handleAdminPolicyCompliance()
            DevicePolicyManager.ACTION_PROVISIONING_SUCCESSFUL -> handleProvisioningSuccessful()
            else -> {
                Log.w(TAG, "Unsupported provisioning action: ${intent.action}")
                finish()
            }
        }
    }

    private fun handleGetProvisioningMode() {
        val requestedModes = intent.getIntegerArrayListExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES,
        )
        val selectedMode = when {
            requestedModes.isNullOrEmpty() -> DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            requestedModes.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE) -> {
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            }
            requestedModes.contains(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE) -> {
                DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE
            }
            else -> requestedModes.first()
        }

        Log.i(TAG, "Returning provisioning mode=$selectedMode")
        val resultData = Intent()
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, selectedMode)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS, false)

        setResult(Activity.RESULT_OK, resultData)
        finish()
    }

    private fun handleAdminPolicyCompliance() {
        Log.i(TAG, "Admin policy compliance acknowledged")
        DevicePolicyBootstrap.configureActiveAdmin(this, "admin-policy-compliance")
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun handleProvisioningSuccessful() {
        Log.i(TAG, "Provisioning successful activity launched")
        DevicePolicyBootstrap.configureActiveAdmin(this, "provisioning-successful")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}