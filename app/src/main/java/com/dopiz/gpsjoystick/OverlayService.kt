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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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

        val box = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(180, 33, 150, 243))
                setStroke(6, Color.WHITE)
            }
        }
        overlayView = box

        val params = baseLayoutParams()
        makeDraggable(box, params)
        windowManager.addView(box, params)
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

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
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
            .setContentTitle("懸浮搖桿顯示中")
            .setContentText("點此開啟 app，或按隱藏關閉搖桿")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(null, "隱藏", hideIntent).build()
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
