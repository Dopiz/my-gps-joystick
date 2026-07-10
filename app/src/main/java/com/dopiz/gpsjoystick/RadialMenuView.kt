package com.dopiz.gpsjoystick

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Speed-dial style linear control menu hosted in a floating overlay window.
 *
 * A draggable circular HUB expands into a vertical column of child buttons (joystick toggle,
 * ▶/⏸ pause, speed). Tapping speed opens a horizontal row (走 / 跑 / 車). The view owns only the visuals
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
    private val childPx: Int = dp(56f).toInt()
    private val gapPx: Float = dp(8f)               // >=8dp gap between adjacent discs
    private val stepPx: Float = childPx + gapPx      // centre-to-centre along a line
    // Hub centre -> first child centre: hub edge to child edge = gapPx.
    private val firstOffsetPx: Float = hubPx / 2f + childPx / 2f + gapPx
    private val marginPx: Float = dp(16f)            // shadow + breathing room at the far edge

    // Number of children in the vertical column (地圖 / 搖桿 / 鎖定 / ⏸ / 速度) and the index of
    // the 速度 (speed) child, off which the horizontal 走/跑/車 sub-row fans.
    private val columnCount = 5
    private val speedIndex = columnCount - 1

    // Extents from the hub centre used to size the (non-square) expanded window.
    private val nearExtentPx: Float = hubPx / 2f + marginPx
    private val vFarExtentPx: Float =
        firstOffsetPx + (columnCount - 1) * stepPx + childPx / 2f + marginPx
    private val hFarExtentPx: Float = 3 * stepPx + childPx / 2f + marginPx

    /** How far the column reaches past the hub centre (down or up). */
    val vReachPx: Float get() = vFarExtentPx
    /** How far the speed row reaches past the hub centre (right or left). */
    val hReachPx: Float get() = hFarExtentPx

    /** Expanded window is a rectangle: tall enough for the vertical column of 3 children... */
    val expandedW: Int = (nearExtentPx + hFarExtentPx).toInt()
    /** ...and wide enough for the horizontal 走/跑/車 row off the speed child. */
    val expandedH: Int = (nearExtentPx + vFarExtentPx).toInt()

    /** Hub's offset inside the expanded window, given the chosen fan-out directions. */
    fun hubOffsetX(hDir: Int): Float = if (hDir > 0) nearExtentPx else hFarExtentPx
    fun hubOffsetY(vDir: Int): Float = if (vDir > 0) nearExtentPx else vFarExtentPx

    // --- Callbacks wired by the service ---
    var onHubDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    var onRequestExpand: () -> Unit = {}
    var onRequestCollapse: () -> Unit = {}
    var onToggleJoystick: () -> Unit = {}
    var onTogglePause: () -> Unit = {}
    var onSetSpeed: (idx: Int) -> Unit = {}
    var onOpenMap: () -> Unit = {}
    var onToggleGpx: () -> Unit = {}
    var onToggleLock: () -> Unit = {}

    var expanded: Boolean = false
        private set
    private var speedOpen = false
    private var mapOpen = false

    private val hub = ChildButton(context, ChildButton.Glyph.HUB)
    private val btnMap = ChildButton(context, ChildButton.Glyph.MAP)
    private val btnJoystick = ChildButton(context, ChildButton.Glyph.JOYSTICK)
    private val btnLock = ChildButton(context, ChildButton.Glyph.LOCK_OPEN)
    private val btnPause = ChildButton(context, ChildButton.Glyph.PAUSE)
    private val btnSpeed = ChildButton(context, ChildButton.Glyph.SPEED)
    // Vertical column order top→bottom: 地圖 / 搖桿 / 鎖定 / ⏸暫停 / 速度. Speed sits at the outer
    // end so its 走/跑/車 sub-row fans further out without colliding with the others.
    private val mains = listOf(btnMap, btnJoystick, btnLock, btnPause, btnSpeed)

    private val subWalk = ChildButton(context, ChildButton.Glyph.WALK)
    private val subRun = ChildButton(context, ChildButton.Glyph.RUN)
    private val subCar = ChildButton(context, ChildButton.Glyph.CAR)
    private val subs = listOf(subWalk, subRun, subCar)

    // 地圖 sub-row (fans off the 地圖 child, toward screen centre like the speed row): 開啟地圖頁 +
    // GPX 開始/暫停 toggle.
    private val subMapOpen = ChildButton(context, ChildButton.Glyph.MAP_OPEN)
    private val subGpx = ChildButton(context, ChildButton.Glyph.PLAY)
    private val mapSubs = listOf(subMapOpen, subGpx)

    private var hubCX = hubPx / 2f
    private var hubCY = hubPx / 2f
    // Fan-out directions chosen by the service so the column/row stay on-screen:
    // vDir = +1 column grows DOWN (hub in top half), -1 grows UP; hDir = +1 speed row grows RIGHT
    // (hub in left half), -1 grows LEFT.
    private var vDir = 1
    private var hDir = 1

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
        // Add order = z-order: sub-rings below mains, hub on top so children emerge from behind it.
        subs.forEach { addChild(it, childPx) }
        mapSubs.forEach { addChild(it, childPx) }
        mains.forEach { addChild(it, childPx) }
        addChild(hub, hubPx)
        (subs + mapSubs + mains).forEach { it.visibility = View.GONE }

        btnMap.setOnClickListener { toggleMapRing() }
        btnJoystick.setOnClickListener { onToggleJoystick() }
        btnLock.setOnClickListener { onToggleLock() }
        btnPause.setOnClickListener { onTogglePause() }
        btnSpeed.setOnClickListener { toggleSpeedRing() }
        subWalk.setOnClickListener { onSetSpeed(0) }
        subRun.setOnClickListener { onSetSpeed(1) }
        subCar.setOnClickListener { onSetSpeed(2) }
        subMapOpen.setOnClickListener { onOpenMap() }
        subGpx.setOnClickListener { onToggleGpx() }
        subMapOpen.contentDescription = context.getString(R.string.overlay_open_map_page)
        subGpx.contentDescription = context.getString(R.string.overlay_gpx_toggle)

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
        // The 速度 parent shows the current bucket's icon (walk/run/car), or the speedometer
        // when the speed is a custom slider value that matches no bucket.
        btnSpeed.glyph = when (idx) {
            0 -> ChildButton.Glyph.WALK
            1 -> ChildButton.Glyph.RUN
            2 -> ChildButton.Glyph.CAR
            else -> ChildButton.Glyph.SPEED
        }
        btnSpeed.invalidate()
    }

    /** Reflect the GPX playback toggle state (disabled / playing-blue / paused-amber). */
    fun setGpxState(state: ChildButton.GpxVisual) {
        subGpx.applyGpx(state)
    }

    /** Reflect the joystick hands-free lock state: locked = green disc + closed padlock. */
    fun setLockActive(locked: Boolean) {
        btnLock.active = locked
        btnLock.glyph = if (locked) ChildButton.Glyph.LOCK else ChildButton.Glyph.LOCK_OPEN
        btnLock.invalidate()
    }

    // --- Geometry / positioning ---

    /**
     * Position the hub at ([cx],[cy]) in current window coords and set the fan-out directions.
     * Called by the service after every window resize.
     */
    fun configure(cx: Float, cy: Float, verticalDir: Int, horizontalDir: Int) {
        hubCX = cx
        hubCY = cy
        vDir = verticalDir
        hDir = horizontalDir
        hub.x = cx - hubPx / 2f
        hub.y = cy - hubPx / 2f
        if (expanded) layoutFan(animate = false)
    }

    // Main children: a straight VERTICAL column from the hub, evenly spaced, growing in [vDir].
    private fun mainX(@Suppress("UNUSED_PARAMETER") i: Int): Float = hubCX
    private fun mainY(i: Int): Float = hubCY + vDir * (firstOffsetPx + i * stepPx)

    // Speed sub-menu: a HORIZONTAL row from the speed child (main index 2), growing in [hDir]
    // toward the screen centre so 走/跑/車 stay on-screen.
    private val speedY: Float get() = hubCY + vDir * (firstOffsetPx + speedIndex * stepPx)
    private fun subX(j: Int): Float = hubCX + hDir * stepPx * (j + 1)
    private fun subY(@Suppress("UNUSED_PARAMETER") j: Int): Float = speedY

    // --- Expand / collapse ---

    /** Run the fan-out animation. The service has already grown the window to [expandedPx]. */
    fun expand() {
        expanded = true
        speedOpen = false
        mapOpen = false
        hub.hubExpanded = true            // swap app icon -> "+" so the 45° spin reads as ✕
        hub.animate().rotation(45f).setDuration(DUR).start()
        layoutFan(animate = true)
    }

    private fun layoutFan(animate: Boolean) {
        mains.forEachIndexed { i, b ->
            showChild(b, mainX(i), mainY(i), delay = i * STAGGER, animate = animate)
        }
        (subs + mapSubs).forEach { it.visibility = View.GONE }
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        speedOpen = false
        mapOpen = false
        hub.animate().rotation(0f).setDuration(DUR)
            .withEndAction { hub.hubExpanded = false }   // restore the app icon once un-rotated
            .start()
        (mains + subs + mapSubs).forEach { hideChild(it) }
        // Shrink the window only after the children have animated back under the hub.
        postDelayed({ if (!expanded) onRequestCollapse() }, DUR + STAGGER * mains.size)
    }

    private fun toggleSpeedRing() {
        if (mapOpen) { mapOpen = false; mapSubs.forEach { hideChild(it) } }
        speedOpen = !speedOpen
        if (speedOpen) {
            subs.forEachIndexed { j, b ->
                showChild(b, subX(j), subY(j), delay = j * STAGGER, animate = true)
            }
        } else {
            subs.forEach { hideChild(it) }
        }
    }

    // The 地圖 sub-row shares the horizontal subX offsets but sits at the 地圖 child's row (index 0).
    private fun toggleMapRing() {
        if (speedOpen) { speedOpen = false; subs.forEach { hideChild(it) } }
        mapOpen = !mapOpen
        if (mapOpen) {
            mapSubs.forEachIndexed { j, b ->
                showChild(b, subX(j), mainY(0), delay = j * STAGGER, animate = true)
            }
        } else {
            mapSubs.forEach { hideChild(it) }
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
    }
}
