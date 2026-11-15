/*
 * ONA Controls - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.onalauncher.controls.input.InputDispatcher
import com.onalauncher.controls.model.OverlayKey
import kotlin.math.abs

/**
 * Mouse mode SWIPE: a small floating touchpad (its own window, so the game keeps every other touch).
 *  - top bar: drag it to move the panel
 *  - pad: drag = move the cursor, tap = left click, two-finger tap = right click,
 *         two-finger drag up / down = mouse wheel
 *  - L / R: hold = mouse button held (hold L and drag on the pad with another finger = drag / box select)
 */
@SuppressLint("ViewConstructor")
class TouchpadPanelView(
    context: Context,
    private val dispatcher: InputDispatcher,
    private val haptics: Boolean,
    private val hint: String,
    private val onMovePanel: (dx: Int, dy: Int) -> Unit,
    private val onMovePanelEnd: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val barH = 20f * d
    private val btnH = 36f * d

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 10, 12, 16) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.argb(220, 230, 230, 230)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 3, 136, 209) }
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 3, 136, 209) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(2f * d, 0f, 0f, Color.BLACK)
    }
    private val rect = RectF()

    private enum class Role { BAR, PAD, LEFT, RIGHT }

    private val roles = HashMap<Int, Role>()
    private var leftDown = false
    private var rightDown = false

    // pad state
    private var padPointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = 0f
    private var maxPadFingers = 0
    private var scrollAcc = 0f

    // bar drag
    private var barRawX = 0f
    private var barRawY = 0f

    private fun roleAt(x: Float, y: Float): Role = when {
        y < barH -> Role.BAR
        y > height - btnH -> if (x < width / 2f) Role.LEFT else Role.RIGHT
        else -> Role.PAD
    }

    private fun buzz() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun padFingers() = roles.values.count { it == Role.PAD }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val role = roleAt(e.getX(i), e.getY(i))
                roles[id] = role
                when (role) {
                    Role.BAR -> { barRawX = e.rawX; barRawY = e.rawY }
                    Role.LEFT -> if (!leftDown) { leftDown = true; buzz(); dispatcher.press(OverlayKey.MLB, true) }
                    Role.RIGHT -> if (!rightDown) { rightDown = true; buzz(); dispatcher.press(OverlayKey.MRB, true) }
                    Role.PAD -> {
                        if (padPointer == -1) {
                            padPointer = id
                            lastX = e.getX(i); lastY = e.getY(i)
                            downTime = SystemClock.uptimeMillis()
                            moved = 0f; scrollAcc = 0f; maxPadFingers = 0
                        }
                        maxPadFingers = maxOf(maxPadFingers, padFingers())
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    when (roles[id]) {
                        Role.BAR -> if (i == 0 || e.pointerCount == 1) {
                            val dx = e.rawX - barRawX
                            val dy = e.rawY - barRawY
                            barRawX = e.rawX; barRawY = e.rawY
                            onMovePanel(dx.toInt(), dy.toInt())
                        }
                        Role.PAD -> if (id == padPointer) {
                            val dx = e.getX(i) - lastX
                            val dy = e.getY(i) - lastY
                            lastX = e.getX(i); lastY = e.getY(i)
                            moved += abs(dx) + abs(dy)
                            if (padFingers() >= 2) {
                                // two fingers on the pad = wheel
                                scrollAcc += dy
                                val step = 22f * d
                                while (abs(scrollAcc) >= step) {
                                    dispatcher.wheel(if (scrollAcc > 0) -1 else 1)
                                    scrollAcc -= if (scrollAcc > 0) step else -step
                                }
                            } else {
                                dispatcher.mouseMove(dx * 1.8f, dy * 1.8f)
                            }
                        }
                        else -> Unit
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL) roles.keys.toList() else listOf(e.getPointerId(e.actionIndex))
                for (id in ids) {
                    when (roles.remove(id)) {
                        Role.BAR -> onMovePanelEnd()
                        Role.LEFT -> if (leftDown && roles.values.none { it == Role.LEFT }) { leftDown = false; dispatcher.press(OverlayKey.MLB, false) }
                        Role.RIGHT -> if (rightDown && roles.values.none { it == Role.RIGHT }) { rightDown = false; dispatcher.press(OverlayKey.MRB, false) }
                        Role.PAD -> if (padFingers() == 0) {
                            // all fingers off the pad: was it a tap?
                            val quick = SystemClock.uptimeMillis() - downTime < 250
                            if (e.actionMasked != MotionEvent.ACTION_CANCEL && quick && moved < 12f * d) {
                                val btn = if (maxPadFingers >= 2) OverlayKey.MRB else OverlayKey.MLB
                                buzz()
                                dispatcher.press(btn, true)
                                dispatcher.press(btn, false)
                            }
                            padPointer = -1
                        } else if (id == padPointer) {
                            // the moving finger left, another one stays: continue with it
                            padPointer = roles.entries.first { it.value == Role.PAD }.key
                            val idx = e.findPointerIndex(padPointer)
                            if (idx >= 0) { lastX = e.getX(idx); lastY = e.getY(idx) }
                        }
                        null -> Unit
                    }
                }
                invalidate()
            }
        }
        return true
    }

    /** Overlay hidden / panel removed: let go of held buttons. */
    fun releaseAll() {
        if (leftDown) { leftDown = false; dispatcher.press(OverlayKey.MLB, false) }
        if (rightDown) { rightDown = false; dispatcher.press(OverlayKey.MRB, false) }
        roles.clear()
        padPointer = -1
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 12f * d
        rect.set(d, d, w - d, h - d)
        c.drawRoundRect(rect, r, r, bg)
        // top bar (drag handle)
        rect.set(d, d, w - d, barH)
        c.drawRoundRect(rect, r, r, bar)
        text.textSize = 11f * d
        c.drawText("≡  MOUSE  ≡", w / 2f, barH / 2f + 4f * d, text)
        // pad hint
        text.textSize = 10f * d
        text.alpha = 170
        c.drawText(hint, w / 2f, barH + (h - barH - btnH) / 2f + 4f * d, text)
        text.alpha = 255
        // L / R buttons
        val top = h - btnH
        if (leftDown) c.drawRect(d * 2, top, w / 2f, h - d * 2, pressedFill)
        if (rightDown) c.drawRect(w / 2f, top, w - d * 2, h - d * 2, pressedFill)
        c.drawLine(d, top, w - d, top, border)
        c.drawLine(w / 2f, top, w / 2f, h - d, border)
        text.textSize = 14f * d
        c.drawText("L", w / 4f, top + btnH / 2f + 5f * d, text)
        c.drawText("R", w * 3f / 4f, top + btnH / 2f + 5f * d, text)
        rect.set(d, d, w - d, h - d)
        c.drawRoundRect(rect, r, r, border)
    }
}

/**
 * The mouse pointer drawn by ONA. Its window is NOT touchable and starts 2 px right / below the
 * real cursor spot, so injected mouse clicks land on the game, never on this window.
 */
class CursorView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()

    override fun onDraw(c: Canvas) {
        val s = d
        arrow.reset()
        arrow.moveTo(1f * s, 1f * s)
        arrow.lineTo(1f * s, 19f * s)
        arrow.lineTo(6f * s, 14.5f * s)
        arrow.lineTo(9.5f * s, 22f * s)
        arrow.lineTo(12.5f * s, 20.5f * s)
        arrow.lineTo(9f * s, 13.5f * s)
        arrow.lineTo(15f * s, 13.5f * s)
        arrow.close()
        c.drawPath(arrow, fill)
        c.drawPath(arrow, edge)
    }
}
