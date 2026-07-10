package com.dopiz.gpsjoystick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import kotlin.math.min

/**
 * A single circular button in the radial control menu (hub or child).
 *
 * Glyphs are drawn in code (no emoji, no bitmap assets) so the whole menu shares one visual
 * language, tinted with the Material 3 dark tokens from colors.xml. The [HUB] is filled with the
 * brand primary; children are SOLID OPAQUE [surfaceVariant] discs with a thin [outline] ring and a
 * drop shadow so they stay legible over any background. When [active] the whole disc fills
 * [statusActive] green (e.g. the current speed bucket).
 */
class ChildButton(
    context: Context,
    var glyph: Glyph,
    private val label: String? = null,
) : View(context) {

    enum class Glyph { HUB, JOYSTICK, PLAY, PAUSE, SPEED, TEXT, WALK, RUN, CAR, MAP, LOCK, LOCK_OPEN }

    /** Green ring highlight (e.g. joystick visible, current speed bucket). */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** For the HUB: collapsed shows the app icon, expanded shows the ✕ close glyph. */
    var hubExpanded: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** The launcher (adaptive) icon rendered inside the collapsed hub, clipped to the circle. */
    private val appIcon: Drawable? =
        if (glyph == Glyph.HUB) ContextCompat.getDrawable(context, R.mipmap.ic_launcher) else null

    private val surfaceVariant = 0xFF1B242E.toInt() // surface_variant #1B242E, fully opaque
    private val outline = 0xFF2A3542.toInt()       // outline #2A3542
    private val outlineActive = 0xFF16A34A.toInt()  // darker green rim for the active disc
    private val primary = 0xFF3B82F6.toInt()       // primary #3B82F6
    private val statusActive = 0xFF22C55E.toInt()  // status_active #22C55E
    private val onSurface = 0xFFE5EDF5.toInt()     // on_surface #E5EDF5
    private val white = 0xFFFFFFFF.toInt()
    private val shadowColor = 0xB3000000.toInt()   // ~70% black drop shadow

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

    // Material-style single-path icons (24dp viewport), parsed once per glyph and cached so a
    // button can swap between LOCK/LOCK_OPEN without re-parsing on every draw.
    private val iconCache = HashMap<Glyph, Path?>()
    private fun iconFor(g: Glyph): Path? = iconCache.getOrPut(g) {
        when (g) {
            Glyph.WALK -> PathParser.createPathFromPathData(WALK_PATH)
            Glyph.RUN -> PathParser.createPathFromPathData(RUN_PATH)
            Glyph.CAR -> PathParser.createPathFromPathData(CAR_PATH)
            Glyph.MAP -> PathParser.createPathFromPathData(MAP_PATH)
            Glyph.LOCK -> PathParser.createPathFromPathData(LOCK_PATH)
            Glyph.LOCK_OPEN -> PathParser.createPathFromPathData(LOCK_OPEN_PATH)
            else -> null
        }
    }

    init {
        isClickable = true
        // Software layer so the disc drop shadow renders reliably across API levels.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        val stroke = 1.6f * density
        val shadowR = 5f * density
        // Leave room inside the view bounds for both the ring and the drop shadow.
        val r = min(w, h) / 2f - stroke - shadowR

        val isHub = glyph == Glyph.HUB
        val collapsedHub = isHub && !hubExpanded
        // Solid opaque disc: primary for the hub, green when active, surface-variant otherwise.
        fillPaint.color = when {
            isHub -> primary
            active -> statusActive
            else -> surfaceVariant
        }
        fillPaint.setShadowLayer(shadowR, 0f, 2f * density, shadowColor)
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.clearShadowLayer()

        // Collapsed hub: render the app launcher icon, clipped to the circle so it reads as a
        // round app icon filling the disc. Oversize slightly so the foreground glyph reaches the rim.
        if (collapsedHub && appIcon != null) {
            val save = canvas.save()
            path.reset()
            path.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.clipPath(path)
            val half = r * 1.55f
            appIcon.setBounds((cx - half).toInt(), (cy - half).toInt(),
                (cx + half).toInt(), (cy + half).toInt())
            appIcon.draw(canvas)
            canvas.restoreToCount(save)
        }

        ringPaint.color = when {
            isHub -> 0x66FFFFFF
            active -> outlineActive
            else -> outline
        }
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r, ringPaint)

        if (!collapsedHub) {
            // Glyph/text: white on the coloured (hub / active) discs, on-surface on plain discs.
            val gcol = if (isHub || active) white else onSurface
            glyphStroke.color = gcol
            glyphFill.color = gcol
            textPaint.color = gcol
            drawGlyph(canvas, cx, cy, r)
        }
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
            Glyph.WALK, Glyph.RUN, Glyph.CAR, Glyph.MAP, Glyph.LOCK, Glyph.LOCK_OPEN ->
                drawIcon(canvas, cx, cy, r)
            Glyph.TEXT -> {
                textPaint.textSize = r * 1.05f
                val fm = textPaint.fontMetrics
                canvas.drawText(label ?: "", cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
            }
        }
    }

    /** Draw a 24dp-viewport [iconPath] as a solid fill, scaled to fit and centred in the disc. */
    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val icon = iconFor(glyph) ?: return
        val box = r * 1.5f            // icon bounding box side within the disc
        val scale = box / 24f
        canvas.save()
        canvas.translate(cx - box / 2f, cy - box / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(icon, glyphFill)
        canvas.restore()
    }

    private companion object {
        // Material Symbols icon geometry (Apache-2.0), single filled path, 24x24 viewport.
        const val WALK_PATH =
            "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9L7 23h2.1l1.8-8 2.1 " +
            "2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1" +
            "-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7"
        const val RUN_PATH =
            "M13.49 5.48c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm-3.6 13.9l1-4.4 2.1 2v6h2v-7." +
            "5l-2.1-2 .6-3c1.3 1.5 3.3 2.5 5.5 2.5v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-" +
            "1-.3 0-.5.1-.8.1l-5.2 2.2v4.7h2v-3.4l1.8-.7-1.6 8.1-4.9-1-.4 2 7 1.4z"
        const val CAR_PATH =
            "M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 " +
            "1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c" +
            "-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-" +
            ".67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"
        const val MAP_PATH =
            "M20.5 3l-.16.03L15 5.1 9 3 3.36 4.9c-.21.07-.36.25-.36.48V20.5c0 .28.22.5.5.5l.16-.03" +
            "L9 18.9l6 2.1 5.64-1.9c.21-.07.36-.25.36-.48V3.5c0-.28-.22-.5-.5-.5zM15 19l-6-2.11V5l6 " +
            "2.11V19z"
        const val LOCK_PATH =
            "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 " +
            "2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6" +
            "c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"
        const val LOCK_OPEN_PATH =
            "M12 17c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm6-9h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 " +
            "6h1.9c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 " +
            "2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2z"
    }
}
