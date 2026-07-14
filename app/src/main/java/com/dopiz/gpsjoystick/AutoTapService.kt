package com.dopiz.gpsjoystick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AccessibilityService that powers the 連點 (auto-tap) feature: it dispatches synthetic taps onto
 * fixed screen coordinates the user picked earlier. No events are observed — the service exists only
 * so we may call [dispatchGesture] (which requires an enabled accessibility service).
 *
 * Like [MockLocationService], the live running flag is exposed as a companion [StateFlow] so the
 * [OverlayService] toggle reflects it without any binding/DI. The live [instance] lets the overlay
 * reach the engine directly.
 */
class AutoTapService : AccessibilityService() {

    // Cycle 1→2→3→1… on the main thread; each step fires a 1ms tap on the next point.
    private val handler = Handler(Looper.getMainLooper())
    private var points: List<PointF> = emptyList()
    private var index = 0

    private val tick = object : Runnable {
        override fun run() {
            val pts = points
            if (pts.isEmpty()) return
            val p = pts[index % pts.size]
            tapAt(p.x, p.y)
            index = (index + 1) % pts.size
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    val isRunning: Boolean get() = _running.value

    /** Start the fixed-interval tap loop over [pts] (screen-absolute coords). No-op if empty. */
    fun startTapping(pts: List<PointF>) {
        if (pts.isEmpty()) return
        points = pts
        index = 0
        handler.removeCallbacks(tick)
        handler.post(tick)
        _running.value = true
    }

    fun stopTapping() {
        handler.removeCallbacks(tick)
        _running.value = false
    }

    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopTapping()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopTapping()
        instance = null
        super.onDestroy()
    }

    // Not monitoring anything — the config requests the minimal event set and we ignore it.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val INTERVAL_MS = 250L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Live service instance while enabled, else null (auto-tap unavailable). */
        var instance: AutoTapService? = null
            private set
    }
}
