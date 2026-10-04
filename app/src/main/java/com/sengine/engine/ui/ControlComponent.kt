package com.sengine.engine.ui

import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Signals
import com.sengine.engine.core.SignalListener
import com.sengine.engine.math.Rect2

/** Control kinds available to the visual UI editor. */
object ControlType {
    const val PANEL = "Panel"
    const val LABEL = "Label"
    const val BUTTON = "Button"
    const val IMAGE = "Image"
    const val PROGRESS = "ProgressBar"
    const val SLIDER = "Slider"
    const val CHECKBOX = "CheckBox"
    const val TEXT_FIELD = "TextField"
    const val SCROLL = "ScrollContainer"
    const val ROW = "Row"
    const val COLUMN = "Column"
    const val GRID = "Grid"
    const val CENTER = "Center"
    const val TABS = "TabContainer"
    const val MENU = "Menu"
    const val SPACER = "Spacer"

    val ALL = listOf(
        PANEL, LABEL, BUTTON, IMAGE, PROGRESS, SLIDER, CHECKBOX, TEXT_FIELD,
        SCROLL, ROW, COLUMN, GRID, CENTER, TABS, MENU, SPACER
    )

    /** Controls that layout their children instead of using anchors. */
    val CONTAINERS = setOf(ROW, COLUMN, GRID, CENTER, SCROLL, TABS)

    /** Controls the runtime UI system reacts to. */
    val INTERACTIVE = setOf(BUTTON, SLIDER, CHECKBOX, TEXT_FIELD, SCROLL, TABS, MENU)

    fun isContainer(type: String) = type in CONTAINERS
    fun isInteractive(type: String) = type in INTERACTIVE
}

/**
 * A UI node. Layout data lives on [GameObject.UiProps] (so it serializes with the node), the
 * computed rectangle and interaction state live here.
 */
class ControlComponent : Component(), SignalListener {
    override val type = TYPE
    override val category = "UI"
    override val description = "UI control laid out by the UI system"

    /** Computed by [UiLayout] each frame, in UI (pixel) space. */
    var rect = Rect2.EMPTY
    var hovered = false
    var pressed = false
    var focused = false
    var dragOrigin = 0f

    /** Layout data kept while the component is not attached yet (loading, prefabs, registry). */
    private var looseUi: GameObject.UiProps? = null

    val ui: GameObject.UiProps
        get() {
            val loose = looseUi ?: GameObject.UiProps().also { looseUi = it }
            if (!attached) return loose
            var u = gameObject.ui
            if (u == null) {
                u = loose
                gameObject.ui = u
            }
            return u
        }

