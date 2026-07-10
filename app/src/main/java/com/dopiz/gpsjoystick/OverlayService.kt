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

/**
 * Hosts the floating overlay (SYSTEM_ALERT_WINDOW) that must survive switching to other apps.
 *
 * Started (not foreground) service: the overlay window is owned by the process and stays drawn
 * on top of whatever app is in front. The already-foreground [MockLocationService] keeps the
 * process alive while the joystick is actually driving the mock; this service just owns the
 * window.
 *
 * Slice 5: a bare draggable box, to isolate and prove the highest overlay risk (does a
 * SYSTEM_ALERT_WINDOW view really stay visible over other apps and stay draggable). Slice 6
 * replaces the box with a live joystick.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeOverlay()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> showOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "尚未授予懸浮窗權限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        startForegroundCompat()

        if (overlayView != null) return  // already showing

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = baseLayoutParams()
        val container = FrameLayout(this)

        var locked = false
        val joystick = JoystickView(this).apply {
            listener = object : JoystickView.Listener {
                override fun onMove(north: Double, east: Double, magnitude: Double) {
                    MockLocationService.setJoystick(north, east, magnitude)
                }
                override fun onRelease() {
                    // Feature 2: when locked, do NOT clear — keep marching in the last heading
                    // at the current (shared SpeedModel) speed until the user unlocks.
                    if (!locked) MockLocationService.clearJoystick()
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

        val lockPx = (resources.displayMetrics.density * 48).toInt()
        val lockBtn = TextView(this).apply {
            text = LOCK_GLYPH_OFF
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            background = lockButtonBg(false)
        }
        fun applyLockUi() {
            joystick.locked = locked
            lockBtn.text = if (locked) LOCK_GLYPH_ON else LOCK_GLYPH_OFF
            lockBtn.background = lockButtonBg(locked)
        }
        lockBtn.setOnClickListener {
            locked = !locked
            applyLockUi()
            // Unlocking is a deliberate stop.
            if (!locked) MockLocationService.clearJoystick()
        }
        container.addView(
            lockBtn,
            FrameLayout.LayoutParams(lockPx, lockPx, Gravity.TOP or Gravity.END),
        )

        overlayView = container
        windowManager.addView(container, params)
    }

    private fun lockButtonBg(locked: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        // status_active green when locked, surface_variant when idle.
        setColor(if (locked) Color.argb(255, 34, 197, 94) else Color.argb(230, 27, 36, 46))
        setStroke((resources.displayMetrics.density * 1.5f).toInt(), Color.argb(255, 42, 53, 66))
    }

    private fun baseLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val sizePx = (resources.displayMetrics.density * 140).toInt()
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 320
        }
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }

    /**
     * A foreground service is what keeps a SYSTEM_ALERT_WINDOW overlay drawn once the app is
     * backgrounded — a plain started service has its overlay surface hidden on app switch.
     */
    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Floating joystick",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps the floating joystick visible over other apps" }
            )
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
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
                Notification.Action.Builder(
                    null, getString(R.string.notif_action_hide), hideIntent
                ).build()
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
        removeOverlay()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW = "com.dopiz.gpsjoystick.overlay.SHOW"
        const val ACTION_HIDE = "com.dopiz.gpsjoystick.overlay.HIDE"

        private const val CHANNEL_ID = "overlay_joystick"
        private const val NOTIFICATION_ID = 1002
        private const val LOCK_GLYPH_ON = "🔒"   // 🔒
        private const val LOCK_GLYPH_OFF = "🔓"  // 🔓

        fun show(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
            )
        }

        fun hide(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_HIDE)
            )
        }
    }
}
