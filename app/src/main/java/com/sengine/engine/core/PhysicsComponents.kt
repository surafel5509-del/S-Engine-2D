package com.sengine.engine.core

import com.sengine.engine.math.Vec2

/** Body simulation types. `Character` is the platformer/top-down controller body: it never rotates
 *  from collisions, resolves movement in two passes and reports grounded/ceiling/wall state. */
object BodyType {
    const val DYNAMIC = 0
    const val KINEMATIC = 1
    const val STATIC = 2
    const val CHARACTER = 3
    val NAMES = listOf("Dynamic", "Kinematic", "Static", "Character")
}

object ColliderShape {
    const val BOX = 0
    const val CIRCLE = 1
    const val POLYGON = 2
    val NAMES = listOf("Box", "Circle", "Polygon")
}

/**
 * Physics body. Velocity, mass, gravity scale, damping, material and collision filtering
 * (layer/mask) live here; shape data lives on [Collider2D].
 */
class Rigidbody2D : Component() {
    override val type = TYPE
    override val category = "Physics"
    override val description = "Physics body (dynamic, kinematic, static or character)"

    var bodyType = BodyType.DYNAMIC
    var mass = 1f
    var gravityScale = 1f
    var drag = 0f
    var friction = 0.4f
    var restitution = 0f
    var fixedRotation = true
    var continuous = false

    /** Collision filter: node is in this layer, and collides with nodes whose layer is in [mask]. */
    var collisionLayer = 1
    var collisionMask = 0xFFFF

    var startVx = 0f
    var startVy = 0f
    var oneWayPlatform = false

    // runtime state
    var vx = 0f
    var vy = 0f
    var grounded = false
    var onWall = 0
    var onCeiling = false
    var sleeping = false
    var sleepCounter = 0f
    var lastContact: GameObject? = null

    override fun props() = listOf(
        Prop.E("Body Type", BodyType.NAMES, { bodyType }, { bodyType = it }),
        Prop.F("Mass", { mass }, { mass = it.coerceAtLeast(0.001f) }, 0.1f, 0.001f, 1000f),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it }, 0.1f, -10f, 10f),
        Prop.F("Drag", { drag }, { drag = it.coerceAtLeast(0f) }, 0.05f, 0f, 50f),
        Prop.F("Friction", { friction }, { friction = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.F("Bounciness", { restitution }, { restitution = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.B("Fixed Rotation", { fixedRotation }, { fixedRotation = it }),
        Prop.B("One Way Platform", { oneWayPlatform }, { oneWayPlatform = it }, tooltip = "Only collides with bodies falling from above"),
        Prop.F("Start Velocity X", { startVx }, { startVx = it }),
        Prop.F("Start Velocity Y", { startVy }, { startVy = it }),
        Prop.I("Collision Layer", { collisionLayer }, { collisionLayer = it.coerceAtLeast(1) }, 1, 0x7FFF, section = "Filtering"),
        Prop.I("Collision Mask", { collisionMask }, { collisionMask = it }, 0, 0xFFFF, section = "Filtering"),
        Prop.Info("Grounded", { grounded.toString() }, section = "Runtime"),
        Prop.Info("Velocity", { "%.2f, %.2f".format(vx, vy) }, section = "Runtime")
    )

    override fun resetRuntime() {
        vx = startVx
        vy = startVy
        grounded = false
        onWall = 0
        onCeiling = false
        sleeping = false
        sleepCounter = 0f
        lastContact = null
    }

    val isDynamic get() = bodyType == BodyType.DYNAMIC
    val isStatic get() = bodyType == BodyType.STATIC
    val isCharacter get() = bodyType == BodyType.CHARACTER
    val inverseMass: Float get() = if (bodyType == BodyType.DYNAMIC) 1f / mass.coerceAtLeast(0.001f) else 0f

    companion object {
        const val TYPE = "Rigidbody2D"
    }
}

/**
 * Collision shape: box, circle or convex polygon, with an offset, a trigger flag, a physics
 * material and per-collider filtering overrides.
 */
class Collider2D : Component() {
    override val type = TYPE
    override val category = "Physics"
    override val description = "Box / circle / polygon collision shape"

    var shape = ColliderShape.BOX
    var width = 1f
    var height = 1f
    var radius = 0.5f
    var points = FloatArray(0)         // polygon, local space, 2 floats per vertex
    var offsetX = 0f
    var offsetY = 0f
    var isTrigger = false
    var oneWay = false
    var friction = -1f                 // <0 = use the body's material
    var restitution = -1f
    var layerOverride = 0              // 0 = use body layer
    var maskOverride = -1              // -1 = use body mask

    /** Debug colour for the collision overlay (editable in the inspector). */
    var debugColor = 0xCC66FF66.toInt()

    override fun props() = listOf(
        Prop.E("Shape", ColliderShape.NAMES, { shape }, { shape = it }),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 1000f),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 1000f),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 1000f),
        Prop.Points("Points", { points }, { points = it }, tooltip = "Polygon vertices (x0,y0,x1,y1,…) in local units"),
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 0.05f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 0.05f),
        Prop.B("Is Trigger", { isTrigger }, { isTrigger = it }, tooltip = "Reports overlaps without pushing bodies apart"),
        Prop.B("One Way", { oneWay }, { oneWay = it }),
        Prop.F("Friction", { friction }, { friction = it }, 0.05f, -1f, 2f, section = "Material", tooltip = "-1 uses the body material"),
        Prop.F("Bounciness", { restitution }, { restitution = it }, 0.05f, -1f, 1f, section = "Material"),
        Prop.I("Layer Override", { layerOverride }, { layerOverride = it }, 0, 0x7FFF, section = "Filtering"),
        Prop.I("Mask Override", { maskOverride }, { maskOverride = it }, -1, 0xFFFF, section = "Filtering"),
        Prop.C("Debug Color", { debugColor }, { debugColor = it }, section = "Debug")
    )

    /** Local-space size (half extents) used for bounds and the physics broad phase. */
    fun bounds(): Pair<Float, Float> = when (shape) {
        ColliderShape.CIRCLE -> radius * 2f to radius * 2f
        ColliderShape.POLYGON -> {
            if (points.size < 4) 1f to 1f
            else {
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                var i = 0
                while (i + 1 < points.size) {
                    minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i])
                    minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
                    i += 2
                }
                (maxX - minX) to (maxY - minY)
            }
        }
        else -> width to height
    }

    fun polygonPoints(): FloatArray {
        if (points.size >= 6) return points
        // default polygon: a box outline so a fresh polygon collider can be edited immediately
        val hw = width * 0.5f; val hh = height * 0.5f
        return floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
    }

    companion object {
        const val TYPE = "Collider2D"
    }
}

