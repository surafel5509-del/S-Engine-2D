package com.sengine.engine.physics

import com.sengine.engine.core.Area2D
import com.sengine.engine.core.BodyType
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.ColliderShape
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.RayCast2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.math.M
import com.sengine.engine.math.Vec2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Impulse based 2D physics with rotation-aware collision detection.
 *
 * Features
 *  * box / circle / polygon shapes, transformed by the node's world matrix (rotation included)
 *  * static, kinematic, dynamic and character bodies
 *  * collision layers + masks
 *  * one-way platforms, triggers and [Area2D] overlap reporting
 *  * contact/trigger enter & exit events for scripts
 *  * ray casts, point/rect/circle overlap queries, shape sweeps
 *  * tile collision generated from painted, collision-enabled tiles
 *  * deterministic fixed timestep with sleeping bodies
 *  * debug data (contacts + shapes) for the physics overlay
 */
class PhysicsWorld {

    interface Listener {
        fun onCollisionEnter(a: GameObject, b: GameObject) {}
        fun onCollisionExit(a: GameObject, b: GameObject) {}
        fun onTriggerEnter(a: GameObject, b: GameObject) {}
        fun onTriggerExit(a: GameObject, b: GameObject) {}
        fun onAreaEnter(area: GameObject, other: GameObject) {}
        fun onAreaExit(area: GameObject, other: GameObject) {}
    }

    var listener: Listener? = null

    /** Fixed simulation step. Games can raise it for high-speed action, lower it for cost. */
    var fixedDt = 1f / 60f
    var maxSubSteps = 5
    var gravityX = 0f
    var gravityY = -9.81f

    /** Scale applied to the scene gravity, exposed as a project setting. */
    var gravityScale = 1f

    private var accumulator = 0f

    // ------------------------------------------------------------------ bodies
    class Body {
        lateinit var go: GameObject
        var rb: Rigidbody2D? = null
        var col: Collider2D? = null
        var area: Area2D? = null

        val isArea get() = area != null
        val isTrigger get() = col?.isTrigger == true

        var cx = 0f; var cy = 0f
        var rotation = 0f
        var scaleX = 1f; var scaleY = 1f

        /** World-space vertices for polygon/box shapes (x,y pairs). */
        var worldPoints = FloatArray(0)
        var radius = 0f
        var hw = 0f; var hh = 0f
        var shape = ColliderShape.BOX

        var invMass = 0f
        var friction = 0.4f
        var restitution = 0f
        var layer = 1
        var mask = 0xFFFF
        var oneWay = false
        var isTile = false

        /** Cached AABB for the broad phase. */
        var minX = 0f; var maxX = 0f; var minY = 0f; var maxY = 0f

        val isStatic get() = invMass == 0f && !isTrigger

        fun aabb() = floatArrayOf(minX, minY, maxX, maxY)
    }

    private val bodies = ArrayList<Body>(128)
    private val activeBodies = ArrayList<Body>(128)
    private val tileBodies = ArrayList<Body>(512)

    private var prevContacts = HashSet<Long>()
    private var prevTriggers = HashSet<Long>()

    /** Debug output consumed by the renderer overlay. */
    val debugContacts = ArrayList<FloatArray>(64)
    val debugShapes = ArrayList<FloatArray>(128)

    var bodyCount = 0
        private set
    var tileColliderCount = 0
        private set

    private var tileSignature = 0L

    fun reset() {
        accumulator = 0f
        prevContacts = HashSet()
        prevTriggers = HashSet()
        tileBodies.clear()
        tileSignature = 0L
        debugContacts.clear()
        contactCount = 0
    }

    var contactCount = 0
        private set

    // ------------------------------------------------------------------ step
    fun step(scene: Scene, dt: Float) {
        gravityX = scene.settings.gravityX * gravityScale
        gravityY = scene.settings.gravityY * gravityScale
        rebuildTiles(scene)
        accumulator += min(dt, 0.25f)
        var steps = 0
        while (accumulator >= fixedDt && steps < maxSubSteps) {
            fixedStep(scene, fixedDt)
            accumulator -= fixedDt
            steps++
        }
        if (steps >= maxSubSteps) accumulator = 0f
    }

