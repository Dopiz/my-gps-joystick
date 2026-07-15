package com.dopiz.gpsjoystick

import android.Manifest
import android.os.Build
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.dopiz.gpsjoystick.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            renderPermissions()
        }

    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantLocation.setOnClickListener {
            if (PermissionChecker.isLocationGranted(this)) {
                startActivity(PermissionChecker.appDetailsIntent(this))
            } else {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        binding.btnSetMockApp.setOnClickListener {
            startActivity(PermissionChecker.developerOptionsIntent())
        }
        binding.btnGrantOverlay.setOnClickListener {
            startActivity(PermissionChecker.overlaySettingsIntent(this))
        }

        // 模擬開/關 toggle — 開始模擬 (orange, real GPS) ↔ 停止模擬 (green, mocking).
        // Starting mock also brings up the floating overlay (hub + joystick); stopping tears it down.
        binding.btnMockToggle.setOnClickListener {
            if (MockLocationService.state.value.isRunning) {
                MockLocationService.stop(this)
                OverlayService.hide(this)
            } else {
                startMock()
            }
        }

        binding.btnOpenMap.setOnClickListener {
            startActivity(android.content.Intent(this, MapActivity::class.java))
        }
        binding.btnOpenGpx.setOnClickListener {
            startActivity(android.content.Intent(this, GpxLibraryActivity::class.java))
        }

        binding.btnRecordTap1.setOnClickListener { recordTapSlot(1) }
        binding.btnRecordTap2.setOnClickListener { recordTapSlot(2) }
        binding.btnRecordTap3.setOnClickListener { recordTapSlot(3) }

        maybeRequestNotifications()
        observeService()
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
        renderTapSlots()
    }

    /**
     * Start recording preset [slot]: tell [OverlayService] to open the full-screen point picker, then
     * drop this activity to the background so the user can pick points on the target app's screen.
     * Needs the overlay permission (same gate as the joystick) to draw the picker.
     */
    private fun recordTapSlot(slot: Int) {
        if (!PermissionChecker.isOverlayGranted(this)) {
            Toast.makeText(this, R.string.autotap_need_overlay, Toast.LENGTH_LONG).show()
            startActivity(PermissionChecker.overlaySettingsIntent(this))
            return
        }
        startService(
            android.content.Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_PICK_TAP)
                .putExtra(OverlayService.EXTRA_SLOT, slot)
        )
        moveTaskToBack(true)
    }

    /** Refresh the three 連點 preset rows: label + "未設定 / N 個點" status. */
    private fun renderTapSlots() {
        val labels = listOf(binding.tapSlotLabel1, binding.tapSlotLabel2, binding.tapSlotLabel3)
        val statuses = listOf(binding.tapSlotStatus1, binding.tapSlotStatus2, binding.tapSlotStatus3)
        for (i in 0..2) {
            val slot = i + 1
            labels[i].text = getString(R.string.autotap_slot_label, slot)
            val n = SessionStore.loadTapPoints(this, slot).size
            statuses[i].text =
                if (n == 0) getString(R.string.autotap_slot_unset)
                else getString(R.string.autotap_slot_count, n)
        }
    }

    private fun startMock() {
        if (!PermissionChecker.isLocationGranted(this)) {
            Toast.makeText(this, "請先授予定位權限", Toast.LENGTH_LONG).show()
            return
        }
        // No manual coordinate: the service seeds from the current real location.
        MockLocationService.startAtRealLocation(this)
        // Bring up the floating overlay (hub + joystick) alongside the mock. If overlay permission
        // is missing we still start mocking, but guide the user to grant it so the joystick appears.
        if (PermissionChecker.isOverlayGranted(this)) {
            OverlayService.showAll(this)
        } else {
            Toast.makeText(this, R.string.overlay_needed_for_joystick, Toast.LENGTH_LONG).show()
            startActivity(PermissionChecker.overlaySettingsIntent(this))
        }
    }

    private fun observeService() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MockLocationService.state.collect { s -> renderServiceState(s) }
            }
        }
    }

    private fun renderServiceState(s: MockState) {
        if (s.error != null) {
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.text = s.error
        } else {
            binding.errorText.visibility = View.GONE
        }
        // Button 1 reflects the live mock state: blue 開始模擬 on real GPS,
        // green 停止模擬 while mocking, so the user can tell at a glance.
        val activeColor = if (s.isRunning) R.color.status_active else R.color.brand_primary
        binding.btnMockToggle.backgroundTintList =
            ColorStateList.valueOf(getColor(activeColor))
        binding.btnMockToggle.setText(
            if (s.isRunning) R.string.stop_mock else R.string.start_mock
        )
        renderPermissions()
    }

    /**
     * Reflect setup state on the 權限設定 section: a green check appears in its title row once all
     * three gates are granted, and the card demotes itself to the bottom of the page.
     */
    private fun renderPermissions() {
        val p = PermissionChecker.status(this)
        val allReady = p.locationGranted && p.isMockAppSelected && p.overlayGranted
        val doneOrCross = { granted: Boolean ->
            if (granted) R.drawable.ic_step_done else R.drawable.ic_step_cross
        }
        binding.permStatusLocation.setImageResource(doneOrCross(p.locationGranted))
        binding.permStatusMock.setImageResource(doneOrCross(p.isMockAppSelected))
        binding.permStatusOverlay.setImageResource(doneOrCross(p.overlayGranted))
        reorderPermissions(allReady)
    }

    /**
     * Keep the permissions card where it is most useful: pinned as the first section (just
     * below the logo at index 0) while any gate is missing, and demoted to the very bottom
     * once all three are granted. Moves the single view instead of rebuilding the page.
     */
    private fun reorderPermissions(allReady: Boolean) {
        val root = binding.contentRoot
        val card = binding.permissionsCard
        val desiredIndex = if (allReady) root.childCount - 1 else 1
        if (root.indexOfChild(card) == desiredIndex) return
        root.removeView(card)
        if (allReady) root.addView(card) else root.addView(card, 1)
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionChecker.isNotificationsGranted(this)
        ) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
