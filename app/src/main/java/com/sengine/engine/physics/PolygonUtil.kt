package com.sengine.engine.physics

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.math.M

/**
 * Polygon helpers shared by the physics narrow phase, the collider overlay and the editor's
 * polygon shape tool. All functions work on flat `x0,y0,x1,y1,…` arrays so no allocations happen in
 * the hot path (callers pass scratch buffers or reuse the returned arrays).
 */
object PolygonUtil {

    /** World-space vertices of a collider, honouring node transform, scale and rotation. */
    fun worldPoints(go: GameObject, col: Collider2D): FloatArray {
        val world = go.computeWorld()
        val local = if (col.shape == com.sengine.engine.core.ColliderShape.CIRCLE) {
            circlePoints(col.radius, SEGMENTS)
        } else col.polygonPoints()
        val out = FloatArray(local.size)
        var i = 0
        while (i + 1 < local.size) {
            val lx = local[i] + col.offsetX
            val ly = local[i + 1] + col.offsetY
            out[i] = world.mapX(lx, ly)
            out[i + 1] = world.mapY(lx, ly)
            i += 2
        }
        return out
    }

    fun circlePoints(radius: Float, segments: Int): FloatArray {
        val out = FloatArray(segments * 2)
        for (i in 0 until segments) {
            val a = i * Math.PI * 2 / segments
            out[i * 2] = (Math.cos(a) * radius).toFloat()
            out[i * 2 + 1] = (Math.sin(a) * radius).toFloat()
        }
        return out
    }

    const val SEGMENTS = 20

    fun boundingBox(points: FloatArray): FloatArray {
        if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < points.size) {
            minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i])
            minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
            i += 2
        }
        return floatArrayOf(minX, minY, maxX - minX, maxY - minY)
    }

    fun contains(points: FloatArray, x: Float, y: Float) = M.pointInPolygon(x, y, points)

    /** Signed area — positive for counter-clockwise winding. */
    fun area(points: FloatArray): Float {
        var sum = 0f
        val n = points.size / 2
        var j = n - 1
        for (i in 0 until n) {
            sum += (points[j * 2] + points[i * 2]) * (points[j * 2 + 1] - points[i * 2 + 1])
            j = i
        }
        return -sum * 0.5f
    }

    fun assertWinding(points: FloatArray): FloatArray = if (area(points) < 0f) reversed(points) else points

    fun reversed(points: FloatArray): FloatArray {
        val n = points.size / 2
        val out = FloatArray(points.size)
        for (i in 0 until n) {
            out[i * 2] = points[(n - 1 - i) * 2]
            out[i * 2 + 1] = points[(n - 1 - i) * 2 + 1]
        }
        return out
    }

    /** Regular polygon — used by the "make polygon" action in the collider inspector. */
    fun regular(sides: Int, radius: Float): FloatArray {
        val n = sides.coerceIn(3, 32)
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = i * Math.PI * 2 / n - Math.PI / 2
            out[i * 2] = (Math.cos(a) * radius).toFloat()
            out[i * 2 + 1] = (Math.sin(a) * radius).toFloat()
        }
        return out
    }

    /** Convex hull (gift wrapping) — keeps hand-drawn polygons physics friendly. */
    fun convexHull(points: FloatArray): FloatArray {
        val n = points.size / 2
        if (n < 3) return points
        val idx = (0 until n).sortedBy { points[it * 2] }
        val hull = ArrayList<Int>()
        for (i in idx) {
            while (hull.size >= 2 && cross(points, hull[hull.size - 2], hull[hull.size - 1], i) <= 0f) hull.removeAt(hull.size - 1)
            hull.add(i)
        }
        val lower = hull.size + 1
        for (i in idx.reversed()) {
            while (hull.size >= lower && cross(points, hull[hull.size - 2], hull[hull.size - 1], i) <= 0f) hull.removeAt(hull.size - 1)
            hull.add(i)
        }
        if (hull.isNotEmpty()) hull.removeAt(hull.size - 1)
        val out = FloatArray(hull.size * 2)
        for (i in hull.indices) {
            out[i * 2] = points[hull[i] * 2]
            out[i * 2 + 1] = points[hull[i] * 2 + 1]
        }
        return out
    }

    private fun cross(points: FloatArray, o: Int, a: Int, b: Int): Float =
        (points[a * 2] - points[o * 2]) * (points[b * 2 + 1] - points[o * 2 + 1]) -
            (points[a * 2 + 1] - points[o * 2 + 1]) * (points[b * 2] - points[o * 2])
}
