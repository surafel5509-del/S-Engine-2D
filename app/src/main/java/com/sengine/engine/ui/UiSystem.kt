package com.sengine.engine.ui

import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Scene
import com.sengine.engine.input.InputSystem

/**
 * Runtime for UI nodes: layout + interaction.
 *
 * The editor's UI mode, the resolution preview and the running game all use this same system, so
 * what you lay out is exactly what the player gets. Everything here is event driven — no polling,
 * no per-frame allocation for the common path.
 */
class UiSystem {
    /** Design resolution used when the UI is authored; the runtime scales to the real viewport. */
    var designWidth = 1280f
    var designHeight = 720f

    /** Pointer state in design space. */
    var pointerX = 0f
    var pointerY = 0f
    var pointerDown = false
    var pointerJustDown = false
    var pointerJustUp = false

    var hoveredId = -1L
    var pressedId = -1L
    var focusedFieldId = -1L

    var scaleX = 1f
    var scaleY = 1f
    var stretchMode = 0        // 0 = scale to fit (letterbox-free stretch), 1 = keep aspect and fit, 2 = fixed

    /** Set by the platform layer when the soft keyboard text arrives. */
    var onTextRequested: (() -> Unit)? = null
    var onTextDismissed: (() -> Unit)? = null

    private var dragging = false
    private var dragStartValue = 0f
    private var dragStartX = 0f

    var visible = true
    var lastEvent = ""

    fun beginFrame(viewportW: Float, viewportH: Float) {
        when (stretchMode) {
            1 -> {
                val s = minOf(viewportW / designWidth.coerceAtLeast(1f), viewportH / designHeight.coerceAtLeast(1f))
                scaleX = s; scaleY = s
            }
            2 -> { scaleX = 1f; scaleY = 1f }
            else -> { scaleX = viewportW / designWidth.coerceAtLeast(1f); scaleY = viewportH / designHeight.coerceAtLeast(1f) }
        }
    }

    /** Convert viewport pixels → design space. */
    fun toDesignX(x: Float) = x / scaleX.coerceAtLeast(0.0001f)
    fun toDesignY(y: Float) = y / scaleY.coerceAtLeast(0.0001f)

    fun update(scene: Scene, input: InputSystem, viewportW: Float, viewportH: Float, dt: Float, interactive: Boolean) {
        beginFrame(viewportW, viewportH)
        UiLayout.layout(scene, designWidth, designHeight)
        if (!visible) return

        val d = input.devices
        if (d.touchActive) {
            pointerX = toDesignX(d.touchX)
            pointerY = toDesignY(d.touchY)
        } else if (d.mouseX != 0f || d.mouseY != 0f) {
            pointerX = toDesignX(d.mouseX)
            pointerY = toDesignY(d.mouseY)
        }
        val down = d.touchActive || d.mouseButtons[0]
        pointerJustDown = down && !pointerDown
        pointerJustUp = !down && pointerDown
        pointerDown = down

        val hover = UiLayout.hitTest(scene, pointerX, pointerY)
        hoveredId = hover?.id ?: -1L

        if (!interactive) {
            dragging = false
            pressedId = -1
            return
        }

        if (pointerJustDown) {
            val target = hover
            pressedId = target?.id ?: -1L
            dragging = false
            if (target != null) {
                val control = target.getAny<ControlComponent>()!!
                target.emit("ui_input")
                when (control.ui.controlType) {
                    ControlType.TEXT_FIELD -> {
                        if (focusedFieldId != target.id) {
                            focusedFieldId = target.id
                            control.focused = true
                            onTextRequested?.invoke()
                        }
                    }
                    ControlType.SLIDER -> {
                        dragging = true
                        dragStartX = pointerX
                        dragStartValue = control.ui.value
                        applySlider(scene, control, target)
                    }
                    ControlType.SCROLL -> {
                        dragging = true
                        dragStartX = pointerX
                        dragStartValue = pointerY
                        val st = UiLayout.scrollState(target.id)
                        st.grabbing = true
                        st.startOffsetX = st.offsetX
                        st.startOffsetY = st.offsetY
                        st.grabStartX = pointerX
                        st.grabStartY = pointerY
                    }
                    ControlType.TABS -> {
                        if (pointerY < control.rect.y + 30f) {
                            val names = UiLayout.tabNames(control.ui)
                            var x = control.rect.x
                            for ((i, name) in names.withIndex()) {
                                val w = 24f + name.length * control.ui.fontSize * 0.62f
                                if (pointerX >= x && pointerX <= x + w) {
                                    control.ui.activeTab = i
                                    lastEvent = "tab:$name"
                                    control.click()
                                    break
                                }
                                x += w
                            }
                        }
                    }
                    ControlType.BUTTON, ControlType.CHECKBOX, ControlType.MENU -> control.pressed = true
                }
            } else {
                // clicking empty space clears focus
                if (focusedFieldId > 0) {
                    clearFocus(scene)
                    onTextDismissed?.invoke()
                }
            }
        }

        if (pointerDown && dragging) {
            val target = scene.findById(pressedId)
            val control = target?.getAny<ControlComponent>()
            when {
                control != null && control.ui.controlType == ControlType.SLIDER -> applySlider(scene, control, target)
                control != null && control.ui.controlType == ControlType.SCROLL -> {
                    val st = UiLayout.scrollState(target.id)
                    st.offsetX = st.startOffsetX - (pointerX - st.grabStartX)
                    st.offsetY = st.startOffsetY - (pointerY - st.grabStartY)
                    st.offsetX = st.offsetX.coerceIn(0f, maxOf(0f, st.contentW - control.rect.width))
                    st.offsetY = st.offsetY.coerceIn(0f, maxOf(0f, st.contentH - control.rect.height))
                }
            }
        }

        if (pointerJustUp) {
            val target = scene.findById(pressedId)
            val control = target?.getAny<ControlComponent>()
            if (control != null) {
                val inside = control.rect.contains(pointerX, pointerY)
                control.pressed = false
                if (inside) {
                    when (control.ui.controlType) {
                        ControlType.BUTTON -> {
                            lastEvent = "click:${target.name}"
                            control.click()
                        }
                        ControlType.CHECKBOX -> {
                            control.toggle()
                            lastEvent = "toggle:${target.name}=${control.ui.checked}"
                        }
                        ControlType.MENU -> control.click()
                    }
                }
                if (control.ui.controlType == ControlType.SCROLL) UiLayout.scrollState(target.id).grabbing = false
            }
            dragging = false
            pressedId = -1L
        }

        // hover flags for the UI renderer
        for (go in scene.objects) {
            val c = go.getAny<ControlComponent>() ?: continue
            c.hovered = go.id == hoveredId
            c.focused = go.id == focusedFieldId
        }
    }

