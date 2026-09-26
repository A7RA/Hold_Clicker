package com.raclicker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.raclicker.app.accessibility.RaAccessibilityService
import com.raclicker.app.databinding.ActivityMainBinding
import com.raclicker.app.overlay.OverlayService
import com.raclicker.app.settings.SettingsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "RaClicker"

/**
 * MainActivity — single-screen UI built with Material 3 + ViewBinding.
 *
 * Sections:
 *  1. Accessibility Service status + enable button
 *  2. Overlay permission status + enable button
 *  3. Target coordinate display + Set Target button
 *  4. Trigger (Overlay Service) toggle
 *  5. Tap Interval spinner with TPS display
 *  6. Test button (fires a single tap without holding trigger)
 *
 * State
 * ─────
 * UI state is driven by:
 *  - AccessibilityService.isConnected (polled on resume)
 *  - Settings.canDrawOverlays (polled on resume)
 *  - DataStore settings flow
 *  - OverlayService.isRunning (polled on resume + broadcast)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: SettingsRepository

    // ─── Permission launcher (overlay) ───────────────────────────────────────

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Result is always RESULT_CANCELED for this settings screen — check state
        refreshPermissionStates()
    }

    // ─── Notification permission launcher (Android 13+) ──────────────────────

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled silently */ }

    // ─── Broadcast receiver (target updated from OverlayService) ─────────────

    private val targetUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == OverlayService.ACTION_TARGET_UPDATED) {
                val x = intent.getFloatExtra(OverlayService.EXTRA_TARGET_X, -1f)
                val y = intent.getFloatExtra(OverlayService.EXTRA_TARGET_Y, -1f)
                binding.tvTargetCoords.text = getString(R.string.target_coords, x.toInt(), y.toInt())
                binding.tvTargetCoords.visibility = View.VISIBLE
                showSnackbar(getString(R.string.target_saved, x.toInt(), y.toInt()))
            }
        }
    }

    // ─── Interval options ─────────────────────────────────────────────────────

    private val intervalOptions = listOf(10, 20, 30, 50, 75, 100, 150, 200, 300, 500, 1000)
    private val intervalLabels  = intervalOptions.map { ms ->
        val tps = 1000f / ms
        "$ms ms  (${formatTps(tps)} tps)"
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = SettingsRepository(applicationContext)

        setupIntervalSpinner()
        setupClickListeners()
        observeSettings()
        requestNotificationPermissionIfNeeded()

        Log.d(TAG, "MainActivity created")
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()

        // Register receivers
        val filter = IntentFilter(OverlayService.ACTION_TARGET_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(targetUpdatedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(targetUpdatedReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(targetUpdatedReceiver) } catch (_: Exception) {}
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupIntervalSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerInterval.adapter = adapter

        binding.spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val selectedMs = intervalOptions[pos]
                val tps = 1000f / selectedMs
                binding.tvTps.text = getString(R.string.tps_display, formatTps(tps))

                lifecycleScope.launch {
                    repo.saveTapInterval(selectedMs)
                    if (OverlayService.isRunning) {
                        OverlayService.updateInterval(this@MainActivity, selectedMs)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    private fun setupClickListeners() {

        // Accessibility
        binding.btnAccessibility.setOnClickListener {
            if (RaAccessibilityService.isConnected) {
                showSnackbar(getString(R.string.accessibility_already_on))
            } else {
                showAccessibilityExplanationDialog()
            }
        }

        // Overlay permission
        binding.btnOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                showSnackbar(getString(R.string.overlay_already_granted))
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermLauncher.launch(intent)
            }
        }

        // Set target
        binding.btnSetTarget.setOnClickListener {
            when {
                !RaAccessibilityService.isConnected -> {
                    showSnackbar(getString(R.string.error_no_accessibility))
                }
                !Settings.canDrawOverlays(this) -> {
                    showSnackbar(getString(R.string.error_no_overlay))
                }
                !OverlayService.isRunning -> {
                    // Auto-start overlay service, then enter placement mode
                    OverlayService.start(this)
                    // Small delay then request placement — service needs a moment to attach
                    binding.root.postDelayed({
                        OverlayService.requestSetTarget(this)
                        showSnackbar(getString(R.string.tap_to_set_target))
                    }, 400)
                }
                else -> {
                    OverlayService.requestSetTarget(this)
                    showSnackbar(getString(R.string.tap_to_set_target))
                }
            }
        }

        // Trigger (overlay service) toggle
        binding.switchTrigger.setOnCheckedChangeListener { _, isChecked ->
            when {
                !RaAccessibilityService.isConnected -> {
                    binding.switchTrigger.isChecked = false
                    showSnackbar(getString(R.string.error_no_accessibility))
                }
                !Settings.canDrawOverlays(this) -> {
                    binding.switchTrigger.isChecked = false
                    showSnackbar(getString(R.string.error_no_overlay))
                }
                isChecked -> {
                    OverlayService.start(this)
                    showSnackbar(getString(R.string.overlay_started))
                }
                else -> {
                    OverlayService.stop(this)
                    showSnackbar(getString(R.string.overlay_stopped))
                }
            }
        }

        // Test single tap
        binding.btnTest.setOnClickListener {
            val service = RaAccessibilityService.instance
            val settings = OverlayService.instance?.currentSettings

            when {
                service == null -> showSnackbar(getString(R.string.error_no_accessibility))
                settings == null || !settings.isTargetSet ->
                    showSnackbar(getString(R.string.error_no_target))
                else -> {
                    service.injectTap(settings.targetX, settings.targetY)
                    showSnackbar(getString(R.string.test_tap_sent, settings.targetX.toInt(), settings.targetY.toInt()))
                    Log.d(TAG, "Test tap → (${settings.targetX}, ${settings.targetY})")
                }
            }
        }
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            repo.settings.collectLatest { settings ->
                // Sync interval spinner
                val idx = intervalOptions.indexOf(settings.tapIntervalMs)
                if (idx >= 0 && binding.spinnerInterval.selectedItemPosition != idx) {
                    binding.spinnerInterval.setSelection(idx, false)
                }

                // TPS label
                binding.tvTps.text = getString(R.string.tps_display, formatTps(settings.tapsPerSecond))

                // Target coords
                if (settings.isTargetSet) {
                    binding.tvTargetCoords.text = getString(
                        R.string.target_coords,
                        settings.targetX.toInt(),
                        settings.targetY.toInt()
                    )
                    binding.tvTargetCoords.visibility = View.VISIBLE
                } else {
                    binding.tvTargetCoords.text = getString(R.string.target_not_set)
                    binding.tvTargetCoords.visibility = View.VISIBLE
                }
            }
        }
    }

    // ─── Permission state refresh ─────────────────────────────────────────────

    private fun refreshPermissionStates() {
        val accessibilityOn = RaAccessibilityService.isConnected
        val overlayOn       = Settings.canDrawOverlays(this)
        val overlayRunning  = OverlayService.isRunning

        // Accessibility chip
        binding.chipAccessibility.apply {
            text = if (accessibilityOn) getString(R.string.status_enabled)
                   else getString(R.string.status_disabled)
            setChipBackgroundColorResource(
                if (accessibilityOn) R.color.chip_on else R.color.chip_off
            )
        }
        binding.btnAccessibility.text = if (accessibilityOn)
            getString(R.string.btn_accessibility_enabled)
        else
            getString(R.string.btn_accessibility_enable)

        // Overlay chip
        binding.chipOverlay.apply {
            text = if (overlayOn) getString(R.string.status_granted)
                   else getString(R.string.status_denied)
            setChipBackgroundColorResource(
                if (overlayOn) R.color.chip_on else R.color.chip_off
            )
        }
        binding.btnOverlay.text = if (overlayOn)
            getString(R.string.btn_overlay_granted)
        else
            getString(R.string.btn_overlay_grant)

        // Trigger switch (sync without firing listener)
        binding.switchTrigger.setOnCheckedChangeListener(null)
        binding.switchTrigger.isChecked = overlayRunning
        setupClickListeners() // re-attach listener with current state

        // Enable/disable action buttons based on requirements
        val canAct = accessibilityOn && overlayOn
        binding.btnSetTarget.isEnabled = canAct
        binding.btnTest.isEnabled      = canAct
    }

    // ─── Dialogs ──────────────────────────────────────────────────────────────

    private fun showAccessibilityExplanationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_accessibility_title))
            .setMessage(getString(R.string.dialog_accessibility_message))
            .setPositiveButton(getString(R.string.dialog_open_settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun formatTps(tps: Float): String =
        if (tps == kotlin.math.floor(tps.toDouble()).toFloat()) tps.toInt().toString()
        else "%.1f".format(tps)

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
