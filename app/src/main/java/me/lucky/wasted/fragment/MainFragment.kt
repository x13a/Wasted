package me.lucky.wasted.fragment
import me.lucky.wasted.Preferences


import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.*
import me.lucky.wasted.R
import me.lucky.wasted.Utils
import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.databinding.FragmentMainBinding

class MainFragment : Fragment() {
    private lateinit var binding: FragmentMainBinding
    private lateinit var ctx: Context
    private lateinit var prefs: Preferences
    private lateinit var prefsdb: Preferences
    private val admin by lazy { DeviceAdminManager(ctx) }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefs.copyTo(prefsdb, key)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        init()
        setup()
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefsListener)
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterListener(prefsListener)
    }

    private fun init() {
        ctx = requireContext()
        prefs = Preferences(ctx)
        prefsdb = Preferences(ctx, encrypted = false)

        if (prefs.notificationKeyword.isEmpty()) {
            val generatedSecret = makeSecret()
            prefs.notificationKeyword = generatedSecret
            Log.d("MainFragment", "Generated new secret: $generatedSecret")
        }

        binding.apply {
            secret.text = prefs.notificationKeyword

            val colorRes = if (prefs.triggers != 0) R.color.secret_1 else R.color.secret_0
            try {
                secret.setBackgroundColor(ContextCompat.getColor(ctx, colorRes))
            } catch (e: Exception) {
                Log.w("MainFragment", "Color resource not found: $colorRes")
            }

            wipeData.isChecked = prefs.isWipeData
            wipeEmbeddedSim.isChecked = prefs.isWipeEmbeddedSim
            wipeEmbeddedSim.isEnabled = wipeData.isChecked
            toggle.isChecked = prefs.isEnabled
        }
    }

    private fun setup() = binding.apply {
        wipeData.setOnCheckedChangeListener { _, isChecked ->
            prefs.isWipeData = isChecked
            wipeEmbeddedSim.isEnabled = isChecked
        }
        wipeEmbeddedSim.setOnCheckedChangeListener { _, isChecked ->
            prefs.isWipeEmbeddedSim = isChecked
        }
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestAdmin() else setOff()
        }
    }

    private fun setOn() {
        prefs.isEnabled = true
        Utils(ctx).setEnabled(true)
        binding.toggle.isChecked = true
    }

    private fun setOff() {
        prefs.isEnabled = false
        Utils(ctx).setEnabled(false)
        try {
            admin.remove()
        } catch (exc: SecurityException) {
            Log.e("MainFragment", "Failed to remove device admin", exc)
        }
        binding.toggle.isChecked = false
    }

    private val registerForDeviceAdmin =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                Log.d("MainFragment", "Device admin granted")
                setOn()
            } else {
                Log.w("MainFragment", "Device admin NOT granted")
                setOff()
            }
        }

    private fun requestAdmin() = registerForDeviceAdmin.launch(admin.makeRequestIntent())

    private fun makeSecret() = UUID.randomUUID().toString()
}
