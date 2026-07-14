package com.dopiz.gpsjoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hosts the floating overlay(s) (SYSTEM_ALERT_WINDOW) that must survive switching to other apps.
 *
 * Window model — EVERY interactive element is its own small content-sized overlay window:
 *  - the HUB ([RadialMenuView]) window (a fixed [RadialMenuView.hubPx] square) that never resizes,
 *    so its centre stays put; tapping it toggles expand/collapse.
 *  - one window per VISIBLE child button (地圖 / 搖桿 / 鎖定 / 速度) and per visible sub-row button
 *    (走/跑/車 off 速度, 開啟地圖頁 + GPX off 地圖).
 *  - the [JoystickView] window, toggled by the 搖桿 child.
 *
 * Because each button has its own tiny window and the GAPS between them have no window at all, every
 * window uses FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL, so taps on the empty areas fall straight
 * through to the app behind while the menu stays expanded. The menu only collapses when the user
 * taps the hub (✕). A [MockLocationService] state observer keeps the buttons reflecting the live
 * joystick-visible / GPX / lock / active-speed state.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val density by lazy { resources.displayMetrics.density }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    // --- Hub window ---
    private var hubView: RadialMenuView? = null
    private var hubParams: WindowManager.LayoutParams? = null

    // --- Child buttons (each hosted in its own window when visible) ---
    private var btnMap: ChildButton? = null
    private var btnJoystick: ChildButton? = null
    private var btnLock: ChildButton? = null
    private var btnAutoTap: ChildButton? = null
    private var btnSpeed: ChildButton? = null
    private var subWalk: ChildButton? = null
    private var subRun: ChildButton? = null
    private var subCar: ChildButton? = null
    private var subMapOpen: ChildButton? = null
    private var subGpx: ChildButton? = null
    private var subTap1: ChildButton? = null
    private var subTap2: ChildButton? = null
    private var subTap3: ChildButton? = null
    private val columnButtons get() = listOfNotNull(btnMap, btnJoystick, btnLock, btnAutoTap, btnSpeed)
    private val speedSubs get() = listOfNotNull(subWalk, subRun, subCar)
    private val mapSubs get() = listOfNotNull(subMapOpen, subGpx)
    private val tapSubs get() = listOfNotNull(subTap1, subTap2, subTap3)

    // Live windowed children + their params, so we can move/remove them precisely.
    private val childParams = HashMap<ChildButton, WindowManager.LayoutParams>()

    // Fan-out state. vDir = +1 column grows DOWN, -1 UP; hDir = +1 sub-rows grow RIGHT, -1 LEFT.
    private var expanded = false
    private var speedOpen = false
    private var mapOpen = false
    private var tapOpen = false
    private var vDir = 1
    private var hDir = 1

    // --- 連點選點層 (full-screen picker window) ---
    private var pickRoot: View? = null
    private var pickInner: TapPickView? = null
    private var pickSlot = 1   // which preset (1..3) the current pick session writes to

    // --- Joystick window state ---
    private var joystickView: View? = null
    private var joystickInner: JoystickView? = null
    private var joystickParams: WindowManager.LayoutParams? = null
    private var joystickLocked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                teardown()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            // Mock-start path: show the hub AND the joystick together by default.
            ACTION_SHOW_ALL -> showHub(alsoJoystick = true)
            // App-driven point recording: ensure the hub is up, then open the picker for the slot.
            ACTION_PICK_TAP -> {
                showHub()
                if (hubView != null) enterAutoTapPick(intent.getIntExtra(EXTRA_SLOT, 1))
            }
            else -> showHub()
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------------------------
    // Hub window
    // ---------------------------------------------------------------------------------------

    private fun showHub(alsoJoystick: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "尚未授予懸浮窗權限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        startForegroundCompat()
        if (hubView != null) {
            // Hub already up (e.g. re-tapped 開始模擬): still honour a show-all request.
            if (alsoJoystick && joystickView == null) showJoystick()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = RadialMenuView(this)
        hubView = view

        // Restore the hub to its last CENTRE position (else default top-left).
        val dm = resources.displayMetrics
        val savedHub = SessionStore.loadHubPos(this)
        val params = baseParams(view.hubPx, view.hubPx).apply {
            if (savedHub != null) {
                x = clampInt(savedHub.first - view.hubPx / 2, 0, dm.widthPixels - view.hubPx)
                y = clampInt(savedHub.second - view.hubPx / 2, 0, dm.heightPixels - view.hubPx)
            } else {
                x = (density * 20).toInt()
                y = (density * 160).toInt()
            }
        }
        hubParams = params

        // Restore the joystick lock state before any joystick is shown.
        joystickLocked = SessionStore.loadLock(this)

        // Build the child buttons once; they are added to / removed from their own windows on demand.
        buildChildren()

        view.onHubDrag = { dx, dy -> moveHub(dx, dy) }
        view.onDragEnd = { saveHubPos() }
        view.onRequestExpand = { expand() }
        view.onRequestCollapse = { collapse() }

        windowManager.addView(view, params)

        // Apply the persisted speed on startup so the app opens at the last speed (shared SpeedModel
        // → the map reflects it too). Don't clobber a live running session.
        if (!MockLocationService.state.value.isRunning) {
            val chip = SessionStore.loadSpeedChip(this)
            if (chip in PRESET_KMH.indices) MockLocationService.setSpeed(PRESET_KMH[chip] / 3.6)
        }

        applyState()
        startObserving()

        // On a fresh 開始模擬 the joystick shows by default; otherwise re-show it only if it was
        // visible last time.
        if (alsoJoystick || SessionStore.loadJoystickVisible(this)) showJoystick()
    }

    private fun buildChildren() {
        btnMap = ChildButton(this, ChildButton.Glyph.MAP).also {
            it.setOnClickListener { toggleMapRing() }
        }
        btnJoystick = ChildButton(this, ChildButton.Glyph.JOYSTICK).also {
            it.setOnClickListener { toggleJoystick() }
        }
        btnLock = ChildButton(this, ChildButton.Glyph.LOCK_OPEN).also {
            it.setOnClickListener { toggleLock() }
        }
        btnAutoTap = ChildButton(this, ChildButton.Glyph.TAP).also {
            it.contentDescription = getString(R.string.overlay_autotap)
            it.setOnClickListener { toggleTapRing() }
        }
        btnSpeed = ChildButton(this, ChildButton.Glyph.SPEED).also {
            it.setOnClickListener { toggleSpeedRing() }
        }
        subWalk = ChildButton(this, ChildButton.Glyph.WALK).also {
            it.setOnClickListener { selectSpeed(0) }
        }
        subRun = ChildButton(this, ChildButton.Glyph.RUN).also {
            it.setOnClickListener { selectSpeed(1) }
        }
        subCar = ChildButton(this, ChildButton.Glyph.CAR).also {
            it.setOnClickListener { selectSpeed(2) }
        }
        subMapOpen = ChildButton(this, ChildButton.Glyph.MAP_OPEN).also {
            it.contentDescription = getString(R.string.overlay_open_map_page)
            it.setOnClickListener { openMap() }
        }
        subGpx = ChildButton(this, ChildButton.Glyph.PLAY).also {
            it.contentDescription = getString(R.string.overlay_gpx_toggle)
            it.setOnClickListener { toggleGpx() }
        }
        // 連點 sub-row: three preset slots shown as 1 / 2 / 3; only one can be active at a time.
        subTap1 = ChildButton(this, ChildButton.Glyph.TEXT, "1").also {
            it.contentDescription = getString(R.string.autotap_slot_label, 1)
            it.setOnClickListener { onTapSlot(1) }
        }
        subTap2 = ChildButton(this, ChildButton.Glyph.TEXT, "2").also {
            it.contentDescription = getString(R.string.autotap_slot_label, 2)
            it.setOnClickListener { onTapSlot(2) }
        }
        subTap3 = ChildButton(this, ChildButton.Glyph.TEXT, "3").also {
            it.contentDescription = getString(R.string.autotap_slot_label, 3)
            it.setOnClickListener { onTapSlot(3) }
        }
    }

    private fun saveHubPos() {
        SessionStore.saveHubPos(this, hubCx().toInt(), hubCy().toInt())
    }

    private fun openMap() {
        runCatching {
            startActivity(
                Intent(this, MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 地圖 sub-row GPX toggle. Controls GPX PLAYBACK (start / pause). Starting/resuming here also
     * lifts the global movement freeze so the route actually moves (blue = really playing).
     */
    private fun toggleGpx() {
        val pb = MockLocationService.state.value.playback
        if (pb.active && !pb.paused) {           // playing -> pause playback only
            MockLocationService.pausePlayback()
            return
        }
        if (pb.active && pb.paused) {             // paused mid-route -> resume from the cursor
            MockLocationService.setMovementPaused(false)
            MockLocationService.resumePlayback()
            return
        }
        // Not active: start. Ensure a route is loaded into the service (may only have a persisted
        // current_gpx_id if the map was never opened this session).
        var points = pb.points
        if (points.size < 2) {
            val id = SessionStore.loadCurrentGpxId(this) ?: return   // disabled: nothing to play
            points = GpxStore.get(this, id)
            if (points.size < 2) return
            MockLocationService.setPlaybackRoute(points, SessionStore.loadPlaybackMode(this))
        }
        if (!PermissionChecker.isLocationGranted(this)) {
            Toast.makeText(this, R.string.gpx_toggle_need_perm, Toast.LENGTH_LONG).show()
            return
        }
        // Restart from the top if the cursor is parked at the final point (e.g. a finished ONCE run).
        var startIdx = MockLocationService.state.value.playback.segmentIndex
            .coerceIn(0, points.size - 1)
        if (startIdx >= points.size - 1) {
            MockLocationService.setPlaybackStartIndex(0)
            startIdx = 0
        }
        val start = points[startIdx]
        MockLocationService.setMovementPaused(false)
        MockLocationService.startPlayback(this, start.lat, start.lng)
        MockLocationService.play()
    }

    private fun toggleLock() {
        joystickLocked = !joystickLocked
        joystickInner?.locked = joystickLocked
        SessionStore.saveLock(this, joystickLocked)
        // Unlocking must not keep marching a held heading.
        if (!joystickLocked) MockLocationService.clearJoystick()
        applyState()
    }

    // ---------------------------------------------------------------------------------------
    // Per-button window geometry
    // ---------------------------------------------------------------------------------------

    private fun hubCx(): Float = (hubParams?.x ?: 0) + (hubView?.hubPx ?: 0) / 2f
    private fun hubCy(): Float = (hubParams?.y ?: 0) + (hubView?.hubPx ?: 0) / 2f

    // Column child i sits straight below/above the hub centre.
    private fun columnCenterX(): Float = hubCx()
    private fun columnCenterY(i: Int): Float {
        val v = hubView ?: return hubCy()
        return hubCy() + vDir * (v.firstOffsetPx + i * v.stepPx)
    }

    // Sub-row button j (0-based) at column row [rowIndex], fanning horizontally toward screen centre.
    private fun rowCenterX(j: Int): Float {
        val v = hubView ?: return hubCx()
        return hubCx() + hDir * v.stepPx * (j + 1)
    }

    private fun addChildWin(btn: ChildButton, cx: Float, cy: Float) {
        val v = hubView ?: return
        val p = baseParams(v.childPx, v.childPx).apply {
            x = (cx - v.childPx / 2f).toInt()
            y = (cy - v.childPx / 2f).toInt()
        }
        btn.alpha = 0f
        btn.scaleX = 0.3f
        btn.scaleY = 0.3f
        runCatching { windowManager.addView(btn, p) }
        childParams[btn] = p
        btn.animate().cancel()
        btn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun moveChildWin(btn: ChildButton, cx: Float, cy: Float) {
        val v = hubView ?: return
        val p = childParams[btn] ?: return
        p.x = (cx - v.childPx / 2f).toInt()
        p.y = (cy - v.childPx / 2f).toInt()
        runCatching { windowManager.updateViewLayout(btn, p) }
    }

    private fun removeChildWin(btn: ChildButton) {
        if (childParams.remove(btn) == null) return
        btn.animate().cancel()
        runCatching { windowManager.removeView(btn) }
    }

    private fun expand() {
        val v = hubView ?: return
        val dm = resources.displayMetrics
        val cx = hubCx()
        val cy = hubCy()
        // Prefer column DOWN and sub-rows RIGHT (toward screen centre); flip only when that would
        // run off the edge and the opposite direction has room.
        vDir = if (cy + v.vReachPx > dm.heightPixels && cy - v.vReachPx >= 0) -1 else 1
        hDir = if (cx + v.hReachPx > dm.widthPixels && cx - v.hReachPx >= 0) -1 else 1

        expanded = true
        speedOpen = false
        mapOpen = false
        tapOpen = false
        columnButtons.forEachIndexed { i, b -> addChildWin(b, columnCenterX(), columnCenterY(i)) }
        v.setExpandedVisual(true)
        applyState()
    }

    private fun collapse() {
        expanded = false
        speedOpen = false
        mapOpen = false
        tapOpen = false
        childParams.keys.toList().forEach { removeChildWin(it) }
        hubView?.setExpandedVisual(false)
        saveHubPos()
    }

    private fun toggleSpeedRing() {
        if (!expanded) return
        if (mapOpen) { mapOpen = false; mapSubs.forEach { removeChildWin(it) } }
        if (tapOpen) { tapOpen = false; tapSubs.forEach { removeChildWin(it) } }
        speedOpen = !speedOpen
        if (speedOpen) {
            val v = hubView ?: return
            speedSubs.forEachIndexed { j, b -> addChildWin(b, rowCenterX(j), columnCenterY(v.speedIndex)) }
            applyState()
        } else {
            speedSubs.forEach { removeChildWin(it) }
        }
    }

    private fun toggleMapRing() {
        if (!expanded) return
        if (speedOpen) { speedOpen = false; speedSubs.forEach { removeChildWin(it) } }
        if (tapOpen) { tapOpen = false; tapSubs.forEach { removeChildWin(it) } }
        mapOpen = !mapOpen
        if (mapOpen) {
            mapSubs.forEachIndexed { j, b -> addChildWin(b, rowCenterX(j), columnCenterY(0)) }
            applyState()
        } else {
            mapSubs.forEach { removeChildWin(it) }
        }
    }

    private fun toggleTapRing() {
        if (!expanded) return
        if (speedOpen) { speedOpen = false; speedSubs.forEach { removeChildWin(it) } }
        if (mapOpen) { mapOpen = false; mapSubs.forEach { removeChildWin(it) } }
        tapOpen = !tapOpen
        if (tapOpen) {
            val v = hubView ?: return
            tapSubs.forEachIndexed { j, b -> addChildWin(b, rowCenterX(j), columnCenterY(v.autoTapIndex)) }
            applyState()
        } else {
            tapSubs.forEach { removeChildWin(it) }
        }
    }

    private fun relayoutChildren() {
        if (!expanded) return
        val v = hubView ?: return
        columnButtons.forEachIndexed { i, b -> moveChildWin(b, columnCenterX(), columnCenterY(i)) }
        if (speedOpen) speedSubs.forEachIndexed { j, b ->
            moveChildWin(b, rowCenterX(j), columnCenterY(v.speedIndex))
        }
        if (mapOpen) mapSubs.forEachIndexed { j, b -> moveChildWin(b, rowCenterX(j), columnCenterY(0)) }
        if (tapOpen) tapSubs.forEachIndexed { j, b ->
            moveChildWin(b, rowCenterX(j), columnCenterY(v.autoTapIndex))
        }
    }

    private fun moveHub(dx: Float, dy: Float) {
        val view = hubView ?: return
        val params = hubParams ?: return
        val dm = resources.displayMetrics
        params.x = clampInt(params.x + dx.toInt(), 0, dm.widthPixels - view.hubPx)
        params.y = clampInt(params.y + dy.toInt(), 0, dm.heightPixels - view.hubPx)
        runCatching { windowManager.updateViewLayout(view, params) }
        relayoutChildren()
    }

    private fun clampInt(v: Int, lo: Int, hi: Int): Int =
        if (lo > hi) lo else v.coerceIn(lo, hi)

    // ---------------------------------------------------------------------------------------
    // State reflection
    // ---------------------------------------------------------------------------------------

    private fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            launch { MockLocationService.state.collectLatest { applyState() } }
            launch { AutoTapService.running.collectLatest { applyState() } }
        }
    }

    private fun applyState() {
        val s = MockLocationService.state.value
        btnJoystick?.active = joystickView != null
        setLockVisual(joystickLocked)
        setSpeedVisual(bucketOf(s.speedMps))
        subGpx?.applyGpx(gpxVisual(s))
        setAutoTapVisual()
    }

    /**
     * 連點 slot buttons (1/2/3): each disabled (dimmed) with no saved points; the currently running
     * slot renders green (active). Only one slot is ever active.
     */
    private fun setAutoTapVisual() {
        val runningSlot = AutoTapService.running.value
        listOf(subTap1, subTap2, subTap3).forEachIndexed { i, b ->
            b ?: return@forEachIndexed
            val slot = i + 1
            val hasPoints = SessionStore.loadTapPoints(this, slot).isNotEmpty()
            b.isEnabled = hasPoints
            b.dimmed = !hasPoints
            b.active = runningSlot == slot
        }
    }

    private fun setLockVisual(locked: Boolean) {
        val b = btnLock ?: return
        b.active = locked
        b.glyph = if (locked) ChildButton.Glyph.LOCK else ChildButton.Glyph.LOCK_OPEN
        b.invalidate()
    }

    /** Highlight the active speed bucket (0 走 / 1 跑 / 2 車; -1 = custom). */
    private fun setSpeedVisual(idx: Int) {
        subWalk?.active = idx == 0
        subRun?.active = idx == 1
        subCar?.active = idx == 2
        val b = btnSpeed ?: return
        b.active = idx in 0..2
        b.glyph = when (idx) {
            0 -> ChildButton.Glyph.WALK
            1 -> ChildButton.Glyph.RUN
            2 -> ChildButton.Glyph.CAR
            else -> ChildButton.Glyph.SPEED
        }
        b.invalidate()
    }

    /**
     * GPX toggle visual: DISABLED when no route is loaded AND no GPX is selected in the library;
     * PLAYING (blue) while the playback cursor is advancing; PAUSED (amber) otherwise.
     */
    private fun gpxVisual(s: MockState): ChildButton.GpxVisual {
        val loaded = s.playback.hasRoute || SessionStore.loadCurrentGpxId(this) != null
        return when {
            !loaded -> ChildButton.GpxVisual.DISABLED
            s.playback.active && !s.playback.paused -> ChildButton.GpxVisual.PLAYING
            else -> ChildButton.GpxVisual.PAUSED
        }
    }

    /** Map a speed (m/s) to a preset bucket index (0 走 / 1 跑 / 2 車), or -1 if fine-tuned. */
    private fun bucketOf(mps: Double): Int {
        PRESET_KMH.forEachIndexed { i, kmh ->
            if (kotlin.math.abs(mps - kmh / 3.6) < 0.02) return i
        }
        return -1
    }

    private fun selectSpeed(idx: Int) {
        if (idx !in PRESET_KMH.indices) return
        MockLocationService.setSpeed(PRESET_KMH[idx] / 3.6)
        // Unify with the map's speed chip persistence so both stay in sync.
        SessionStore.saveSpeedChip(this, idx)
    }

    // ---------------------------------------------------------------------------------------
    // Joystick window (toggled by the hub's 搖桿 child)
    // ---------------------------------------------------------------------------------------

    private fun toggleJoystick() {
        if (joystickView != null) removeJoystick() else showJoystick()
        applyState()
    }

    private fun showJoystick() {
        // Restore the joystick window to its last top-left (else default).
        val savedJoy = SessionStore.loadJoystickPos(this)
        val params = baseParams(
            (density * 96).toInt(),
            (density * 96).toInt() + (density * 24).toInt(),
        ).apply {
            x = savedJoy?.first ?: (density * 40).toInt()
            y = savedJoy?.second ?: (density * 320).toInt()
        }
        joystickParams = params
        val container = FrameLayout(this)

        val joystick = JoystickView(this).apply {
            locked = joystickLocked
            listener = object : JoystickView.Listener {
                override fun onMove(north: Double, east: Double, magnitude: Double) {
                    MockLocationService.setJoystick(north, east, magnitude)
                }
                override fun onRelease() {
                    if (!joystickLocked) MockLocationService.clearJoystick()
                }
            }
            onDragWindow = { dx, dy ->
                params.x += dx.toInt()
                params.y += dy.toInt()
                runCatching { windowManager.updateViewLayout(container, params) }
                SessionStore.saveJoystickPos(this@OverlayService, params.x, params.y)
            }
        }
        container.addView(
            joystick,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        joystickInner = joystick
        joystickView = container
        windowManager.addView(container, params)
        SessionStore.saveJoystickVisible(this, true)
    }

    private fun removeJoystick() {
        joystickView?.let { runCatching { windowManager.removeView(it) } }
        joystickView = null
        joystickInner = null
        joystickParams = null
        SessionStore.saveJoystickVisible(this, false)
        // Leaving the joystick must not keep marching a locked heading.
        if (!joystickLocked) MockLocationService.clearJoystick()
    }

    // ---------------------------------------------------------------------------------------
    // 連點 (auto-tap): start/stop toggle + full-screen point picker
    // ---------------------------------------------------------------------------------------

    /**
     * Tap a 連點 slot button (1/2/3): start that preset, stopping whatever slot was running (one at a
     * time). Tapping the already-running slot stops it. Empty slots are disabled, so a no-point tap
     * is a no-op. Without the accessibility service we can't dispatch taps: guide the user to enable it.
     */
    private fun onTapSlot(slot: Int) {
        val svc = AutoTapService.instance
        if (svc == null) {
            Toast.makeText(this, R.string.autotap_need_service, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        if (AutoTapService.running.value == slot) {
            svc.stopTapping()
        } else {
            val points = SessionStore.loadTapPoints(this, slot)
            if (points.isEmpty()) return   // slot is disabled anyway
            svc.startTapping(slot, points)   // replaces any other running slot
        }
        applyState()
    }

    /**
     * Enter the point picker: a full-screen touch-catching overlay. The hub + joystick are hidden so
     * they can't block the taps, and restored on exit. Existing points start cleared each time.
     */
    private fun enterAutoTapPick(slot: Int) {
        pickSlot = slot
        if (pickRoot != null) return
        collapse()
        hubView?.visibility = View.GONE
        joystickView?.visibility = View.GONE

        val root = FrameLayout(this)
        val pick = TapPickView(this)
        root.addView(pick, FrameLayout.LayoutParams(MP, MP))

        val hint = TextView(this).apply {
            setText(R.string.autotap_pick_hint)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            val padH = (16 * density).toInt()
            setPadding(padH, (48 * density).toInt(), padH, (16 * density).toInt())
        }
        root.addView(hint, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val padH = (16 * density).toInt()
            setPadding(padH, padH, padH, (32 * density).toInt())
        }
        fun addBarButton(textRes: Int, onClick: () -> Unit) {
            val b = Button(this).apply {
                setText(textRes)
                setOnClickListener { onClick() }
            }
            bar.addView(b, LinearLayout.LayoutParams(0, WC, 1f))
        }
        addBarButton(R.string.autotap_clear) { pick.clearPoints() }
        addBarButton(R.string.autotap_save) { exitAutoTapPick(save = true) }
        addBarButton(R.string.autotap_cancel) { exitAutoTapPick(save = false) }
        root.addView(bar, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))

        pickRoot = root
        pickInner = pick
        runCatching { windowManager.addView(root, fullscreenParams()) }
    }

    private fun exitAutoTapPick(save: Boolean) {
        if (save) pickInner?.let { SessionStore.saveTapPoints(this, pickSlot, it.points) }
        pickRoot?.let { runCatching { windowManager.removeView(it) } }
        pickRoot = null
        pickInner = null
        hubView?.visibility = View.VISIBLE
        joystickView?.visibility = View.VISIBLE
        applyState()
    }

    /** Full-screen overlay whose view coords equal screen coords (NO_LIMITS), for absolute taps. */
    private fun fullscreenParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    // ---------------------------------------------------------------------------------------
    // Window / lifecycle plumbing
    // ---------------------------------------------------------------------------------------

    private fun baseParams(widthPx: Int, heightPx: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun teardown() {
        observeJob?.cancel()
        observeJob = null
        // 連點 must not keep firing after the overlay is gone.
        AutoTapService.instance?.stopTapping()
        pickRoot?.let { runCatching { windowManager.removeView(it) } }
        pickRoot = null
        pickInner = null
        joystickView?.let { runCatching { windowManager.removeView(it) } }
        joystickView = null
        childParams.keys.toList().forEach { runCatching { windowManager.removeView(it) } }
        childParams.clear()
        hubView?.let { runCatching { windowManager.removeView(it) } }
        hubView = null
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Floating joystick",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps the floating control menu visible over other apps" }
            )
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hideIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setSmallIcon(R.drawable.ic_stat_location)
            .setColor(getColor(R.color.brand_primary))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notif_action_hide), hideIntent).build()
            )
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        teardown()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW = "com.dopiz.gpsjoystick.overlay.SHOW"
        const val ACTION_SHOW_ALL = "com.dopiz.gpsjoystick.overlay.SHOW_ALL"
        const val ACTION_HIDE = "com.dopiz.gpsjoystick.overlay.HIDE"
        const val ACTION_PICK_TAP = "com.dopiz.gpsjoystick.overlay.PICK_TAP"
        const val EXTRA_SLOT = "slot"

        private const val CHANNEL_ID = "overlay_joystick"
        private const val NOTIFICATION_ID = 1002

        private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Speed presets in km/h, in sub-row order 走 / 跑 / 車 — matches MapActivity chips. */
        private val PRESET_KMH = listOf(5.0, 15.0, 40.0)

        fun show(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_SHOW))
        }

        /** Show the hub AND the joystick together (the mock-start path). */
        fun showAll(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_SHOW_ALL))
        }

        fun hide(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_HIDE))
        }
    }
}
