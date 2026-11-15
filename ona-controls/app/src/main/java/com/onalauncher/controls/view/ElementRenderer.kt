/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.onalauncher.controls.model.ButtonShape
import com.onalauncher.controls.model.ElementType
import com.onalauncher.controls.model.OverlayElement
import com.onalauncher.controls.model.OverlayKey
import kotlin.math.min

/** Live state a control draws (which parts are pressed, where the stick thumb is). */
class ElementState {
    var pressed = BooleanArray(8)
    var stickX = 0f
    var stickY = 0f

    fun ensure(n: Int) {
        if (pressed.size < n) pressed = pressed.copyOf(n)
    }
}

/** Sizes in px for an element (base dp per type * scale). Shared by the play windows and the editor. */
object ElementGeometry {
    fun sizeDp(el: OverlayElement): Pair<Float, Float> {
        val (w, h) = when (el.type) {
            ElementType.BUTTON -> when (el.shape) {
                ButtonShape.CIRCLE -> 56f to 56f
                ButtonShape.SQUARE -> 52f to 52f
                ButtonShape.RECT -> 72f to 38f
                ButtonShape.PILL -> 66f to 32f
            }
            ElementType.BUTTON_ROW -> 40f * el.bindings.size.coerceAtLeast(1) to 40f
            ElementType.DPAD -> 136f to 136f
            ElementType.STICK -> 130f to 130f
            ElementType.TRACKPAD -> 220f to 140f
        }
        // scale = overall size, width / height = stretch each side on its own
        return w * el.scale * el.width.coerceIn(0.3f, 4f) to h * el.scale * el.height.coerceIn(0.3f, 4f)
    }

    /** Profiles are laid out for a 915 x 412 dp landscape phone; smaller screens shrink every control evenly. */
    private const val REF_W_DP = 915f
    private const val REF_H_DP = 412f

    fun fit(screenW: Int, screenH: Int, density: Float): Float {
        if (screenW <= 0 || screenH <= 0) return 1f
        return minOf(1f, screenW / density / REF_W_DP, screenH / density / REF_H_DP)
    }

    fun sizePx(el: OverlayElement, density: Float, screenW: Int, screenH: Int): Pair<Int, Int> {
        val (w, h) = sizeDp(el)
        val k = density * fit(screenW, screenH, density)
        return (w * k).toInt().coerceAtLeast(8) to (h * k).toInt().coerceAtLeast(8)
    }
}

/**
 * Readable on any game picture: a dark see-through fill behind every control, an outline in the
 * control's colour, white bold text with a dark shadow. Overlay opacity fades outline and fill; the
 * text stays readable (never below 80 %). Pressed parts light up in the control's colour.
 * Draws into any rect so the same code paints play windows and the full-screen editor.
 */
