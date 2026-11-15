/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.movtery.zalithlauncher.overlay.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.movtery.zalithlauncher.overlay.model.ButtonShape
import com.movtery.zalithlauncher.overlay.model.ElementType
import com.movtery.zalithlauncher.overlay.model.OverlayElement
import com.movtery.zalithlauncher.overlay.model.OverlayKey
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
        return w * el.scale to h * el.scale
    }

    fun sizePx(el: OverlayElement, density: Float): Pair<Int, Int> {
        val (w, h) = sizeDp(el)
        return (w * density).toInt().coerceAtLeast(8) to (h * density).toInt().coerceAtLeast(8)
    }
}

/**
 * Winlator-like look: thin light outlines, labels in the middle, pressed parts filled.
 * Draws into any rect so the same code paints play windows and the full-screen editor.
 */
class ElementRenderer(private val density: Float) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
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
        val a = (opacity.coerceIn(0.05f, 1f) * 255).toInt()
        stroke.color = Color.argb(a, 230, 230, 230)
        text.color = Color.argb(a, 235, 235, 235)
        fill.color = Color.argb((a * 0.55f).toInt(), 200, 200, 200)
        val inset = stroke.strokeWidth
        r.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)

        when (el.type) {
            ElementType.BUTTON -> drawButton(c, el, state?.pressed?.getOrNull(0) == true)
            ElementType.BUTTON_ROW -> drawRow(c, el, state)
            ElementType.DPAD -> drawDpad(c, state)
            ElementType.STICK -> drawStick(c, state)
            ElementType.TRACKPAD -> {
                val rad = 12f * density
                c.drawRoundRect(r, rad, rad, stroke)
                label(c, "TOUCHPAD", r.centerX(), r.centerY(), r.width(), r.height() * 0.22f)
            }
        }
        if (selected) c.drawRect(bounds, select)
    }

    private fun shapeRect(c: Canvas, shape: ButtonShape, rect: RectF, pressed: Boolean) {
        val radius = when (shape) {
            ButtonShape.CIRCLE -> min(rect.width(), rect.height()) / 2f
            ButtonShape.PILL -> rect.height() / 2f
            ButtonShape.SQUARE -> 10f * density
            ButtonShape.RECT -> 3f * density
        }
        if (shape == ButtonShape.CIRCLE) {
            val rr = min(rect.width(), rect.height()) / 2f
            if (pressed) c.drawCircle(rect.centerX(), rect.centerY(), rr, fill)
            c.drawCircle(rect.centerX(), rect.centerY(), rr, stroke)
        } else {
            if (pressed) c.drawRoundRect(rect, radius, radius, fill)
            c.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    private fun drawButton(c: Canvas, el: OverlayElement, pressed: Boolean) {
        shapeRect(c, el.shape, r, pressed)
        val t = el.text.ifBlank {
            el.bindings.filter { it != OverlayKey.NONE }.joinToString("+") { it.label }.ifBlank { "NONE" }
        }
        label(c, t, r.centerX(), r.centerY(), r.width() * 0.86f, r.height() * 0.36f)
    }

    private fun drawRow(c: Canvas, el: OverlayElement, state: ElementState?) {
        val n = el.bindings.size.coerceAtLeast(1)
        val cell = r.width() / n
        val rad = 8f * density
        val tmp = RectF()
        for (i in 0 until n) {
            if (state?.pressed?.getOrNull(i) == true) {
                tmp.set(r.left + i * cell, r.top, r.left + (i + 1) * cell, r.bottom)
                c.drawRect(tmp, fill)
            }
            if (i > 0) c.drawLine(r.left + i * cell, r.top, r.left + i * cell, r.bottom, stroke)
            val k = el.bindings.getOrNull(i) ?: OverlayKey.NONE
            label(c, k.label, r.left + (i + 0.5f) * cell, r.centerY(), cell * 0.84f, r.height() * 0.42f)
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
            if (state?.pressed?.getOrNull(dir) == true) c.drawPath(path, fill)
            c.drawPath(path, stroke)
        }
    }

    private fun drawStick(c: Canvas, state: ElementState?) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = min(r.width(), r.height()) / 2f
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
