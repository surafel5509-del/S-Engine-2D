package com.sengine.engine.math

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D affine transform. Maps (x, y) -> (a*x + c*y + tx, b*x + d*y + ty).
 */
class Affine(
    var a: Float = 1f, var b: Float = 0f,
    var c: Float = 0f, var d: Float = 1f,
    var tx: Float = 0f, var ty: Float = 0f
) {
    fun set(o: Affine): Affine {
        a = o.a; b = o.b; c = o.c; d = o.d; tx = o.tx; ty = o.ty; return this
    }

    fun identity(): Affine { a = 1f; b = 0f; c = 0f; d = 1f; tx = 0f; ty = 0f; return this }

    fun setTRS(x: Float, y: Float, rotDeg: Float, sx: Float, sy: Float): Affine {
        val r = Math.toRadians(rotDeg.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        a = cs * sx; b = sn * sx
        c = -sn * sy; d = cs * sy
        tx = x; ty = y
        return this
    }

    /** this = p * ch */
    fun setMul(p: Affine, ch: Affine): Affine {
        val na = p.a * ch.a + p.c * ch.b
        val nb = p.b * ch.a + p.d * ch.b
        val nc = p.a * ch.c + p.c * ch.d
        val nd = p.b * ch.c + p.d * ch.d
        val ntx = p.a * ch.tx + p.c * ch.ty + p.tx
        val nty = p.b * ch.tx + p.d * ch.ty + p.ty
        a = na; b = nb; c = nc; d = nd; tx = ntx; ty = nty
        return this
    }

    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    fun inverted(): Affine? {
        val det = a * d - b * c
        if (det == 0f || det.isNaN()) return null
        val id = 1f / det
        val na = d * id
        val nb = -b * id
        val nc = -c * id
        val nd = a * id
        val ntx = -(na * tx + nc * ty)
        val nty = -(nb * tx + nd * ty)
        return Affine(na, nb, nc, nd, ntx, nty)
    }

    val scaleX: Float get() = sqrt(a * a + b * b)
    val scaleY: Float get() = sqrt(c * c + d * d)
    val rotationDeg: Float get() = Math.toDegrees(atan2(b, a).toDouble()).toFloat()

    /** Column-major 4x4 for OpenGL. */
    fun toMat4(out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        out[0] = a; out[1] = b
        out[4] = c; out[5] = d
        out[10] = 1f
        out[12] = tx; out[13] = ty
        out[15] = 1f
    }
}
