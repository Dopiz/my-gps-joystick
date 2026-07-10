package com.dopiz.gpsjoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.atan2

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
    private var fanAngle = -Math.PI / 2

    // --- Joystick window state ---
    private var joystickView: View? = null
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

        val params = baseParams(view.hubPx, view.hubPx).apply {
            x = (density * 20).toInt()
            y = (density * 160).toInt()
        }
        hubParams = params

        view.onHubDrag = { dx, dy -> moveHub(dx, dy) }
        view.onRequestExpand = { expandHub() }
        view.onRequestCollapse = { collapseHub() }
        view.onToggleJoystick = { toggleJoystick() }
        view.onTogglePause = {
            MockLocationService.setMovementPaused(!MockLocationService.state.value.movementPaused)
        }
        view.onSetSpeed = { idx -> selectSpeed(idx) }

        windowManager.addView(view, params)
        view.configure(hubInWinX, hubInWinY, fanAngle)

        // Reflect current state immediately + keep it live.
        applyState()
        startObserving()
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
        val exp = view.expandedPx

        val cxScreen = params.x + hubInWinX
        val cyScreen = params.y + hubInWinY

        val nx = fitStart(cxScreen - exp / 2f, exp, dm.widthPixels)
        val ny = fitStart(cyScreen - exp / 2f, exp, dm.heightPixels)

        hubInWinX = cxScreen - nx
        hubInWinY = cyScreen - ny
        // Open toward the screen centre so children never fan off the nearest edge.
        fanAngle = atan2((dm.heightPixels / 2f - cyScreen).toDouble(),
            (dm.widthPixels / 2f - cxScreen).toDouble())

        params.width = exp
        params.height = exp
        params.x = nx
        params.y = ny
        winW = exp
        winH = exp
        runCatching { windowManager.updateViewLayout(view, params) }
        view.configure(hubInWinX, hubInWinY, fanAngle)
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
        view.configure(hubInWinX, hubInWinY, fanAngle)
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
        val params = baseParams(
            (density * 96).toInt(),
            (density * 96).toInt() + (density * 24).toInt(),
        ).apply {
            x = (density * 40).toInt()
            y = (density * 320).toInt()
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
            }
        }
        container.addView(
            joystick,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val lockPx = (density * 40).toInt()
        val lockBtn = TextView(this).apply {
            text = if (joystickLocked) LOCK_GLYPH_ON else LOCK_GLYPH_OFF
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            background = lockButtonBg(joystickLocked)
        }
        lockBtn.setOnClickListener {
            joystickLocked = !joystickLocked
            joystick.locked = joystickLocked
            lockBtn.text = if (joystickLocked) LOCK_GLYPH_ON else LOCK_GLYPH_OFF
            lockBtn.background = lockButtonBg(joystickLocked)
            if (!joystickLocked) MockLocationService.clearJoystick()
        }
        container.addView(
            lockBtn,
            FrameLayout.LayoutParams(lockPx, lockPx, Gravity.TOP or Gravity.END),
        )

        joystickView = container
        windowManager.addView(container, params)
    }

    private fun removeJoystick() {
        joystickView?.let { runCatching { windowManager.removeView(it) } }
        joystickView = null
        joystickParams = null
        // Leaving the joystick must not keep marching a locked heading.
        if (!joystickLocked) MockLocationService.clearJoystick()
    }

    private fun lockButtonBg(locked: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (locked) Color.argb(255, 34, 197, 94) else Color.argb(230, 27, 36, 46))
        setStroke((density * 1.5f).toInt(), Color.argb(255, 42, 53, 66))
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
        private const val LOCK_GLYPH_ON = "🔒"
        private const val LOCK_GLYPH_OFF = "🔓"

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
