package com.sengine.engine.math

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable 2D vector with the usual arithmetic. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)

    val length get() = sqrt(x * x + y * y)
    val lengthSquared get() = x * x + y * y
    val normalized: Vec2
        get() {
            val l = length
            return if (l < 1e-6f) ZERO else Vec2(x / l, y / l)
        }

    /** Angle in degrees, measured counter-clockwise from +X. */
    val angleDeg get() = Math.toDegrees(atan2(y, x).toDouble()).toFloat()

    fun rotated(deg: Float): Vec2 {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Vec2(x * c - y * s, x * s + y * c)
    }

    fun dot(o: Vec2) = x * o.x + y * o.y
    fun cross(o: Vec2) = x * o.y - y * o.x
    fun distanceTo(o: Vec2) = (this - o).length
    fun lerp(o: Vec2, t: Float) = Vec2(x + (o.x - x) * t, y + (o.y - y) * t)
    fun withX(nx: Float) = Vec2(nx, y)
    fun withY(ny: Float) = Vec2(x, ny)

    companion object {
        val ZERO = Vec2(0f, 0f)
        val ONE = Vec2(1f, 1f)
        val UP = Vec2(0f, 1f)
        val RIGHT = Vec2(1f, 0f)
        fun fromAngle(deg: Float, length: Float = 1f) =
            Vec2(cos(Math.toRadians(deg.toDouble())).toFloat() * length, sin(Math.toRadians(deg.toDouble())).toFloat() * length)
    }
}

/** Axis aligned rectangle, [x],[y] is the top-left corner in world space. */
data class Rect2(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right get() = x + width
    val bottom get() = y + height
    val centerX get() = x + width * 0.5f
    val centerY get() = y + height * 0.5f
    val center get() = Vec2(centerX, centerY)
    val isEmpty get() = width <= 0f || height <= 0f

    fun contains(px: Float, py: Float) = px >= x && px <= right && py >= y && py <= bottom
    fun contains(p: Vec2) = contains(p.x, p.y)
    fun intersects(o: Rect2) = !(o.x > right || o.right < x || o.y > bottom || o.bottom < y)
    fun intersection(o: Rect2): Rect2 {
        val nx = max(x, o.x); val ny = max(y, o.y)
        val nr = min(right, o.right); val nb = min(bottom, o.bottom)
        return Rect2(nx, ny, max(0f, nr - nx), max(0f, nb - ny))
    }

    fun union(o: Rect2): Rect2 {
        val nx = min(x, o.x); val ny = min(y, o.y)
        return Rect2(nx, ny, max(right, o.right) - nx, max(bottom, o.bottom) - ny)
    }

    fun grow(amount: Float) = Rect2(x - amount, y - amount, width + amount * 2, height + amount * 2)
    fun offset(dx: Float, dy: Float) = Rect2(x + dx, y + dy, width, height)
    fun toArray() = floatArrayOf(x, y, width, height)

    companion object {
        val EMPTY = Rect2(0f, 0f, 0f, 0f)
        fun fromCorners(ax: Float, ay: Float, bx: Float, by: Float) =
            Rect2(min(ax, bx), min(ay, by), abs(bx - ax), abs(by - ay))
        fun of(vararg values: Float): Rect2 {
            val v = values.copyOf(4)
            return Rect2(v[0], v[1], v[2], v[3])
        }
    }
}

/**
 * Named easing curves shared by the animation system, tween helpers and UI transitions.
 * All functions map `t` in 0..1 to an eased value in (usually) 0..1.
 */
object Easing {
    val NAMES = listOf(
        "Linear", "In Sine", "Out Sine", "In Out Sine",
        "In Quad", "Out Quad", "In Out Quad",
        "In Cubic", "Out Cubic", "In Out Cubic",
        "In Quart", "Out Quart", "In Out Quart",
        "In Expo", "Out Expo", "In Out Expo",
        "In Back", "Out Back", "In Out Back",
        "In Bounce", "Out Bounce", "In Out Bounce",
        "In Elastic", "Out Elastic", "In Out Elastic",
        "Smoothstep", "Step"
    )

    private const val PI = Math.PI.toFloat()

