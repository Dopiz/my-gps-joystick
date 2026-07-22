package com.dopiz.gpsjoystick

import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.text.InputType
import android.widget.TextView
import android.widget.Toast
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
    private var walkStartMarker: Marker? = null
    private var walkEndMarker: Marker? = null
    private var walkGuideLine: Polyline? = null
    private var shownWalkTarget: Pair<Double, Double>? = null
    private var centeredOnce = false
    private var gpxPolyline: Polyline? = null
    /** Feature 5: whether the control card is collapsed so the map fills the screen. */
    private var mapExpanded = false

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

    private val favLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val lat = data.getDoubleExtra(FavoritesActivity.EXTRA_FAV_LAT, Double.NaN)
                val lng = data.getDoubleExtra(FavoritesActivity.EXTRA_FAV_LNG, Double.NaN)
                if (!lat.isNaN() && !lng.isNaN()) {
                    binding.coordInput.setText("${fmt(lat)}, ${fmt(lng)}")
                    when (data.getStringExtra(FavoritesActivity.EXTRA_FAV_ACTION)) {
                        FavoritesActivity.ACTION_WALK -> startWalkTo(lat, lng)
                        else -> teleportTo(
                            lat, lng, getString(R.string.coord_teleport, fmt(lat), fmt(lng))
                        )
                    }
                }
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
        binding.btnFavList.setOnClickListener {
            favLauncher.launch(android.content.Intent(this, FavoritesActivity::class.java))
        }
        binding.btnPasteCoord.setOnClickListener { pasteCoordFromClipboard() }

        binding.btnPlay.setOnClickListener { togglePlayPause() }
        // 停止 = 歸零: stop AND rewind the cursor to point 0 so next 開始 starts from the top.
        binding.btnStopPlayback.setOnClickListener { MockLocationService.resetPlayback() }
        binding.btnExpandMap.setOnClickListener { toggleControlPanel() }
        setupModeChips()
        setupSpeedChips()
        observeMockState()

        restoreRouteForThisProcess()
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
            currentGpxIdInProcess = null
            return
        }
        currentGpxIdInProcess = id
        loadRoute(pts)
    }

    /**
     * Keep an explicitly loaded route while this app process lives, but do not resurrect an idle
     * route after an app restart. An active/paused playback owns its live points and cursor, so
     * reopening the map only redraws those points without calling setPlaybackRoute() and resetting
     * progress.
     */
    private fun restoreRouteForThisProcess() {
        // Remove the legacy persisted selection; route selection is process-scoped from now on.
        SessionStore.clearCurrentGpxId(this)
        val playback = MockLocationService.state.value.playback
        if (playback.active && playback.hasRoute) {
            routePts = playback.points
            mode = playback.mode
            drawTrack(routePts.map { GeoPoint(it.lat, it.lng) })
            return
        }
        currentGpxIdInProcess?.let { loadGpxById(it) }
    }

    /** Draw the route and hand it to the playback engine in the current mode. */
    private fun loadRoute(pts: List<GeoPt>) {
        drawTrack(pts.map { GeoPoint(it.lat, it.lng) })
        routePts = pts
        MockLocationService.setPlaybackRoute(pts, mode)
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
        zoomToBoundsSafely(points, ROUTE_BOUNDS_PADDING_PX)
        binding.map.invalidate()
    }

    /**
     * OSMDroid 1.6.20 can spin forever inside Projection.getCloserPixel when asked to fit bounds
     * while a multi-window resize leaves the MapView at zero/tiny height. Never call its bounds
     * math until the laid-out viewport has positive space remaining after padding.
     */
    private fun zoomToBoundsSafely(points: List<GeoPoint>, paddingPx: Int) {
        if (points.isEmpty()) return
        binding.map.post {
            val usableWidth = binding.map.width - paddingPx * 2
            val usableHeight = binding.map.height - paddingPx * 2
            if (!binding.map.isAttachedToWindow || usableWidth <= 0 || usableHeight <= 0) {
                return@post
            }
            binding.map.zoomToBoundingBox(
                BoundingBox.fromGeoPoints(points), true, paddingPx
            )
        }
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
            Toast.makeText(this,
                getString(R.string.start_index_set, idx + 1, routePts.size),
                Toast.LENGTH_SHORT).show()
            return
        }
        showPin(p)
        binding.coordInput.setText("${fmt(p.latitude)}, ${fmt(p.longitude)}")
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

    private fun startWalkTo(lat: Double, lng: Double) {
        if (!MockLocationService.state.value.isRunning) {
            Toast.makeText(this, R.string.coord_walk_requires_mock, Toast.LENGTH_LONG).show()
            return
        }
        MockLocationService.walkTo(lat, lng)
        binding.map.controller.animateTo(GeoPoint(lat, lng))
        Toast.makeText(
            this,
            getString(R.string.coord_walk_started, fmt(lat), fmt(lng)),
            Toast.LENGTH_SHORT,
        ).show()
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
        Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
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
        val raw = binding.coordInput.text?.toString().orEmpty()
        if (raw.isBlank()) {
            Toast.makeText(this, R.string.coord_empty, Toast.LENGTH_LONG).show()
            return
        }
        val coord = parseCoord(raw)
        if (coord == null) {
            Toast.makeText(this, R.string.coord_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val (lat, lng) = coord
        val coordLabel = "${fmt(lat)}, ${fmt(lng)}"
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
                FavoritesStore.add(this, FavoritesStore.Fav(lat, lng, name))
                Toast.makeText(this, getString(R.string.fav_saved, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
        // Lift the global movement freeze so the route actually moves, matching the overlay's
        // GPX play button (blue = really playing).
        MockLocationService.setMovementPaused(false)
        MockLocationService.startPlayback(this, start.lat, start.lng)
        MockLocationService.play()
        // Change 1: bring up the floating overlay (hub + joystick) just like MainActivity's 開始模擬.
        // Playback + mock are already running above; if overlay permission is missing we still keep
        // playing and only guide the user to grant it — never block or crash the play.
        if (PermissionChecker.isOverlayGranted(this)) {
            OverlayService.showAll(this)
        } else {
            Toast.makeText(this, R.string.overlay_needed_for_joystick, Toast.LENGTH_LONG).show()
            startActivity(PermissionChecker.overlaySettingsIntent(this))
        }
    }

    /**
     * Change 1: stateful 開始/暫停 toggle mirroring the overlay's GPX play/pause. Playing → pause;
     * paused mid-route → resume from the cursor; stopped → start (from cursor, or point 0 if reset).
     */
    private fun togglePlayPause() {
        val pb = MockLocationService.state.value.playback
        when {
            pb.active && !pb.paused -> MockLocationService.pausePlayback()
            pb.active && pb.paused -> {
                MockLocationService.setMovementPaused(false)
                MockLocationService.resumePlayback()
            }
            else -> startPlayback()
        }
    }

    /** Feature 5: collapse the control card so the OSMDroid map fills the screen; tap again restores. */
    private fun toggleControlPanel() {
        mapExpanded = !mapExpanded
        binding.mapControlCard.visibility = if (mapExpanded) View.GONE else View.VISIBLE
        binding.btnExpandMap.setIconResource(
            if (mapExpanded) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        binding.btnExpandMap.contentDescription =
            getString(if (mapExpanded) R.string.map_collapse else R.string.map_expand)
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
                    updateWalkGuide(s)
                    updateHud(s)
                    binding.map.invalidate()
                }
            }
        }
    }

    /** Show the complete active coordinate-walk leg; clear it as soon as that mode ends. */
    private fun updateWalkGuide(s: MockState) {
        if (!s.walkToActive) {
            clearWalkGuide()
            return
        }

        val start = GeoPoint(s.walkFromLat, s.walkFromLng)
        val end = GeoPoint(s.walkToLat, s.walkToLng)
        val startMarker = walkStartMarker ?: Marker(binding.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = getString(R.string.walk_start_marker)
        }.also {
            walkStartMarker = it
            binding.map.overlays.add(it)
        }
        val endMarker = walkEndMarker ?: Marker(binding.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = getString(R.string.walk_end_marker)
        }.also {
            walkEndMarker = it
            binding.map.overlays.add(it)
        }
        startMarker.position = start
        endMarker.position = end

        val line = walkGuideLine ?: Polyline(binding.map).apply {
            outlinePaint.color = getColor(R.color.brand_primary)
            outlinePaint.strokeWidth = 6f
            setOnClickListener { _, _, _ -> false }
        }.also {
            walkGuideLine = it
            // Keep the guide below all markers.
            binding.map.overlays.add(0, it)
        }
        line.setPoints(listOf(start, end))

        val target = s.walkToLat to s.walkToLng
        if (shownWalkTarget != target) {
            shownWalkTarget = target
            zoomToBoundsSafely(listOf(start, end), WALK_BOUNDS_PADDING_PX)
        }
    }

    private fun clearWalkGuide() {
        walkStartMarker?.let(binding.map.overlays::remove)
        walkEndMarker?.let(binding.map.overlays::remove)
        walkGuideLine?.let(binding.map.overlays::remove)
        walkStartMarker = null
        walkEndMarker = null
        walkGuideLine = null
        shownWalkTarget = null
    }

    /**
     * Live playback indicator from the shared state: the slim progress bar (Change 3, the sole
     * non-textual indicator) plus the 開始/暫停 toggle label + icon (Change 1).
     */
    private fun updateHud(s: MockState) {
        val pb = s.playback
        binding.hudProgress.progress =
            if (pb.hasRoute) (playbackFraction(pb) * 100).toInt().coerceIn(0, 100) else 0
        val playing = pb.active && !pb.paused
        binding.btnPlay.setText(if (playing) R.string.pb_pause else R.string.pb_start)
        binding.btnPlay.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
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
        const val ROUTE_BOUNDS_PADDING_PX = 64
        const val WALK_BOUNDS_PADDING_PX = 72

        /** Deliberately process-only: an app process restart clears an idle GPX selection. */
        var currentGpxIdInProcess: String? = null

        /** Feature 1 speed presets in km/h, in chip order: Walk / Cycle / Drive. */
        val presetKmh = listOf(5.0, 15.0, 40.0)
    }
}
