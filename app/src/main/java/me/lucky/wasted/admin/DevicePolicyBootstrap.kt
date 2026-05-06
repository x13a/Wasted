package me.lucky.wasted.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

object DevicePolicyBootstrap {
    private const val TAG = "DeviceAdminBootstrap"

    fun configureActiveAdmin(context: Context, source: String) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        if (manager == null) {
            Log.e(TAG, "DevicePolicyManager unavailable while configuring admin from $source")
            return
        }

        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        if (!manager.isAdminActive(admin)) {
            Log.w(TAG, "Admin not active while configuring from $source")
            return
        }

        val isDeviceOwner = manager.isDeviceOwnerApp(context.packageName)
        val isProfileOwner = manager.isProfileOwnerApp(context.packageName)
        Log.i(
            TAG,
            "configureActiveAdmin source=$source deviceOwner=$isDeviceOwner profileOwner=$isProfileOwner",
        )

        runCatching {
            manager.setShortSupportMessage(
                admin,
                "Wasted controls lock and reset behavior for this managed phone.",
            )
        }.onFailure {
            Log.w(TAG, "Unable to set short support message", it)
        }

        runCatching {
            manager.setLongSupportMessage(
                admin,
                "Wasted is configured as the device management app for this phone. Remote lock and remote reset depend on this management role remaining active.",
            )
        }.onFailure {
            Log.w(TAG, "Unable to set long support message", it)
        }

        if (isProfileOwner) {
            runCatching {
                manager.setProfileName(admin, "Wasted")
            }.onFailure {
                Log.w(TAG, "Unable to set managed profile name", it)
            }

            runCatching {
                manager.setProfileEnabled(admin)
            }.onFailure {
                Log.w(TAG, "Unable to enable managed profile", it)
            }
        }

        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isDeviceOwner) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isProfileOwner)
        ) {
            runCatching {
                manager.setOrganizationName(admin, "Wasted")
            }.onFailure {
                Log.w(TAG, "Unable to set organization name", it)
            }
        }
    }
}