    private fun fixedStep(scene: Scene, dt: Float) {
        gatherBodies(scene)
        val moving = ArrayList<Body>(16)
        for (b in bodies) {
            val rb = b.rb
            if (rb == null || b.isArea) continue
            b.go.getAny<RayCast2D>()?.let { updateRay(scene, b.go, it) }
            when (rb.bodyType) {
                BodyType.DYNAMIC -> {
                    if (rb.sleeping) {
                        if (abs(rb.vx) < SLEEP_SPEED && abs(rb.vy) < SLEEP_SPEED) continue
                        rb.sleeping = false
                    }
                    rb.vx += gravityX * rb.gravityScale * dt
                    rb.vy += gravityY * rb.gravityScale * dt
                    if (rb.drag > 0f) {
                        val k = max(0f, 1f - rb.drag * dt)
                        rb.vx *= k; rb.vy *= k
                    }
                    rb.grounded = false
                    rb.onWall = 0
                    rb.onCeiling = false
                    integrateBody(b, rb.vx * dt, rb.vy * dt, dt)
                    moving.add(b)
                }
                BodyType.KINEMATIC -> {
                    rb.grounded = false
                    integrateBody(b, rb.vx * dt, rb.vy * dt, dt)
                    moving.add(b)
                }
                BodyType.CHARACTER -> {
                    rb.grounded = false
                    rb.onWall = 0
                    rb.onCeiling = false
                    integrateBody(b, rb.vx * dt, rb.vy * dt, dt)
                    moving.add(b)
                }
                else -> {}
            }
        }

        // broad + narrow phase
        val contacts = HashSet<Long>()
        val triggers = HashSet<Long>()
        contactCount = 0
        debugContacts.clear()
        for (i in 0 until bodies.size) {
            val a = bodies[i]
            for (j in i + 1 until bodies.size) {
                val b = bodies[j]
                if (!canCollide(a, b)) continue
                if (!aabbOverlap(a, b)) continue
                val m = collide(a, b) ?: continue
                val key = pairKey(a.go.id, b.go.id)
                val trigger = a.isArea || b.isArea || a.isTrigger || b.isTrigger
                if (trigger) {
                    triggers.add(key)
                    if (key !in prevTriggers) {
                        if (a.isArea) {
                            registerArea(a, b, true)
                            listener?.onAreaEnter(a.go, b.go)
                        }
                        if (b.isArea) {
                            registerArea(b, a, true)
                            listener?.onAreaEnter(b.go, a.go)
                        }
                        if (!a.isArea && !b.isArea) listener?.onTriggerEnter(a.go, b.go)
                    }
                    continue
                }
                contacts.add(key)
                if (key !in prevContacts) listener?.onCollisionEnter(a.go, b.go)
                resolve(a, b, m)
                contactCount++
                if (debugContacts.size < 256) {
                    debugContacts.add(floatArrayOf(m.px, m.py, m.nx, m.ny))
                }
            }
        }
        // tile collisions
        for (b in moving) {
            for (tile in tileBodies) {
                if (!aabbOverlap(b, tile)) continue
                if (b.oneWay && !tile.oneWay) continue
                val m = collide(b, tile) ?: continue
                if (tile.oneWay && b.rb != null && b.rb!!.vy > 0f) continue
                if (b.oneWay && b.rb != null) {
                    // one-way platforms only collide from above
                    val feet = b.minY
                    if (feet < tile.maxY - 0.02f) continue
                }
                contacts.add(pairKey(b.go.id, -1L))
                resolve(b, tile, m)
                contactCount++
                // Same convention as body/body: the normal points from the body towards the tile.
                if (b.rb != null) {
                    if (m.ny < -0.5f) b.rb!!.grounded = true
                    if (m.ny > 0.5f) b.rb!!.onCeiling = true
                    if (abs(m.nx) > 0.5f) b.rb!!.onWall = if (m.nx > 0) 1 else -1
                }
                if (debugContacts.size < 256) debugContacts.add(floatArrayOf(m.px, m.py, m.nx, m.ny))
            }
        }

        for (k in prevTriggers) if (k !in triggers) {
            val a = scene.findById(k ushr 32)
            val b = scene.findById(k and 0xFFFFFFFFL)
            if (a != null && b != null) {
                val aArea = a.getAny<Area2D>()
                val bArea = b.getAny<Area2D>()
                if (aArea != null) {
                    aArea.overlapping.remove(b)
                    listener?.onAreaExit(a, b)
                }
                if (bArea != null) {
                    bArea.overlapping.remove(a)
                    listener?.onAreaExit(b, a)
                }
                if (aArea == null && bArea == null) listener?.onTriggerExit(a, b)
            }
        }
        for (k in prevContacts) if (k !in contacts) {
            if (k and 0xFFFFFFFFL == 0xFFFFFFFFL) continue
            val a = scene.findById(k ushr 32)
            val b = scene.findById(k and 0xFFFFFFFFL)
            if (a != null && b != null) listener?.onCollisionExit(a, b)
        }
        prevContacts = contacts
        prevTriggers = triggers

        // sleeping: park slow bodies so large scenes stay cheap
        for (b in moving) {
            val rb = b.rb ?: continue
            if (rb.bodyType != BodyType.DYNAMIC || rb.sleeping) continue
            if (abs(rb.vx) < SLEEP_SPEED && abs(rb.vy) < SLEEP_SPEED && rb.grounded) {
                rb.sleepCounter += dt
                if (rb.sleepCounter > SLEEP_TIME) {
                    rb.sleeping = true
                    rb.vx = 0f; rb.vy = 0f
                }
            } else rb.sleepCounter = 0f
        }
    }