    override fun props(): List<Prop> {
        val u = ui
        return listOf(
            Prop.E("Control", ControlType.ALL, { ControlType.ALL.indexOf(u.controlType).coerceAtLeast(0) }, { u.controlType = ControlType.ALL[it] }),
            Prop.S("Text", { u.text }, { u.text = it }, multiline = false, section = "Content"),
            Prop.F("Font Size", { u.fontSize }, { u.fontSize = it.coerceAtLeast(6f) }, 1f, 6f, 96f, section = "Content"),
            Prop.E("Align", listOf("Left", "Center", "Right"), { u.textAlign }, { u.textAlign = it }, section = "Content"),
            Prop.S("Placeholder", { u.placeholder }, { u.placeholder = it }, section = "Content"),
            Prop.S("Tabs", { u.tabs }, { u.tabs = it }, tooltip = "Comma separated tab names", section = "Content"),
            Prop.B("Checked", { u.checked }, { u.checked = it }, section = "Value"),
            Prop.F("Value", { u.value }, { u.value = it.coerceIn(0f, 1f) }, 0.01f, 0f, 1f, section = "Value"),
            Prop.B("Editable", { u.editable }, { u.editable = it }, section = "Value"),
            Prop.I("Active Tab", { u.activeTab }, { u.activeTab = it.coerceAtLeast(0) }, 0, 32, section = "Value"),
            Prop.F("Anchor Min X", { u.anchorMinX }, { u.anchorMinX = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f, section = "Anchors"),
            Prop.F("Anchor Min Y", { u.anchorMinY }, { u.anchorMinY = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f, section = "Anchors"),
            Prop.F("Anchor Max X", { u.anchorMaxX }, { u.anchorMaxX = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f, section = "Anchors"),
            Prop.F("Anchor Max Y", { u.anchorMaxY }, { u.anchorMaxY = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f, section = "Anchors"),
            Prop.F("Offset Left", { u.offsetLeft }, { u.offsetLeft = it }, 1f, section = "Offsets"),
            Prop.F("Offset Top", { u.offsetTop }, { u.offsetTop = it }, 1f, section = "Offsets"),
            Prop.F("Offset Right", { u.offsetRight }, { u.offsetRight = it }, 1f, section = "Offsets"),
            Prop.F("Offset Bottom", { u.offsetBottom }, { u.offsetBottom = it }, 1f, section = "Offsets"),
            Prop.F("Min Width", { u.minWidth }, { u.minWidth = it.coerceAtLeast(0f) }, 1f, 0f, 4096f, section = "Layout"),
            Prop.F("Min Height", { u.minHeight }, { u.minHeight = it.coerceAtLeast(0f) }, 1f, 0f, 4096f, section = "Layout"),
            Prop.E("Grow H", listOf("None", "Start", "Center", "End", "Both"), { u.growHorizontal }, { u.growHorizontal = it }, section = "Layout"),
            Prop.E("Grow V", listOf("None", "Start", "Center", "End", "Both"), { u.growVertical }, { u.growVertical = it }, section = "Layout"),
            Prop.F("Spacing", { u.spacing }, { u.spacing = it }, 1f, 0f, 128f, section = "Layout"),
            Prop.F("Padding", { u.padding }, { u.padding = it }, 1f, 0f, 256f, section = "Layout"),
            Prop.B("Scroll X", { u.scrollX }, { u.scrollX = it }, section = "Layout"),
            Prop.B("Scroll Y", { u.scrollY }, { u.scrollY = it }, section = "Layout"),
            Prop.E("Style", UiTheme.STYLE_NAMES, { UiTheme.STYLE_NAMES.indexOf(u.style).coerceAtLeast(0) }, { u.style = UiTheme.STYLE_NAMES[it] }, section = "Style"),
            Prop.S("On Click", { u.onClick }, { u.onClick = it }, tooltip = "Script method called when the control is activated", section = "Interaction"),
            Prop.S("On Value Changed", { u.onValueChanged }, { u.onValueChanged = it }, section = "Interaction"),
            Prop.B("Visible In Play", { u.visibleInPlay }, { u.visibleInPlay = it }, section = "Interaction"),
            Prop.Info("Rect", { "%.0f, %.0f  %.0f×%.0f".format(rect.x, rect.y, rect.width, rect.height) }, section = "Info")
        )
    }

    fun click() {
        val u = ui
        gameObject.emit(Signals.PRESSED)
        if (u.onClick.isNotBlank()) gameObject.emit(u.onClick)
    }

    fun valueChanged(value: Float) {
        ui.value = value.coerceIn(0f, 1f)
        gameObject.emit(Signals.VALUE_CHANGED, ui.value)
        val cb = ui.onValueChanged
        if (cb.isNotBlank()) gameObject.emit(cb, ui.value)
    }

    fun toggle() {
        ui.checked = !ui.checked
        gameObject.emit(Signals.TOGGLED, ui.checked)
    }

    override fun resetRuntime() {
        hovered = false
        pressed = false
        focused = false
    }

    override fun onSignal(name: String, data: Any?): Boolean = false

    companion object {
        const val TYPE = "Control"
    }
}
