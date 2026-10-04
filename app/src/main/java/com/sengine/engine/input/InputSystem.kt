package com.sengine.engine.input

import com.sengine.engine.json.JVal
import com.sengine.engine.json.jobj
import com.sengine.engine.math.Vec2

/**
 * A single physical binding of an action.
 *  * `key`   — Android key code (hardware keyboard, TV remote, gamepad buttons)
 *  * `axis`  — gamepad axis: 0 = left stick X, 1 = left stick Y, 2 = right stick X, 3 = right stick Y
 *  * `mouse` — 0 left, 1 right, 2 middle
 *  * `touch` — virtual on-screen controls: 0 = dpad, 1 = A button, 2 = B button
 */
data class Binding(
    var kind: Int,
    var code: Int,
    var positive: Boolean = true,
    var scale: Float = 1f,
    var deadZone: Float = 0.2f
) {
    fun toJson(): JVal.Obj = jobj(
        "kind" to KIND_NAMES[kind.coerceIn(0, KIND_NAMES.size - 1)],
        "code" to code,
        "positive" to positive,
        "scale" to scale,
        "deadZone" to deadZone
    )

    fun label(keyNames: (Int) -> String): String = when (kind) {
        KEY -> keyNames(code) + if (positive) "" else " (neg)"
        AXIS -> "${AXIS_NAMES.getOrElse(code) { "Axis $code" }}" + if (positive) "+" else "-"
        MOUSE -> "Mouse ${MOUSE_NAMES.getOrElse(code) { code.toString() }}"
        TOUCH -> "Touch ${TOUCH_NAMES.getOrElse(code) { code.toString() }}"
        else -> "?"
    }

    companion object {
        const val KEY = 0
        const val AXIS = 1
        const val MOUSE = 2
        const val TOUCH = 3
        val KIND_NAMES = listOf("key", "axis", "mouse", "touch")
        val AXIS_NAMES = listOf("LS X", "LS Y", "RS X", "RS Y")
        val MOUSE_NAMES = listOf("Left", "Right", "Middle")
        val TOUCH_NAMES = listOf("D-Pad", "Button A", "Button B", "Swipe")

        fun key(code: Int, positive: Boolean = true) = Binding(KEY, code, positive)
        fun axis(code: Int, positive: Boolean = true) = Binding(AXIS, code, positive)
        fun mouse(code: Int) = Binding(MOUSE, code)
        fun touch(code: Int) = Binding(TOUCH, code)

        fun fromJson(o: JVal.Obj): Binding {
            val kind = KIND_NAMES.indexOf(o.str("kind", "key")).coerceAtLeast(0)
            return Binding(kind, o.i("code"), o.bool("positive", true), o.f("scale", 1f), o.f("deadZone", 0.2f))
        }
    }
}

/** One logical action with its bindings and runtime state. */
class InputAction(var name: String, var axis: Boolean = false) {
    val bindings = ArrayList<Binding>()

    var pressed = false
    var justPressed = false
    var justReleased = false
    var value = 0f          // analogue value in -1..1
    var rawX = 0f
    var rawY = 0f

    fun add(b: Binding): InputAction {
        bindings.add(b)
        return this
    }

    fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("name", name)
        o.put("axis", axis)
        o.put("bindings", JVal.Arr().also { a -> bindings.forEach { a.add(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JVal.Obj): InputAction {
            val a = InputAction(o.str("name"), o.bool("axis"))
            o.objects("bindings").forEach { a.bindings.add(Binding.fromJson(it)) }
            return a
        }
    }
}

/**
 * The input map: named actions resolved from keyboard, mouse, gamepad and touch, editable in the
 * Input panel and stored in the project settings. Defaults match the common case
 * (`move_left`, `move_right`, `move_up`, `move_down`, `jump`, `attack`, `interact`, `pause`).
 */
class InputMap {
    val actions = LinkedHashMap<String, InputAction>()

    fun action(name: String): InputAction = actions.getOrPut(name) { InputAction(name) }

    fun axisAction(name: String): InputAction = actions.getOrPut(name) { InputAction(name, axis = true) }

    fun remove(name: String) = actions.remove(name)

    fun rename(old: String, new: String): Boolean {
        if (new.isBlank() || actions.containsKey(new)) return false
        val a = actions.remove(old) ?: return false
        a.name = new
        actions[new] = a
        return true
    }

    fun toJson(): JVal.Obj = jobj("actions" to JVal.Arr().also { arr -> actions.values.forEach { arr.add(it.toJson()) } })

    fun fromJson(o: JVal.Obj) {
        actions.clear()
        o.objects("actions").forEach { ao ->
            val a = InputAction.fromJson(ao)
            if (a.name.isNotBlank()) actions[a.name] = a
        }
    }

    /** Human readable list used by the docs panel and the inspector. */
    fun summary(): String = actions.entries.joinToString("\n") { (_, a) ->
        "${a.name}: " + a.bindings.joinToString(", ") { it.label { c -> keyName(c) } }
    }

    companion object {
        fun defaults(): InputMap {
            val m = InputMap()
            m.axisAction("move_x").add(Binding(Binding.KEY, KEY_LEFT)).add(Binding(Binding.KEY, KEY_A, positive = false))
            m.axisAction("move_y").add(Binding(Binding.KEY, KEY_DOWN)).add(Binding(Binding.KEY, KEY_W, positive = false))
            m.axisAction("move_left").add(Binding(Binding.AXIS, 0, positive = false)).add(Binding(Binding.KEY, KEY_LEFT)).add(Binding(Binding.TOUCH, 0, positive = false))
            m.axisAction("move_right").add(Binding(Binding.AXIS, 0, positive = true)).add(Binding(Binding.KEY, KEY_RIGHT)).add(Binding(Binding.TOUCH, 0, positive = true))
            m.axisAction("move_up").add(Binding(Binding.AXIS, 1, positive = false)).add(Binding(Binding.KEY, KEY_UP)).add(Binding(Binding.TOUCH, 0, positive = false))
            m.axisAction("move_down").add(Binding(Binding.AXIS, 1, positive = true)).add(Binding(Binding.KEY, KEY_DOWN)).add(Binding(Binding.TOUCH, 0, positive = true))
            m.action("jump").add(Binding(Binding.KEY, KEY_SPACE)).add(Binding(Binding.KEY, KEY_BUTTON_A)).add(Binding(Binding.TOUCH, 1))
            m.action("attack").add(Binding(Binding.KEY, KEY_J)).add(Binding(Binding.KEY, KEY_BUTTON_X)).add(Binding(Binding.TOUCH, 2))
            m.action("interact").add(Binding(Binding.KEY, KEY_E)).add(Binding(Binding.KEY, KEY_ENTER))
            m.action("pause").add(Binding(Binding.KEY, KEY_ESCAPE)).add(Binding(Binding.KEY, KEY_BUTTON_START))
            m.action("ui_accept").add(Binding(Binding.KEY, KEY_ENTER)).add(Binding(Binding.KEY, KEY_SPACE))
            m.action("ui_cancel").add(Binding(Binding.KEY, KEY_ESCAPE)).add(Binding(Binding.KEY, KEY_BACK))
            return m
        }

        // Android key codes, duplicated here so the core stays free of android imports.
        const val KEY_LEFT = 21
        const val KEY_UP = 19
        const val KEY_RIGHT = 22
        const val KEY_DOWN = 20
        const val KEY_A = 29
        const val KEY_D = 32
        const val KEY_W = 51
        const val KEY_S = 47
        const val KEY_SPACE = 62
        const val KEY_ENTER = 66
        const val KEY_ESCAPE = 111
        const val KEY_BACK = 4
        const val KEY_J = 38
        const val KEY_E = 33
        const val KEY_Q = 45
        const val KEY_TAB = 61
        const val KEY_BUTTON_A = 96
        const val KEY_BUTTON_B = 97
        const val KEY_BUTTON_X = 99
        const val KEY_BUTTON_Y = 100
        const val KEY_BUTTON_START = 108
        const val KEY_BUTTON_SELECT = 109
        const val KEY_SHIFT_LEFT = 59

        fun keyName(code: Int): String = KEY_NAMES[code] ?: "Key $code"

        val KEY_NAMES: Map<Int, String> = buildMap {
            put(KEY_LEFT, "←"); put(KEY_RIGHT, "→"); put(KEY_UP, "↑"); put(KEY_DOWN, "↓")
            put(KEY_SPACE, "Space"); put(KEY_ENTER, "Enter"); put(KEY_ESCAPE, "Esc"); put(KEY_BACK, "Back")
            put(KEY_TAB, "Tab"); put(KEY_SHIFT_LEFT, "Shift")
            put(KEY_BUTTON_A, "Pad A"); put(KEY_BUTTON_B, "Pad B"); put(KEY_BUTTON_X, "Pad X")
            put(KEY_BUTTON_Y, "Pad Y"); put(KEY_BUTTON_START, "Start"); put(KEY_BUTTON_SELECT, "Select")
            for (c in 'A'.code..'Z'.code) put(c, ('A' + (c - 'A'.code)).toString())
            for (d in 0..9) put(7 + d, d.toString())
        }
    }
}

/** Raw device state pushed by the platform layer every frame. */
class InputDevices {
    val keys = HashSet<Int>()
    val keysPressedThisFrame = HashSet<Int>()
    val keysReleasedThisFrame = HashSet<Int>()

    var mouseX = 0f
    var mouseY = 0f
    var mouseInUi = false
    val mouseButtons = BooleanArray(3)
    val mouseJustPressed = BooleanArray(3)
    val mouseJustReleased = BooleanArray(3)
    var scrollDelta = 0f

    var gamepadConnected = false
    val axes = FloatArray(4)
    val buttons = BooleanArray(16)

    /** Touch state in *screen* pixels (converted to world by the runtime). */
    var touchActive = false
    var touchX = 0f
    var touchY = 0f
    var touchStartX = 0f
    var touchStartY = 0f
    var touchMoved = false
    var tapPending = false
    var pinchDelta = 0f
    var touchCount = 0

    /** Virtual joystick + buttons drawn by the runtime. */
    var stickX = 0f
    var stickY = 0f
    var stickActive = false
    var buttonA = false
    var buttonB = false

    fun isKeyDown(code: Int) = code in keys || code in keysPressedThisFrame
    fun wasKeyPressed(code: Int) = code in keysPressedThisFrame
    fun wasKeyReleased(code: Int) = code in keysReleasedThisFrame

    fun axisValue(index: Int, positive: Boolean, deadZone: Float): Float {
        if (index !in axes.indices) return 0f
        val raw = axes[index]
        val v = if (kotlin.math.abs(raw) < deadZone) 0f else raw
        return if (positive) maxOf(v, 0f) else maxOf(-v, 0f)
    }

    fun endFrame() {
        keysPressedThisFrame.clear()
        keysReleasedThisFrame.clear()
        for (i in 0..2) { mouseJustPressed[i] = false; mouseJustReleased[i] = false }
        tapPending = false
        scrollDelta = 0f
        pinchDelta = 0f
    }

    fun clear() {
        keys.clear()
        endFrame()
        for (i in 0..2) { mouseButtons[i] = false }
        for (i in axes.indices) axes[i] = 0f
        for (i in buttons.indices) buttons[i] = false
        touchActive = false
        touchCount = 0
        stickX = 0f; stickY = 0f; stickActive = false
        buttonA = false; buttonB = false
    }
}

/**
 * Resolves the [InputMap] against raw device state and exposes per-frame action state to scripts,
 * the UI system and the editor (remote input view).
 */
class InputSystem {
    var map = InputMap.defaults()
    val devices = InputDevices()

    /** Roster of pressed actions this frame, kept in sync for fast lookups. */
    private val currentValues = HashMap<String, Float>()

    fun action(name: String): InputAction? = map.actions[name]

    fun isPressed(name: String): Boolean = map.actions[name]?.pressed ?: false
    fun isJustPressed(name: String): Boolean = map.actions[name]?.justPressed ?: false
    fun isJustReleased(name: String): Boolean = map.actions[name]?.justReleased ?: false
    fun value(name: String): Float = map.actions[name]?.value ?: 0f
    fun axis(nameNegative: String, namePositive: String): Float = value(namePositive) - value(nameNegative)
    fun vector(xNegative: String, xPositive: String, yNegative: String, yPositive: String): Vec2 =
        Vec2(axis(xNegative, xPositive), axis(yNegative, yPositive))

    /** Convenience accessors used by scripts and gameplay code. */
    val moveX: Float get() = axis("move_left", "move_right")
    val moveY: Float get() = axis("move_down", "move_up")

    fun beginFrame() {
        for (a in map.actions.values) {
            var value = 0f
            var pressed = false
            for (b in a.bindings) {
                val v = when (b.kind) {
                    Binding.KEY -> {
                        val positive = if (b.positive) b.code else -b.code
                        val code = if (positive >= 0) positive else -positive
                        val down = devices.isKeyDown(code)
                        if (down) b.scale else 0f
                    }
                    Binding.AXIS -> devices.axisValue(b.code, b.positive, b.deadZone) * b.scale
                    Binding.MOUSE -> if (devices.mouseButtons.getOrElse(b.code) { false }) b.scale else 0f
                    Binding.TOUCH -> touchValue(b) * b.scale
                    else -> 0f
                }
                if (v > 0f) pressed = true
                if (v > value) value = v
            }
            // digital actions report 0/1; analogue actions keep their magnitude
            if (!a.axis) value = if (pressed) 1f else 0f
            a.justPressed = pressed && !a.pressed
            a.justReleased = !pressed && a.pressed
            a.pressed = pressed
            a.value = value
            currentValues[a.name] = value
        }
    }

    private fun touchValue(b: Binding): Float = when (b.code) {
        0 -> if (b.positive) maxOf(devices.stickX, 0f) else maxOf(-devices.stickX, 0f)
        1 -> if (devices.buttonA) 1f else 0f
        2 -> if (devices.buttonB) 1f else 0f
        else -> 0f
    }

    /** Vertical variant of the virtual stick, used by the default move_up/down bindings. */
    fun touchVertical(positive: Boolean): Float = if (positive) maxOf(devices.stickY, 0f) else maxOf(-devices.stickY, 0f)

    fun endFrame() = devices.endFrame()

    fun clear() {
        devices.clear()
        for (a in map.actions.values) {
            a.pressed = false; a.justPressed = false; a.justReleased = false; a.value = 0f
        }
    }

    fun snapshot(): Map<String, Float> = currentValues

    /** Rebind helper used by the Input panel: wait for the next key/axis and return a binding. */
    class RebindCapture {
        var active = false
        var actionName = ""
        var axisSlot = -1

        fun start(action: String) {
            active = true
            actionName = action
            axisSlot = -1
        }

        fun applyTo(map: InputMap, binding: Binding): Boolean {
            val a = map.actions[actionName] ?: return false
            a.bindings.removeAll { it.kind == binding.kind && it.code == binding.code }
            a.bindings.add(binding)
            active = false
            return true
        }
    }

    val rebind = RebindCapture()
}
