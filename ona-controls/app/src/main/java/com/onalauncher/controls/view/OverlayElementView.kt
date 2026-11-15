/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.onalauncher.controls.input.InputDispatcher
import com.onalauncher.controls.input.TouchAction
import com.onalauncher.controls.model.ElementType
import com.onalauncher.controls.model.GamepadAxis
import com.onalauncher.controls.model.OverlayElement
import com.onalauncher.controls.model.OverlayKey
import com.onalauncher.controls.model.OverlayProfile
import com.onalauncher.controls.model.StickOutput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One control in play mode. Each control lives in its OWN small overlay window, so every touch
 * outside the controls reaches the app underneath natively (no pass-through tricks, no Android 12
 * "untrusted touch" blocking). Touches that start on a control stay with it even when the finger
 * leaves the window (that is how a stick can be dragged past its edge).
 */
@SuppressLint("ViewConstructor")
class OverlayElementView(
    context: Context,
    val element: OverlayElement,
    private val profile: () -> OverlayProfile,
    private val dispatcher: InputDispatcher,
    private val haptics: Boolean
) : View(context) {

    private val renderer = ElementRenderer(resources.displayMetrics.density)
    private val state = ElementState().apply { ensure(element.bindings.size + 4) }
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        renderer.draw(canvas, element, bounds, state, profile().opacity)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (element.type) {
            ElementType.BUTTON -> onButton(e)
            ElementType.BUTTON_ROW -> onRow(e)
            ElementType.DPAD -> onDpad(e)
            ElementType.STICK -> onStick(e)
            ElementType.TRACKPAD -> onTrackpad(e)
        }
        return true
    }

    /** Called when the overlay hides: let go of whatever this control holds. */
    fun releaseAll() {
        when (element.type) {
            ElementType.BUTTON -> if (state.pressed[0]) setButton(false)
            ElementType.BUTTON_ROW -> rowPointers.keys.toList().forEach { releaseRowPointer(it) }
            ElementType.DPAD -> applyDirs(BooleanArray(4))
            ElementType.STICK -> stickRelease(immediate = true)
            ElementType.TRACKPAD -> Unit
        }
        invalidate()
    }

    private fun buzz() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ---------------------------------------------------------------- button

    private var buttonPointer = 0

    private fun setButton(down: Boolean) {
        if (state.pressed[0] == down) return
        state.pressed[0] = down
        if (down) { buzz(); buttonPointer = dispatcher.newPointerId() }
        element.bindings.forEach { dispatcher.press(it, down, element, buttonPointer) }
        invalidate()
    }

    private fun onButton(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (element.toggle) setButton(!state.pressed[0]) else setButton(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!element.toggle) setButton(false)
        }
    }

    // ---------------------------------------------------------------- row (Winlator "1 2 3 4 5 6")

    private val rowPointers = HashMap<Int, Int>() // pointerId -> cell

    private fun cellAt(x: Float): Int {
        val n = element.bindings.size.coerceAtLeast(1)
        return floor(x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)
    }

    private fun pressCell(cell: Int, down: Boolean) {
        val k = element.bindings.getOrNull(cell) ?: return
        state.pressed[cell] = down || rowPointers.values.count { it == cell } > 0
        if (down) buzz()
        dispatcher.press(k, down, element)
    }

    private fun releaseRowPointer(id: Int) {
        val cell = rowPointers.remove(id) ?: return
        pressCell(cell, false)
    }

    private fun onRow(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val cell = cellAt(e.getX(i))
                rowPointers[e.getPointerId(i)] = cell
                pressCell(cell, true)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                val old = rowPointers[id] ?: continue
                val cell = cellAt(e.getX(i))
                if (cell != old) { // slide across cells like a keyboard row
                    rowPointers[id] = cell
                    pressCell(old, false)
                    pressCell(cell, true)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> releaseRowPointer(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> rowPointers.keys.toList().forEach { releaseRowPointer(it) }
        }
        invalidate()
    }

    // ---------------------------------------------------------------- d-pad (4 / 8 way)

    private fun dirsFor(x: Float, y: Float): BooleanArray {
        val out = BooleanArray(4)
        val cx = width / 2f
        val cy = height / 2f
        val dx = (x - cx) / cx
        val dy = (y - cy) / cy
        if (hypot(dx, dy) < 0.2f) return out // centre = nothing
        if (element.eightWay) {
            // 8 sectors of 45 degrees, diagonals press two arrows
            val sector = ((atan2(dy, dx) * 180 / PI + 360 + 22.5) % 360 / 45).toInt() // 0 = right, clockwise
            when (sector) {
                0 -> out[1] = true
                1 -> { out[1] = true; out[2] = true }
                2 -> out[2] = true
                3 -> { out[2] = true; out[3] = true }
                4 -> out[3] = true
                5 -> { out[3] = true; out[0] = true }
                6 -> out[0] = true
                else -> { out[0] = true; out[1] = true }
            }
        } else if (abs(dx) > abs(dy)) {
            if (dx > 0) out[1] = true else out[3] = true
        } else {
            if (dy > 0) out[2] = true else out[0] = true
        }
        return out
    }

    private fun applyDirs(dirs: BooleanArray) {
        for (d in 0 until 4) {
            if (state.pressed[d] != dirs[d]) {
                state.pressed[d] = dirs[d]
                if (dirs[d]) buzz()
                dispatcher.press(element.bindings.getOrNull(d) ?: OverlayKey.NONE, dirs[d], element)
            }
        }
    }

    private fun onDpad(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> applyDirs(dirsFor(e.x, e.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> applyDirs(BooleanArray(4))
        }
        invalidate()
    }

    // ---------------------------------------------------------------- analog stick

    /** Output after the deadzone, -1..1 per axis (screen y down). */
    private var outX = 0f
    private var outY = 0f
    private var stickHeld = false
    private var velX = 0f
    private var velY = 0f
    private var lastFrameNs = 0L
    private var touchPointer = 0
    private var touchActive = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val stickDirs = BooleanArray(4)

    /** Spring + mouse-look loop; runs only while the stick is held or still springing back. */
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrameNs = now
            var again = false
            if (stickHeld) {
                if (element.stickOutput == StickOutput.MOUSE && (outX != 0f || outY != 0f)) {
                    // camera look: speed grows with the square of the tilt for fine aim near the centre
                    val m = sqrt(outX * outX + outY * outY)
                    val base = 330f * resources.displayMetrics.density // px per second at full tilt
                    dispatcher.mouseMove(outX * m * base * dt, outY * m * base * dt)
                }
                again = true
            } else {
                // critically damped spring back to the centre: x'' = -w^2 x - 2 w x'
                val w = 28f
                velX += (-w * w * state.stickX - 2 * w * velX) * dt
                velY += (-w * w * state.stickY - 2 * w * velY) * dt
                state.stickX += velX * dt
                state.stickY += velY * dt
                if (abs(state.stickX) < 0.003f && abs(state.stickY) < 0.003f && abs(velX) < 0.02f && abs(velY) < 0.02f) {
                    state.stickX = 0f; state.stickY = 0f; velX = 0f; velY = 0f
                } else {
                    again = true
                }
                // gamepad axes follow the spring so the game sees a smooth return
                if (isGamepadStick()) emitAxes(state.stickX, state.stickY)
                invalidate()
            }
            if (again) Choreographer.getInstance().postFrameCallback(this) else lastFrameNs = 0L
        }
    }

    private fun isGamepadStick() =
        element.stickOutput == StickOutput.GAMEPAD_LEFT || element.stickOutput == StickOutput.GAMEPAD_RIGHT

    private fun emitAxes(x: Float, y: Float) {
        val dz = element.deadzone.coerceIn(0f, 0.9f)
        val m = hypot(x, y)
        val (ax, ay) = if (m <= dz) 0f to 0f else {
            val k = ((m - dz) / (1f - dz)).coerceAtMost(1f) / m
            x * k to y * k
        }
        if (element.stickOutput == StickOutput.GAMEPAD_LEFT) {
            dispatcher.axis(GamepadAxis.LX, ax); dispatcher.axis(GamepadAxis.LY, ay)
        } else {
            dispatcher.axis(GamepadAxis.RX, ax); dispatcher.axis(GamepadAxis.RY, ay)
        }
    }

    private fun startLoop() {
        Choreographer.getInstance().removeFrameCallback(frame)
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun onStick(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stickHeld = true
                velX = 0f; velY = 0f
                stickMove(e.x, e.y)
                startLoop()
            }
            MotionEvent.ACTION_MOVE -> stickMove(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stickRelease(immediate = false)
        }
    }

    /** Polar maths: angle + clamped radius, deadzone rescaled so output starts at 0 right after it. */
    private fun stickMove(x: Float, y: Float) {
        val radius = min(width, height) / 2f * 0.6f
        val dx = x - width / 2f
        val dy = y - height / 2f
        val dist = hypot(dx, dy)
        val angle = atan2(dy, dx)
        val r = min(dist / radius, 1f)
        state.stickX = cos(angle) * r
        state.stickY = sin(angle) * r

        val dz = element.deadzone.coerceIn(0f, 0.9f)
        val m = if (r <= dz) 0f else (r - dz) / (1f - dz)
        outX = cos(angle) * m
        outY = sin(angle) * m

        when (element.stickOutput) {
            StickOutput.KEYS -> {
                val dirs = BooleanArray(4)
                if (m > 0f) {
                    val sector = ((angle * 180 / PI + 360 + 22.5) % 360 / 45).toInt()
                    when (sector) {
                        0 -> dirs[1] = true
                        1 -> { dirs[1] = true; dirs[2] = true }
                        2 -> dirs[2] = true
                        3 -> { dirs[2] = true; dirs[3] = true }
                        4 -> dirs[3] = true
                        5 -> { dirs[3] = true; dirs[0] = true }
                        6 -> dirs[0] = true
                        else -> { dirs[0] = true; dirs[1] = true }
                    }
                }
                applyStickDirs(dirs)
            }
            StickOutput.GAMEPAD_LEFT, StickOutput.GAMEPAD_RIGHT -> emitAxes(state.stickX, state.stickY)
            StickOutput.MOUSE -> Unit // handled every frame
            StickOutput.TOUCH_DRAG -> {
                val (sw, sh) = screenSize()
                val reach = element.dragRadius * sh
                val px = element.tapX * sw + outX * reach
                val py = element.tapY * sh + outY * reach
                if (!touchActive && m > 0f) {
                    touchActive = true
                    touchPointer = dispatcher.newPointerId()
                    lastTouchX = element.tapX; lastTouchY = element.tapY
                    dispatcher.touch(touchPointer, element.tapX, element.tapY, TouchAction.DOWN)
                }
                if (touchActive) {
                    lastTouchX = px / sw; lastTouchY = py / sh
                    dispatcher.touch(touchPointer, lastTouchX, lastTouchY, TouchAction.MOVE)
                }
            }
        }
        invalidate()
    }

    private fun screenSize(): Pair<Float, Float> {
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    private fun applyStickDirs(dirs: BooleanArray) {
        for (d in 0 until 4) {
            if (stickDirs[d] != dirs[d]) {
                stickDirs[d] = dirs[d]
                dispatcher.press(element.bindings.getOrNull(d) ?: OverlayKey.NONE, dirs[d], element)
            }
        }
    }

    private fun stickRelease(immediate: Boolean) {
        stickHeld = false
        outX = 0f; outY = 0f
        applyStickDirs(BooleanArray(4)) // digital keys let go at once
        if (touchActive) {
            touchActive = false
            dispatcher.touch(touchPointer, lastTouchX, lastTouchY, TouchAction.UP)
        }
        if (immediate) {
            Choreographer.getInstance().removeFrameCallback(frame)
            state.stickX = 0f; state.stickY = 0f; velX = 0f; velY = 0f
            if (isGamepadStick()) emitAxes(0f, 0f)
        } else {
            startLoop() // spring back
        }
        invalidate()
    }

    // ---------------------------------------------------------------- trackpad (relative mouse)

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = 0f
    private var maxPointers = 1
    private var scrollAcc = 0f

    private fun onTrackpad(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; moved = 0f; maxPointers = 1; scrollAcc = 0f
                downTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                lastX = e.getX(0); lastY = e.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.getX(0) - lastX
                val dy = e.getY(0) - lastY
                lastX = e.getX(0); lastY = e.getY(0)
                moved += abs(dx) + abs(dy)
                if (e.pointerCount >= 2) {
                    // two fingers = wheel
                    scrollAcc += dy
                    val step = 24f * resources.displayMetrics.density
                    while (abs(scrollAcc) >= step) {
                        dispatcher.backend.mouseWheel(if (scrollAcc > 0) -1 else 1)
                        scrollAcc -= step * (if (scrollAcc > 0) 1 else -1)
                    }
                } else {
                    dispatcher.mouseMove(dx * 1.6f, dy * 1.6f)
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick = SystemClock.uptimeMillis() - downTime < 220
                if (quick && moved < 12f * resources.displayMetrics.density) {
                    val btn = if (maxPointers >= 2) OverlayKey.MRB else OverlayKey.MLB
                    buzz()
                    dispatcher.press(btn, true)
                    dispatcher.press(btn, false)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }
}
