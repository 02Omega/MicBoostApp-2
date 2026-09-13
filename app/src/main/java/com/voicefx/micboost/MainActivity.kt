package com.voicefx.micboost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.voicefx.micboost.databinding.ActivityMainBinding
import com.voicefx.micboost.dsp.MicId
import com.voicefx.micboost.dsp.Presets

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Multi-select: any combination of mics can be active at once (the "combo" feature).
    private val activeMics = mutableSetOf(MicId.GOLDEN)
    private var isRunning = false

    private lateinit var rows: Map<MicId, LinearLayout>
    private lateinit var switches: Map<MicId, SwitchMaterial>

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startService()
        } else {
            Toast.makeText(this, "Mic permission is required to boost your voice.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rows = mapOf(
            MicId.SILVER to binding.rowSilver, MicId.VINTAGE to binding.rowVintage,
            MicId.GOLDEN to binding.rowGolden, MicId.ECHO to binding.rowEcho,
            MicId.BEAST to binding.rowBeast, MicId.ALPHA to binding.rowAlpha
        )
        switches = mapOf(
            MicId.SILVER to binding.swSilver, MicId.VINTAGE to binding.swVintage,
            MicId.GOLDEN to binding.swGolden, MicId.ECHO to binding.swEcho,
            MicId.BEAST to binding.swBeast, MicId.ALPHA to binding.swAlpha
        )

        rows.forEach { (id, row) -> row.setOnClickListener { toggleMic(id) } }
        switches.forEach { (id, sw) ->
            sw.setOnCheckedChangeListener { button, isChecked ->
                // Only react to real user taps on the switch itself, not the
                // programmatic updates render() does — avoids a feedback loop.
                if (button.isPressed) setMic(id, isChecked)
            }
        }

        updateSelectedVisual()

        binding.btnMasterPower.setOnClickListener {
            if (isRunning) stopService() else requestPermissionsAndStart()
        }
    }

    private fun toggleMic(id: MicId) = setMic(id, !activeMics.contains(id))

    private fun setMic(id: MicId, on: Boolean) {
        if (on) {
            activeMics.add(id)
        } else if (activeMics.size > 1) {
            activeMics.remove(id) // keep at least one active
        }
        updateSelectedVisual()
        if (isRunning) {
            val intent = Intent(this, MicFxService::class.java).apply {
                action = MicFxService.ACTION_SWITCH
                putExtra(MicFxService.EXTRA_PRESETS, activeMics.joinToString(",") { it.name })
            }
            startService(intent)
        }
    }

    private fun updateSelectedVisual() {
        rows.forEach { (id, row) -> row.alpha = if (activeMics.contains(id)) 1.0f else 0.55f }
        switches.forEach { (id, sw) -> sw.isChecked = activeMics.contains(id) }

        val comboLabel = activeMics.joinToString(" + ") { Presets.byId(it).label.substringBefore(" —") }
        binding.tvStatus.text = if (isRunning)
            "Running — $comboLabel\n${activeMics.size} mic${if (activeMics.size > 1) "s stacked" else ""} · headset required"
        else
            "Stopped. Tap a row (or its switch) to combo mics, then hit power.\nSelected: $comboLabel"

        binding.btnMasterPower.text = if (isRunning) "POWER OFF" else "POWER ON"
        binding.btnMasterPower.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (isRunning) 0xFF2E7D32.toInt() else 0xFFE63946.toInt()
            )
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startService() {
        val intent = Intent(this, MicFxService::class.java).apply {
            action = MicFxService.ACTION_START
            putExtra(MicFxService.EXTRA_PRESETS, activeMics.joinToString(",") { it.name })
        }
        ContextCompat.startForegroundService(this, intent)
        isRunning = true
        updateSelectedVisual()
    }

    private fun stopService() {
        val intent = Intent(this, MicFxService::class.java).apply { action = MicFxService.ACTION_STOP }
        startService(intent)
        isRunning = false
        updateSelectedVisual()
    }
}
