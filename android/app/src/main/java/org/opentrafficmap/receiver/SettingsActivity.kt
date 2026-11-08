package org.opentrafficmap.receiver

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.textfield.TextInputEditText
import org.opentrafficmap.receiver.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var defaultRecordTint: android.content.res.ColorStateList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        defaultRecordTint = binding.btnRecord.backgroundTintList

        // --- Keep screen on -------------------------------------------------
        binding.swKeepScreenOn.isChecked = Prefs.keepScreenOn(this)
        binding.swKeepScreenOn.setOnCheckedChangeListener { _, c ->
            Prefs.setKeepScreenOn(this, c)
            SettingsBus.keepScreenOnChanged()
        }

        // --- Dark mode ------------------------------------------------------
        binding.swDarkMode.isChecked = Prefs.forceDarkMode(this)
        binding.swDarkMode.setOnCheckedChangeListener { _, c ->
            Prefs.setForceDarkMode(this, c)
            SettingsBus.darkModeChanged(c)
            AppCompatDelegate.setDefaultNightMode(
                if (c) AppCompatDelegate.MODE_NIGHT_YES
                else   AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
            recreate()
        }

        // --- Theme color ------------------------------------------------------
        val themeButtonId = when (Prefs.themeMode(this)) {
            Prefs.ThemeMode.DEFAULT   -> R.id.rbThemeDefault
            Prefs.ThemeMode.WALLPAPER -> R.id.rbThemeWallpaper
            Prefs.ThemeMode.CUSTOM    -> R.id.rbThemeCustom
        }
        binding.rgThemeMode.check(themeButtonId)
        binding.themeSwatches.visibility = if (Prefs.themeMode(this) == Prefs.ThemeMode.CUSTOM)
            android.view.View.VISIBLE else android.view.View.GONE
        buildThemeSwatches()
        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeWallpaper -> Prefs.ThemeMode.WALLPAPER
                R.id.rbThemeCustom    -> Prefs.ThemeMode.CUSTOM
                else                   -> Prefs.ThemeMode.DEFAULT
            }
            Prefs.setThemeMode(this, mode)
            binding.themeSwatches.visibility = if (mode == Prefs.ThemeMode.CUSTOM)
                android.view.View.VISIBLE else android.view.View.GONE
            SettingsBus.themeChanged()
            recreate()
        }

        // --- Units ------------------------------------------------------------
        val unitsButtonId = when (Prefs.unitsMode(this)) {
            Prefs.UnitsMode.METRIC   -> R.id.rbUnitsMetric
            Prefs.UnitsMode.IMPERIAL -> R.id.rbUnitsImperial
            Prefs.UnitsMode.AUTO     -> R.id.rbUnitsAuto
        }
        binding.rgUnits.check(unitsButtonId)
        binding.rgUnits.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbUnitsMetric   -> Prefs.UnitsMode.METRIC
                R.id.rbUnitsImperial -> Prefs.UnitsMode.IMPERIAL
                else                  -> Prefs.UnitsMode.AUTO
            }
            Prefs.setUnitsMode(this, mode)
        }

        // --- Reset ----------------------------------------------------------
        binding.btnReset.setOnClickListener {
            SettingsBus.resetAll()
            Toast.makeText(this, getString(R.string.reset), Toast.LENGTH_SHORT).show()
        }

        // --- Auto-follow ----------------------------------------------------
        binding.swFollow.isChecked = Prefs.followEnabled(this)
        binding.swFollow.setOnCheckedChangeListener { _, c ->
            Prefs.setFollowEnabled(this, c)
            SettingsBus.followChanged(c)
        }

        // --- Geiger-counter audio -------------------------------------------
        binding.swAudio.isChecked = Prefs.audioFeedback(this)
        binding.swAudio.setOnCheckedChangeListener { _, c ->
            Prefs.setAudioFeedback(this, c)
            SettingsBus.audioChanged(c)
        }

        // --- SPATEM traffic light -------------------------------------------
        binding.swSpatLight.isChecked = Prefs.spatLightEnabled(this)
        binding.swSpatLight.setOnCheckedChangeListener { _, c ->
            Prefs.setSpatLightEnabled(this, c)
            SettingsBus.spatLightChanged(c)
        }

        // --- MQTT -----------------------------------------------------------
        loadBrokerRows()
        binding.btnAddBroker.setOnClickListener { addBrokerRow("") }

        binding.edNodeId.setText(Prefs.nodeId(this))
        binding.swMqtt.isChecked = Prefs.mqttEnabled(this)
        binding.swMqtt.setOnCheckedChangeListener { _, c ->
            saveBrokerList()
            Prefs.setNodeId(this, binding.edNodeId.text.toString().trim())
            Prefs.setMqttEnabled(this, c)
            SettingsBus.mqttToggle(c)
        }

        // --- MQTT type filter -----------------------------------------------
        binding.btnMqttFilter.setOnClickListener { showMqttFilterDialog() }

        // --- Recording ------------------------------------------------------
        binding.swAutoRecord.isChecked = Prefs.autoRecording(this)
        binding.swAutoRecord.setOnCheckedChangeListener { _, c -> Prefs.setAutoRecording(this, c) }
        renderRecordingState()
        binding.btnRecord.setOnClickListener {
            val msg = SettingsBus.recordingToggle()
            Toast.makeText(this, msg ?: getString(R.string.rec_status_idle), Toast.LENGTH_LONG).show()
            renderRecordingState()
        }
        binding.btnOpenRecordingsFolder.setOnClickListener { openRecordingsFolder() }
        binding.btnExportKml.setOnClickListener {
            val f = SettingsBus.exportKml()
            Toast.makeText(this, if (f != null) getString(R.string.kml_exported, f.absolutePath)
                else getString(R.string.kml_export_failed), Toast.LENGTH_LONG).show()
        }

        // --- Marker / path TTL ----------------------------------------------
        binding.edMarkerTtl.setText(Prefs.markerTtlMinutes(this).toString())

        // --- Map prefetch ---------------------------------------------------
        binding.btnDownloadMap.setOnClickListener {
            SettingsBus.downloadVisibleMap()
            finish()
        }

        // --- Marker size ------------------------------------------------------
        val markerSizeButtonId = when (Prefs.markerSize(this)) {
            Prefs.MarkerSize.SMALL  -> R.id.rbMarkerSmall
            Prefs.MarkerSize.NORMAL -> R.id.rbMarkerNormal
            Prefs.MarkerSize.LARGE  -> R.id.rbMarkerLarge
        }
        binding.rgMarkerSize.check(markerSizeButtonId)
        binding.rgMarkerSize.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setMarkerSize(this, when (checkedId) {
                R.id.rbMarkerSmall -> Prefs.MarkerSize.SMALL
                R.id.rbMarkerLarge -> Prefs.MarkerSize.LARGE
                else                 -> Prefs.MarkerSize.NORMAL
            })
        }

        // --- Own track ------------------------------------------------------
        binding.swOwnTrack.isChecked = Prefs.ownTrackEnabled(this)
        binding.swOwnTrack.setOnCheckedChangeListener { _, c ->
            Prefs.setOwnTrackEnabled(this, c)
            SettingsBus.ownTrackChanged(c)
        }

        // --- High Rate Mode ---------------------------------------------------
        binding.swHighRate.isChecked = Prefs.highRateMode(this)
        binding.swHighRate.setOnCheckedChangeListener { _, c ->
            Prefs.setHighRateMode(this, c)
            SettingsBus.highRateChanged(c)
        }

        // --- BLE coex -------------------------------------------------------
        binding.btnCycle.setOnClickListener {
            val controller = SettingsBus.btController()
            if (controller == null) {
                Toast.makeText(this, getString(R.string.cycle_not_connected), Toast.LENGTH_SHORT).show()
            } else {
                CycleSettingsDialog.show(this, SettingsBus.lastCycleConfig()) { SettingsBus.applyCycle(it) }
            }
        }

    }

    override fun onPause() {
        super.onPause()
        saveBrokerList()
        Prefs.setNodeId(this, binding.edNodeId.text.toString().trim())
        binding.edMarkerTtl.text.toString().trim().toIntOrNull()
            ?.let { Prefs.setMarkerTtlMinutes(this, it) }
    }

    // ── Broker row management ────────────────────────────────────────────────

    private fun loadBrokerRows() {
        binding.brokerContainer.removeAllViews()
        val brokers = Prefs.mqttBrokerList(this)
        if (brokers.isEmpty()) {
            addBrokerRow("")
        } else {
            brokers.forEach { addBrokerRow(it) }
        }
    }

    private fun addBrokerRow(url: String) {
        val row = layoutInflater.inflate(R.layout.item_broker_entry, binding.brokerContainer, false)
        row.findViewById<TextInputEditText>(R.id.edBrokerEntry).setText(url)
        row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteEntry)
            .setOnClickListener {
                binding.brokerContainer.removeView(row)
                // Always keep at least one empty row so the field is never fully gone
                if (binding.brokerContainer.childCount == 0) addBrokerRow("")
            }
        binding.brokerContainer.addView(row)
        // Focus the new (empty) field
        if (url.isEmpty()) {
            row.findViewById<TextInputEditText>(R.id.edBrokerEntry).requestFocus()
        }
    }

    private fun saveBrokerList() {
        val urls = mutableListOf<String>()
        for (i in 0 until binding.brokerContainer.childCount) {
            val url = binding.brokerContainer.getChildAt(i)
                .findViewById<TextInputEditText>(R.id.edBrokerEntry)
                ?.text?.toString()?.trim()
            if (!url.isNullOrBlank()) urls.add(url)
        }
        Prefs.setMqttBrokerList(this, urls)
    }

    // ── MQTT filter dialog ───────────────────────────────────────────────────

    private fun showMqttFilterDialog() {
        TypeFilterDialog.show(this, R.string.mqtt_filter_btn, Prefs.mqttFilterTypes(this)) { newFilter ->
            Prefs.setMqttFilterTypes(this, newFilter)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun renderRecordingState() {
        val rec = SettingsBus.recorder()
        val recording = rec?.isRecording == true
        binding.btnRecord.text = getString(if (recording) R.string.rec_on else R.string.rec_off)
        binding.btnRecord.backgroundTintList = if (recording)
            android.content.res.ColorStateList.valueOf(StatusColors.RED) else defaultRecordTint
        binding.recStatus.text = if (recording)
            getString(R.string.rec_status_active, rec?.file?.absolutePath ?: "?", rec?.frameCount ?: 0)
        else getString(R.string.rec_status_idle)
    }

    private fun openRecordingsFolder() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", dir)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.open_folder_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildThemeSwatches() {
        binding.themeSwatches.removeAllViews()
        val dp = resources.displayMetrics.density
        val size = (40 * dp).toInt()
        val current = Prefs.themeColor(this)
        for (color in THEME_SWATCH_COLORS) {
            val swatch = android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (8 * dp).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    if (color == current) setStroke((3 * dp).toInt(), android.graphics.Color.WHITE)
                }
                setOnClickListener {
                    Prefs.setThemeColor(this@SettingsActivity, color)
                    buildThemeSwatches()
                    SettingsBus.themeChanged()
                    recreate()
                }
            }
            binding.themeSwatches.addView(swatch)
        }
    }

    companion object {
        private val THEME_SWATCH_COLORS = listOf(
            0xFF58A6FF.toInt(), // blue (accent)
            0xFF7EE787.toInt(), // green (ok)
            0xFFF0883E.toInt(), // orange (warn)
            0xFFDA3633.toInt(), // red (danger)
            0xFF9C6ADE.toInt(), // purple
            0xFF2DD4BF.toInt(), // teal
        )
    }
}
