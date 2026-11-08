package org.opentrafficmap.receiver

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import org.opentrafficmap.receiver.databinding.ActivityMainBinding

/**
 * Host Activity for the 4-tab bottom nav (Map / Radar / Statistics / Range).
 * Owns only what's genuinely Activity-scoped: the legal-acceptance gate,
 * window flags (keep-screen-on), dark-mode recreation, and fragment
 * switching. Everything else — connections, the frame stream, MQTT,
 * recording, location — lives in [MainViewModel], shared by all tabs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applyTo(this)
        super.onCreate(savedInstanceState)
        if (!Prefs.legalAccepted(this)) { showLegalDialog(savedInstanceState); return }
        setupUi(savedInstanceState)
    }

    private fun showLegalDialog(savedInstanceState: Bundle?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.legal_title)
            .setMessage(R.string.legal_body)
            .setCancelable(false)
            .setPositiveButton(R.string.legal_accept) { _, _ ->
                Prefs.setLegalAccepted(this, true)
                setupUi(savedInstanceState)
            }
            .setNegativeButton(R.string.legal_quit) { _, _ -> finish() }
            .show()
    }

    private fun setupUi(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(when (item.itemId) {
                R.id.nav_radar      -> RadarFragment.TAG
                R.id.nav_statistics -> StatisticsFragment.TAG
                R.id.nav_range      -> RangeFragment.TAG
                R.id.nav_settings   -> SettingsFragment.TAG
                else                 -> MapFragment.TAG
            })
            true
        }

        if (savedInstanceState == null) {
            showTab(MapFragment.TAG)
        }

        applyKeepScreenOn()
        wireSettingsBus()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) applyKeepScreenOn()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ReceiverForegroundService::class.java))
    }

    // ----------------------------------------------------------- Fragments

    private fun showTab(tag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.fragments.forEach { tx.hide(it) }

        var frag = fm.findFragmentByTag(tag)
        if (frag == null) {
            frag = when (tag) {
                RadarFragment.TAG      -> RadarFragment()
                StatisticsFragment.TAG -> StatisticsFragment()
                RangeFragment.TAG      -> RangeFragment()
                SettingsFragment.TAG   -> SettingsFragment()
                else                     -> MapFragment()
            }
            tx.add(R.id.fragmentContainer, frag, tag)
        } else {
            tx.show(frag)
        }
        tx.commitNow()
    }

    private fun mapFragment(): MapFragment? =
        supportFragmentManager.findFragmentByTag(MapFragment.TAG) as? MapFragment

    // ----------------------------------------------------------- Settings bus

    private fun wireSettingsBus() {
        SettingsBus.onFollowChanged = SettingsBus.OnFollowChanged { on ->
            viewModel.setFollowEnabled(on)
            mapFragment()?.refreshLocationSubscription()
        }
        SettingsBus.onMqttToggle = SettingsBus.OnMqttToggle { on -> viewModel.setMqttEnabled(on) }
        SettingsBus.onRecordingToggle = SettingsBus.OnRecordingToggle { viewModel.toggleRecording() }
        SettingsBus.onMapDownload = SettingsBus.OnMapDownload { mapFragment()?.downloadVisibleMap() }
        SettingsBus.onAudioChanged = SettingsBus.OnAudioChanged { on -> viewModel.setAudioFeedback(on) }
        SettingsBus.onSpatLightChanged = SettingsBus.OnSpatLightChanged { on -> viewModel.setSpatLightEnabled(on) }
        SettingsBus.onKeepScreenOnChanged = SettingsBus.OnKeepScreenOnChanged { applyKeepScreenOn() }
        SettingsBus.onDarkModeChanged = SettingsBus.OnDarkModeChanged { on ->
            AppCompatDelegate.setDefaultNightMode(
                if (on) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
        SettingsBus.onOwnTrackChanged = SettingsBus.OnOwnTrackChanged { on ->
            viewModel.setOwnTrackEnabled(on)
            mapFragment()?.refreshLocationSubscription()
            if (!on) mapFragment()?.clearOwnTrack()
        }
        SettingsBus.onHighRateChanged = SettingsBus.OnHighRateChanged { on -> viewModel.setHighRateMode(on) }
        SettingsBus.onCycleApply = SettingsBus.OnCycleApply { c ->
            val ok = viewModel.applyCycle(c)
            Toast.makeText(this, getString(if (ok) R.string.cycle_saved else R.string.cycle_not_connected), Toast.LENGTH_SHORT).show()
        }
        SettingsBus.onResetAll = SettingsBus.OnResetAll {
            viewModel.resetAll()
            mapFragment()?.resetMapUi()
        }
        SettingsBus.onExportKml = SettingsBus.OnExportKml { viewModel.exportKml() }
        SettingsBus.onThemeChanged = SettingsBus.OnThemeChanged { recreate() }
    }

    // ------------------------------------------ Keep screen on

    private fun applyKeepScreenOn() {
        if (Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
