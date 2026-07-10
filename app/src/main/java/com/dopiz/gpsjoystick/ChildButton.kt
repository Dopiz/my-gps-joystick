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

    enum class Glyph { HUB, JOYSTICK, PLAY, PAUSE, SPEED, TEXT, WALK, RUN, CAR, MAP, MAP_OPEN, LOCK, LOCK_OPEN }

    /** Green ring highlight (e.g. joystick visible, current speed bucket). */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Explicit disc fill (e.g. the GPX toggle: blue when playing, amber when paused). When non-null
     * it overrides the plain surface / active-green fill and the glyph is drawn white on top.
     */
    var fillOverride: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Disabled look: the whole disc + glyph render dimmed (e.g. GPX toggle with no route loaded). */
    var dimmed: Boolean = false
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
    private val amber = 0xFFF59E0B.toInt()          // GPX playback paused #F59E0B
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
        val data = when (g) {
            Glyph.JOYSTICK -> JOYSTICK_PATH
            Glyph.PLAY -> PLAY_PATH
            Glyph.PAUSE -> PAUSE_PATH
            Glyph.SPEED -> SPEED_PATH
            Glyph.WALK -> WALK_PATH
            Glyph.RUN -> RUN_PATH
            Glyph.CAR -> CAR_PATH
            Glyph.MAP -> MAP_PATH
            Glyph.MAP_OPEN -> MAP_OPEN_PATH
            Glyph.LOCK -> LOCK_PATH
            Glyph.LOCK_OPEN -> LOCK_OPEN_PATH
            else -> null
        }
        data?.let { PathParser.createPathFromPathData(it) }
    }

    /** GPX-toggle visual states, driven live from the playback StateFlow. */
    enum class GpxVisual { DISABLED, PLAYING, PAUSED }

    /** Apply the GPX toggle look: dim/disabled with ▶, blue with �‖, or amber with ▶. */
    fun applyGpx(state: GpxVisual) {
        when (state) {
            GpxVisual.DISABLED -> { glyph = Glyph.PLAY; fillOverride = null; dimmed = true; isEnabled = false }
            GpxVisual.PLAYING -> { glyph = Glyph.PAUSE; fillOverride = primary; dimmed = false; isEnabled = true }
            GpxVisual.PAUSED -> { glyph = Glyph.PLAY; fillOverride = amber; dimmed = false; isEnabled = true }
        }
        invalidate()
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
        val dimA = if (dimmed) 90 else 255
        // Solid opaque disc: primary for the hub, override (GPX blue/amber), green when active,
        // surface-variant otherwise.
        fillPaint.color = when {
            isHub -> primary
            fillOverride != null -> fillOverride!!
            active -> statusActive
            else -> surfaceVariant
        }
        fillPaint.alpha = dimA
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
        if (dimmed) ringPaint.alpha = dimA          // else keep the alpha carried by the ring colour
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r, ringPaint)

        if (!collapsedHub) {
            // Glyph/text: white on the coloured (hub / override / active) discs, on-surface on plain.
            val gcol = if (isHub || active || fillOverride != null) white else onSurface
            glyphStroke.color = gcol
            glyphFill.color = gcol
            textPaint.color = gcol
            glyphStroke.alpha = dimA
            glyphFill.alpha = dimA
            textPaint.alpha = dimA
            drawGlyph(canvas, cx, cy, r)
        }
    }

    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        when (glyph) {
            Glyph.HUB -> {
                // Plus sign; the whole view rotates 45° to an "×" when the menu expands.
                val u = r * 0.5f
                glyphStroke.strokeWidth = r * 0.16f
                canvas.drawLine(cx - u, cy, cx + u, cy, glyphStroke)
                canvas.drawLine(cx, cy - u, cx, cy + u, glyphStroke)
            }
            Glyph.TEXT -> {
                textPaint.textSize = r * 1.05f
                val fm = textPaint.fontMetrics
                canvas.drawText(label ?: "", cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
            }
            // One coherent Material icon family: every other glyph is a single 24dp filled path,
            // measured by its own bounds and centred in the disc (see [drawIcon]).
            else -> drawIcon(canvas, cx, cy, r)
        }
    }

    /**
     * Draw a 24dp-viewport [iconFor] path as a solid fill, scaled by its ACTUAL path bounds (not the
     * nominal viewport) to a consistent fraction of the disc and centred exactly on ([cx],[cy]).
     * Centring by real bounds fixes Material paths that don't sit dead-centre in their 24x24 box.
     */
    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val icon = iconFor(glyph) ?: return
        @Suppress("DEPRECATION")
        icon.computeBounds(rect, true)
        val extent = maxOf(rect.width(), rect.height())
        if (extent <= 0f) return
        val scale = (r * 2f * ICON_FRAC) / extent   // longest icon side spans ICON_FRAC of the disc
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.translate(-rect.centerX(), -rect.centerY())
        canvas.drawPath(icon, glyphFill)
        canvas.restore()
    }

    private companion object {
        // Longest side of every path glyph spans this fraction of the disc diameter (kept in the
        // 55-60% band so the icon family reads consistently with breathing room to the rim).
        const val ICON_FRAC = 0.58f

        // Material Symbols icon geometry (Apache-2.0), single filled path, 24x24 viewport.
        // sports_esports (game controller) — a clean, instantly-legible "joystick" glyph.
        const val JOYSTICK_PATH =
            "M21.58 16.09l-1.09-7.66C20.21 6.46 18.52 5 16.53 5H7.47C5.48 5 3.79 6.46 3.51 8.43l-1" +
            ".09 7.66C2.2 17.63 3.39 19 4.94 19c.68 0 1.32-.27 1.8-.75L9 15h6l2.25 3.25c.48.48 1.13" +
            ".75 1.8.75 1.56 0 2.75-1.37 2.53-2.91zM11 11H9v2H8v-2H6v-1h2V8h1v2h2v1zm4-1c-.55 0-1-" +
            ".45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm2 3c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-" +
            "1 1z"
        const val PLAY_PATH = "M8 5v14l11-7z"
        const val PAUSE_PATH = "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
        const val SPEED_PATH =
            "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0" +
            " 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 " +
            "2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z"
        const val MAP_OPEN_PATH =
            "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3" +
            ".59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"
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
