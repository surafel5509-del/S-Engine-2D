package com.sengine.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.sengine.engine.Input
import kotlin.math.hypot
import kotlin.math.min

/** Virtual joystick (left) and A/B buttons (right). Touches elsewhere pass through. */
class GameControlsView(context: Context, private val input: () -> Input?) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private var joyPointer = -1
    private var aPointer = -1
    private var bPointer = -1
    private var knobX = 0f
    private var knobY = 0f

    private val baseR get() = min(width, height) * 0.13f
    private val joyCx get() = baseR * 1.6f
    private val joyCy get() = height - baseR * 1.6f
    private val btnR get() = baseR * 0.55f
    private val aCx get() = width - btnR * 2.0f
    private val aCy get() = height - btnR * 2.4f
    private val bCx get() = width - btnR * 4.4f
    private val bCy get() = height - btnR * 1.4f

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0x33FFFFFF
        canvas.drawCircle(joyCx, joyCy, baseR, paint)
        paint.color = if (joyPointer >= 0) 0xAAFFFFFF.toInt() else 0x77FFFFFF
        val kx = if (joyPointer >= 0) knobX else joyCx
        val ky = if (joyPointer >= 0) knobY else joyCy
        canvas.drawCircle(kx, ky, baseR * 0.45f, paint)

        text.textSize = btnR * 0.8f
        paint.color = if (aPointer >= 0) 0xCC57AB5A.toInt() else 0x6657AB5A
        canvas.drawCircle(aCx, aCy, btnR, paint)
        canvas.drawText("A", aCx, aCy + text.textSize * 0.35f, text)
        paint.color = if (bPointer >= 0) 0xCCE5534B.toInt() else 0x66E5534B
        canvas.drawCircle(bCx, bCy, btnR, paint)
        canvas.drawText("B", bCx, bCy + text.textSize * 0.35f, text)
    }

    private fun hit(x: Float, y: Float): Int = when {
        hypot(x - joyCx, y - joyCy) < baseR * 1.7f -> 1
        hypot(x - aCx, y - aCy) < btnR * 1.3f -> 2
        hypot(x - bCx, y - bCy) < btnR * 1.3f -> 3
        else -> 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inp = input() ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                when (hit(e.getX(i), e.getY(i))) {
                    1 -> if (joyPointer < 0) { joyPointer = id; updateJoy(e.getX(i), e.getY(i), inp) }
                    2 -> if (aPointer < 0) { aPointer = id; inp.rawA = true }
                    3 -> if (bPointer < 0) { bPointer = id; inp.rawB = true }
                    else -> if (e.actionMasked == MotionEvent.ACTION_DOWN) return false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (joyPointer >= 0) {
                    val i = e.findPointerIndex(joyPointer)
                    if (i >= 0) updateJoy(e.getX(i), e.getY(i), inp)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val all = e.actionMasked != MotionEvent.ACTION_POINTER_UP
                val id = e.getPointerId(e.actionIndex)
                if (all || id == joyPointer) { joyPointer = -1; inp.joyX = 0f; inp.joyY = 0f }
                if (all || id == aPointer) { aPointer = -1; inp.rawA = false }
                if (all || id == bPointer) { bPointer = -1; inp.rawB = false }
            }
        }
        invalidate()
        return true
    }

    private fun updateJoy(x: Float, y: Float, inp: Input) {
        var dx = x - joyCx
        var dy = y - joyCy
        val d = hypot(dx, dy)
        if (d > baseR) { dx = dx / d * baseR; dy = dy / d * baseR }
        knobX = joyCx + dx; knobY = joyCy + dy
        inp.joyX = dx / baseR
        inp.joyY = -dy / baseR
    }

    fun reset() {
        joyPointer = -1; aPointer = -1; bPointer = -1
        input()?.let { it.joyX = 0f; it.joyY = 0f; it.rawA = false; it.rawB = false }
        invalidate()
    }
}