    fun apply(index: Int, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (index.coerceIn(0, NAMES.size - 1)) {
            0 -> x
            1 -> 1f - cos(x * PI / 2f)
            2 -> sin(x * PI / 2f)
            3 -> -(cos(PI * x) - 1f) / 2f
            4 -> x * x
            5 -> 1f - (1f - x) * (1f - x)
            6 -> if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).let { it * it } / 2f
            7 -> x * x * x
            8 -> 1f - (1f - x).let { it * it * it }
            9 -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
            10 -> x * x * x * x
            11 -> 1f - (1f - x).let { it * it * it * it }
            12 -> if (x < 0.5f) 8f * x * x * x * x else 1f - (-2f * x + 2f).let { it * it * it * it } / 2f
            13 -> if (x == 0f) 0f else Math.pow(2.0, (10.0 * x - 10.0)).toFloat()
            14 -> if (x == 1f) 1f else 1f - Math.pow(2.0, (-10.0 * x)).toFloat()
            15 -> if (x == 0f) 0f else if (x == 1f) 1f
            else if (x < 0.5f) Math.pow(2.0, 20.0 * x - 10.0).toFloat() / 2f
            else (2f - Math.pow(2.0, -20.0 * x + 10.0).toFloat()) / 2f
            16 -> 2.70158f * x * x * x - 1.70158f * x * x
            17 -> 1f + 2.70158f * (x - 1f).let { it * it * it } + 1.70158f * (x - 1f).let { it * it }
            18 -> if (x < 0.5f) (2f * x).let { (2f * x) * (2f * x) * ((3.5949095f + 1f) * 2f * x - 3.5949095f) } / 2f
            else ((2f * x - 2f).let { it * it * ((3.5949095f + 1f) * (x * 2f - 2f) + 3.5949095f) } + 2f) / 2f
            19 -> bounceOut(x)
            20 -> 1f - bounceOut(1f - x)
            21 -> if (x < 0.5f) (1f - bounceOut(1f - 2f * x)) / 2f else (1f + bounceOut(2f * x - 1f)) / 2f
            22 -> if (x == 0f) 0f else -(Math.pow(2.0, 10.0 * x - 10.0).toFloat()) * sin((x * 10f - 10.75f) * 2f * PI / 3f)
            23 -> if (x == 1f) 1f else Math.pow(2.0, -10.0 * x).toFloat() * sin((x * 10f - 0.75f) * 2f * PI / 3f) + 1f
            24 -> if (x == 0f) 0f else if (x == 1f) 1f else
                if (x < 0.5f) -(Math.pow(2.0, 20.0 * x - 10.0).toFloat() * sin((20f * x - 11.125f) * 2f * PI / 4.5f)) / 2f
                else (Math.pow(2.0, -20.0 * x + 10.0).toFloat() * sin((20f * x - 11.125f) * 2f * PI / 4.5f)) / 2f + 1f
            25 -> x * x * (3f - 2f * x)
            else -> if (x < 1f) 0f else 1f
        }
    }

    fun apply(name: String, t: Float) = apply(NAMES.indexOf(name).coerceAtLeast(0), t)

    private fun bounceOut(x: Float): Float {
        val n = 7.5625f; val d = 2.75f
        return when {
            x < 1f / d -> n * x * x
            x < 2f / d -> n * (x - 1.5f / d).let { it * it } + 0.75f
            x < 2.5f / d -> n * (x - 2.25f / d).let { it * it } + 0.9375f
            else -> n * (x - 2.625f / d).let { it * it } + 0.984375f
        }
    }

    fun displayName(index: Int) = NAMES.getOrElse(index) { "Linear" }
}

/** Small math helpers used all over the engine (no magic numbers in call sites). */
object M {
    const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
    const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    const val EPS = 1e-6f

    fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun invLerp(a: Float, b: Float, v: Float) = if (abs(b - a) < EPS) 0f else (v - a) / (b - a)
    fun approach(current: Float, target: Float, delta: Float): Float =
        if (current < target) min(current + delta, target) else max(current - delta, target)

    fun smoothDamp(current: Float, target: Float, smoothing: Float, dt: Float): Float =
        if (smoothing <= 0f) target else lerp(current, target, 1f - kotlin.math.exp(-smoothing * dt))

    fun wrap(value: Float, minValue: Float, maxValue: Float): Float {
        val range = maxValue - minValue
        if (range <= EPS) return minValue
        var v = value
        while (v < minValue) v += range
        while (v >= maxValue) v -= range
        return v
    }

    fun snap(value: Float, step: Float) = if (step <= EPS) value else (value / step).roundToInt() * step
    fun snapVec(v: Vec2, step: Float) = Vec2(snap(v.x, step), snap(v.y, step))
    fun floorTo(value: Float, step: Float) = if (step <= EPS) value else floor(value / step) * step
    fun ceilTo(value: Float, step: Float) = if (step <= EPS) value else ceil(value / step) * step

    fun nextPowerOfTwo(v: Int): Int {
        var n = 1
        while (n < v) n = n shl 1
        return n
    }

    /** Shortest distance between angles in degrees. */
    fun angleDelta(from: Float, to: Float) = wrap(to - from + 180f, 0f, 360f) - 180f

    /** Distance from point [px],[py] to segment a-b, plus the closest point. */
    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < EPS) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = clamp(((px - ax) * dx + (py - ay) * dy) / len2, 0f, 1f)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    fun pointInPolygon(px: Float, py: Float, points: FloatArray): Boolean {
        var inside = false
        val n = points.size / 2
        if (n < 3) return false
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[i * 2]; val yi = points[i * 2 + 1]
            val xj = points[j * 2]; val yj = points[j * 2 + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi + EPS) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
