package com.sengine.engine.render

import android.opengl.Matrix

/** Orthographic 2D view: centre + half-height in world units. */
class View2D {
    @Volatile var cx = 0f
    @Volatile var cy = 0f
    @Volatile var size = 5f
    @Volatile var widthPx = 1
    @Volatile var heightPx = 1

    val aspect get() = widthPx.toFloat() / heightPx.coerceAtLeast(1)
    val halfW get() = size * aspect
    val pixelsPerUnit get() = heightPx / (2f * size)

    fun screenToWorldX(sx: Float) = cx + (sx / widthPx * 2f - 1f) * halfW
    fun screenToWorldY(sy: Float) = cy + (1f - sy / heightPx * 2f) * size

    fun matrix(out: FloatArray) {
        Matrix.orthoM(out, 0, cx - halfW, cx + halfW, cy - size, cy + size, -1f, 1f)
    }

    fun copyFrom(o: View2D) {
        cx = o.cx; cy = o.cy; size = o.size; widthPx = o.widthPx; heightPx = o.heightPx
    }
}
