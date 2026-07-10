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
import android.view.WindowManager
import android.widget.FrameLayout
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
 * Owns two independent, content-sized windows:
 *  - the [RadialMenuView] HUB (speed-dial control menu): a small draggable circle that resizes
 *    its own window to fan out child buttons on expand and shrinks back on collapse.
 *  - the [JoystickView] window, toggled by the hub's 搖桿 child button.
 *
 * Both use FLAG_NOT_TOUCH_MODAL so touches outside the (content-sized) windows fall through to
 * the app behind. A [MockLocationService] state observer keeps the hub's buttons reflecting the
 * live joystick-visible / paused / active-speed state.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val density by lazy { resources.displayMetrics.density }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    // --- Hub window state ---
    private var hubView: RadialMenuView? = null
    private var hubParams: WindowManager.LayoutParams? = null
    private var winW = 0
    private var winH = 0
    private var hubInWinX = 0f
    private var hubInWinY = 0f
    // Fan-out directions (see RadialMenuView): +1 column down / row right, -1 up / left.
    private var vDir = 1
    private var hDir = 1

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
            else -> showHub()
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------------------------
    // Hub window
    // ---------------------------------------------------------------------------------------

    private fun showHub() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "尚未授予懸浮窗權限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        startForegroundCompat()
        if (hubView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = RadialMenuView(this)
        hubView = view

        winW = view.hubPx
        winH = view.hubPx
        hubInWinX = view.hubPx / 2f
        hubInWinY = view.hubPx / 2f

        // Feature 6: restore the hub to its last CENTRE position (else default top-left).
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

        // Feature 3/6: restore the joystick lock state before any joystick is shown.
        joystickLocked = SessionStore.loadLock(this)

        view.onHubDrag = { dx, dy -> moveHub(dx, dy) }
        view.onDragEnd = { saveHubPos() }
        view.onRequestExpand = { expandHub() }
        view.onRequestCollapse = { collapseHub() }
        view.onToggleJoystick = { toggleJoystick() }
        view.onToggleLock = { toggleLock() }
        view.onOpenMap = { openMap() }
        view.onToggleGpx = { toggleGpx() }
        view.onTogglePause = {
            MockLocationService.setMovementPaused(!MockLocationService.state.value.movementPaused)
        }
        view.onSetSpeed = { idx -> selectSpeed(idx) }

        windowManager.addView(view, params)
        view.configure(hubInWinX, hubInWinY, vDir, hDir)

        // Feature 1: apply the persisted speed on startup so the app opens at the last speed
        // (shared SpeedModel → the map reflects it too). Don't clobber a live running session.
        if (!MockLocationService.state.value.isRunning) {
            val chip = SessionStore.loadSpeedChip(this)
            if (chip in PRESET_KMH.indices) MockLocationService.setSpeed(PRESET_KMH[chip] / 3.6)
        }

        // Reflect current state immediately + keep it live.
        applyState()
        startObserving()

        // Feature 6: re-show the joystick if it was visible last time.
        if (SessionStore.loadJoystickVisible(this)) showJoystick()
    }

    private fun saveHubPos() {
        val params = hubParams ?: return
        SessionStore.saveHubPos(this, (params.x + hubInWinX).toInt(), (params.y + hubInWinY).toInt())
    }

    private fun openMap() {
        runCatching {
            startActivity(
                Intent(this, MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 地圖 sub-row GPX toggle. Controls GPX PLAYBACK specifically (start / pause), distinct from the
     * hub's ▶/⏸ which is the master movement freeze over everything.
     *
     * Interaction with the master freeze: starting/resuming GPX here also lifts the global freeze so
     * the route actually moves (blue = really playing). The global ▶/⏸ remains the master gate — a
     * user can still freeze a playing route with it; this toggle stays blue (playback is "active")
     * while the master gate holds the position.
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

    private fun moveHub(dx: Float, dy: Float) {
        val view = hubView ?: return
        val params = hubParams ?: return
        val dm = resources.displayMetrics
        params.x += dx.toInt()
        params.y += dy.toInt()
        // Keep the hub CENTER on screen regardless of window size.
        params.x = clampInt(params.x, (view.hubPx / 2 - hubInWinX).toInt(),
            (dm.widthPixels - view.hubPx / 2 - hubInWinX).toInt())
        params.y = clampInt(params.y, (view.hubPx / 2 - hubInWinY).toInt(),
            (dm.heightPixels - view.hubPx / 2 - hubInWinY).toInt())
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun expandHub() {
        val view = hubView ?: return
        val params = hubParams ?: return
        val dm = resources.displayMetrics
        val exW = view.expandedW
        val exH = view.expandedH

        val cxScreen = params.x + hubInWinX
        val cyScreen = params.y + hubInWinY

        // Optimise for the common top-left placement: default the column DOWN and the speed row
        // RIGHT (toward the screen centre). Only flip when that direction would run off the edge
        // and the opposite direction actually has room — clamp stays the final safety net.
        vDir = if (cyScreen + view.vReachPx > dm.heightPixels && cyScreen - view.vReachPx >= 0) -1 else 1
        hDir = if (cxScreen + view.hReachPx > dm.widthPixels && cxScreen - view.hReachPx >= 0) -1 else 1

        val hubOffX = view.hubOffsetX(hDir)
        val hubOffY = view.hubOffsetY(vDir)
        val nx = fitStart(cxScreen - hubOffX, exW, dm.widthPixels)
        val ny = fitStart(cyScreen - hubOffY, exH, dm.heightPixels)

        hubInWinX = cxScreen - nx
        hubInWinY = cyScreen - ny

        params.width = exW
        params.height = exH
        params.x = nx
        params.y = ny
        winW = exW
        winH = exH
        runCatching { windowManager.updateViewLayout(view, params) }
        view.configure(hubInWinX, hubInWinY, vDir, hDir)
        view.expand()
    }

    private fun collapseHub() {
        val view = hubView ?: return
        val params = hubParams ?: return
        val dm = resources.displayMetrics
        val cxScreen = params.x + hubInWinX
        val cyScreen = params.y + hubInWinY

        params.width = view.hubPx
        params.height = view.hubPx
        params.x = clampInt((cxScreen - view.hubPx / 2f).toInt(), 0, dm.widthPixels - view.hubPx)
        params.y = clampInt((cyScreen - view.hubPx / 2f).toInt(), 0, dm.heightPixels - view.hubPx)
        winW = view.hubPx
        winH = view.hubPx
        hubInWinX = view.hubPx / 2f
        hubInWinY = view.hubPx / 2f
        runCatching { windowManager.updateViewLayout(view, params) }
        view.configure(hubInWinX, hubInWinY, vDir, hDir)
        saveHubPos()
    }

    /** Top-left so a window of [size] fits within [extent]; centres it if it can't fit. */
    private fun fitStart(desired: Float, size: Int, extent: Int): Int =
        if (size <= extent) clampInt(desired.toInt(), 0, extent - size) else (extent - size) / 2

    private fun clampInt(v: Int, lo: Int, hi: Int): Int =
        if (lo > hi) lo else v.coerceIn(lo, hi)

    // ---------------------------------------------------------------------------------------
    // State reflection
    // ---------------------------------------------------------------------------------------

    private fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            MockLocationService.state.collectLatest { applyState() }
        }
    }

    private fun applyState() {
        val view = hubView ?: return
        val s = MockLocationService.state.value
        view.setJoystickVisible(joystickView != null)
        view.setPaused(s.movementPaused)
        view.setActiveSpeed(bucketOf(s.speedMps))
        view.setLockActive(joystickLocked)
        view.setGpxState(gpxVisual(s))
    }

    /**
     * GPX toggle visual: DISABLED when no route is loaded AND no GPX is selected in the library;
     * PLAYING (blue) while the playback cursor is advancing; PAUSED (amber) when a route is loaded
     * but not currently playing. Colour tracks playback.active/paused only — the master ▶/⏸ freeze
     * is reported separately and can still hold a "playing" route in place.
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
        // Feature 6: restore the joystick window to its last top-left (else default).
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
        joystickView?.let { runCatching { windowManager.removeView(it) } }
        joystickView = null
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
        const val ACTION_HIDE = "com.dopiz.gpsjoystick.overlay.HIDE"

        private const val CHANNEL_ID = "overlay_joystick"
        private const val NOTIFICATION_ID = 1002

        /** Speed presets in km/h, in sub-ring order 走 / 跑 / 車 — matches MapActivity chips. */
        private val PRESET_KMH = listOf(5.0, 15.0, 40.0)

        fun show(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_SHOW))
        }

        fun hide(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_HIDE))
        }
    }
}
