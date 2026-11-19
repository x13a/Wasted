package me.lucky.wasted.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log

class DeviceAdminReceiver : DeviceAdminReceiver() {
	companion object {
		private const val TAG = "DeviceAdminReceiver"
	}

	override fun onEnabled(context: Context, intent: Intent) {
		super.onEnabled(context, intent)
		Log.i(TAG, "Device admin enabled")
		DevicePolicyBootstrap.configureActiveAdmin(context, "device-admin-enabled")
	}

	override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
		super.onProfileProvisioningComplete(context, intent)
		Log.i(TAG, "Provisioning complete broadcast received")
		DevicePolicyBootstrap.configureActiveAdmin(context, "profile-provisioning-complete")
	}

	override fun onTransferOwnershipComplete(context: Context, bundle: PersistableBundle?) {
		super.onTransferOwnershipComplete(context, bundle)
		Log.i(TAG, "Ownership transfer complete")
		DevicePolicyBootstrap.configureActiveAdmin(context, "ownership-transfer-complete")
	}

	override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
		return "Disabling Wasted removes the lock and reset privileges that protect this phone."
	}
}