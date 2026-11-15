/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.service

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.onalauncher.controls.R
import com.onalauncher.controls.model.ButtonShape
import com.onalauncher.controls.model.ElementType
import com.onalauncher.controls.model.KeyCategory
import com.onalauncher.controls.model.OverlayElement
import com.onalauncher.controls.model.OverlayKey
import com.onalauncher.controls.model.MouseMode
import com.onalauncher.controls.model.StickOutput
import com.onalauncher.controls.view.ControlIcons
import java.util.UUID

/** Plain-View dialogs for the overlay editor (Compose needs an activity; these run from the service). */
object ElementDialogs {

    private const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

    fun keyName(k: OverlayKey): String = when (k.category) {
        KeyCategory.NONE -> "NONE"
        KeyCategory.KEYBOARD -> "${k.label}  (${k.name})"
        KeyCategory.MOUSE -> "Mouse ${k.label}"
        KeyCategory.GAMEPAD -> "Pad ${k.label}"
        KeyCategory.TOUCH -> "Touch tap (point)"
        KeyCategory.MODE -> "Mode: right-click taps (R-CLICK)"
    }

    fun chooseNewElement(ctx: Context, show: (AlertDialog.Builder) -> AlertDialog, onCreated: (OverlayElement) -> Unit) {
        val types = ElementType.entries
        val names = arrayOf(
            ctx.getString(R.string.ona_overlay_type_button),
            ctx.getString(R.string.ona_overlay_type_row),
            ctx.getString(R.string.ona_overlay_type_dpad),
            ctx.getString(R.string.ona_overlay_type_stick),
            ctx.getString(R.string.ona_overlay_type_trackpad)
        )
        show(
            AlertDialog.Builder(ctx, THEME)
                .setTitle(R.string.ona_overlay_add)
                .setItems(names) { _, i -> onCreated(newElement(types[i])) }
        )
    }

    fun newElement(type: ElementType): OverlayElement {
        val id = "e" + UUID.randomUUID().toString().take(8)
        return when (type) {
            ElementType.BUTTON -> OverlayElement(id, type, 0.5f, 0.5f, bindings = mutableListOf(OverlayKey.SPACE))
            ElementType.BUTTON_ROW -> OverlayElement(id, type, 0.5f, 0.5f, shape = ButtonShape.SQUARE,
                bindings = mutableListOf(OverlayKey.K1, OverlayKey.K2, OverlayKey.K3, OverlayKey.K4))
            ElementType.DPAD -> OverlayElement(id, type, 0.5f, 0.5f,
                bindings = mutableListOf(OverlayKey.UP, OverlayKey.RIGHT, OverlayKey.DOWN, OverlayKey.LEFT))
            ElementType.STICK -> OverlayElement(id, type, 0.5f, 0.5f,
                bindings = mutableListOf(OverlayKey.W, OverlayKey.D, OverlayKey.S, OverlayKey.A))
            ElementType.TRACKPAD -> OverlayElement(id, type, 0.5f, 0.5f)
        }
    }