/**
 * Trigger volume. Reports overlaps via signals (`entered` / `exited`) and keeps the list of bodies
 * currently inside, which scripts can query. Distinct from a trigger [Collider2D] because an area
 * also works without a body and exposes filtering + monitor flags.
 */
class Area2D : Component(), SignalListener {
    override val type = TYPE
    override val category = "Physics"
    override val description = "Trigger volume that reports enter/exit overlaps"

    var width = 2f
    var height = 2f
    var radius = 1f
    var shape = ColliderShape.BOX
    var offsetX = 0f
    var offsetY = 0f
    var monitorBodies = true
    var monitorAreas = false
    var collisionLayer = 8
    var collisionMask = 0xFFFF

    /** Runtime: nodes currently overlapping. */
    val overlapping = ArrayList<GameObject>()

    override fun props() = listOf(
        Prop.E("Shape", ColliderShape.NAMES, { shape }, { shape = it }),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.01f) }, 0.1f),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.01f) }, 0.1f),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.01f) }, 0.1f),
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 0.1f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 0.1f),
        Prop.B("Monitor Bodies", { monitorBodies }, { monitorBodies = it }),
        Prop.B("Monitor Areas", { monitorAreas }, { monitorAreas = it }),
        Prop.I("Collision Layer", { collisionLayer }, { collisionLayer = it }, 1, 0x7FFF, section = "Filtering"),
        Prop.I("Collision Mask", { collisionMask }, { collisionMask = it }, 0, 0xFFFF, section = "Filtering"),
        Prop.Info("Overlapping", { overlapping.size.toString() }, section = "Runtime")
    )

    override fun resetRuntime() {
        overlapping.clear()
    }

    fun contains(x: Float, y: Float): Boolean {
        val go = if (attached) gameObject else return false
        val w = go.computeWorld()
        val cx = w.mapX(offsetX, offsetY)
        val cy = w.mapY(offsetX, offsetY)
        return when (shape) {
            ColliderShape.CIRCLE -> {
                val dx = x - cx; val dy = y - cy
                dx * dx + dy * dy <= radius * radius
            }
            else -> kotlin.math.abs(x - cx) <= width * 0.5f && kotlin.math.abs(y - cy) <= height * 0.5f
        }
    }

    override fun onSignal(name: String, data: Any?): Boolean = false

    companion object {
        const val TYPE = "Area2D"
    }
}

/** Ray query with a visible gizmo, hit reporting and scripting access. */
class RayCast2D : Component() {
    override val type = TYPE
    override val category = "Physics"
    override val description = "Ray query with hit reporting"

    var direction = Vec2(0f, -1f)
    var length = 4f
    override var enabled = true
    var collideWithBodies = true
    var collideWithAreas = false
    var collisionMask = 0xFFFF

    // runtime
    var hit = false
    var hitNode: GameObject? = null
    var hitX = 0f
    var hitY = 0f
    var hitNormalX = 0f
    var hitNormalY = 0f
    var hitDistance = 0f

    override fun props() = listOf(
        Prop.V2("Direction", { direction }, { direction = it }, 0.05f),
        Prop.F("Length", { length }, { length = it.coerceAtLeast(0f) }, 0.1f, 0f, 1000f),
        Prop.B("Enabled", { enabled }, { enabled = it }),
        Prop.B("Hit Bodies", { collideWithBodies }, { collideWithBodies = it }),
        Prop.B("Hit Areas", { collideWithAreas }, { collideWithAreas = it }),
        Prop.I("Collision Mask", { collisionMask }, { collisionMask = it }, 0, 0xFFFF, section = "Filtering"),
        Prop.Info("Hit", { if (hit) "${hitNode?.name ?: "?"} at ${"%.2f".format(hitDistance)}" else "none" }, section = "Runtime")
    )

    override fun resetRuntime() {
        hit = false
        hitNode = null
        hitDistance = 0f
    }

    companion object {
        const val TYPE = "RayCast2D"
    }
}