    private fun registerArea(area: Body, other: Body, entering: Boolean) {
        val a = area.area ?: return
        if (entering) {
            if (!a.overlapping.contains(other.go)) a.overlapping.add(other.go)
        } else a.overlapping.remove(other.go)
    }

    private fun gatherBodies(scene: Scene) {
        bodies.clear()
        activeBodies.clear()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>()
            val area = go.get<Area2D>()
            if (col == null && area == null) continue
            val b = Body()
            b.go = go
            b.rb = go.get<Rigidbody2D>()
            b.col = col
            b.area = area
            if (col != null) {
                b.shape = col.shape
                b.friction = if (col.friction >= 0f) col.friction else (b.rb?.friction ?: 0.4f)
                b.restitution = if (col.restitution >= 0f) col.restitution else (b.rb?.restitution ?: 0f)
                b.oneWay = col.oneWay || (b.rb?.oneWayPlatform ?: false)
                b.layer = if (col.layerOverride != 0) col.layerOverride else (b.rb?.collisionLayer ?: 1)
                b.mask = if (col.maskOverride >= 0) col.maskOverride else (b.rb?.collisionMask ?: 0xFFFF)
            } else if (area != null) {
                b.shape = area.shape
                b.friction = 0.2f
                b.layer = area.collisionLayer
                b.mask = area.collisionMask
            }
            val rb = b.rb
            b.invMass = when {
                area != null -> 0f
                rb == null -> 0f
                rb.bodyType == BodyType.DYNAMIC -> 1f / max(rb.mass, 0.001f)
                else -> 0f
            }
            refresh(b)
            bodies.add(b)
            if (b.invMass > 0f || (rb != null && rb.bodyType != BodyType.STATIC)) activeBodies.add(b)
        }
        bodyCount = bodies.size
        debugShapes.clear()
        for (b in bodies) debugShapes.add(shapeDebugArray(b))
    }

    // ------------------------------------------------------------------ shape refresh
    private fun refresh(b: Body) {
        val world = b.go.computeWorld()
        b.cx = world.tx
        b.cy = world.ty
        b.rotation = world.rotationDeg
        b.scaleX = world.scaleX
        b.scaleY = world.scaleY
        val offX: Float
        val offY: Float
        when {
            b.col != null -> { offX = b.col!!.offsetX; offY = b.col!!.offsetY }
            b.area != null -> { offX = b.area!!.offsetX; offY = b.area!!.offsetY }
            else -> { offX = 0f; offY = 0f }
        }
        val ox = world.mapX(offX, offY)
        val oy = world.mapY(offX, offY)
        b.cx = ox
        b.cy = oy
        when (b.shape) {
            ColliderShape.CIRCLE -> {
                b.radius = (if (b.col != null) b.col!!.radius else b.area!!.radius) * max(world.scaleX, world.scaleY)
                b.hw = b.radius; b.hh = b.radius
                b.worldPoints = FloatArray(0)
            }
            ColliderShape.POLYGON -> {
                val pts = if (b.col != null) b.col!!.polygonPoints() else {
                    val a = b.area!!
                    floatArrayOf(-a.width / 2, -a.height / 2, a.width / 2, -a.height / 2, a.width / 2, a.height / 2, -a.width / 2, a.height / 2)
                }
                b.worldPoints = transformPoints(world, pts)
                val box = boundingBox(b.worldPoints)
                b.hw = (box[2] - box[0]) * 0.5f
                b.hh = (box[3] - box[1]) * 0.5f
            }
            else -> {
                val w = if (b.col != null) b.col!!.width else b.area!!.width
                val h = if (b.col != null) b.col!!.height else b.area!!.height
                b.hw = abs(w * world.scaleX) * 0.5f
                b.hh = abs(h * world.scaleY) * 0.5f
                b.worldPoints = transformPoints(
                    world,
                    floatArrayOf(-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2)
                )
            }
        }
        computeAabb(b)
    }

    private fun transformPoints(world: com.sengine.engine.math.Affine, pts: FloatArray): FloatArray {
        val out = FloatArray(pts.size)
        var i = 0
        while (i + 1 < pts.size) {
            out[i] = world.mapX(pts[i], pts[i + 1])
            out[i + 1] = world.mapY(pts[i], pts[i + 1])
            i += 2
        }
        return out
    }

    private fun boundingBox(points: FloatArray): FloatArray {
        if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < points.size) {
            minX = min(minX, points[i]); maxX = max(maxX, points[i])
            minY = min(minY, points[i + 1]); maxY = max(maxY, points[i + 1])
            i += 2
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun computeAabb(b: Body) {
        when (b.shape) {
            ColliderShape.CIRCLE -> {
                b.minX = b.cx - b.radius; b.maxX = b.cx + b.radius
                b.minY = b.cy - b.radius; b.maxY = b.cy + b.radius
            }
            else -> {
                val box = boundingBox(b.worldPoints)
                b.minX = box[0]; b.minY = box[1]; b.maxX = box[2]; b.maxY = box[3]
            }
        }
    }

    private fun shapeDebugArray(b: Body): FloatArray {
        // x, y, hw, hh, rotationDeg, shape, radius
        return floatArrayOf(b.cx, b.cy, b.hw, b.hh, b.rotation, b.shape.toFloat(), b.radius)
    }

    // ------------------------------------------------------------------ collision
    private fun canCollide(a: Body, b: Body): Boolean {
        if (a.go === b.go) return false
        if (a.isArea && b.isArea && !(a.area!!.monitorAreas || b.area!!.monitorAreas)) return false
        if (a.isArea && !b.isArea && !a.area!!.monitorBodies) return false
        if (b.isArea && !a.isArea && !b.area!!.monitorBodies) return false
        if (a.layer and b.mask == 0) return false
        if (b.layer and a.mask == 0) return false
        if (a.invMass == 0f && b.invMass == 0f && !a.isArea && !b.isArea &&
            !a.isTrigger && !b.isTrigger && a.rb?.bodyType != BodyType.CHARACTER && b.rb?.bodyType != BodyType.CHARACTER
        ) {
            // both static and neither is a sensor -> nothing to do
            if (a.rb == null && b.rb == null) return false
        }
        return true
    }

    private fun aabbOverlap(a: Body, b: Body) =
        a.minX < b.maxX && a.maxX > b.minX && a.minY < b.maxY && a.maxY > b.minY

    class Manifold {
        var nx = 0f; var ny = 0f
        var depth = 0f
        var px = 0f; var py = 0f
    }

    private val manifoldPool = ArrayDeque<Manifold>()

    private fun manifold(): Manifold = if (manifoldPool.isEmpty()) Manifold() else manifoldPool.removeFirst()

    private fun recycle(m: Manifold) {
        if (manifoldPool.size < 64) manifoldPool.addLast(m)
    }

    /** Narrow phase. Geometry uses world-space polygons, so rotated boxes collide correctly. */
    private fun collide(a: Body, b: Body): Manifold? {
        val result = when {
            a.shape == ColliderShape.CIRCLE && b.shape == ColliderShape.CIRCLE -> circleCircle(a, b)
            a.shape == ColliderShape.CIRCLE && b.shape != ColliderShape.CIRCLE -> circlePolygon(a, b)
            a.shape != ColliderShape.CIRCLE && b.shape == ColliderShape.CIRCLE -> circlePolygon(b, a)?.also {
                it.nx = -it.nx; it.ny = -it.ny
            }
            else -> polygonPolygon(a, b)
        }
        return result
    }

    private fun circleCircle(a: Body, b: Body): Manifold? {
        val dx = b.cx - a.cx
        val dy = b.cy - a.cy
        val rs = a.radius + b.radius
        val d2 = dx * dx + dy * dy
        if (d2 >= rs * rs) return null
        val d = sqrt(d2)
        val m = manifold()
        if (d < 1e-5f) {
            m.nx = 0f; m.ny = 1f; m.depth = rs
        } else {
            m.nx = dx / d; m.ny = dy / d; m.depth = rs - d
        }
        m.px = a.cx + m.nx * a.radius
        m.py = a.cy + m.ny * a.radius
        return m
    }

    /** Normal points from circle to polygon. */
    private fun circlePolygon(circle: Body, poly: Body): Manifold? {
        val pts = poly.worldPoints
        if (pts.size < 6) return null
        var bestDist = Float.MAX_VALUE
        var bestX = 0f
        var bestY = 0f
        var inside = true
        val n = pts.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val ax = pts[j * 2]; val ay = pts[j * 2 + 1]
            val bx = pts[i * 2]; val by = pts[i * 2 + 1]
            val ex = bx - ax; val ey = by - ay
            val len2 = ex * ex + ey * ey
            val t = if (len2 < 1e-9f) 0f else M.clamp(((circle.cx - ax) * ex + (circle.cy - ay) * ey) / len2, 0f, 1f)
            val px = ax + ex * t
            val py = ay + ey * t
            val dx = circle.cx - px
            val dy = circle.cy - py
            val d2 = dx * dx + dy * dy
            if (d2 < bestDist) {
                bestDist = d2
                bestX = px; bestY = py
            }
            // point-in-polygon test (ray cast)
            if ((ay > circle.cy) != (by > circle.cy) && circle.cx < (bx - ax) * (circle.cy - ay) / (by - ay + 1e-9f) + ax) inside = !inside
            j = i
        }
        val dist = sqrt(bestDist)
        if (!inside && dist > circle.radius) return null
        val m = manifold()
        if (dist > 1e-5f) {
            m.nx = (bestX - circle.cx) / dist
            m.ny = (bestY - circle.cy) / dist
            m.depth = circle.radius - dist
        } else {
            m.nx = 0f; m.ny = 1f; m.depth = circle.radius
        }
        if (inside) m.depth = circle.radius + dist
        m.px = bestX; m.py = bestY
        return m
    }

    /** SAT for convex polygons. Normal points from a to b. */
    private fun polygonPolygon(a: Body, b: Body): Manifold? {
        val pa = a.worldPoints
        val pb = b.worldPoints
        if (pa.size < 6 || pb.size < 6) return null
        var bestDepth = Float.MAX_VALUE
        var bestNx = 0f
        var bestNy = 0f
        for (pass in 0..1) {
            val poly = if (pass == 0) pa else pb
            val count = poly.size / 2
            for (i in 0 until count) {
                val j = (i + 1) % count
                var nx = -(poly[j * 2 + 1] - poly[i * 2 + 1])
                var ny = poly[j * 2] - poly[i * 2]
                val len = sqrt(nx * nx + ny * ny)
                if (len < 1e-6f) continue
                nx /= len; ny /= len
                // project both polygons
                val (minA, maxA) = project(pa, nx, ny)
                val (minB, maxB) = project(pb, nx, ny)
                val overlap = min(maxA, maxB) - max(minA, minB)
                if (overlap <= 0f) return null
                if (overlap < bestDepth) {
                    bestDepth = overlap
                    // orient the normal from a to b
                    val dirX = b.cx - a.cx
                    val dirY = b.cy - a.cy
                    if (nx * dirX + ny * dirY < 0f) { nx = -nx; ny = -ny }
                    bestNx = nx; bestNy = ny
                }
            }
        }
        val m = manifold()
        m.nx = bestNx; m.ny = bestNy; m.depth = bestDepth
        m.px = (a.cx + b.cx) * 0.5f
        m.py = (a.cy + b.cy) * 0.5f
        return m
    }

    private fun project(pts: FloatArray, nx: Float, ny: Float): Pair<Float, Float> {
        var minP = Float.MAX_VALUE
        var maxP = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            val p = pts[i] * nx + pts[i + 1] * ny
            minP = min(minP, p); maxP = max(maxP, p)
            i += 2
        }
        return minP to maxP
    }

    // ------------------------------------------------------------------ resolution
    private fun resolve(a: Body, b: Body, m: Manifold) {
        val ia = if (b.isArea) 0f else a.invMass
        val ib = if (a.isArea || b.isTile) 0f else b.invMass
        val sum = ia + ib
        if (sum <= 0f) {
            recycle(m)
            return
        }
        val correctionScale = 0.9f
        val slop = 0.005f
        val corr = max(m.depth - slop, 0f) / sum * correctionScale
        if (ia > 0f) moveWorld(a.go, -m.nx * corr * ia, -m.ny * corr * ia)
        if (ib > 0f) moveWorld(b.go, m.nx * corr * ib, m.ny * corr * ib)

        // The manifold normal points from a to b, so a body is "grounded" when its partner is below
        // it (the normal towards it points up) and "on ceiling" when the partner is above it.
        if (a.rb != null) {
            if (m.ny < -0.5f) a.rb!!.grounded = true
            if (m.ny > 0.5f) a.rb!!.onCeiling = true
            if (abs(m.nx) > 0.5f) a.rb!!.onWall = if (m.nx > 0) 1 else -1   // +1 wall on the right
        }
        if (b.rb != null) {
            if (m.ny > 0.5f) b.rb!!.grounded = true
            if (m.ny < -0.5f) b.rb!!.onCeiling = true
            if (abs(m.nx) > 0.5f) b.rb!!.onWall = if (m.nx > 0) -1 else 1
        }
        // A resting body must be allowed to fall asleep: only an awake moving neighbour wakes it.
        val bAwake = b.rb != null && b.rb!!.bodyType == BodyType.DYNAMIC && !b.rb!!.sleeping
        val aAwake = a.rb != null && a.rb!!.bodyType == BodyType.DYNAMIC && !a.rb!!.sleeping
        if (a.rb != null && a.rb!!.bodyType == BodyType.DYNAMIC && bAwake) {
            a.rb!!.sleeping = false
            a.rb!!.sleepCounter = 0f
        }
        if (b.rb != null && b.rb!!.bodyType == BodyType.DYNAMIC && aAwake) {
            b.rb!!.sleeping = false
            b.rb!!.sleepCounter = 0f
        }

        val avx = a.rb?.takeIf { it.bodyType != BodyType.STATIC }?.vx ?: 0f
        val avy = a.rb?.takeIf { it.bodyType != BodyType.STATIC }?.vy ?: 0f
        val bvx = b.rb?.takeIf { it.bodyType != BodyType.STATIC }?.vx ?: 0f
        val bvy = b.rb?.takeIf { it.bodyType != BodyType.STATIC }?.vy ?: 0f
        val rvx = bvx - avx
        val rvy = bvy - avy
        val vn = rvx * m.nx + rvy * m.ny
        if (vn > 0f) {
            recycle(m)
            return
        }
        val e = if (abs(vn) < 0.6f) 0f else max(a.restitution, b.restitution)
        val j = -(1f + e) * vn / sum
        applyImpulse(a, -j * m.nx, -j * m.ny)
        applyImpulse(b, j * m.nx, j * m.ny)

        // friction
        val tx = -m.ny
        val ty = m.nx
        val vt = rvx * tx + rvy * ty
        val mu = sqrt(max(0f, a.friction) * max(0f, b.friction))
        var jt = -vt / sum
        val maxFriction = abs(j) * mu
        jt = jt.coerceIn(-maxFriction, maxFriction)
        applyImpulse(a, -jt * tx, -jt * ty)
        applyImpulse(b, jt * tx, jt * ty)
        if (a.rb != null) a.rb!!.lastContact = b.go
        if (b.rb != null) b.rb!!.lastContact = a.go
        recycle(m)
    }

    private fun applyImpulse(b: Body, ix: Float, iy: Float) {
        if (b.invMass == 0f) return
        val rb = b.rb ?: return
        if (rb.bodyType != BodyType.DYNAMIC) return
        rb.vx += ix * b.invMass
        rb.vy += iy * b.invMass
    }

    /**
     * Moves a body by (dx,dy) for this step.
     *
     * Bodies flagged [Rigidbody2D.continuous] are swept in sub-steps bounded by their own size
     * instead of teleporting by `velocity * dt`: a fast bullet, dash or falling platform can then
     * never pass through a thin wall. Sub-steps are capped so the cost stays bounded.
     */
    private fun integrateBody(b: Body, dx: Float, dy: Float, dt: Float) {
        val rb = b.rb
        if (rb == null || !rb.continuous || (dx == 0f && dy == 0f)) {
            integrate(b, dx, dy, dt)
            return
        }
        val extent = if (b.shape == ColliderShape.CIRCLE) b.radius * 2f else minOf(b.hw, b.hh) * 2f
        val bound = (extent * 0.75f).coerceAtLeast(0.02f)
        val steps = ((sqrt(dx * dx + dy * dy) / bound).toInt() + 1).coerceIn(1, MAX_CCD_STEPS)
        val sx = dx / steps
        val sy = dy / steps
        for (i in 0 until steps) {
            integrate(b, sx, sy, dt / steps)
            if (sweptIntoGeometry(b)) {
                // step back to the last free position and stop: the contact solver separates the rest
                moveWorld(b.go, -sx, -sy)
                refresh(b)
                rb.vx = 0f
                rb.vy = 0f
                return
            }
        }
    }

    /** True when the body overlaps solid geometry (used by the continuous sweep). */
    private fun sweptIntoGeometry(b: Body): Boolean {
        for (o in bodies) {
            if (o === b || o.isArea || o.isTrigger) continue
            if (!canCollide(b, o)) continue
            if (overlapsAabb(b, o)) return true
        }
        for (t in tileBodies) if (overlapsAabb(b, t)) return true
        return false
    }

    private fun overlapsAabb(a: Body, b: Body): Boolean =
        a.minX < b.maxX && a.maxX > b.minX && a.minY < b.maxY && a.maxY > b.minY

    private fun integrate(b: Body, dx: Float, dy: Float, dt: Float) {
        val rb = b.rb ?: return
        if (b.go.parent == null) {
            b.go.x += dx; b.go.y += dy
        } else {
            val world = b.go.computeWorld()
            b.go.setWorldPosition(world.tx + dx, world.ty + dy)
        }
        refresh(b)
    }

    private fun moveWorld(go: GameObject, dx: Float, dy: Float) {
        if (go.parent == null) {
            go.x += dx; go.y += dy
        } else {
            val w = go.computeWorld()
            go.setWorldPosition(w.tx + dx, w.ty + dy)
        }
    }

    private fun pairKey(a: Long, b: Long): Long {
        val lo = min(a, b)
        val hi = max(a, b)
        return (lo shl 32) or (hi and 0xFFFFFFFFL)
    }

    // ------------------------------------------------------------------ tile collision
    private fun rebuildTiles(scene: Scene) {
        var signature = scene.structureRevision.toLong()
        for (go in scene.objects) {
            val tm = go.getAny<TileMap2D>() ?: continue
            signature = signature * 31 + tm.data.layers.size + tm.tileWidth + tm.tileHeight
        }
        if (signature == tileSignature && tileSignature != 0L) return
        tileSignature = signature
        tileBodies.clear()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val tm = go.getAny<TileMap2D>() ?: continue
            val world = go.computeWorld()
            val scale = if (tm.pixelsPerUnit > 0f) 1f / tm.pixelsPerUnit else 1f
            for (layer in tm.data.layers) {
                if (!layer.collision) continue
                for (y in 0 until layer.height) {
                    for (x in 0 until layer.width) {
                        val id = layer[x, y]
                        if (id == 0) continue
                        val def = tm.runtimeTileSet?.tile(id) ?: continue
                        if (def.collision == 0) continue
                        val body = Body()
                        body.go = go
                        body.isTile = true
                        body.invMass = 0f
                        body.friction = 0.6f
                        body.layer = 2
                        body.mask = 0xFFFF
                        body.oneWay = layer.parallax <= 0f
                        body.shape = when (def.collision) {
                            2 -> ColliderShape.CIRCLE
                            3 -> ColliderShape.POLYGON
                            else -> ColliderShape.BOX
                        }
                        val cxLocal = (x + 0.5f) * tm.tileWidth * scale
                        val cyLocal = (y + 0.5f) * tm.tileHeight * scale
                        val cx = world.mapX(cxLocal, cyLocal)
                        val cy = world.mapY(cxLocal, cyLocal)
                        body.cx = cx; body.cy = cy
                        body.rotation = world.rotationDeg
                        if (body.shape == ColliderShape.CIRCLE) {
                            body.radius = tm.tileWidth * scale * 0.5f * max(world.scaleX, world.scaleY)
                            body.hw = body.radius; body.hh = body.radius
                        } else if (body.shape == ColliderShape.POLYGON && def.points.size >= 6) {
                            body.worldPoints = transformPoints(world, def.points)
                            val box = boundingBox(body.worldPoints)
                            body.hw = (box[2] - box[0]) * 0.5f; body.hh = (box[3] - box[1]) * 0.5f
                        } else {
                            body.hw = tm.tileWidth * scale * world.scaleX * 0.5f
                            body.hh = tm.tileHeight * scale * world.scaleY * 0.5f
                            body.worldPoints = floatArrayOf(
                                cx - body.hw, cy - body.hh, cx + body.hw, cy - body.hh,
                                cx + body.hw, cy + body.hh, cx - body.hw, cy + body.hh
                            )
                        }
                        computeAabb(body)
                        tileBodies.add(body)
                    }
                }
            }
        }
        tileColliderCount = tileBodies.size
    }

    // ------------------------------------------------------------------ queries
    class RayHit {
        var node: GameObject? = null
        var x = 0f; var y = 0f
        var nx = 0f; var ny = 0f
        var distance = 0f
        val hit get() = node != null
    }

    private val rayHitPool = ArrayDeque<RayHit>()

    fun obtainHit(): RayHit = if (rayHitPool.isEmpty()) RayHit() else rayHitPool.removeFirst()

    fun recycle(hit: RayHit) {
        if (rayHitPool.size < 32) rayHitPool.addLast(hit)
    }

    fun raycast(scene: Scene, ox: Float, oy: Float, dx: Float, dy: Float, length: Float, mask: Int = 0xFFFF, includeTriggers: Boolean = false): RayHit? {
        val hit = RayHit()
        var best = length
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return null
        val nx = dx / len; val ny = dy / len
        gatherBodies(scene)
        for (b in bodies) {
            if (b.layer and mask == 0) continue
            if (b.isTrigger && !includeTriggers) continue
            val t = rayShape(nx, ny, ox, oy, b, best) ?: continue
            if (t < best) {
                best = t
                hit.node = b.go
                hit.distance = t
                hit.x = ox + nx * t
                hit.y = oy + ny * t
                hit.nx = -nx; hit.ny = -ny
            }
        }
        for (b in tileBodies) {
            if (b.layer and mask == 0) continue
            val t = rayShape(nx, ny, ox, oy, b, best) ?: continue
            if (t < best) {
                best = t
                hit.node = b.go
                hit.distance = t
                hit.x = ox + nx * t
                hit.y = oy + ny * t
                hit.nx = -nx; hit.ny = -ny
            }
        }
        return if (hit.node != null) hit else null
    }

    private fun rayShape(nx: Float, ny: Float, ox: Float, oy: Float, b: Body, maxDist: Float): Float? {
        return if (b.shape == ColliderShape.CIRCLE) rayCircle(ox, oy, nx, ny, b.cx, b.cy, b.radius, maxDist)
        else rayPolygon(ox, oy, nx, ny, b.worldPoints, maxDist)
    }

    private fun rayCircle(ox: Float, oy: Float, nx: Float, ny: Float, cx: Float, cy: Float, r: Float, maxDist: Float): Float? {
        val fx = ox - cx
        val fy = oy - cy
        val a = nx * nx + ny * ny
        val bq = 2f * (fx * nx + fy * ny)
        val c = fx * fx + fy * fy - r * r
        val disc = bq * bq - 4f * a * c
        if (disc < 0f) return null
        val sq = sqrt(disc)
        val t1 = (-bq - sq) / (2f * a)
        val t2 = (-bq + sq) / (2f * a)
        val t = if (t1 >= 0f) t1 else t2
        return if (t in 0f..maxDist) t else null
    }

    private fun rayPolygon(ox: Float, oy: Float, nx: Float, ny: Float, pts: FloatArray, maxDist: Float): Float? {
        if (pts.size < 6) return null
        var best: Float? = null
        val n = pts.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val bx = pts[j * 2]; val by = pts[j * 2 + 1]
            val ex = bx - ax; val ey = by - ay
            val denom = nx * ey - ny * ex
            if (abs(denom) < 1e-9f) continue
            val t = ((ax - ox) * ey - (ay - oy) * ex) / denom
            val u = ((ax - ox) * ny - (ay - oy) * nx) / denom
            if (t >= 0f && u in 0f..1f && t <= maxDist) {
                if (best == null || t < best!!) best = t
            }
        }
        return best
    }

    /** True when a world point is inside any collider (used by tap picking and scripts). */
    fun overlapPoint(scene: Scene, x: Float, y: Float, mask: Int = 0xFFFF): GameObject? {
        gatherBodies(scene)
        for (b in bodies.asReversed()) {
            if (b.layer and mask == 0) continue
            val inside = when (b.shape) {
                ColliderShape.CIRCLE -> {
                    val dx = x - b.cx; val dy = y - b.cy
                    dx * dx + dy * dy <= b.radius * b.radius
                }
                ColliderShape.BOX -> abs(x - b.cx) <= b.hw && abs(y - b.cy) <= b.hh
                else -> M.pointInPolygon(x, y, b.worldPoints)
            }
            if (inside) return b.go
        }
        return null
    }

    fun overlapCircle(scene: Scene, x: Float, y: Float, r: Float, mask: Int = 0xFFFF): List<GameObject> {
        gatherBodies(scene)
        val out = ArrayList<GameObject>()
        for (b in bodies) {
            if (b.layer and mask == 0) continue
            val inside = when (b.shape) {
                ColliderShape.CIRCLE -> {
                    val dx = x - b.cx; val dy = y - b.cy
                    val rr = r + b.radius
                    dx * dx + dy * dy <= rr * rr
                }
                else -> {
                    val cx = M.clamp(x, b.minX, b.maxX)
                    val cy = M.clamp(y, b.minY, b.maxY)
                    val dx = x - cx; val dy = y - cy
                    dx * dx + dy * dy <= r * r
                }
            }
            if (inside) out.add(b.go)
        }
        return out
    }

    fun overlapRect(scene: Scene, x: Float, y: Float, w: Float, h: Float, mask: Int = 0xFFFF): List<GameObject> {
        gatherBodies(scene)
        val out = ArrayList<GameObject>()
        val minX = x; val maxX = x + w
        val minY = y; val maxY = y + h
        for (b in bodies) {
            if (b.layer and mask == 0) continue
            if (b.minX < maxX && b.maxX > minX && b.minY < maxY && b.maxY > minY) out.add(b.go)
        }
        return out
    }

    private fun updateRay(scene: Scene, go: GameObject, ray: RayCast2D) {
        if (!ray.enabled) {
            ray.hit = false
            return
        }
        val world = go.computeWorld()
        val ox = world.tx
        val oy = world.ty
        val dir = ray.direction
        val hit = raycast(
            scene, ox, oy, dir.x, dir.y, ray.length,
            ray.collisionMask, ray.collideWithAreas
        )
        if (hit != null && (!ray.collideWithBodies || hit.node !== go)) {
            ray.hit = true
            ray.hitNode = hit.node
            ray.hitX = hit.x; ray.hitY = hit.y
            ray.hitNormalX = hit.nx; ray.hitNormalY = hit.ny
            ray.hitDistance = hit.distance
        } else {
            ray.hit = false
            ray.hitNode = null
            ray.hitDistance = ray.length
        }
        recycle(hit ?: return)
    }

    /** Apply an impulse at a world point (used by scripts for explosions/knockback). */
    fun applyRadialImpulse(scene: Scene, x: Float, y: Float, radius: Float, strength: Float, mask: Int = 0xFFFF) {
        for (go in scene.objects) {
            val rb = go.get<Rigidbody2D>() ?: continue
            if (rb.bodyType != BodyType.DYNAMIC) continue
            val col = go.get<Collider2D>() ?: continue
            if ((if (col.layerOverride != 0) col.layerOverride else rb.collisionLayer) and mask == 0) continue
            val world = go.computeWorld()
            val dx = world.tx - x
            val dy = world.ty - y
            val d = sqrt(dx * dx + dy * dy)
            if (d > radius || d < 1e-4f) continue
            val falloff = 1f - d / radius
            val imp = strength * falloff
            rb.vx += dx / d * imp / max(rb.mass, 0.001f)
            rb.vy += dy / d * imp / max(rb.mass, 0.001f)
            rb.sleeping = false
        }
    }

    fun raycastAll(scene: Scene, ox: Float, oy: Float, dx: Float, dy: Float, length: Float, mask: Int = 0xFFFF): List<GameObject> {
        val out = ArrayList<GameObject>()
        gatherBodies(scene)
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return out
        val nx = dx / len; val ny = dy / len
        for (b in bodies) {
            if (b.layer and mask == 0) continue
            if (rayShape(nx, ny, ox, oy, b, length) != null) out.add(b.go)
        }
        return out
    }

    companion object {
        private const val SLEEP_SPEED = 0.05f
        private const val SLEEP_TIME = 0.6f

        /** Upper bound for continuous-collision sub-steps per body per tick. */
        private const val MAX_CCD_STEPS = 16
    }
}
