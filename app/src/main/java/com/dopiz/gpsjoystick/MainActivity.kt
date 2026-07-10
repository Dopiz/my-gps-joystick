package com.dopiz.gpsjoystick

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dopiz.gpsjoystick.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            renderStatus()
        }

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
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val s = PermissionChecker.status(this)
        binding.statusText.text = buildString {
            appendLine("Location granted   : ${mark(s.locationGranted)}")
            appendLine("Mock app selected  : ${mark(s.isMockAppSelected)}")
            appendLine("Overlay granted    : ${mark(s.overlayGranted)}")
            appendLine("Notifications      : ${mark(s.notificationsGranted)}")
            appendLine("SDK                : ${Build.VERSION.SDK_INT}")
            append("Mock ready         : ${mark(s.mockReady)}")
        }
    }

    private fun mark(ok: Boolean) = if (ok) "YES" else "no"
}
