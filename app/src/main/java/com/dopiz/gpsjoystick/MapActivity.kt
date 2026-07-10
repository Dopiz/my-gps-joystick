package com.dopiz.gpsjoystick

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dopiz.gpsjoystick.databinding.ActivityMapBinding
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * In-app OSMDroid map. Subscribes to [MockLocationService.state] so the marker always equals
 * the current mock coordinates; a tap teleports the mock (and therefore any external app's
 * position) to the tapped point via [MockLocationService.update].
 */
class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var marker: Marker
    private var centeredOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid needs a user-agent + writable cache set BEFORE the MapView inflates,
        // otherwise tile downloads are rejected. App-specific dirs avoid storage permissions.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }

        marker = Marker(binding.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Mock 位置"
        }
        binding.map.overlays.add(marker)

        val tapReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                teleport(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        binding.map.overlays.add(0, MapEventsOverlay(tapReceiver))

        observeMockState()
    }

    private fun teleport(p: GeoPoint) {
        MockLocationService.update(this, p.latitude, p.longitude)
        binding.mapStatus.text = "瞬移到 %.5f, %.5f".format(p.latitude, p.longitude)
    }

    private fun observeMockState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MockLocationService.state.collect { s ->
                    val gp = GeoPoint(s.latitude, s.longitude)
                    marker.position = gp
                    if (!centeredOnce) {
                        binding.map.controller.setCenter(gp)
                        centeredOnce = true
                    }
                    binding.map.invalidate()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
    }
}
