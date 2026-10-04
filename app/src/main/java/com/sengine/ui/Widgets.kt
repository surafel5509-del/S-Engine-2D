package com.sengine.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.sengine.engine.math.Rect2

/**
 * Hand-built widgets for the editor.
 *
 * The editor owns its look (and compiles without generated resources), so every control here is a
 * small, reusable View: buttons, tabs, collapsible sections, numeric fields with drag-scrubbing,
 * colour pickers, context menus, popovers and dock splitters. Touch and mouse both work; every
 * control scales with [Theme.scale].
 */

// ---------------------------------------------------------------------------- buttons & tabs

class EditorButton(
    context: Context,
    val theme: Theme,
    text: String,
    val onClick: () -> Unit,
    var toggled: Boolean = false,
    var icon: String = ""
) : FrameLayout(context) {

    private val label = TextView(context)

    init {
        label.text = if (icon.isEmpty()) text else "$icon  $text"
        label.gravity = Gravity.CENTER
        label.setTextColor(theme.text)
        label.textSize = theme.textSize(12f)
        label.setPadding(theme.pad(10f), theme.pad(6f), theme.pad(10f), theme.pad(6f))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refresh()
        setOnClickListener { onClick() }
        isClickable = true
        isFocusable = true
    }

    fun refresh() {
        background = theme.rounded(
            if (toggled) theme.selection else theme.panelAlt,
            4f,
            if (toggled) theme.accent else theme.border
        )
        label.setTextColor(if (toggled) theme.text else theme.text)
    }

    fun setText(text: String) {
        label.text = if (icon.isEmpty()) text else "$icon  $text"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> alpha = 0.7f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 1f
        }
        return super.onTouchEvent(event)
    }
}

/** Horizontal tab strip used by every dock. */
class TabStrip(context: Context, val theme: Theme, var onSelect: (Int) -> Unit = {}) : LinearLayout(context) {
    private val buttons = ArrayList<EditorButton>()
    var selected = 0
        private set

    init { orientation = HORIZONTAL }

    fun setTabs(names: List<String>) {
        removeAllViews()
        buttons.clear()
        for ((i, name) in names.withIndex()) {
            val b = EditorButton(context, theme, name, {
                select(i)
                onSelect(i)
            })
            b.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addView(b)
            buttons.add(b)
        }
        select(selected.coerceIn(0, names.size - 1))
    }

    fun select(index: Int) {
        selected = index.coerceIn(0, (buttons.size - 1).coerceAtLeast(0))
        for ((i, b) in buttons.withIndex()) {
            b.toggled = i == selected
            b.refresh()
        }
    }
}

/** Collapsible section header + body, used by the inspector and settings. */
class SectionBox(context: Context, val theme: Theme, title: String, var expanded: Boolean = true) : LinearLayout(context) {
    val body = LinearLayout(context)
    private val header = TextView(context)

    init {
        orientation = VERTICAL
        header.text = "▾  $title"
        header.setTextColor(theme.text)
        header.textSize = theme.textSize(12f)
        header.setTypeface(header.typeface, Typeface.BOLD)
        header.setPadding(theme.pad(6f), theme.pad(5f), theme.pad(6f), theme.pad(5f))
        header.setBackgroundColor(theme.panelAlt)
        header.isClickable = true
        header.setOnClickListener {
            expanded = !expanded
            header.text = (if (expanded) "▾  " else "▸  ") + title
            body.visibility = if (expanded) VISIBLE else GONE
        }
        addView(header)
        body.orientation = VERTICAL
        addView(body)
    }

    fun setTitle(title: String) {
        header.text = (if (expanded) "▾  " else "▸  ") + title
    }
}

// ---------------------------------------------------------------------------- numeric fields

/**
 * Numeric row with drag scrubbing: drag sideways to change the value, tap to type it.
 * Matching Godot/Unity behaviour keeps the editor familiar; the value is always constrained.
 */
