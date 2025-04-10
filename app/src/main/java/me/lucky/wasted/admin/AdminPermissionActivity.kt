package me.lucky.wasted.admin

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class AdminPermissionActivity : Activity() {

    private lateinit var adminManager: DeviceAdminManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adminManager = DeviceAdminManager(this)

        val intent = adminManager.makeRequestIntent()
        try {
            startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur lors de la demande d'autorisation admin", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            if (adminManager.isActive()) {
                Toast.makeText(this, "🎉 Droits administrateur activés !", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "❌ Autorisation refusée", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    companion object {
        private const val REQUEST_CODE = 1234
    }
}
