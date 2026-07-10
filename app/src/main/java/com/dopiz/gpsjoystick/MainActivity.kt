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
    private var overlayShown = false

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

        // One stateful button: 開始模擬 (orange, real GPS) ↔ 模擬中·回到真實定位 (green, mocking).
        binding.btnMockToggle.setOnClickListener {
            if (MockLocationService.state.value.isRunning) {
                MockLocationService.stop(this)
            } else {
                startMock()
            }
        }

        binding.btnToggleOverlay.setOnClickListener { toggleOverlay() }
        binding.btnOpenMap.setOnClickListener {
            startActivity(android.content.Intent(this, MapActivity::class.java))
        }
        binding.btnOpenGpx.setOnClickListener {
            startActivity(android.content.Intent(this, GpxLibraryActivity::class.java))
        }

        maybeRequestNotifications()
        observeService()
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
    }

    private fun startMock() {
        if (!PermissionChecker.isLocationGranted(this)) {
            Toast.makeText(this, "請先授予定位權限", Toast.LENGTH_LONG).show()
            return
        }
        // No manual coordinate: the service seeds from the current real location.
        MockLocationService.startAtRealLocation(this)
    }

    private fun toggleOverlay() {
        if (!PermissionChecker.isOverlayGranted(this)) {
            Toast.makeText(this, "請先授予懸浮窗權限", Toast.LENGTH_LONG).show()
            startActivity(PermissionChecker.overlaySettingsIntent(this))
            return
        }
        overlayShown = !overlayShown
        if (overlayShown) {
            OverlayService.show(this)
            binding.btnToggleOverlay.setText(R.string.hide_overlay)
        } else {
            OverlayService.hide(this)
            binding.btnToggleOverlay.setText(R.string.show_overlay)
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
        // The single toggle reflects the live mock state: orange 開始模擬 on real GPS,
        // green 模擬中·回到真實定位 while mocking, so the user can tell at a glance.
        val activeColor = if (s.isRunning) R.color.status_active else R.color.brand_tertiary
        binding.btnMockToggle.backgroundTintList =
            ColorStateList.valueOf(getColor(activeColor))
        binding.btnMockToggle.setText(
            if (s.isRunning) R.string.mock_toggle_active else R.string.start_mock
        )
        renderPermissions(s)
    }

    private fun renderPermissions(serviceState: MockState = MockLocationService.state.value) {
        val p = PermissionChecker.status(this)
        renderOnboarding(p)
        binding.statusText.text = buildString {
            appendLine("Location granted  : ${mark(p.locationGranted)}")
            appendLine("Mock app selected : ${mark(p.isMockAppSelected)}")
            appendLine("Overlay granted   : ${mark(p.overlayGranted)}")
            appendLine("Notifications     : ${mark(p.notificationsGranted)}")
            appendLine("Mock running      : ${mark(serviceState.isRunning)}")
            appendLine("Speed             : ${"%.0f".format(serviceState.speedMps)} m/s")
            appendLine("Direction         : ${serviceState.direction}")
            append("Position          : ${"%.5f".format(serviceState.latitude)}, " +
                "%.5f".format(serviceState.longitude))
        }
    }

    /**
     * First-run guidance (Slice 11): while any of the three setup gates is missing, show a
     * banner listing the outstanding steps; the existing grant buttons perform the jumps. Once
     * everything is in place it collapses to a short "done" note.
     */
    private fun renderOnboarding(p: PermissionChecker.Status) {
        val allReady = p.locationGranted && p.isMockAppSelected && p.overlayGranted
        binding.onboardingTitle.setText(
            if (allReady) R.string.onboarding_done else R.string.onboarding_title
        )
        stepIcon(binding.stepIcon1, p.locationGranted)
        stepIcon(binding.stepIcon2, p.isMockAppSelected)
        stepIcon(binding.stepIcon3, p.overlayGranted)
    }

    private fun stepIcon(view: android.widget.ImageView, done: Boolean) {
        view.setImageResource(if (done) R.drawable.ic_step_done else R.drawable.ic_step_todo)
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionChecker.isNotificationsGranted(this)
        ) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun mark(ok: Boolean) = if (ok) "YES" else "no"
}
