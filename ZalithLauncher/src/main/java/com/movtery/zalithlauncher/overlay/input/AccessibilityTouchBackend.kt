/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.movtery.zalithlauncher.overlay.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.movtery.zalithlauncher.overlay.model.OverlayKey
import com.movtery.zalithlauncher.overlay.monitor.OnaAccessibilityService

/**
 * No root, no PC: buttons bound to TAP touch the app at their tap point, TOUCH_DRAG sticks move a
 * finger around a point (drives the game's own on-screen joystick). ESC = system Back.
 *
 * Android's gesture API has ONE injected gesture at a time (a new dispatch cancels the running one),
 * so this backend keeps one live finger: a new DOWN lifts the previous finger first.
 * Held touches use continueStroke (API 26+): each segment is dispatched only after the previous one
 * completed, the newest target point wins (moves are coalesced).
 */
class AccessibilityTouchBackend : InputBackend {
    override val id = "a11y"

    private val main = Handler(Looper.getMainLooper())
    private val svc: AccessibilityService? get() = OnaAccessibilityService.instance

    private var pointer = -1
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var busy = false
    private var pendingX = Float.NaN
    private var pendingY = Float.NaN
    private var pendingUp = false
    /** Bumped for every new finger, so callbacks of an old (cancelled) gesture are ignored. */
    private var session = 0

    override fun supports(category: InputBackend.Capability) = category == InputBackend.Capability.TOUCH

    override fun status(): String = if (svc != null) "Touch ✓" else "Touch: accessibility off"

    override fun key(key: OverlayKey, down: Boolean) {
        if (down && key == OverlayKey.ESC) svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override fun touch(pointer: Int, x: Float, y: Float, action: TouchAction) {
        when (action) {
            TouchAction.DOWN -> {
                if (this.pointer != -1) forceLift()
                this.pointer = pointer
                begin(x, y)
            }
            TouchAction.MOVE -> if (pointer == this.pointer) {
                pendingX = x; pendingY = y
                flush()
            }
            TouchAction.UP -> if (pointer == this.pointer) {
                pendingUp = true
                flush()
            }
        }
    }

    private fun begin(x: Float, y: Float) {
        val s = svc ?: return reset()
        val path = Path().apply { moveTo(x, y) }
        val st = GestureDescription.StrokeDescription(path, 0L, 1L, true)
        session++
        busy = false
        stroke = st; lastX = x; lastY = y
        pendingX = Float.NaN; pendingY = Float.NaN; pendingUp = false
        dispatch(s, st)
    }

    private fun flush() {
        if (busy) return
        val s = svc ?: return reset()
        val prev = stroke ?: return
        val hasMove = !pendingX.isNaN()
        if (!hasMove && !pendingUp) return
        val tx = if (hasMove) pendingX else lastX
        val ty = if (hasMove) pendingY else lastY
        val path = Path().apply {
            moveTo(lastX, lastY)
            if (tx != lastX || ty != lastY) lineTo(tx, ty)
        }
        val end = pendingUp
        val next = prev.continueStroke(path, 0L, if (hasMove) 16L else 1L, !end)
        pendingX = Float.NaN; pendingY = Float.NaN
        lastX = tx; lastY = ty
        if (end) {
            pendingUp = false
            stroke = null
            pointer = -1
        } else {
            stroke = next
        }
        dispatch(s, next)
    }

    private fun dispatch(s: AccessibilityService, st: GestureDescription.StrokeDescription) {
        busy = true
        val mine = session
        val ok = s.dispatchGesture(
            GestureDescription.Builder().addStroke(st).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (mine != session) return
                    busy = false
                    flush()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (mine != session) return
                    busy = false
                    reset()
                }
            },
            main
        )
        if (!ok) { busy = false; reset() }
    }

    private fun forceLift() {
        // The next dispatch cancels the running gesture, which lifts the old finger.
        session++
        busy = false
        reset()
    }

    private fun reset() {
        stroke = null
        pointer = -1
        pendingX = Float.NaN; pendingY = Float.NaN; pendingUp = false
    }

    override fun stop() {
        if (pointer != -1) {
            pendingUp = true
            busy = false
            flush()
        }
    }
}
