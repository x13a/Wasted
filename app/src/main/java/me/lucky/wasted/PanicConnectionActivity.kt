package me.lucky.wasted

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog

import info.guardianproject.panic.PanicResponder

class PanicConnectionActivity : MainActivity() {
    private val pm by lazy { packageManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PanicResponder.checkForDisconnectIntent(this)) {
            finish()
            return
        }
        val sender = PanicResponder.getConnectIntentSender(this)
        val packageName = PanicResponder.getTriggerPackageName(this)
        if (sender != "" && sender != packageName) {
            showOptInDialog()
        }
    }

    private fun showOptInDialog() {
        var app: CharSequence = getString(R.string.panic_app_unknown_app)
        val packageName = callingActivity?.packageName
        if (packageName != null) {
            try {
                app = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
            } catch (exc: PackageManager.NameNotFoundException) {}
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.panic_app_dialog_title))
            .setMessage(String.format(getString(R.string.panic_app_dialog_message), app))
            .setNegativeButton(R.string.allow) { _, _ ->
                PanicResponder.setTriggerPackageName(this@PanicConnectionActivity)
                setResult(RESULT_OK)
            }
            .setPositiveButton(R.string.cancel) { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .show()
    }
}