class NumberField(
    context: Context,
    val theme: Theme,
    val label: String,
    var value: Float,
    var step: Float = 0.1f,
    var min: Float = -1_000_000f,
    var max: Float = 1_000_000f,
    var decimals: Int = 2,
    val onChange: (Float) -> Unit
) : LinearLayout(context) {

    private val field = EditText(context)
    private val name = TextView(context)
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartValue = 0f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        name.text = label
        name.setTextColor(theme.textDim)
        name.textSize = theme.textSize(11f)
        name.setPadding(theme.pad(6f), 0, theme.pad(4f), 0)
        name.width = theme.dp(88f)
        addView(name)
        field.setText(format(value))
        field.setTextColor(theme.text)
        field.textSize = theme.textSize(12f)
        field.setBackgroundColor(theme.panelAlt)
        field.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        field.setPadding(theme.pad(6f), theme.pad(3f), theme.pad(6f), theme.pad(3f))
        field.setOnEditorActionListener { _, _, _ ->
            commit(field.text.toString().toFloatOrNull())
            true
        }
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit(field.text.toString().toFloatOrNull()) }
        field.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = false
                    dragStartX = event.rawX
                    dragStartValue = value
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    if (!dragging && Math.abs(dx) > theme.dp(6f)) {
                        dragging = true
                        field.clearFocus()
                    }
                    if (dragging) {
                        set(dragStartValue + dx / theme.dp(4f) * step, notify = true)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    dragging
                }
                else -> false
            }
        }
        addView(field, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    fun set(newValue: Float, notify: Boolean = false) {
        val clamped = newValue.coerceIn(min, max)
        val changed = clamped != value
        value = clamped
        field.setText(format(value))
        if (notify && changed) onChange(value)
    }

    private fun commit(parsed: Float?) {
        if (parsed == null || parsed.isNaN()) {
            field.setText(format(value))
            return
        }
        val old = value
        value = parsed.coerceIn(min, max)
        field.setText(format(value))
        if (value != old) onChange(value)
    }

    private fun format(v: Float): String {
        if (decimals <= 0) return v.toInt().toString()
        val s = String.format("%.${decimals}f", v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    companion object {
        fun row(context: Context, theme: Theme, label: String, value: Float, onChange: (Float) -> Unit, step: Float = 0.1f): NumberField =
            NumberField(context, theme, label, value, step, onChange = onChange)
    }
}

/** Vector2 row (X / Y) used for positions, sizes, velocities and offsets. */
class Vector2Field(
    context: Context,
    theme: Theme,
    label: String,
    x: Float,
    y: Float,
    step: Float = 0.1f,
    onChange: (Float, Float) -> Unit
) : LinearLayout(context) {
    private lateinit var fx: NumberField
    private lateinit var fy: NumberField
    private val callback: (Float, Float) -> Unit = onChange

    init {
        orientation = VERTICAL
        addView(Ui.label(context, label, theme, 11f, theme.textDim))
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        addView(row)
        fx = NumberField(context, theme, "X", x, step, onChange = { nx -> callback(nx, fy.value) })
        fy = NumberField(context, theme, "Y", y, step, onChange = { ny -> callback(fx.value, ny) })
        row.addView(fx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(fy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun set(nx: Float, ny: Float) {
        fx.set(nx)
        fy.set(ny)
    }
}

// ---------------------------------------------------------------------------- colour & strings

class ColorField(context: Context, val theme: Theme, val label: String, var color: Int, val onChange: (Int) -> Unit) : LinearLayout(context) {
    private val swatch = View(context)
    private val text = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        text.text = label
        text.setTextColor(theme.textDim)
        text.textSize = theme.textSize(11f)
        text.width = theme.dp(88f)
        addView(text)
        swatch.background = theme.rounded(color, 3f, theme.border)
        addView(swatch, LinearLayout.LayoutParams(theme.dp(38f), theme.dp(22f)))
        val hex = Ui.label(context, hex(), theme, 11f)
        addView(hex)
        setOnClickListener { showColorPicker(context, theme, color) { c -> set(c); onChange(c) } }
        isClickable = true
        refresh()
    }

    fun set(value: Int) {
        color = value
        refresh()
    }

    private fun refresh() {
        swatch.background = theme.rounded(color, 3f, theme.border)
        (getChildAt(2) as TextView).text = hex()
    }

    private fun hex(): String = String.format("#%08X", color)
}

fun showColorPicker(context: Context, theme: Theme, current: Int, onPick: (Int) -> Unit) {
    val root = LinearLayout(context)
    root.orientation = LinearLayout.VERTICAL
    root.setPadding(theme.pad(12f), theme.pad(12f), theme.pad(12f), theme.pad(12f))
    root.setBackgroundColor(theme.panel)
    val preview = View(context)
    var color = current
    preview.background = theme.rounded(color, 4f, theme.border)
    root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(36f)))
    val sliders = ArrayList<SeekBar>()
    val channels = listOf("R", "G", "B", "A")
    val values = intArrayOf(Color.red(current), Color.green(current), Color.blue(current), Color.alpha(current))
    fun update() {
        color = Color.argb(values[3], values[0], values[1], values[2])
        preview.background = theme.rounded(color, 4f, theme.border)
    }
    for ((i, name) in channels.withIndex()) {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val label = Ui.label(context, name, theme, 12f)
        label.width = theme.dp(20f)
        row.addView(label)
        val bar = SeekBar(context)
        bar.max = 255
        bar.progress = values[i]
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                values[i] = progress
                update()
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val field = EditText(context)
        field.setText("%02X".format(values[i]))
        field.textSize = theme.textSize(11f)
        field.inputType = InputType.TYPE_CLASS_TEXT
        field.setOnEditorActionListener { _, _, _ ->
            val v = field.text.toString().toIntOrNull(16) ?: values[i]
            values[i] = v.coerceIn(0, 255)
            bar.progress = values[i]
            update()
            true
        }
        row.addView(field, LinearLayout.LayoutParams(theme.dp(54f), ViewGroup.LayoutParams.WRAP_CONTENT))
        sliders.add(bar)
        root.addView(row)
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle("Colour")
        .setView(root)
        .setPositiveButton("OK") { _, _ -> onPick(color) }
        .setNegativeButton("Cancel", null)
        .create()
    dialog.show()
}

// ---------------------------------------------------------------------------- dialogs & menus

fun inputDialog(context: Context, theme: Theme, title: String, initial: String, hint: String = "", onOk: (String) -> Unit) {
    val edit = Ui.edit(context, initial, theme, hint)
    val box = FrameLayout(context)
    box.setPadding(theme.pad(16f), theme.pad(8f), theme.pad(16f), 0)
    box.addView(edit)
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> onOk(edit.text.toString()) }
        .setNegativeButton("Cancel", null)
        .show()
    edit.requestFocus()
}

fun confirmDialog(context: Context, title: String, message: String, onYes: () -> Unit) {
    AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("Yes") { _, _ -> onYes() }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Real context menu (works with mouse right-click and touch long-press). */
fun showMenu(anchor: View, theme: Theme, items: List<Pair<String, () -> Unit>>) {
    if (items.isEmpty()) return
    val list = LinearLayout(anchor.context)
    list.orientation = LinearLayout.VERTICAL
    list.setBackgroundColor(theme.panel)
    val popup = PopupWindow(list, theme.dp(220f), ViewGroup.LayoutParams.WRAP_CONTENT, true)
    popup.elevation = theme.dp(8f).toFloat()
    for ((title, action) in items) {
        if (title == "-") {
            val line = View(anchor.context)
            line.setBackgroundColor(theme.border)
            list.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(1f)))
            continue
        }
        val row = Ui.label(anchor.context, title, theme, 13f)
        row.setPadding(theme.pad(14f), theme.pad(11f), theme.pad(14f), theme.pad(11f))
        row.isClickable = true
        row.setOnClickListener {
            popup.dismiss()
            action()
        }
        list.addView(row)
    }
    popup.showAsDropDown(anchor, 0, 0)
}

fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** Long-press tooltip used on toolbar buttons and scene tree rows. */
fun attachTooltip(view: View, text: String) {
    if (text.isEmpty()) return
    view.setOnLongClickListener {
        Toast.makeText(view.context, text, Toast.LENGTH_SHORT).show()
        true
    }
}

/** Draggable splitter between docks. */
class Splitter(context: Context, val theme: Theme, val vertical: Boolean, val onDelta: (Int) -> Unit) : View(context) {
    private var last = 0f

    init {
        setBackgroundColor(theme.border)
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    last = if (vertical) event.rawX else event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = if (vertical) event.rawX else event.rawY
                    val delta = (now - last).toInt()
                    if (delta != 0) {
                        last = now
                        onDelta(delta)
                        v.performClick()
                    }
                    true
                }
                else -> true
            }
        }
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val thickness = theme.dp(4f)
        if (vertical) setMeasuredDimension(thickness, MeasureSpec.getSize(heightMeasureSpec))
        else setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), thickness)
    }
}

