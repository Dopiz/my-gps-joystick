package com.dopiz.gpsjoystick

import android.Manifest
import android.os.Build
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

        binding.inputLat.setText(MockState.DEFAULT_LAT.toString())
        binding.inputLng.setText(MockState.DEFAULT_LNG.toString())

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

        binding.btnStart.setOnClickListener { startMock() }
        binding.btnStop.setOnClickListener { MockLocationService.stop(this) }

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
        val lat = binding.inputLat.text.toString().toDoubleOrNull()
        val lng = binding.inputLng.text.toString().toDoubleOrNull()
        if (lat == null || lng == null) {
            Toast.makeText(this, "經緯度格式錯誤", Toast.LENGTH_LONG).show()
            return
        }
        MockLocationService.start(this, lat, lng)
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
        renderPermissions(s)
    }

    private fun renderPermissions(serviceState: MockState = MockLocationService.state.value) {
        val p = PermissionChecker.status(this)
        binding.statusText.text = buildString {
            appendLine("Location granted  : ${mark(p.locationGranted)}")
            appendLine("Mock app selected : ${mark(p.isMockAppSelected)}")
            appendLine("Overlay granted   : ${mark(p.overlayGranted)}")
            appendLine("Notifications     : ${mark(p.notificationsGranted)}")
            append("Mock running      : ${mark(serviceState.isRunning)}")
            if (serviceState.isRunning) {
                append("  @ ${"%.5f".format(serviceState.latitude)}, " +
                    "%.5f".format(serviceState.longitude))
            }
        }
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
