package com.dopiz.gpsjoystick

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Speed-dial style radial control menu hosted in a floating overlay window.
 *
 * A draggable circular HUB expands into a fan of child buttons (joystick toggle, ▶/⏸ pause,
 * speed). Tapping speed opens a second sub-ring (走 / 跑 / 車). The view owns only the visuals
 * and gesture routing; the [OverlayService] owns the WindowManager window and resizes it to fit
 * the fan (see [expandedPx]) — the view asks the service to grow/shrink via callbacks so the
 * window is the right size before children animate out.
 *
 * Geometry (dp) is centralised here so the service and the view never disagree on sizing.
 */
class RadialMenuView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** Collapsed hub window is a square of this side. */
    val hubPx: Int = dp(60f).toInt()
    private val childPx: Int = dp(52f).toInt()
    private val fanRadiusPx: Float = dp(88f)
    private val subRadiusPx: Float = dp(150f)

    /** Expanded window square side — big enough for the furthest sub-ring button plus margin. */
    val expandedPx: Int = ((subRadiusPx + childPx / 2f + dp(10f)) * 2f).toInt()

    // --- Callbacks wired by the service ---
    var onHubDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    var onRequestExpand: () -> Unit = {}
    var onRequestCollapse: () -> Unit = {}
    var onToggleJoystick: () -> Unit = {}
    var onTogglePause: () -> Unit = {}
    var onSetSpeed: (idx: Int) -> Unit = {}

    var expanded: Boolean = false
        private set
    private var speedOpen = false

    private val hub = ChildButton(context, ChildButton.Glyph.HUB)
    private val btnJoystick = ChildButton(context, ChildButton.Glyph.JOYSTICK)
    private val btnPause = ChildButton(context, ChildButton.Glyph.PAUSE)
    private val btnSpeed = ChildButton(context, ChildButton.Glyph.SPEED)
    // Main ring order: joystick, pause, speed. Speed sits at the outer end so its sub-ring
    // fans further out without colliding with the others.
    private val mains = listOf(btnJoystick, btnPause, btnSpeed)

    private val subWalk = ChildButton(context, ChildButton.Glyph.TEXT, "走")
    private val subRun = ChildButton(context, ChildButton.Glyph.TEXT, "跑")
    private val subCar = ChildButton(context, ChildButton.Glyph.TEXT, "車")
    private val subs = listOf(subWalk, subRun, subCar)

    private var hubCX = hubPx / 2f
    private var hubCY = hubPx / 2f
    private var fanAngle = -Math.PI / 2 // default: open upward

    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // --- Hub gesture: tap = expand/collapse, drag = reposition window ---
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val hubTouch = OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY
                dragging = false
                v.isPressed = true
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    dragging = true
                    v.isPressed = false
                }
                if (dragging) {
                    onHubDrag(e.rawX - lastX, e.rawY - lastY)
                    lastX = e.rawX; lastY = e.rawY
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                if (dragging) onDragEnd() else onHubClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                true
            }
            else -> false
        }
    }

    init {
        // Add order = z-order: sub-ring below mains, hub on top so children emerge from behind it.
        subs.forEach { addChild(it, childPx) }
        mains.forEach { addChild(it, childPx) }
        addChild(hub, hubPx)
        (subs + mains).forEach { it.visibility = View.GONE }

        btnJoystick.setOnClickListener { onToggleJoystick() }
        btnPause.setOnClickListener { onTogglePause() }
        btnSpeed.setOnClickListener { toggleSpeedRing() }
        subWalk.setOnClickListener { onSetSpeed(0) }
        subRun.setOnClickListener { onSetSpeed(1) }
        subCar.setOnClickListener { onSetSpeed(2) }

        hub.setOnTouchListener(hubTouch)
    }

    private fun addChild(v: View, size: Int) =
        addView(v, LayoutParams(size, size))

    // --- Service-facing state reflection ---

    fun setJoystickVisible(visible: Boolean) { btnJoystick.active = visible }

    fun setPaused(paused: Boolean) {
        btnPause.glyph = if (paused) ChildButton.Glyph.PLAY else ChildButton.Glyph.PAUSE
        btnPause.invalidate()
    }

    /** Highlight the active speed bucket (0 走 / 1 跑 / 2 車; -1 = none). */
    fun setActiveSpeed(idx: Int) {
        subs.forEachIndexed { i, b -> b.active = i == idx }
        btnSpeed.active = idx in subs.indices
    }

    // --- Geometry / positioning ---

    /**
     * Position the hub at ([cx],[cy]) in current window coords and set the fan opening direction.
     * Called by the service after every window resize.
     */
    fun configure(cx: Float, cy: Float, fanAngleRad: Double) {
        hubCX = cx
        hubCY = cy
        fanAngle = fanAngleRad
        hub.x = cx - hubPx / 2f
        hub.y = cy - hubPx / 2f
        if (expanded) layoutFan(animate = false)
    }

    private fun mainAngle(i: Int): Double = fanAngle + (i - 1) * STEP
    // Sub-ring is an OUTER arc centred on the same toward-screen-centre [fanAngle] as the main
    // fan, so 走/跑/車 stay on-screen even when the hub (and thus the speed button) sits against
    // an edge — anchoring the sub arc to the speed button's own radial angle would fan it off-screen.
    private fun subAngle(j: Int): Double = fanAngle + (j - 1) * SUB_STEP

    private fun centerX(angle: Double, radius: Float) = hubCX + radius * cos(angle).toFloat()
    private fun centerY(angle: Double, radius: Float) = hubCY + radius * sin(angle).toFloat()

    // --- Expand / collapse ---

    /** Run the fan-out animation. The service has already grown the window to [expandedPx]. */
    fun expand() {
        expanded = true
        speedOpen = false
        hub.animate().rotation(45f).setDuration(DUR).start()
        layoutFan(animate = true)
    }

    private fun layoutFan(animate: Boolean) {
        mains.forEachIndexed { i, b ->
            showChild(b, centerX(mainAngle(i), fanRadiusPx), centerY(mainAngle(i), fanRadiusPx),
                delay = i * STAGGER, animate = animate)
        }
        subs.forEach { it.visibility = View.GONE }
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        speedOpen = false
        hub.animate().rotation(0f).setDuration(DUR).start()
        (mains + subs).forEach { hideChild(it) }
        // Shrink the window only after the children have animated back under the hub.
        postDelayed({ if (!expanded) onRequestCollapse() }, DUR + STAGGER * mains.size)
    }

    private fun toggleSpeedRing() {
        speedOpen = !speedOpen
        if (speedOpen) {
            subs.forEachIndexed { j, b ->
                showChild(b, centerX(subAngle(j), subRadiusPx), centerY(subAngle(j), subRadiusPx),
                    delay = j * STAGGER, animate = true)
            }
        } else {
            subs.forEach { hideChild(it) }
        }
    }

    private fun showChild(v: View, cx: Float, cy: Float, delay: Long, animate: Boolean) {
        val left = cx - childPx / 2f
        val top = cy - childPx / 2f
        v.visibility = View.VISIBLE
        if (!animate) {
            v.animate().cancel()
            v.x = left; v.y = top; v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
            return
        }
        v.x = hubCX - childPx / 2f
        v.y = hubCY - childPx / 2f
        v.scaleX = 0.3f; v.scaleY = 0.3f; v.alpha = 0f
        v.animate().cancel()
        v.animate().x(left).y(top).scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(delay).setDuration(DUR).start()
    }

    private fun hideChild(v: View) {
        if (v.visibility != View.VISIBLE) return
        v.animate().cancel()
        v.animate().x(hubCX - childPx / 2f).y(hubCY - childPx / 2f)
            .scaleX(0.3f).scaleY(0.3f).alpha(0f)
            .setStartDelay(0).setDuration(DUR)
            .withEndAction { v.visibility = View.GONE }
            .start()
    }

    private fun onHubClick() {
        if (expanded) collapse() else onRequestExpand()
    }

    /** Empty-area taps inside the expanded window collapse the menu (children consume their own). */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!expanded) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> { collapse(); true }
            else -> false
        }
    }

    private companion object {
        const val DUR = 200L
        const val STAGGER = 28L
        val STEP = Math.toRadians(46.0)
        val SUB_STEP = Math.toRadians(40.0)
    }
}