/** Horizontal search field with a clear button. */
class SearchField(context: Context, val theme: Theme, hint: String = "Search", val onQuery: (String) -> Unit) : LinearLayout(context) {
    val edit: EditText = Ui.edit(context, "", theme, hint)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(theme.panelAlt)
        val icon = Ui.label(context, "⌕", theme, 15f, theme.textDim)
        addView(icon)
        edit.background = null
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onQuery(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        addView(edit, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val clear = Ui.label(context, "✕", theme, 13f, theme.textDim)
        clear.isClickable = true
        clear.setOnClickListener {
            edit.setText("")
            onQuery("")
        }
        addView(clear)
    }
}

/** Rect editor used for camera limits, collider size, clipping and UI anchoring. */
class RectField(
    context: Context,
    theme: Theme,
    label: String,
    rect: Rect2,
    val onChange: (Rect2) -> Unit
) : LinearLayout(context) {
    private lateinit var fx: NumberField
    private lateinit var fy: NumberField
    private lateinit var fw: NumberField
    private lateinit var fh: NumberField

    init {
        orientation = VERTICAL
        fx = NumberField(context, theme, "$label X", rect.x, 0.1f, onChange = { v -> onChange(Rect2(v, fy.value, fw.value, fh.value)) })
        fy = NumberField(context, theme, "$label Y", rect.y, 0.1f, onChange = { v -> onChange(Rect2(fx.value, v, fw.value, fh.value)) })
        fw = NumberField(context, theme, "$label W", rect.width, 0.1f, onChange = { v -> onChange(Rect2(fx.value, fy.value, v, fh.value)) })
        fh = NumberField(context, theme, "$label H", rect.height, 0.1f, onChange = { v -> onChange(Rect2(fx.value, fy.value, fw.value, v)) })
        addView(fx)
        addView(fy)
        addView(fw)
        addView(fh)
    }

    fun set(r: Rect2) {
        fx.set(r.x); fy.set(r.y); fw.set(r.width); fh.set(r.height)
    }
}

/** Makes any view act like a toolbar item with hover/press feedback. */
fun toolbarItem(view: View, theme: Theme, tooltip: String = ""): View {
    view.setBackgroundColor(theme.panel)
    view.isClickable = true
    view.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.setBackgroundColor(theme.selection)
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.setBackgroundColor(theme.panel)
                false
            }
            else -> false
        }
    }
    attachTooltip(view, tooltip)
    return view
}

/** Simple progress meter (memory, frame time budgets). */
class MeterView(context: Context, val theme: Theme, val label: String) : View(context) {
    var value = 0f
    var text = ""

    override fun onDraw(canvas: android.graphics.Canvas) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(theme.panel)
        p.color = theme.track
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.color = if (value > 0.85f) theme.error else if (value > 0.6f) theme.warning else theme.accent
        canvas.drawRect(0f, 0f, width * value.coerceIn(0f, 1f), height.toFloat(), p)
        p.color = theme.text
        p.textSize = theme.textSize(11f)
        val t = if (text.isEmpty()) label else "$label  $text"
        canvas.drawText(t, theme.pad(6f).toFloat(), height * 0.7f, p)
    }

    fun update(v: Float, t: String = "") {
        value = v
        text = t
        invalidate()
    }
}

/** Utility: run a block with the activity's theme applied to a freshly built view tree. */
inline fun Activity.editorTheme(theme: Theme, block: (Theme) -> View): View = block(theme)