    private fun applySlider(scene: Scene, control: ControlComponent, target: GameObject) {
        val rect = control.rect
        val t = ((pointerX - rect.x) / rect.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        control.valueChanged(t)
    }

    fun clearFocus(scene: Scene) {
        if (focusedFieldId <= 0) return
        scene.findById(focusedFieldId)?.getAny<ControlComponent>()?.focused = false
        focusedFieldId = -1L
    }

    /** Feed typed characters into the focused text field (wired to the Android soft keyboard). */
    fun textInput(text: String): Boolean {
        val scene = focusedScene ?: return false
        val go = scene.findById(focusedFieldId) ?: return false
        val control = go.getAny<ControlComponent>() ?: return false
        if (!control.ui.editable) return false
        control.ui.text += text
        return true
    }

    fun backspace(): Boolean {
        val scene = focusedScene ?: return false
        val go = scene.findById(focusedFieldId) ?: return false
        val control = go.getAny<ControlComponent>() ?: return false
        if (control.ui.text.isNotEmpty()) {
            control.ui.text = control.ui.text.dropLast(1)
        }
        return true
    }

    /** Keyboard/gamepad navigation for accessibility: move the focus between interactive controls. */
    fun navigate(scene: Scene, dx: Int, dy: Int): Boolean {
        val controls = scene.objects.filter { it.ui != null && ControlType.isInteractive(it.ui!!.controlType) }
        if (controls.isEmpty()) return false
        val current = scene.findById(focusedFieldId)
        val currentRect = current?.getAny<ControlComponent>()?.rect ?: return false.also {
            focusedFieldId = controls.first().id
            return true
        }
        var best: GameObject? = null
        var bestScore = Float.MAX_VALUE
        for (c in controls) {
            if (c.id == focusedFieldId) continue
            val r = c.getAny<ControlComponent>()?.rect ?: continue
            val vx = r.centerX - currentRect.centerX
            val vy = r.centerY - currentRect.centerY
            if (dx != 0 && (if (dx > 0) vx <= 0 else vx >= 0)) continue
            if (dy != 0 && (if (dy > 0) vy <= 0 else vy >= 0)) continue
            val score = kotlin.math.abs(vx) + kotlin.math.abs(vy) * 2f + if (dx != 0) kotlin.math.abs(vy) * 2f else kotlin.math.abs(vx) * 2f
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }
        if (best == null) return false
        clearFocus(scene)
        focusedFieldId = best.id
        best.getAny<ControlComponent>()?.focused = true
        return true
    }

    /** Activate the focused control (keyboard / gamepad "accept"). */
    fun activateFocused(scene: Scene): Boolean {
        val go = scene.findById(focusedFieldId) ?: return false
        val control = go.getAny<ControlComponent>() ?: return false
        when (control.ui.controlType) {
            ControlType.CHECKBOX -> control.toggle()
            else -> control.click()
        }
        return true
    }

    var focusedScene: Scene? = null

    fun reset() {
        hoveredId = -1L
        pressedId = -1L
        focusedFieldId = -1L
        dragging = false
        pointerDown = false
        UiLayout.clear()
    }
}
