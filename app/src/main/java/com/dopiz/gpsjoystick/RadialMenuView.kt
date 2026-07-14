package com.dopiz.gpsjoystick

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * The draggable HUB of the floating speed-dial menu, hosted in its OWN small content-sized overlay
 * window (side [hubPx]). It renders only the hub disc (app icon collapsed / ✕ expanded) and routes
 * the hub gesture: a tap toggles expand/collapse, a drag repositions the window.
 *
 * Unlike the previous single-window design, every child button lives in its OWN window managed by
 * [OverlayService] (so the gaps between buttons have no window and touches fall through to the app
 * behind). This view therefore owns only the hub visuals + gesture and publishes the shared fan
 * geometry (dp) so the service and the view never disagree on sizing.
 */
class RadialMenuView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** Collapsed hub window is a square of this side. */
    val hubPx: Int = dp(60f).toInt()
    /** Each child button window is a square of this side. */
    val childPx: Int = dp(56f).toInt()
    private val gapPx: Float = dp(8f)                    // >=8dp gap between adjacent discs
    /** Centre-to-centre spacing along a fan line. */
    val stepPx: Float = childPx + gapPx
    /** Hub centre -> first child centre (hub edge to child edge = gapPx). */
    val firstOffsetPx: Float = hubPx / 2f + childPx / 2f + gapPx
    private val marginPx: Float = dp(16f)               // shadow + breathing room at the far edge

    // Vertical column: 地圖 / 搖桿 / 鎖定 / 連點 / 速度 (5 children). Speed sits at the outer end so its
    // 走/跑/車 sub-row fans further out without colliding with the others.
    val columnCount = 5
    val autoTapIndex = 3
    val speedIndex = columnCount - 1

    /** How far the column reaches past the hub centre (down or up). */
    val vReachPx: Float = firstOffsetPx + (columnCount - 1) * stepPx + childPx / 2f + marginPx
    /** How far a horizontal sub-row reaches past the hub centre (right or left, up to 4 subs: 速度 走/跑/車/自訂). */
    val hReachPx: Float = 4 * stepPx + childPx / 2f + marginPx

    // --- Callbacks wired by the service ---
    var onHubDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    var onRequestExpand: () -> Unit = {}
    var onRequestCollapse: () -> Unit = {}

    var expanded: Boolean = false
        private set

    private val hub = ChildButton(context, ChildButton.Glyph.HUB)

    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

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
        addView(hub, LayoutParams(hubPx, hubPx))
        hub.setOnTouchListener(hubTouch)
    }

    private fun onHubClick() {
        if (expanded) onRequestCollapse() else onRequestExpand()
    }

    /** Flip the hub to its expanded (✕) or collapsed (app icon) look with a 45° spin. */
    fun setExpandedVisual(exp: Boolean) {
        expanded = exp
        if (exp) {
            hub.hubExpanded = true
            hub.animate().rotation(45f).setDuration(DUR).start()
        } else {
            hub.animate().rotation(0f).setDuration(DUR)
                .withEndAction { hub.hubExpanded = false }
                .start()
        }
    }

    private companion object {
        const val DUR = 200L
    }
}
