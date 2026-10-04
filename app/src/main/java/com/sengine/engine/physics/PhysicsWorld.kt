package com.sengine.engine.physics

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight impulse-based 2D physics: axis-aligned boxes and circles,
 * gravity, restitution, friction, triggers and collision callbacks.
 */
class PhysicsWorld {

    interface Listener {
        fun onCollisionEnter(a: GameObject, b: GameObject)
        fun onTriggerEnter(a: GameObject, b: GameObject)
        fun onTriggerExit(a: GameObject, b: GameObject)
    }

    var listener: Listener? = null
    private var accumulator = 0f
    private val fixedDt = 1f / 60f

    private class Body(
        val go: GameObject,
        val rb: Rigidbody2D?,
        val col: Collider2D,
        var cx: Float = 0f, var cy: Float = 0f,
        var hw: Float = 0f, var hh: Float = 0f, var r: Float = 0f
    ) {
        val isCircle get() = col.shape == 1
        val invMass: Float
            get() = if (rb == null || rb.bodyType != 0) 0f else 1f / rb.mass
    }

    private var prevContacts = HashSet<Long>()
    private var prevTriggers = HashSet<Long>()

    fun reset() {
        accumulator = 0f
        prevContacts = HashSet(); prevTriggers = HashSet()
    }

    fun step(scene: Scene, dt: Float) {
        accumulator += min(dt, 0.25f)
        var steps = 0
        while (accumulator >= fixedDt && steps < 5) {
            fixedStep(scene, fixedDt)
            accumulator -= fixedDt
            steps++
        }
        if (steps == 5) accumulator = 0f
    }

    private fun fixedStep(scene: Scene, dt: Float) {
        // integrate
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val rb = go.get<Rigidbody2D>() ?: continue
            rb.grounded = false
            when (rb.bodyType) {
                0 -> {
                    rb.vx += scene.gravityX * rb.gravityScale * dt
                    rb.vy += scene.gravityY * rb.gravityScale * dt
                    if (rb.drag > 0f) {
                        val k = max(0f, 1f - rb.drag * dt)
                        rb.vx *= k; rb.vy *= k
                    }
                    moveWorld(go, rb.vx * dt, rb.vy * dt)
                }
                1 -> moveWorld(go, rb.vx * dt, rb.vy * dt)
                else -> {}
            }
        }

