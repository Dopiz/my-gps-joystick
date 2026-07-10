package com.dopiz.gpsjoystick

import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * the current mock coordinates. Hosts GPX playback controls: a single 開始 (start/restart) plus
 * 停止, a 3-way mode selector (一次 / 巡迴 / 往返), speed presets 走/跑/車 + a fine-tune slider,
 * and tap-to-set-start-index on a loaded route. A tap away from a route now PREVIEWS the tapped
 * coordinate into the input field (no teleport); the user commits the move with 前往.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var marker: Marker
    private var pinMarker: Marker? = null
    private var centeredOnce = false
    private var gpxPolyline: Polyline? = null

    /** The imported route (osmdroid-free); empty until a GPX is loaded. */
    private var routePts: List<GeoPt> = emptyList()
    private var mode: PlaybackMode = PlaybackMode.LOOP

    private val gpxLibLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(GpxLibraryActivity.EXTRA_GPX_ID)
                    ?.let { loadGpxById(it) }
            }
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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        mode = SessionStore.loadPlaybackMode(this)

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
            override fun longPressHelper(p: GeoPoint): Boolean {
                dropPin(p)
                return true
            }
        }
        binding.map.overlays.add(0, MapEventsOverlay(tapReceiver))

        binding.btnImportGpx.setOnClickListener {
            gpxLibLauncher.launch(android.content.Intent(this, GpxLibraryActivity::class.java))
        }

        binding.btnCoordGo.setOnClickListener { submitCoord() }
        binding.coordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { submitCoord(); true } else false
        }
        binding.btnFavSave.setOnClickListener { saveFavorite() }
        binding.btnFavList.setOnClickListener { showFavorites() }
        binding.btnPasteCoord.setOnClickListener { pasteCoordFromClipboard() }

        binding.btnPlay.setOnClickListener { startPlayback() }
        binding.btnStopPlayback.setOnClickListener { MockLocationService.stopPlayback() }
        setupModeChips()
        setupSpeedChips()
        observeMockState()

        // Batch 2: reload the last-chosen library GPX so reopening the map restores the route.
        SessionStore.loadCurrentGpxId(this)?.let { loadGpxById(it) }
    }

    private val speedButtonIds
        get() = listOf(
            binding.btnSpeedWalk.id, binding.btnSpeedCycle.id,
            binding.btnSpeedDrive.id, binding.btnSpeedCustom.id,
        )
    private var suppressSpeedCb = false

    /**
     * Feature 1: speed presets 走/跑/車 + 自訂 (user-typed km/h). Selecting a preset sets that
     * km/h on the shared SpeedModel; 自訂 prompts for a value. The choice (and any custom km/h)
     * is persisted so it restores on reopen. Index 3 == custom.
     */
    private fun setupSpeedChips() {
        val ids = speedButtonIds
        binding.speedToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressSpeedCb) return@addOnButtonCheckedListener
            when (val idx = ids.indexOf(checkedId)) {
                0, 1, 2 -> {
                    MockLocationService.setSpeed(presetKmh[idx] / 3.6)
                    SessionStore.saveSpeedChip(this, idx)
                    setCustomButtonLabel(null)
                }
                3 -> promptCustomSpeed()
            }
        }
        // Restore the last selection + speed.
        when (val saved = SessionStore.loadSpeedChip(this)) {
            0, 1, 2 -> {
                MockLocationService.setSpeed(presetKmh[saved] / 3.6)
                checkSpeedSilently(ids[saved])
            }
            3 -> {
                val kmh = SessionStore.loadCustomKmh(this)
                MockLocationService.setSpeed(kmh / 3.6)
                setCustomButtonLabel(kmh)
                checkSpeedSilently(ids[3])
            }
        }
    }

    /** Check a segment without re-triggering the selection callback. */
    private fun checkSpeedSilently(id: Int) {
        suppressSpeedCb = true
        binding.speedToggle.check(id)
        suppressSpeedCb = false
    }

    private fun setCustomButtonLabel(kmh: Double?) {
        binding.btnSpeedCustom.text =
            if (kmh == null) getString(R.string.speed_preset_custom)
            else getString(R.string.speed_preset_custom_value, "%.0f".format(kmh))
    }

    /** 自訂: prompt for a km/h value; apply on OK, revert the selection on cancel/blank. */
    private fun promptCustomSpeed() {
        val current = MockLocationService.state.value.speedMps * 3.6
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.speed_custom_hint)
            setText("%.0f".format(current))
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speed_custom_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val kmh = input.text.toString().trim().toDoubleOrNull()
                if (kmh == null || kmh <= 0.0) {
                    revertSpeedSelection()
                } else {
                    MockLocationService.setSpeed(kmh / 3.6)
                    // Persist the APPLIED value (SpeedModel may have clamped it).
                    val appliedKmh = MockLocationService.state.value.speedMps * 3.6
                    SessionStore.saveSpeedChip(this, 3)
                    SessionStore.saveCustomKmh(this, appliedKmh)
                    setCustomButtonLabel(appliedKmh)
                }
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> revertSpeedSelection() }
            .setOnCancelListener { revertSpeedSelection() }
            .show()
    }

    /** Restore the toggle to the persisted preset after a 自訂 prompt is dismissed. */
    private fun revertSpeedSelection() {
        val ids = speedButtonIds
        when (val saved = SessionStore.loadSpeedChip(this)) {
            0, 1, 2, 3 -> checkSpeedSilently(ids[saved])
            else -> {
                suppressSpeedCb = true
                binding.speedToggle.clearChecked()
                suppressSpeedCb = false
            }
        }
    }

    /** Load a saved GPX (from the library) by id: draw its polyline and arm playback. */
    private fun loadGpxById(id: String) {
        val pts = GpxStore.get(this, id)
        if (pts.isEmpty()) {
            SessionStore.clearCurrentGpxId(this)
            return
        }
        SessionStore.saveCurrentGpxId(this, id)
        loadRoute(pts)
    }

    /** Draw the route and hand it to the playback engine in the current mode. */
    private fun loadRoute(pts: List<GeoPt>) {
        drawTrack(pts.map { GeoPoint(it.lat, it.lng) })
        routePts = pts
        MockLocationService.setPlaybackRoute(pts, mode)
        binding.mapStatus.text = getString(R.string.gpx_loaded, pts.size)
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
     * A tap on/near a loaded route sets the playback start index; a tap elsewhere now PREVIEWS
     * the coordinate into the input field (drops a pin, fills "lat, lng") instead of teleporting.
     * The user commits the move by tapping 前往. "Near" is judged in screen pixels.
     */
    private fun handleTap(p: GeoPoint) {
        val idx = nearestRouteIndex(p)
        if (idx != null) {
            MockLocationService.setPlaybackStartIndex(idx)
            binding.mapStatus.text =
                getString(R.string.start_index_set, idx + 1, routePts.size)
            return
        }
        showPin(p)
        binding.coordInput.setText("${fmt(p.latitude)}, ${fmt(p.longitude)}")
        binding.mapStatus.text =
            getString(R.string.coord_filled, fmt(p.latitude), fmt(p.longitude))
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

    /**
     * Paste button: read the system clipboard's primary text into the coordinate field so the
     * user need not long-press-paste. Shows a toast and does nothing when the clip is empty or
     * non-text. Parsing / 傳送 flow is unchanged — this only fills the field.
     */
    private fun pasteCoordFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        binding.coordInput.setText(text)
        binding.coordInput.setSelection(text.length)
    }

    // --- Feature 3: coordinate search / paste ---
    private fun submitCoord() {
        val raw = binding.coordInput.text?.toString().orEmpty()
        val coord = parseCoord(raw)
        if (coord == null) {
            Toast.makeText(this, R.string.coord_invalid, Toast.LENGTH_LONG).show()
            return
        }
        teleportTo(coord.first, coord.second,
            getString(R.string.coord_teleport, fmt(coord.first), fmt(coord.second)))
        binding.coordInput.text?.clear()
    }

    /** Accepts "lat,lng" and "lat, lng"; validates lat -90..90, lng -180..180. */
    private fun parseCoord(raw: String): Pair<Double, Double>? {
        val parts = raw.trim().split(Regex(",\\s*"))
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    /** Teleport (glides via the service when running) + recenter the map. */
    private fun teleportTo(lat: Double, lng: Double, status: String) {
        MockLocationService.update(this, lat, lng)
        val gp = GeoPoint(lat, lng)
        marker.position = gp
        binding.map.controller.animateTo(gp)
        binding.map.invalidate()
        binding.mapStatus.text = status
    }

    /** Drop / move the single preview pin at [p] without moving the mock. */
    private fun showPin(p: GeoPoint) {
        val pin = pinMarker ?: Marker(binding.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "圖釘"
        }.also {
            pinMarker = it
            binding.map.overlays.add(it)
        }
        pin.position = p
        binding.map.invalidate()
    }

    // --- Feature 3: long-press drops a persistent pin + teleports there ---
    private fun dropPin(p: GeoPoint) {
        showPin(p)
        teleportTo(p.latitude, p.longitude,
            getString(R.string.pin_dropped, fmt(p.latitude), fmt(p.longitude)))
    }

    // --- Feature 3 / Batch 1: favorites with a user-chosen name ---
    private fun saveFavorite() {
        val s = MockLocationService.state.value
        val coordLabel = "${fmt(s.latitude)}, ${fmt(s.longitude)}"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(coordLabel)
            setSelection(text.length)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_name_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { coordLabel }
                FavoritesStore.add(this, FavoritesStore.Fav(s.latitude, s.longitude, name))
                Toast.makeText(this, getString(R.string.fav_saved, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showFavorites() {
        val dialog = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
            setBackgroundColor(getColor(R.color.surface))
        }
        container.addView(TextView(this).apply {
            setText(R.string.fav_title)
            setTextAppearance(R.style.TextAppearance_GpsJoystick_Title)
        })

        val favs = FavoritesStore.list(this)
        if (favs.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.fav_empty)
                setTextAppearance(R.style.TextAppearance_GpsJoystick_Body)
                setPadding(0, px(12), 0, 0)
            })
        } else {
            favs.forEachIndexed { index, fav ->
                container.addView(favoriteRow(index, fav, dialog, ::px))
            }
        }

        dialog.setContentView(ScrollView(this).apply {
            addView(container, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        dialog.show()
    }

    private fun favoriteRow(
        index: Int, fav: FavoritesStore.Fav, dialog: BottomSheetDialog, px: (Int) -> Int,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(4), 0, px(4))

        addView(TextView(this@MapActivity).apply {
            text = fav.label
            setTextAppearance(R.style.TextAppearance_GpsJoystick_Body)
            minHeight = px(48)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                teleportTo(fav.lat, fav.lng,
                    getString(R.string.coord_teleport, fmt(fav.lat), fmt(fav.lng)))
                dialog.dismiss()
            }
        })
        addView(TextView(this@MapActivity).apply {
            setText(R.string.fav_delete)
            setTextAppearance(R.style.TextAppearance_GpsJoystick_Label)
            setTextColor(getColor(R.color.brand_error))
            minHeight = px(48)
            minWidth = px(48)
            gravity = Gravity.CENTER
            setOnClickListener {
                FavoritesStore.removeAt(this@MapActivity, index)
                dialog.dismiss()
                showFavorites()
            }
        })
    }

    private fun fmt(v: Double): String = "%.5f".format(v)

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
        var startIdx = MockLocationService.state.value.playback.segmentIndex
            .coerceIn(0, routePts.size - 1)
        // 開始 = restart: if the cursor is parked at the final point (e.g. a ONCE run finished),
        // restart from the top rather than replaying zero-length at the end.
        if (startIdx >= routePts.size - 1) {
            MockLocationService.setPlaybackStartIndex(0)
            startIdx = 0
        }
        val start = routePts[startIdx]
        // Ensure the injecting service is running, positioned at the start point. Use the
        // playback-aware start so it does NOT discard the route we are about to play.
        MockLocationService.startPlayback(this, start.lat, start.lng)
        MockLocationService.play()
        binding.mapStatus.text = getString(R.string.playback_started, startIdx + 1)
    }

    /** Batch 1: 3-way playback mode selector (一次 / 巡迴 / 往返). Persisted independently. */
    private fun setupModeChips() {
        val ids = listOf(
            binding.btnModeOnce.id, binding.btnModeLoop.id, binding.btnModeReverse.id,
        )
        val modes = listOf(PlaybackMode.ONCE, PlaybackMode.LOOP, PlaybackMode.REVERSE)
        // check() before wiring the listener → no spurious callback for the initial selection.
        binding.modeToggle.check(ids[modes.indexOf(mode)])
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val idx = ids.indexOf(checkedId)
            if (idx >= 0) {
                mode = modes[idx]
                MockLocationService.setPlaybackMode(mode)
                SessionStore.savePlaybackMode(this, mode)
            }
        }
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
                    binding.speedValue.text = getString(
                        R.string.speed_value,
                        "%.0f".format(s.speedMps * 3.6),
                        "%.1f".format(s.speedMps),
                    )
                    updateHud(s)
                    binding.map.invalidate()
                }
            }
        }
    }

    /** Feature 5: compact live playback HUD driven from the shared state. */
    private fun updateHud(s: MockState) {
        val pb = s.playback
        if (!pb.hasRoute) {
            binding.hudLine.setText(R.string.hud_no_route)
            binding.hudProgress.progress = 0
            return
        }
        val n = pb.points.size
        val stateStr = when {
            pb.active && !pb.paused -> getString(R.string.hud_state_playing)
            pb.paused -> getString(R.string.hud_state_paused)
            else -> getString(R.string.hud_state_idle)
        }
        val modeStr = getString(
            when (pb.mode) {
                PlaybackMode.ONCE -> R.string.hud_mode_once
                PlaybackMode.LOOP -> R.string.hud_mode_loop
                PlaybackMode.REVERSE -> R.string.hud_mode_reverse
            }
        )
        val pct = (playbackFraction(pb) * 100).toInt().coerceIn(0, 100)
        val pointNum = (pb.segmentIndex + 1).coerceIn(1, n)
        binding.hudLine.text = getString(R.string.hud_line, stateStr, modeStr, pointNum, n, pct)
        binding.hudProgress.progress = pct
    }

    /** Fractional progress (0..1) along the polyline: segment index + progress within segment. */
    private fun playbackFraction(pb: Playback): Double {
        val n = pb.points.size
        if (n < 2) return 0.0
        val idx = pb.segmentIndex.coerceIn(0, n - 1)
        val nextIdx = idx + if (pb.forward) 1 else -1
        val segFrac = if (nextIdx in pb.points.indices) {
            val len = PlaybackEngine.segMeters(pb.points[idx], pb.points[nextIdx])
            if (len > 0.0) (pb.segmentProgress / len).coerceIn(0.0, 1.0) else 0.0
        } else 0.0
        return ((idx + segFrac) / (n - 1)).coerceIn(0.0, 1.0)
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

        /** Feature 1 speed presets in km/h, in chip order: Walk / Cycle / Drive. */
        val presetKmh = listOf(5.0, 15.0, 40.0)
    }
}
