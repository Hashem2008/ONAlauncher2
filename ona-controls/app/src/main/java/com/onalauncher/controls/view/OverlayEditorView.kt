/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.onalauncher.controls.R
import com.onalauncher.controls.model.OverlayElement
import com.onalauncher.controls.model.OverlayProfile
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Edit mode (the overlay is "unlocked"): one full-screen touchable window with a grid, every
 * control drawn on it, and a Winlator-like toolbar (move handle, profile name, add, delete,
 * settings, save, discard).
 *  - drag a control to move it (snaps to the grid),
 *  - pinch with two fingers to resize the selected control,
 *  - tap the profile name to switch profile.
 * The game stays visible through the dimmed background so controls can be placed over its UI.
 */
@SuppressLint("ViewConstructor")
class OverlayEditorView(
    context: Context,
    var profile: OverlayProfile,
    private val listener: Listener
) : FrameLayout(context) {

    interface Listener {
        fun onSave(profile: OverlayProfile)
        fun onDiscard()
        fun onAdd()
        fun onEditElement(element: OverlayElement)
        fun onPickProfile()
    }

    private val density = resources.displayMetrics.density
    private val grid = 16f * density
    private val snap = 8f * density

    var selected: OverlayElement? = null
        set(v) { field = v; canvasView.invalidate() }

    /** When set, the next tap on the canvas is a point pick (tap point / drag centre), as screen fractions. */
    private var pick: ((Float, Float) -> Unit)? = null
    private val hint: TextView
    private val nameView: TextView
    private val canvasView = EditCanvas(context)
    private val toolbar: LinearLayout

    init {
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (8 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 18, 18, 20))
                setStroke((1.5f * density).toInt(), Color.argb(255, 60, 64, 72))
                cornerRadius = 14 * density
            }
        }
        val move = icon(R.drawable.ic_drag_handle)
        toolbar.addView(move)
        nameView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((10 * density).toInt(), 0, (14 * density).toInt(), 0)
            setOnClickListener { listener.onPickProfile() }
        }
        toolbar.addView(nameView)
        toolbar.addView(View(context).apply {
            setBackgroundColor(Color.rgb(70, 110, 160))
            layoutParams = LinearLayout.LayoutParams((2 * density).toInt(), (32 * density).toInt())
        })
        toolbar.addView(icon(R.drawable.ic_add) { listener.onAdd() })
        toolbar.addView(icon(R.drawable.ic_delete_outlined) {
            selected?.let { profile.elements.remove(it); selected = null }
        })
        toolbar.addView(icon(R.drawable.ic_settings_filled) { selected?.let(listener::onEditElement) })
        toolbar.addView(icon(R.drawable.ic_check) { listener.onSave(profile) })
        toolbar.addView(icon(R.drawable.ic_close) { listener.onDiscard() })
        addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (8 * density).toInt()
        })
        // drag the toolbar by its handle (like Winlator's move arrows)
        var sx = 0f; var sy = 0f; var tx = 0f; var ty = 0f
        move.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; tx = toolbar.translationX; ty = toolbar.translationY }
                MotionEvent.ACTION_MOVE -> { toolbar.translationX = tx + e.rawX - sx; toolbar.translationY = ty + e.rawY - sy }
            }
            true
        }

        hint = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(Color.argb(200, 33, 150, 243))
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            visibility = GONE
        }
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (16 * density).toInt()
        })
        refreshName()
    }

    private fun icon(res: Int, onClick: (() -> Unit)? = null) = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(Color.rgb(140, 160, 185))
        val p = (8 * density).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
        if (onClick != null) setOnClickListener { onClick() }
    }

    fun refreshName() {
        nameView.text = context.getString(R.string.ona_overlay_editor_profile, profile.name)
        canvasView.invalidate()
    }

    fun setProfileAndRefresh(p: OverlayProfile) {
        profile = p
        selected = null
        refreshName()
    }

    fun refresh() = canvasView.invalidate()

    fun startPick(message: String, onPicked: (Float, Float) -> Unit) {
        pick = onPicked
        hint.text = message
        hint.visibility = VISIBLE
        toolbar.visibility = GONE
    }

    /** Adds a new control at the screen centre and selects it. */
    fun addElement(el: OverlayElement) {
        el.x = 0.5f; el.y = 0.5f
        profile.elements.add(el)
        selected = el
    }

    private inner class EditCanvas(ctx: Context) : View(ctx) {
        private val renderer = ElementRenderer(density)
        private val gridPaint = Paint().apply { color = Color.argb(45, 255, 255, 255); strokeWidth = 1f }
        private val border = Paint().apply { color = Color.rgb(160, 30, 30); style = Paint.Style.STROKE; strokeWidth = 2f }
        private val rect = RectF()
        private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 152, 0); style = Paint.Style.STROKE; strokeWidth = 2 * density }

        // drag / pinch state
        private var dragging: OverlayElement? = null
        private var offX = 0f
        private var offY = 0f
        private var pinchStart = 0f
        private var scaleStart = 1f

        fun boundsOf(el: OverlayElement, out: RectF) {
            val (w, h) = ElementGeometry.sizePx(el, density)
            val cx = el.x * width
            val cy = el.y * height
            out.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.argb(150, 0, 0, 0))
            var x = 0f
            while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += grid }
            var y = 0f
            while (y < height) { c.drawLine(0f, y, width.toFloat(), y, gridPaint); y += grid }
            c.drawRect(1f, 1f, width - 1f, height - 1f, border)
            val opacity = profile.opacity.coerceAtLeast(0.75f) // always readable while editing
            for (el in profile.elements) {
                boundsOf(el, rect)
                renderer.draw(c, el, rect, null, opacity, el === selected)
            }
            selected?.let { el ->
                // show the touch target of TAP / TOUCH_DRAG controls
                if (el.bindings.any { it.name == "TOUCH_TAP" } || el.stickOutput.name == "TOUCH_DRAG") {
                    val tx = el.tapX * width
                    val ty = el.tapY * height
                    c.drawCircle(tx, ty, 14 * density, tapPaint)
                    c.drawLine(tx - 20 * density, ty, tx + 20 * density, ty, tapPaint)
                    c.drawLine(tx, ty - 20 * density, tx, ty + 20 * density, tapPaint)
                }
            }
        }

        private fun hit(x: Float, y: Float): OverlayElement? {
            for (el in profile.elements.asReversed()) {
                boundsOf(el, rect)
                if (rect.contains(x, y)) return el
            }
            return null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            pick?.let { cb ->
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    pick = null
                    hint.visibility = GONE
                    toolbar.visibility = VISIBLE
                    cb(e.x / width, e.y / height)
                    invalidate()
                }
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val el = hit(e.x, e.y)
                    selected = el
                    dragging = el
                    if (el != null) { offX = e.x - el.x * width; offY = e.y - el.y * height }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && selected != null) {
                    dragging = null
                    pinchStart = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    scaleStart = selected!!.scale
                }
                MotionEvent.ACTION_MOVE -> {
                    val sel = selected
                    if (e.pointerCount >= 2 && sel != null && pinchStart > 0f) {
                        val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        sel.scale = ((scaleStart * d / pinchStart) * 20).roundToInt() / 20f
                        sel.scale = sel.scale.coerceIn(0.4f, 3f)
                        invalidate()
                    } else dragging?.let { el ->
                        val cx = ((e.x - offX) / snap).roundToInt() * snap
                        val cy = ((e.y - offY) / snap).roundToInt() * snap
                        el.x = (cx / width).coerceIn(0f, 1f)
                        el.y = (cy / height).coerceIn(0f, 1f)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = null; pinchStart = 0f }
                MotionEvent.ACTION_POINTER_UP -> pinchStart = 0f
            }
            return true
        }
    }
}