class ElementRenderer(private val density: Float) {
    companion object {
        const val DEFAULT_COLOR = 0xFFE6E6E6.toInt()

        /** Colours offered in the editor (0 = default light grey). */
        val PALETTE = intArrayOf(
            0, 0xFFF44336.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(),
            0xFF00BCD4.toInt(), 0xFF2196F3.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(),
            0xFF795548.toInt(), 0xFF9E9E9E.toInt(), 0xFF000000.toInt()
        )
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(2.5f * density, 0f, 0f, Color.argb(230, 0, 0, 0))
    }
    private val select = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.rgb(33, 150, 243)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }
    private val path = Path()
    private val r = RectF()

    fun draw(c: Canvas, el: OverlayElement, bounds: RectF, state: ElementState?, opacity: Float, selected: Boolean = false) {
        val o = opacity.coerceIn(0.05f, 1f)
        val base = if (el.color == 0) DEFAULT_COLOR else el.color
        val a = (o * 255).toInt()
        stroke.color = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
        // dark backing so the outline and the text stand out over bright game pictures
        back.color = Color.argb((o * 0.55f * 255).toInt().coerceIn(40, 170), 0, 0, 0)
        fill.color = Color.argb((0.30f + o * 0.45f).coerceAtMost(0.8f).times(255).toInt(), Color.red(base), Color.green(base), Color.blue(base))
        text.color = Color.argb(((o * 1.6f).coerceIn(0.8f, 1f) * 255).toInt(), 255, 255, 255)
        val inset = stroke.strokeWidth
        r.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)

        when (el.type) {
            ElementType.BUTTON -> drawButton(c, el, state?.pressed?.getOrNull(0) == true)
            ElementType.BUTTON_ROW -> drawRow(c, el, state)
            ElementType.DPAD -> drawDpad(c, state)
            ElementType.STICK -> drawStick(c, state)
            ElementType.TRACKPAD -> {
                val rad = 12f * density
                c.drawRoundRect(r, rad, rad, back)
                c.drawRoundRect(r, rad, rad, stroke)
                label(c, el.text.ifBlank { "TOUCHPAD" }, r.centerX(), r.centerY(), r.width() * 0.8f, r.height() * 0.2f)
            }
        }
        if (selected) c.drawRect(bounds, select)
    }

    private fun shapeRect(c: Canvas, shape: ButtonShape, rect: RectF, pressed: Boolean) {
        if (shape == ButtonShape.CIRCLE) {
            // a circle stretched by width / height becomes an oval
            c.drawOval(rect, back)
            if (pressed) c.drawOval(rect, fill)
            c.drawOval(rect, stroke)
            return
        }
        val radius = when (shape) {
            ButtonShape.PILL -> min(rect.width(), rect.height()) / 2f
            ButtonShape.SQUARE -> 10f * density
            else -> 3f * density
        }
        c.drawRoundRect(rect, radius, radius, back)
        if (pressed) c.drawRoundRect(rect, radius, radius, fill)
        c.drawRoundRect(rect, radius, radius, stroke)
    }

    private fun drawButton(c: Canvas, el: OverlayElement, pressed: Boolean) {
        shapeRect(c, el.shape, r, pressed)
        val t = el.text.ifBlank {
            el.bindings.filter { it != OverlayKey.NONE }.joinToString("+") { it.label }.ifBlank { "NONE" }
        }
        val lines = t.split('\n')
        if (lines.size == 1) {
            label(c, t, r.centerX(), r.centerY(), r.width() * 0.86f, min(r.height() * 0.40f, 20f * density))
        } else {
            // several lines: the first one bigger (the key), the rest smaller (what it does)
            val usable = r.height() * 0.80f
            val first = usable * 0.55f
            val rest = (usable - first) / (lines.size - 1)
            var y = r.centerY() - usable / 2f
            lines.forEachIndexed { i, line ->
                val h = if (i == 0) first else rest
                label(c, line, r.centerX(), y + h / 2f, r.width() * 0.88f, h * 0.76f)
                y += h
            }
        }
    }

    private fun drawRow(c: Canvas, el: OverlayElement, state: ElementState?) {
        val n = el.bindings.size.coerceAtLeast(1)
        val cell = r.width() / n
        val rad = 8f * density
        val tmp = RectF()
        c.drawRoundRect(r, rad, rad, back)
        for (i in 0 until n) {
            if (state?.pressed?.getOrNull(i) == true) {
                tmp.set(r.left + i * cell, r.top, r.left + (i + 1) * cell, r.bottom)
                c.drawRect(tmp, fill)
            }
            if (i > 0) c.drawLine(r.left + i * cell, r.top, r.left + i * cell, r.bottom, stroke)
            val k = el.bindings.getOrNull(i) ?: OverlayKey.NONE
            label(c, k.label, r.left + (i + 0.5f) * cell, r.centerY(), cell * 0.84f, r.height() * 0.46f)
        }
        c.drawRoundRect(r, rad, rad, stroke)
    }

    /** Winlator-style D-pad: four pentagon arrows around the centre. Order: up, right, down, left. */
    private fun drawDpad(c: Canvas, state: ElementState?) {
        val cx = r.centerX()
        val cy = r.centerY()
        val u = min(r.width(), r.height()) / 2f
        val pts = floatArrayOf(-0.28f, -1f, 0.28f, -1f, 0.28f, -0.42f, 0f, -0.14f, -0.28f, -0.42f)
        for (dir in 0 until 4) {
            path.reset()
            for (i in 0 until 5) {
                val px = pts[i * 2]
                val py = pts[i * 2 + 1]
                // rotate by dir * 90 degrees clockwise
                val (rx, ry) = when (dir) {
                    0 -> px to py
                    1 -> -py to px
                    2 -> -px to -py
                    else -> py to -px
                }
                if (i == 0) path.moveTo(cx + rx * u, cy + ry * u) else path.lineTo(cx + rx * u, cy + ry * u)
            }
            path.close()
            c.drawPath(path, back)
            if (state?.pressed?.getOrNull(dir) == true) c.drawPath(path, fill)
            c.drawPath(path, stroke)
        }
    }

    private fun drawStick(c: Canvas, state: ElementState?) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = min(r.width(), r.height()) / 2f
        c.drawCircle(cx, cy, rad, back)
        c.drawCircle(cx, cy, rad, stroke)
        val travel = rad * 0.6f
        val tx = cx + (state?.stickX ?: 0f) * travel
        val ty = cy + (state?.stickY ?: 0f) * travel
        val thumb = rad * 0.4f
        c.drawCircle(tx, ty, thumb, fill)
        c.drawCircle(tx, ty, thumb, stroke)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, maxW: Float, size: Float) {
        text.textSize = size
        val w = text.measureText(s)
        if (w > maxW && w > 0f) text.textSize = size * maxW / w
        val fm = text.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, text)
    }
}