        // gather bodies
        val bodies = ArrayList<Body>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            val b = Body(go, go.get(), col)
            refresh(b)
            bodies.add(b)
        }

        val contacts = HashSet<Long>()
        val triggers = HashSet<Long>()
        for (i in 0 until bodies.size) {
            for (j in i + 1 until bodies.size) {
                val a = bodies[i]
                val b = bodies[j]
                if (a.invMass == 0f && b.invMass == 0f && !a.col.isTrigger && !b.col.isTrigger) continue
                val m = collide(a, b) ?: continue
                val key = pairKey(a.go.id, b.go.id)
                if (a.col.isTrigger || b.col.isTrigger) {
                    triggers.add(key)
                    if (key !in prevTriggers) listener?.onTriggerEnter(a.go, b.go)
                    continue
                }
                contacts.add(key)
                resolve(a, b, m)
                if (key !in prevContacts) listener?.onCollisionEnter(a.go, b.go)
            }
        }
        for (k in prevTriggers) if (k !in triggers) {
            val a = scene.findById(k shr 32)
            val b = scene.findById(k and 0xFFFFFFFFL)
            if (a != null && b != null) listener?.onTriggerExit(a, b)
        }
        prevContacts = contacts
        prevTriggers = triggers
    }

    private fun pairKey(a: Long, b: Long): Long {
        val lo = min(a, b); val hi = max(a, b)
        return (lo shl 32) or (hi and 0xFFFFFFFFL)
    }

    private fun moveWorld(go: GameObject, dx: Float, dy: Float) {
        if (go.parent == null) {
            go.x += dx; go.y += dy
        } else {
            val w = go.computeWorld()
            go.setWorldPosition(w.tx + dx, w.ty + dy)
        }
    }

    private fun refresh(b: Body) {
        val w = b.go.computeWorld()
        val sx = w.scaleX
        val sy = w.scaleY
        b.cx = w.mapX(b.col.offsetX, b.col.offsetY)
        b.cy = w.mapY(b.col.offsetX, b.col.offsetY)
        b.hw = b.col.width * sx * 0.5f
        b.hh = b.col.height * sy * 0.5f
        b.r = b.col.radius * max(sx, sy)
    }

    private class Manifold(val nx: Float, val ny: Float, val depth: Float)

    /** Normal points from a to b. */
    private fun collide(a: Body, b: Body): Manifold? {
        return when {
            !a.isCircle && !b.isCircle -> boxBox(a, b)
            a.isCircle && b.isCircle -> circleCircle(a, b)
            !a.isCircle && b.isCircle -> boxCircle(a, b)
            else -> boxCircle(b, a)?.let { Manifold(-it.nx, -it.ny, it.depth) }
        }
    }

    private fun boxBox(a: Body, b: Body): Manifold? {
        val dx = b.cx - a.cx
        val ox = a.hw + b.hw - abs(dx)
        if (ox <= 0f) return null
        val dy = b.cy - a.cy
        val oy = a.hh + b.hh - abs(dy)
        if (oy <= 0f) return null
        return if (ox < oy) Manifold(if (dx < 0) -1f else 1f, 0f, ox)
        else Manifold(0f, if (dy < 0) -1f else 1f, oy)
    }

    private fun circleCircle(a: Body, b: Body): Manifold? {
        val dx = b.cx - a.cx
        val dy = b.cy - a.cy
        val rs = a.r + b.r
        val d2 = dx * dx + dy * dy
        if (d2 >= rs * rs) return null
        val d = sqrt(d2)
        return if (d < 1e-5f) Manifold(0f, 1f, rs) else Manifold(dx / d, dy / d, rs - d)
    }

    private fun boxCircle(box: Body, c: Body): Manifold? {
        val px = (c.cx).coerceIn(box.cx - box.hw, box.cx + box.hw)
        val py = (c.cy).coerceIn(box.cy - box.hh, box.cy + box.hh)
        var dx = c.cx - px
        var dy = c.cy - py
        val d2 = dx * dx + dy * dy
        if (d2 > c.r * c.r) return null
        if (d2 < 1e-8f) {
            // centre inside box: push out along smallest axis
            val ox = box.hw - abs(c.cx - box.cx)
            val oy = box.hh - abs(c.cy - box.cy)
            return if (ox < oy) Manifold(if (c.cx < box.cx) -1f else 1f, 0f, ox + c.r)
            else Manifold(0f, if (c.cy < box.cy) -1f else 1f, oy + c.r)
        }
        val d = sqrt(d2)
        dx /= d; dy /= d
        return Manifold(dx, dy, c.r - d)
    }

    private fun resolve(a: Body, b: Body, m: Manifold) {
        val ia = a.invMass
        val ib = b.invMass
        val sum = ia + ib
        if (sum == 0f) return
        // positional correction
        val corr = max(m.depth - 0.001f, 0f) / sum * 0.9f
        if (ia > 0f) moveWorld(a.go, -m.nx * corr * ia, -m.ny * corr * ia)
        if (ib > 0f) moveWorld(b.go, m.nx * corr * ib, m.ny * corr * ib)

        // grounded flags (normal a->b pointing down means a is on top of b)
        if (m.ny < -0.5f) a.rb?.grounded = true
        if (m.ny > 0.5f) b.rb?.grounded = true

        val avx = a.rb?.takeIf { it.bodyType != 2 }?.vx ?: 0f
        val avy = a.rb?.takeIf { it.bodyType != 2 }?.vy ?: 0f
        val bvx = b.rb?.takeIf { it.bodyType != 2 }?.vx ?: 0f
        val bvy = b.rb?.takeIf { it.bodyType != 2 }?.vy ?: 0f
        val rvx = bvx - avx
        val rvy = bvy - avy
        val vn = rvx * m.nx + rvy * m.ny
        if (vn > 0f) return
        val e = max(a.rb?.bounciness ?: 0f, b.rb?.bounciness ?: 0f)
        val j = -(1f + e) * vn / sum
        applyImpulse(a, -j * m.nx, -j * m.ny)
        applyImpulse(b, j * m.nx, j * m.ny)

        // friction
        val tx = -m.ny
        val ty = m.nx
        val vt = rvx * tx + rvy * ty
        val mu = sqrt((a.rb?.friction ?: 0.4f) * (b.rb?.friction ?: 0.4f))
        var jt = -vt / sum
        val maxF = j * mu
        jt = jt.coerceIn(-maxF, maxF)
        applyImpulse(a, -jt * tx, -jt * ty)
        applyImpulse(b, jt * tx, jt * ty)
    }

    private fun applyImpulse(b: Body, ix: Float, iy: Float) {
        val inv = b.invMass
        if (inv == 0f) return
        val rb = b.rb ?: return
        rb.vx += ix * inv
        rb.vy += iy * inv
    }

    /** Returns first active object whose collider contains the world point. */
    fun overlapPoint(scene: Scene, x: Float, y: Float): GameObject? {
        for (go in scene.objects.asReversed()) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            val b = Body(go, null, col)
            refresh(b)
            val hit = if (b.isCircle) {
                val dx = x - b.cx; val dy = y - b.cy; dx * dx + dy * dy <= b.r * b.r
            } else abs(x - b.cx) <= b.hw && abs(y - b.cy) <= b.hh
            if (hit) return go
        }
        return null
    }
}
