package com.dopiz.gpsjoystick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * A single circular button in the radial control menu (hub or child).
 *
 * Glyphs are drawn in code (no emoji, no bitmap assets) so the whole menu shares one visual
 * language, tinted with the Material 3 dark tokens from colors.xml. The [HUB] is filled with the
 * brand primary; children sit on a translucent [surface] disc with an [outline] ring that turns
 * [statusActive] green when [active].
 */
class ChildButton(
    context: Context,
    var glyph: Glyph,
    private val label: String? = null,
) : View(context) {

    enum class Glyph { HUB, JOYSTICK, PLAY, PAUSE, SPEED, TEXT }

    /** Green ring highlight (e.g. joystick visible, current speed bucket). */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val surface = 0xF2131A22.toInt()      // surface #131A22, high alpha for legibility
    private val outline = 0xFF2A3542.toInt()       // outline #2A3542
    private val primary = 0xFF3B82F6.toInt()       // primary #3B82F6
    private val statusActive = 0xFF22C55E.toInt()  // status_active #22C55E
    private val onSurface = 0xFFE5EDF5.toInt()     // on_surface #E5EDF5
    private val white = 0xFFFFFFFF.toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val density = resources.displayMetrics.density
    private val path = Path()
    private val rect = RectF()

    init {
        isClickable = true
    }

    /** Press feedback: a quick scale dip so taps feel responsive. */
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().cancel()
        animate().scaleX(if (pressed) 0.9f else 1f)
            .scaleY(if (pressed) 0.9f else 1f)
            .setDuration(120).start()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val stroke = if (active) 2.4f * density else 1.6f * density
        val r = min(w, h) / 2f - stroke

        val isHub = glyph == Glyph.HUB
        fillPaint.color = if (isHub) primary else surface
        canvas.drawCircle(cx, cy, r, fillPaint)
        ringPaint.color = when {
            active -> statusActive
            isHub -> 0x66FFFFFF
            else -> outline
        }
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r, ringPaint)

        val gcol = if (isHub) white else if (active) statusActive else onSurface
        glyphStroke.color = gcol
        glyphFill.color = gcol
        textPaint.color = gcol
        drawGlyph(canvas, cx, cy, r)
    }

    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val u = r * 0.5f // glyph half-extent
        when (glyph) {
            Glyph.HUB -> {
                // Plus sign; the whole view rotates 45° to an "×" when the menu expands.
                glyphStroke.strokeWidth = r * 0.16f
                canvas.drawLine(cx - u, cy, cx + u, cy, glyphStroke)
                canvas.drawLine(cx, cy - u, cx, cy + u, glyphStroke)
            }
            Glyph.JOYSTICK -> {
                glyphStroke.strokeWidth = r * 0.12f
                canvas.drawCircle(cx, cy, u * 0.95f, glyphStroke)     // base ring
                val kx = cx + u * 0.34f
                val ky = cy - u * 0.34f
                canvas.drawLine(cx, cy, kx, ky, glyphStroke)          // stick
                canvas.drawCircle(kx, ky, u * 0.42f, glyphFill)       // knob
            }
            Glyph.PLAY -> {
                path.reset()
                path.moveTo(cx - u * 0.6f, cy - u)
                path.lineTo(cx + u, cy)
                path.lineTo(cx - u * 0.6f, cy + u)
                path.close()
                canvas.drawPath(path, glyphFill)
            }
            Glyph.PAUSE -> {
                val bw = u * 0.42f
                val gap = u * 0.34f
                rect.set(cx - gap - bw, cy - u, cx - gap, cy + u)
                canvas.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, glyphFill)
                rect.set(cx + gap, cy - u, cx + gap + bw, cy + u)
                canvas.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, glyphFill)
            }
            Glyph.SPEED -> {
                // Speedometer: 240° gauge arc + a needle pointing up-right.
                glyphStroke.strokeWidth = r * 0.12f
                rect.set(cx - u, cy - u, cx + u, cy + u)
                canvas.drawArc(rect, 150f, 240f, false, glyphStroke)
                canvas.drawLine(cx, cy, cx + u * 0.62f, cy - u * 0.62f, glyphStroke)
                canvas.drawCircle(cx, cy, u * 0.14f, glyphFill)
            }
            Glyph.TEXT -> {
                textPaint.textSize = r * 1.05f
                val fm = textPaint.fontMetrics
                canvas.drawText(label ?: "", cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
            }
        }
    }
}