    /**
     * Settings of one control: bindings, shape, size, toggle, stick output + deadzone, 4/8-way,
     * touch point. Changes are applied when OK is pressed.
     */
    fun editElement(
        ctx: Context,
        el: OverlayElement,
        show: (AlertDialog.Builder) -> AlertDialog,
        onChanged: () -> Unit,
        onPickPoint: () -> Unit,
        onPickImage: () -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        /** Writes every field of the dialog into [el]; set once all views exist. */
        lateinit var applyAll: () -> Unit
        val extras = ArrayList<() -> Unit>()
        var dialog: AlertDialog? = null
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * d).toInt()
            setPadding(p, p / 2, p, 0)
        }
        fun label(res: Int) = TextView(ctx).apply {
            setText(res)
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }

        val keys = OverlayKey.pickerOrder
        val keyAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, keys.map(::keyName))
        fun keySpinner(k: OverlayKey) = Spinner(ctx).apply {
            adapter = keyAdapter
            setSelection(keys.indexOf(k).coerceAtLeast(0))
        }

        // ---- text (buttons)
        val textEdit = EditText(ctx).apply { setText(el.text); hint = ctx.getString(R.string.ona_overlay_text_hint) }
        if (el.type == ElementType.BUTTON) { root.addView(label(R.string.ona_overlay_text)); root.addView(textEdit) }

        // ---- shape
        val shapeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, ButtonShape.entries.map { it.name })
            setSelection(el.shape.ordinal)
        }
        if (el.type == ElementType.BUTTON) { root.addView(label(R.string.ona_overlay_shape)); root.addView(shapeSpinner) }

        // ---- picture (buttons): built-in icon or own image from the gallery
        if (el.type == ElementType.BUTTON) {
            val iconIds = listOf("") + ControlIcons.all.map { it.first }
            val iconNames = listOf(ctx.getString(R.string.ona_overlay_icon_none)) + ControlIcons.all.map { it.third }
            val iconSpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, iconNames)
                setSelection(iconIds.indexOf(el.icon).coerceAtLeast(0))
            }
            var imagePath = el.imagePath
            val imageInfo = TextView(ctx)
            fun refreshImageInfo() {
                imageInfo.setText(if (imagePath.isBlank()) R.string.ona_overlay_image_none else R.string.ona_overlay_image_set)
            }
            refreshImageInfo()
            val imageRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            imageRow.addView(Button(ctx).apply {
                setText(R.string.ona_overlay_image_pick)
                setOnClickListener { applyAll(); dialog?.dismiss(); onPickImage() }
            })
            imageRow.addView(Button(ctx).apply {
                setText(R.string.ona_overlay_image_remove)
                setOnClickListener { imagePath = ""; refreshImageInfo() }
            })
            val showLabel = CheckBox(ctx).apply { setText(R.string.ona_overlay_show_label); isChecked = el.showLabel }
            root.addView(label(R.string.ona_overlay_icon)); root.addView(iconSpinner)
            root.addView(label(R.string.ona_overlay_image)); root.addView(imageInfo); root.addView(imageRow)
            root.addView(showLabel)
            extras.add {
                el.icon = iconIds[iconSpinner.selectedItemPosition.coerceAtLeast(0)]
                el.imagePath = imagePath
                el.showLabel = showLabel.isChecked
            }
        }

        // ---- bindings
        val bindingBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val spinners = ArrayList<Spinner>()
        fun rebuildBindings(count: Int) {
            bindingBox.removeAllViews()
            val old = spinners.map { keys[it.selectedItemPosition] }
            spinners.clear()
            val dirNames = arrayOf("↑", "→", "↓", "←")
            for (i in 0 until count) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val tag = when (el.type) {
                    ElementType.DPAD, ElementType.STICK -> dirNames[i]
                    else -> "${i + 1}"
                }
                row.addView(TextView(ctx).apply { text = tag; minWidth = (28 * d).toInt() })
                val sp = keySpinner(old.getOrNull(i) ?: el.bindings.getOrNull(i) ?: OverlayKey.NONE)
                row.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                spinners.add(sp)
                bindingBox.addView(row)
            }
        }
        val rowCount = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, (1..12).map { it.toString() })
            setSelection((el.bindings.size.coerceIn(1, 12)) - 1)
        }
        when (el.type) {
            ElementType.BUTTON -> { root.addView(label(R.string.ona_overlay_bindings_button)); rebuildBindings(2) }
            ElementType.BUTTON_ROW -> {
                root.addView(label(R.string.ona_overlay_row_count)); root.addView(rowCount)
                root.addView(label(R.string.ona_overlay_bindings)); rebuildBindings(el.bindings.size.coerceIn(1, 12))
                rowCount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (pos + 1 != spinners.size) rebuildBindings(pos + 1)
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) = Unit
                }
            }
            ElementType.DPAD -> { root.addView(label(R.string.ona_overlay_bindings)); rebuildBindings(4) }
            ElementType.STICK -> Unit // added after the output picker
            ElementType.TRACKPAD -> Unit
        }
        if (el.type != ElementType.STICK && el.type != ElementType.TRACKPAD) root.addView(bindingBox)

        // ---- toggle / 8-way
        val toggle = CheckBox(ctx).apply { setText(R.string.ona_overlay_toggle); isChecked = el.toggle }
        if (el.type == ElementType.BUTTON) {
            root.addView(label(R.string.ona_overlay_click_event))
            root.addView(toggle)
            val swipe = CheckBox(ctx).apply { setText(R.string.ona_overlay_swipe_trigger); isChecked = el.swipeTrigger }
            val moveMouse = CheckBox(ctx).apply { setText(R.string.ona_overlay_move_with_mouse); isChecked = el.moveWithMouse }
            root.addView(swipe)
            root.addView(TextView(ctx).apply { setText(R.string.ona_overlay_swipe_trigger_hint); textSize = 12f })
            root.addView(moveMouse)
            root.addView(TextView(ctx).apply { setText(R.string.ona_overlay_move_with_mouse_hint); textSize = 12f })
            extras.add {
                el.swipeTrigger = swipe.isChecked
                el.moveWithMouse = moveMouse.isChecked
            }
        }
        if (el.type == ElementType.TRACKPAD) {
            val modes = MouseMode.entries
            val modeSpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf(
                    ctx.getString(R.string.ona_overlay_mouse_swipe), ctx.getString(R.string.ona_overlay_mouse_tap)
                ))
                setSelection(el.mouseMode.ordinal)
            }
            root.addView(label(R.string.ona_overlay_mouse_mode)); root.addView(modeSpinner)
            root.addView(TextView(ctx).apply { setText(R.string.ona_overlay_mouse_mode_hint); textSize = 12f })
            extras.add { el.mouseMode = modes[modeSpinner.selectedItemPosition.coerceIn(0, modes.size - 1)] }
        }
        val eight = CheckBox(ctx).apply { setText(R.string.ona_overlay_eight_way); isChecked = el.eightWay }
        if (el.type == ElementType.DPAD) root.addView(eight)

        // ---- stick
        val output = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, StickOutput.entries.map { it.name })
            setSelection(el.stickOutput.ordinal)
        }
        val dead = SeekBar(ctx).apply { max = 90; progress = (el.deadzone * 100).toInt() }
        val reach = SeekBar(ctx).apply { max = 40; progress = (el.dragRadius * 100).toInt() }
        if (el.type == ElementType.STICK) {
            root.addView(label(R.string.ona_overlay_stick_output)); root.addView(output)
            root.addView(label(R.string.ona_overlay_bindings_stick)); rebuildBindings(4); root.addView(bindingBox)
            root.addView(label(R.string.ona_overlay_deadzone)); root.addView(dead)
            root.addView(label(R.string.ona_overlay_drag_radius)); root.addView(reach)
        }

        // ---- size
        val size = SeekBar(ctx).apply { max = 26; progress = ((el.scale - 0.4f) * 10).toInt().coerceIn(0, 26) }
        root.addView(label(R.string.ona_overlay_size)); root.addView(size)

        applyAll = {
            applyTo(el, textEdit, shapeSpinner, spinners, keys, toggle, eight, output, dead, reach, size)
            extras.forEach { it() }
        }

        // ---- touch point
        val pickBtn = Button(ctx).apply {
            setText(R.string.ona_overlay_pick_point)
            setOnClickListener {
                applyAll()
                dialog?.dismiss()
                onPickPoint()
            }
        }
        if (el.type == ElementType.BUTTON || el.type == ElementType.BUTTON_ROW || el.type == ElementType.STICK) {
            root.addView(label(R.string.ona_overlay_touch_point)); root.addView(pickBtn)
        }

        dialog = show(
            AlertDialog.Builder(ctx, THEME)
                .setTitle(R.string.ona_overlay_element_settings)
                .setView(ScrollView(ctx).apply { addView(root) })
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    applyAll()
                    onChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun applyTo(
        el: OverlayElement, textEdit: EditText, shape: Spinner, spinners: List<Spinner>, keys: List<OverlayKey>,
        toggle: CheckBox, eight: CheckBox, output: Spinner, dead: SeekBar, reach: SeekBar, size: SeekBar
    ) {
        el.text = textEdit.text.toString().trim()
        el.shape = ButtonShape.entries[shape.selectedItemPosition.coerceAtLeast(0)]
        if (spinners.isNotEmpty()) {
            val picked = spinners.map { keys[it.selectedItemPosition.coerceAtLeast(0)] }.toMutableList()
            // a button's second key is optional: drop a trailing NONE so the label stays clean
            if (el.type == ElementType.BUTTON) while (picked.size > 1 && picked.last() == OverlayKey.NONE) picked.removeAt(picked.lastIndex)
            el.bindings = picked
        }
        el.toggle = toggle.isChecked
        el.eightWay = eight.isChecked
        el.stickOutput = StickOutput.entries[output.selectedItemPosition.coerceAtLeast(0)]
        el.deadzone = dead.progress / 100f
        el.dragRadius = (reach.progress / 100f).coerceAtLeast(0.03f)
        el.scale = 0.4f + size.progress / 10f
    }
}
