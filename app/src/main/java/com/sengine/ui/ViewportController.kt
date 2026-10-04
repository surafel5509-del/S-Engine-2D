package com.sengine.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.sengine.engine.Engine
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.render.EditorState
import com.sengine.engine.render.Tool
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Scene-view touch handling: pan, pinch-zoom, pick, and move/rotate/scale gizmos. */
class ViewportController(
    private val act: EditorActivity,
    private val engine: Engine,
    private val ed: EditorState,
) : View.OnTouchListener {

    private enum class Op { NONE, PENDING, PAN, MOVE, ROTATE, SCALE, PINCH }

    var snap = false
    private var op = Op.NONE
    private val slop = ViewConfiguration.get(act).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var picked: GameObject? = null
    private var target: GameObject? = null

    // op start state
    private var startWX = 0f
    private var startWY = 0f
    private var objWX = 0f
    private var objWY = 0f
    private var startRot = 0f
    private var startSX = 1f
    private var startSY = 1f
    private var startAngle = 0f

    // pinch
    private var pinchDist = 0f
    private var pinchSize = 5f
    private var pinchWX = 0f
    private var pinchWY = 0f

    private val view get() = ed.view

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        if (engine.mode != Engine.Mode.EDIT) { forwardToGame(e); return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) startPinch(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> {
                if (op == Op.PINCH) {
                    // continue panning with the remaining finger
                    val keep = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(keep); lastY = e.getY(keep)
                    op = Op.PAN
                }
            }
            MotionEvent.ACTION_UP -> onUp(e.x, e.y)
            MotionEvent.ACTION_CANCEL -> { op = Op.NONE; ed.activeAxis = 0 }
        }
        return true
    }

    private fun forwardToGame(e: MotionEvent) {
        val inp = engine.input
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { inp.rawTouching = true; inp.rawTouchSX = e.x; inp.rawTouchSY = e.y; inp.tapPending = true }
            MotionEvent.ACTION_MOVE -> { inp.rawTouchSX = e.x; inp.rawTouchSY = e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> inp.rawTouching = false
        }
    }

    private fun wx(sx: Float) = view.screenToWorldX(sx)
    private fun wy(sy: Float) = view.screenToWorldY(sy)

    private fun onDown(x: Float, y: Float) {
        downX = x; downY = y; lastX = x; lastY = y
        op = Op.PENDING
        target = null
        val wx = wx(x)
        val wy = wy(y)
        synchronized(engine.lock) {
            val sel = engine.scene.findById(ed.selectedId)
            picked = pick(wx, wy)
            if (sel != null && ed.tool != Tool.HAND) {
                val axis = gizmoHit(sel, wx, wy)
                if (axis != 0) {
                    beginOp(sel, axis, wx, wy)
                    return
                }
            }
        }
    }

    private fun beginOp(go: GameObject, axis: Int, wx: Float, wy: Float) {
        act.history.record(ed.selectedId)
        target = go
        ed.activeAxis = axis
        startWX = wx; startWY = wy
        val w = go.computeWorld()
        objWX = w.tx; objWY = w.ty
        startRot = go.rotation
        startSX = go.scaleX; startSY = go.scaleY
        startAngle = Math.toDegrees(atan2((wy - objWY).toDouble(), (wx - objWX).toDouble())).toFloat()
        op = when (ed.tool) {
            Tool.MOVE -> Op.MOVE
            Tool.ROTATE -> Op.ROTATE
            Tool.SCALE -> Op.SCALE
            Tool.HAND -> Op.PAN
        }
    }

    private fun onMove(e: MotionEvent) {
        if (op == Op.PINCH && e.pointerCount >= 2) { updatePinch(e); return }
        val x = e.x
        val y = e.y
        if (op == Op.PENDING) {
            if (hypot(x - downX, y - downY) < slop) return
            val p = picked
            if (ed.tool == Tool.MOVE && p != null) {
                // drag the object under the finger (select it first)
                if (p.id != ed.selectedId) act.select(p.id)
                synchronized(engine.lock) { beginOp(p, 3, wx(downX), wy(downY)) }
            } else op = Op.PAN
        }
        when (op) {
            Op.PAN -> {
                val ppu = view.pixelsPerUnit
                view.cx -= (x - lastX) / ppu
                view.cy += (y - lastY) / ppu
            }
            Op.MOVE, Op.ROTATE, Op.SCALE -> synchronized(engine.lock) { applyOp(wx(x), wy(y)) }
            else -> {}
        }
        lastX = x; lastY = y
    }

    private fun applyOp(wx: Float, wy: Float) {
        val go = target ?: return
        val dx = wx - startWX
        val dy = wy - startWY
        when (op) {
            Op.MOVE -> {
                var nx = objWX + if (ed.activeAxis == 2) 0f else dx
                var ny = objWY + if (ed.activeAxis == 1) 0f else dy
                if (snap) { nx = snapTo(nx, 0.25f); ny = snapTo(ny, 0.25f) }
                go.setWorldPosition(nx, ny)
            }
            Op.ROTATE -> {
                val a = Math.toDegrees(atan2((wy - objWY).toDouble(), (wx - objWX).toDouble())).toFloat()
                var r = startRot + (a - startAngle)
                if (snap) r = snapTo(r, 15f)
                go.rotation = normalizeAngle(r)
            }
            Op.SCALE -> {
                val len = ed.gizmoLength()
                when (ed.activeAxis) {
                    1 -> go.scaleX = scaled(startSX, 1f + dx / len)
                    2 -> go.scaleY = scaled(startSY, 1f + dy / len)
                    else -> {
                        val f = 1f + (dx + dy) / (2f * len)
                        go.scaleX = scaled(startSX, f); go.scaleY = scaled(startSY, f)
                    }
                }
            }
            else -> {}
        }
    }

    private fun scaled(start: Float, f: Float): Float {
        var v = start * f.coerceAtLeast(0.01f)
        if (snap) v = snapTo(v, 0.1f).coerceAtLeast(0.1f)
        return v
    }

    private fun normalizeAngle(a: Float): Float {
        var r = a % 360f
        if (r > 180f) r -= 360f
        if (r < -180f) r += 360f
        return r
    }

    private fun snapTo(v: Float, step: Float) = (v / step).roundToInt() * step

    private fun onUp(x: Float, y: Float) {
        if (op == Op.PENDING && hypot(x - downX, y - downY) < slop) {
            act.select(picked?.id ?: -1L)
        } else if (op == Op.MOVE || op == Op.ROTATE || op == Op.SCALE) {
            act.onObjectEdited()
        }
        op = Op.NONE
        ed.activeAxis = 0
    }

    private fun startPinch(e: MotionEvent) {
        if (op == Op.MOVE || op == Op.ROTATE || op == Op.SCALE) return
        op = Op.PINCH
        pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
        pinchSize = view.size
        val mx = (e.getX(0) + e.getX(1)) / 2f
        val my = (e.getY(0) + e.getY(1)) / 2f
        pinchWX = wx(mx); pinchWY = wy(my)
    }

    private fun updatePinch(e: MotionEvent) {
        val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
        view.size = (pinchSize * pinchDist / d).coerceIn(0.2f, 500f)
        val mx = (e.getX(0) + e.getX(1)) / 2f
        val my = (e.getY(0) + e.getY(1)) / 2f
        // keep the world point that started under the fingers under the fingers
        view.cx = pinchWX - (mx / view.widthPx * 2f - 1f) * view.halfW
        view.cy = pinchWY - (1f - my / view.heightPx * 2f) * view.size
    }

    // ------------------------------------------------------------------ picking
    fun pick(wx: Float, wy: Float): GameObject? {
        val list = engine.scene.objects.withIndex()
            .filter { it.value.isActiveInHierarchy() }
            .sortedWith(compareBy({ -it.value.order }, { -it.index }))
        val ppu = view.pixelsPerUnit
        for ((_, go) in list) {
            val w = go.world
            val hasSprite = go.get<SpriteRenderer>() != null
            val text = go.get<TextRenderer>()
            if (hasSprite || text != null) {
                val inv = w.inverted() ?: continue
                val lx = inv.mapX(wx, wy)
                val ly = inv.mapY(wx, wy)
                var hw = 0.5f
                var hh = 0.5f
                if (!hasSprite && text != null) {
                    val lines = text.text.split('\n')
                    hh = text.size * lines.size / 2f
                    hw = (lines.maxOfOrNull { it.length } ?: 1) * text.size * 0.3f
                    val off = when (text.align) { 0 -> hw; 2 -> -hw; else -> 0f }
                    if (abs(lx - off) <= hw && abs(ly) <= hh) return go
                    continue
                }
                if (abs(lx) <= hw && abs(ly) <= hh) return go
            } else {
                val r = (if (go.get<Camera2D>() != null) 18f else 12f) / ppu
                if (hypot(wx - w.tx, wy - w.ty) <= r) return go
            }
        }
        return null
    }

    /** 0 none, 1 x-axis, 2 y-axis, 3 free/centre. */
    private fun gizmoHit(go: GameObject, wx: Float, wy: Float): Int {
        val w = go.computeWorld()
        val x = w.tx
        val y = w.ty
        val len = ed.gizmoLength()
        val tol = 26f / view.pixelsPerUnit * (view.heightPx / 1080f).coerceAtLeast(0.6f)
        return when (ed.tool) {
            Tool.MOVE -> when {
                hypot(wx - (x + len * 0.18f), wy - (y + len * 0.18f)) < tol * 1.2f -> 3
                abs(wy - y) < tol && wx > x + len * 0.3f && wx < x + len + tol * 1.5f -> 1
                abs(wx - x) < tol && wy > y + len * 0.3f && wy < y + len + tol * 1.5f -> 2
                else -> 0
            }
            Tool.ROTATE -> {
                val d = hypot(wx - x, wy - y)
                if (abs(d - len) < tol * 1.3f) 3 else 0
            }
            Tool.SCALE -> when {
                hypot(wx - (x + len), wy - y) < tol * 1.4f -> 1
                hypot(wx - x, wy - (y + len)) < tol * 1.4f -> 2
                hypot(wx - x, wy - y) < tol * 1.4f -> 3
                else -> 0
            }
            Tool.HAND -> 0
        }
    }

    fun frame(go: GameObject?) {
        if (go == null) { view.cx = 0f; view.cy = 0f; view.size = 6f; return }
        val w = go.computeWorld()
        view.cx = w.tx; view.cy = w.ty
        view.size = (maxOf(w.scaleX, w.scaleY) * 1.5f).coerceIn(2f, 50f)
    }
}
