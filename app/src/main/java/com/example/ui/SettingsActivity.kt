package com.example.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("AitoxCameraSettings", Context.MODE_PRIVATE)

        // Close Activity on Back Button Click
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Initialize spinners
        setupSpinners()

        // Initialize Switches from Preferences
        setupSwitches()
    }

    private fun setupSpinners() {
        // 1. Resolutions
        val resolutions = arrayOf("4K (3840x2160)", "1080p (1920x1080)", "720p (1280x720)")
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinResolution.adapter = resAdapter
        val savedRes = prefs.getString("resolution", "1080p (1920x1080)")
        binding.spinResolution.setSelection(resolutions.indexOf(savedRes).coerceAtLeast(0))
        binding.spinResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("resolution", resolutions[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 2. FPS
        val fpsList = arrayOf("30 FPS", "60 FPS")
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinFps.adapter = fpsAdapter
        val savedFps = prefs.getString("fps", "30 FPS")
        binding.spinFps.setSelection(fpsList.indexOf(savedFps).coerceAtLeast(0))
        binding.spinFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("fps", fpsList[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 3. AI Mode
        val aiModes = arrayOf("Portrait Lighting", "Bokeh Blur", "Sky Replacement", "Scene Detection")
        val aiAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, aiModes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinAiMode.adapter = aiAdapter
        val savedAi = prefs.getString("ai_mode", "Scene Detection")
        binding.spinAiMode.setSelection(aiModes.indexOf(savedAi).coerceAtLeast(0))
        binding.spinAiMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("ai_mode", aiModes[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSwitches() {
        // H.265 (HEVC) switch
        binding.switchH265.isChecked = prefs.getBoolean("use_h265", true)
        binding.switchH265.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_h265", isChecked).apply()
        }

        // EIS switch
        binding.switchEis.isChecked = prefs.getBoolean("use_eis", true)
        binding.switchEis.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_eis", isChecked).apply()
        }

        // 50MP photo switch
        binding.switchFullResolution.isChecked = prefs.getBoolean("full_resolution", false)
        binding.switchFullResolution.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("full_resolution", isChecked).apply()
        }

        // RAW Photo switch
        binding.switchRaw.isChecked = prefs.getBoolean("use_raw", false)
        binding.switchRaw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_raw", isChecked).apply()
        }

        // Screen flash switch
        binding.switchScreenFlash.isChecked = prefs.getBoolean("screen_flash", false)
        binding.switchScreenFlash.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("screen_flash", isChecked).apply()
        }

        // Composition grid switch
        binding.switchGrid.isChecked = prefs.getBoolean("show_grid", false)
        binding.switchGrid.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_grid", isChecked).apply()
        }

        // Focus peaking switch
        binding.switchFocusPeaking.isChecked = prefs.getBoolean("focus_peaking", false)
        binding.switchFocusPeaking.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("focus_peaking", isChecked).apply()
        }

        // HDR switch
        binding.switchHdr.isChecked = prefs.getBoolean("hdr_enabled", false)
        binding.switchHdr.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("hdr_enabled", isChecked).apply()
        }
    }
}
