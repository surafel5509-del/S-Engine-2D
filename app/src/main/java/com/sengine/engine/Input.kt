package com.sengine.engine

import android.view.KeyEvent
import com.sengine.engine.render.View2D
import java.util.concurrent.ConcurrentHashMap

class Input {
    // raw state written from the UI thread
    @Volatile var joyX = 0f
    @Volatile var joyY = 0f
    @Volatile var rawA = false
    @Volatile var rawB = false
    @Volatile var rawTouching = false
    @Volatile var rawTouchSX = 0f
    @Volatile var rawTouchSY = 0f
    @Volatile var tapPending = false
    val keys: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    // per-frame state read by scripts
    var axisX = 0f; private set
    var axisY = 0f; private set
    var a = false; private set
    var b = false; private set
    var aDown = false; private set
    var bDown = false; private set
    var touching = false; private set
    var tapped = false; private set
    var touchX = 0f; private set
    var touchY = 0f; private set

    fun beginFrame(view: View2D) {
        var kx = 0f
        var ky = 0f
        if (KeyEvent.KEYCODE_A in keys || KeyEvent.KEYCODE_DPAD_LEFT in keys) kx -= 1f
        if (KeyEvent.KEYCODE_D in keys || KeyEvent.KEYCODE_DPAD_RIGHT in keys) kx += 1f
        if (KeyEvent.KEYCODE_S in keys || KeyEvent.KEYCODE_DPAD_DOWN in keys) ky -= 1f
        if (KeyEvent.KEYCODE_W in keys || KeyEvent.KEYCODE_DPAD_UP in keys) ky += 1f
        axisX = (joyX + kx).coerceIn(-1f, 1f)
        axisY = (joyY + ky).coerceIn(-1f, 1f)
        val na = rawA || KeyEvent.KEYCODE_SPACE in keys || KeyEvent.KEYCODE_BUTTON_A in keys
        val nb = rawB || KeyEvent.KEYCODE_ENTER in keys || KeyEvent.KEYCODE_BUTTON_B in keys
        aDown = na && !a
        bDown = nb && !b
        a = na; b = nb
        touching = rawTouching
        tapped = tapPending
        tapPending = false
        touchX = view.screenToWorldX(rawTouchSX)
        touchY = view.screenToWorldY(rawTouchSY)
    }

    fun clear() {
        joyX = 0f; joyY = 0f; rawA = false; rawB = false; rawTouching = false; tapPending = false
        keys.clear()
        a = false; b = false; aDown = false; bDown = false; tapped = false; touching = false
    }
}
