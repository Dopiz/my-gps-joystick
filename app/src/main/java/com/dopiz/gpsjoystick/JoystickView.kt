package com.dopiz.gpsjoystick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A virtual joystick drawn inside the floating overlay. The knob's deflection reports a unit
 * heading vector (screen-up = north) plus a magnitude 0..1 to [Listener]; the [OverlayService]
 * forwards that to [MockLocationService.setJoystick]. The stick sets DIRECTION ONLY — the move
 * speed is always the shared [SpeedModel] value, never scaled by deflection; the magnitude only
 * feeds the dead-zone (tiny deflection = stop). Releasing recenters and stops.
 *
 * The view reserves a grip strip along its top edge (any height by which the view is taller than
 * it is wide). A touch that starts on that strip — or outside the base circle — is treated as a
 * window-drag gesture and forwarded to [onDragWindow] so the whole overlay stays repositionable.
 */
class JoystickView(context: Context) : View(context) {

    interface Listener {
        fun onMove(north: Double, east: Double, magnitude: Double)
        fun onRelease()
    }

    var listener: Listener? = null

    /** Called with the incremental raw-screen delta while dragging the window body. */
    var onDragWindow: ((dxRaw: Float, dyRaw: Float) -> Unit)? = null

    /**
     * Feature 2: hands-free direction lock. When true the knob stays where it was released
     * (showing the marching heading) and is drawn in the active/green colour.
     */
    var locked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Semi-opaque dark backing so the joystick stays legible over any wallpaper/app behind it.
    private val backingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 19, 26, 34) // surface #131A22
    }
    // Translucent blue base ring fill.
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(46, 59, 130, 246) // primary #3B82F6, low alpha
    }
    // Outline in the brand outline color.
    private val baseStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(255, 42, 53, 66) // outline #2A3542
    }
    // Subtle glow behind the knob.
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 59, 130, 246)
    }
    // Blue primary knob (resting).
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 59, 130, 246) // primary #3B82F6
    }
    // Brighter knob + ring for the pressed/active state.
    private val knobPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 96, 165, 250) // primary lightened
    }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(255, 219, 234, 254) // on-primary-container #DBEAFE
    }
    // Locked (hands-free auto-march) knob + ring in status_active green.
    private val knobLockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 34, 197, 94) // status_active #22C55E
    }
    private val lockedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.argb(255, 34, 197, 94) // status_active #22C55E
    }
    // Grip dots on the top drag handle, so it reads as "press here to move".
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 148, 163, 184) // on-surface-variant #94A3B8
    }

    private var cx = 0f
    private var cy = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    /** Max distance the knob CENTRE may travel from the base centre, so the knob + its glow stay
     *  fully inside the view on every side (see [onSizeChanged]). */
    private var maxTravel = 0f
    private var knobX = 0f
    private var knobY = 0f
    /** Height of the top grip strip in px = how much taller the view is than it is wide. */
    private var handleStrip = 0f

    private var draggingKnob = false
    private var movingWindow = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // The top strip is whatever extra height the view has beyond a w×w square.
        handleStrip = (h - w).coerceAtLeast(0).toFloat()
        cx = w / 2f
        cy = handleStrip + w / 2f
        baseRadius = w / 2f * 0.85f
        knobRadius = baseRadius * 0.42f
        // The knob centre sits at most [maxTravel] from ([cx],[cy]); the knob edge plus its glow
        // (knobRadius * GLOW) must clear every side. The smallest available half-extent is w/2
        // (left / right / bottom; cy is offset down by handleStrip so the bottom is the tightest),
        // so clamp travel to that minus the glow radius and a hairline of safety padding. This
        // keeps the whole knob + glow on-screen at full deflection instead of clipping past the edge.
        maxTravel = (w / 2f - knobRadius * GLOW - 1f * resources.displayMetrics.density)
            .coerceAtLeast(0f)
        knobX = cx
        knobY = cy
    }

    override fun onDraw(canvas: Canvas) {
        if (handleStrip > 0f) drawGrip(canvas)
        canvas.drawCircle(cx, cy, baseRadius, backingPaint)
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        canvas.drawCircle(cx, cy, baseRadius, if (locked) lockedRingPaint else baseStroke)
        canvas.drawCircle(knobX, knobY, knobRadius * GLOW, glowPaint)
        when {
            locked -> {
                canvas.drawCircle(knobX, knobY, knobRadius, knobLockedPaint)
                canvas.drawCircle(knobX, knobY, knobRadius, lockedRingPaint)
            }
            draggingKnob -> {
                canvas.drawCircle(knobX, knobY, knobRadius, knobPressedPaint)
                canvas.drawCircle(knobX, knobY, knobRadius, knobRingPaint)
            }
            else -> canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        }
    }

    private val handleRect = RectF()

    /** A rounded pill bar with three grip dots, centred in the top strip: "press here to move". */
    private fun drawGrip(canvas: Canvas) {
        val gy = handleStrip / 2f
        val halfW = baseRadius * 0.55f
        val halfH = handleStrip * 0.34f
        handleRect.set(cx - halfW, gy - halfH, cx + halfW, gy + halfH)
        canvas.drawRoundRect(handleRect, halfH, halfH, backingPaint)
        val r = handleStrip * 0.09f
        val gap = r * 3.2f
        for (i in -1..1) canvas.drawCircle(cx + i * gap, gy, r, gripPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d = hypot(event.x - cx, event.y - cy)
                if (event.y >= handleStrip && d <= baseRadius) {
                    draggingKnob = true
                    updateKnob(event.x, event.y)
                } else {
                    // Top grip strip or the corners outside the base circle → move the window.
                    movingWindow = true
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingKnob) {
                    updateKnob(event.x, event.y)
                } else if (movingWindow) {
                    onDragWindow?.invoke(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingKnob) {
                    draggingKnob = false
                    // When locked, keep the knob deflected to show the marching heading.
                    if (!locked) recenter()
                    listener?.onRelease()
                }
                movingWindow = false
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateKnob(x: Float, y: Float) {
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        // Clamp the knob-centre travel to maxTravel (not baseRadius) so the knob + glow never clip.
        val clamped = min(dist, maxTravel)
        if (dist > 0f) {
            knobX = cx + dx / dist * clamped
            knobY = cy + dy / dist * clamped
        } else {
            knobX = cx
            knobY = cy
        }
        invalidate()

        val magnitude = if (maxTravel > 0f) (clamped / maxTravel).toDouble() else 0.0
        val east = if (dist > 0f) (dx / dist).toDouble() else 0.0
        // Screen y grows downward; north is up, so negate.
        val north = if (dist > 0f) (-dy / dist).toDouble() else 0.0
        listener?.onMove(north, east, magnitude)
    }

    private fun recenter() {
        knobX = cx
        knobY = cy
        invalidate()
    }

    private companion object {
        /** Knob glow radius as a multiple of the knob radius (must match the onDraw glow). */
        const val GLOW = 1.35f
    }
}
