package me.lucky.wasted

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

import me.lucky.wasted.databinding.ActivityMainBinding
import me.lucky.wasted.fragment.*
import me.lucky.wasted.trigger.shared.NotificationManager

open class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences
    private lateinit var prefsdb: Preferences

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefs.copyTo(prefsdb, key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
        setup()
    }

    private fun init() {
        prefs = Preferences(this)
        prefsdb = Preferences(this, encrypted = false)
        prefs.copyTo(prefsdb)
        NotificationManager(this).createNotificationChannels()
        replaceFragment(MainFragment())
    }

    private fun setup() {
        binding.apply {
            appBar.setNavigationOnClickListener {
                drawer.open()
            }
            appBar.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.top_settings -> {
                        replaceFragment(when (supportFragmentManager.fragments.last()) {
                            is SettingsFragment ->
                                getFragment(navigation.checkedItem?.itemId ?: R.id.nav_main)
                            else -> SettingsFragment()
                        })
                        true
                    }
                    else -> false
                }
            }
            navigation.setNavigationItemSelectedListener {
                replaceFragment(getFragment(it.itemId))
                it.isChecked = true
                drawer.close()
                true
            }
        }
    }

    private fun replaceFragment(f: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(binding.fragment.id, f)
            .commit()
    }

    private fun getFragment(id: Int) = when (id) {
        R.id.nav_main -> MainFragment()
        R.id.nav_trigger_lock -> LockFragment()
        R.id.top_settings -> SettingsFragment()
        else -> MainFragment()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefsListener)
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterListener(prefsListener)
    }
}