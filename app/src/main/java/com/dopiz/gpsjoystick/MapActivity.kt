package com.dopiz.gpsjoystick

import android.graphics.Color
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dopiz.gpsjoystick.databinding.ActivityMapBinding
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.hypot

/**
 * In-app OSMDroid map. Subscribes to [MockLocationService.state] so the marker always equals
 * the current mock coordinates. Hosts GPX playback controls (Slice 9/10): play/pause/resume/stop,
 * loop/reverse mode, a live speed slider, and tap-to-set-start-index on a loaded route.
 * A tap away from the route teleports the mock (Slice 7 behaviour).
 */
class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var marker: Marker
    private var centeredOnce = false
    private var gpxPolyline: Polyline? = null

    /** The imported route (osmdroid-free); empty until a GPX is loaded. */
    private var routePts: List<GeoPt> = emptyList()
    private var mode: PlaybackMode = PlaybackMode.LOOP

    private val openGpxLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importGpx(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                handleTap(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        binding.map.overlays.add(0, MapEventsOverlay(tapReceiver))

        binding.btnImportGpx.setOnClickListener {
            openGpxLauncher.launch(
                arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")
            )
        }

        binding.btnPlay.setOnClickListener { startPlayback() }
        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnStopPlayback.setOnClickListener { MockLocationService.stopPlayback() }
        binding.btnMode.setOnClickListener { cycleMode() }

        binding.speedSeek.progress =
            (MockLocationService.state.value.speedMps - SpeedModel.MIN_MPS).toInt().coerceAtLeast(0)
        binding.speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) MockLocationService.setSpeed(SpeedModel.MIN_MPS + progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        observeMockState()
    }

    private fun importGpx(uri: Uri) {
        val points = try {
            contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.gpx_error)}: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (points.isNullOrEmpty()) {
            Toast.makeText(this, R.string.gpx_empty, Toast.LENGTH_LONG).show()
            return
        }
        drawTrack(points)
        routePts = points.map { GeoPt(it.latitude, it.longitude) }
        MockLocationService.setPlaybackRoute(routePts, mode)
        binding.mapStatus.text = getString(R.string.gpx_loaded, points.size)
    }

    private fun drawTrack(points: List<GeoPoint>) {
        gpxPolyline?.let { binding.map.overlays.remove(it) }
        val line = Polyline(binding.map).apply {
            setPoints(points)
            outlinePaint.color = Color.rgb(211, 47, 47)
            outlinePaint.strokeWidth = 8f
            // Don't let the polyline swallow taps near the line — return false so the tap falls
            // through to MapEventsOverlay, which is what drives tap-to-set-start-index / teleport.
            setOnClickListener { _, _, _ -> false }
        }
        gpxPolyline = line
        binding.map.overlays.add(line)
        binding.map.post {
            binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 64)
        }
        binding.map.invalidate()
    }

    /**
     * A tap on/near a loaded route sets the playback start index; a tap elsewhere teleports.
     * "Near" is judged in screen pixels so it feels the same at any zoom.
     */
    private fun handleTap(p: GeoPoint) {
        val idx = nearestRouteIndex(p)
        if (idx != null) {
            MockLocationService.setPlaybackStartIndex(idx)
            binding.mapStatus.text =
                getString(R.string.start_index_set, idx + 1, routePts.size)
            return
        }
        MockLocationService.update(this, p.latitude, p.longitude)
        binding.mapStatus.text = "瞬移到 %.5f, %.5f".format(p.latitude, p.longitude)
    }

    /** @return index of the closest route point within the tap threshold, or null if none. */
    private fun nearestRouteIndex(p: GeoPoint): Int? {
        if (routePts.isEmpty()) return null
        val proj = binding.map.projection
        val tapPx = proj.toPixels(p, null)
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        routePts.forEachIndexed { i, pt ->
            val px: Point = proj.toPixels(GeoPoint(pt.lat, pt.lng), null)
            val d = hypot((px.x - tapPx.x).toFloat(), (px.y - tapPx.y).toFloat())
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return if (bestDist <= TAP_THRESHOLD_PX) bestIdx else null
    }

    private fun startPlayback() {
        if (routePts.size < 2) {
            Toast.makeText(this, R.string.playback_no_route, Toast.LENGTH_LONG).show()
            return
        }
        if (!PermissionChecker.isLocationGranted(this)) {
            Toast.makeText(this, "請先在主畫面授予定位權限並設為模擬位置 app", Toast.LENGTH_LONG).show()
            return
        }
        // Route was already loaded at import; keep the current cursor (possibly a tapped
        // start index) instead of resetting it. Just make sure the mode is current.
        MockLocationService.setPlaybackMode(mode)
        val startIdx = MockLocationService.state.value.playback.segmentIndex
            .coerceIn(0, routePts.size - 1)
        val start = routePts[startIdx]
        // Ensure the injecting service is running, positioned at the start point. Use the
        // playback-aware start so it does NOT discard the route we are about to play.
        MockLocationService.startPlayback(this, start.lat, start.lng)
        MockLocationService.play()
        binding.mapStatus.text = getString(R.string.playback_started, startIdx + 1)
    }

    private fun togglePause() {
        val pb = MockLocationService.state.value.playback
        if (pb.paused || !pb.active) MockLocationService.resumePlayback()
        else MockLocationService.pausePlayback()
    }

    private fun cycleMode() {
        mode = if (mode == PlaybackMode.LOOP) PlaybackMode.REVERSE else PlaybackMode.LOOP
        MockLocationService.setPlaybackMode(mode)
        binding.btnMode.setText(
            if (mode == PlaybackMode.LOOP) R.string.mode_loop else R.string.mode_reverse
        )
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
                    binding.btnPause.setText(
                        if (s.playback.active && !s.playback.paused) R.string.pause
                        else R.string.resume
                    )
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

    private companion object {
        const val TAP_THRESHOLD_PX = 60f
    }
}
