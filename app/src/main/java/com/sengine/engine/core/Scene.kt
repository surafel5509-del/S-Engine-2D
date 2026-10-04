package com.sengine.engine.core

import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2
import com.sengine.engine.serialization.SceneFormat

/** Scene-wide settings (persisted with the scene, editable in the Scene panel). */
class SceneSettings {
    var gravityX = 0f
    var gravityY = -9.81f
    var background = 0xFF1B2533.toInt()
    var pixelSnap = false
    var pixelPerfect = false
    var ySort = false
    var snapStep = 0.25f
    var gridStep = 1f
    var showGrid = true
    var physicsLayers = 1
    var ambientLight = 0xFFFFFFFF.toInt()
    var customData = ""
}

/**
 * A 2D scene: ordered set of nodes with hierarchy, groups, metadata, signals and settings.
 *
 * Nodes are stored flat for fast iteration (rendering, physics, serialization) while parent links
 * provide the tree. Structural operations keep both views consistent through [Scene.index].
 */
class Scene(var name: String = "Main") {
    val objects = mutableListOf<GameObject>()
    val signals = SignalHub()
    val settings = SceneSettings()
    val meta = LinkedHashMap<String, String>()

    var nextId = 1L
    var fileVersion = SceneFormat.VERSION

    /** Bumped whenever the node list or parenting changes. */
    @Volatile var structureRevision = 0

    // Backwards compatible accessors ------------------------------------------------
    var gravityX: Float
        get() = settings.gravityX
        set(v) { settings.gravityX = v }

    var gravityY: Float
        get() = settings.gravityY
        set(v) { settings.gravityY = v }

    // ------------------------------------------------------------------ creation
    fun create(name: String, parent: GameObject? = null, type: String = NodeType.NODE): GameObject {
        val go = GameObject(nextId++, uniqueName(name))
        go.type = type
        go.parent = parent
        go.ownerScene = this
        objects.add(go)
        structureRevision++
        return go
    }

    /** Create a node from an editor node type, adding its default component. */
    fun createNode(type: String, parent: GameObject? = null): GameObject {
        val baseName = when (type) {
            NodeType.NODE -> "Node"
            NodeType.NODE2D -> "Node2D"
            else -> type
        }
        val go = create(baseName, parent, type)
        val component = NodeType.componentFor(type)
        if (component != null) ComponentRegistry.create(component)?.let { go.add(it) }
        return go
    }

    fun uniqueName(base: String): String {
        if (objects.none { it.name == base }) return base
        val stem = base.replace(Regex(" \\(\\d+\\)$"), "")
        var i = 1
        while (objects.any { it.name == "$stem ($i)" }) i++
        return "$stem ($i)"
    }

    // ------------------------------------------------------------------ queries
    fun findById(id: Long): GameObject? {
        if (id <= 0L) return null
        for (o in objects) if (o.id == id) return o
        return null
    }

    fun find(name: String): GameObject? = objects.firstOrNull { it.name == name && !it.destroyed }

    fun findPath(path: String): GameObject? {
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var current: GameObject? = objects.firstOrNull { it.name == parts[0] && it.parent == null }
        for (i in 1 until parts.size) {
            current = current?.child(parts[i]) ?: return null
        }
        return current
    }

    fun findAll(tag: String): List<GameObject> = objects.filter { it.tag == tag && !it.destroyed }

    fun inGroup(group: String): List<GameObject> = objects.filter { group in it.groups && !it.destroyed }

    fun withComponent(type: String): List<GameObject> = objects.filter { it.component(type) != null }

    fun childrenOf(go: GameObject?): List<GameObject> = objects.filter { it.parent === go }

    fun descendants(go: GameObject): List<GameObject> {
        val out = ArrayList<GameObject>()
        fun walk(n: GameObject) {
            for (c in objects) if (c.parent === n) { out.add(c); walk(c) }
        }
        walk(go)
        return out
    }

    /** Depth-first hierarchy listing with depth, in draw order within each level. */
    fun hierarchy(): List<Pair<GameObject, Int>> {
        val out = ArrayList<Pair<GameObject, Int>>(objects.size)
        fun walk(p: GameObject?, depth: Int) {
            for (c in objects) if (c.parent === p) {
                out.add(c to depth); walk(c, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    // ------------------------------------------------------------------ mutation
    fun remove(go: GameObject) {
        for (c in childrenOf(go)) remove(c)
        signals.removeNode(go.id)
        go.destroyed = true
        objects.remove(go)
        structureRevision++
    }

    /** Detach every node (used when replacing a scene). */
    fun clear() {
        objects.clear()
        signals.clear()
        nextId = 1
        structureRevision++
    }

    fun reparent(go: GameObject, newParent: GameObject?, keepWorld: Boolean = true) {
        if (go === newParent || (newParent != null && go.isAncestorOf(newParent))) return
        val world = if (keepWorld) go.computeWorld() else null
        go.parent = newParent
        if (world != null) {
            val target = newParent?.computeWorld() ?: null
            if (target == null) {
                go.x = world.tx; go.y = world.ty
                go.rotation = world.rotationDeg
            } else {
                val inv = target.inverted()
                if (inv != null) {
                    go.x = inv.mapX(world.tx, world.ty)
                    go.y = inv.mapY(world.tx, world.ty)
                    go.rotation = M.wrap(world.rotationDeg - target.rotationDeg, -360f, 360f)
                }
            }
        }
        structureRevision++
    }

    /** Reparent keeping the local transform (used by drag & drop in the scene tree). */
    fun reparentLocal(go: GameObject, newParent: GameObject?) {
        if (go === newParent || (newParent != null && go.isAncestorOf(newParent))) return
        go.parent = newParent
        structureRevision++
    }

    fun moveInOrder(go: GameObject, delta: Int) {
        val siblings = objects.filter { it.parent === go.parent }
        val i = siblings.indexOf(go)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, siblings.size - 1)
        if (i == j) return
        val other = siblings[j]
        val a = objects.indexOf(go)
        val b = objects.indexOf(other)
        objects[a] = other
        objects[b] = go
        structureRevision++
    }

    fun bringToFront(go: GameObject) {
        val siblings = objects.filter { it.parent === go.parent }
        objects.remove(go)
        val last = siblings.lastOrNull()
        val insertAt = if (last == null) objects.size else objects.indexOf(last) + 1
        objects.add(insertAt.coerceIn(0, objects.size), go)
        structureRevision++
    }

    /** Deep copy of a node (and its children) placed next to the original. */
    fun duplicate(src: GameObject, newParent: GameObject? = src.parent): GameObject {
        val copy = copyInto(src, newParent)
        structureRevision++
        return copy
    }

    private fun copyInto(src: GameObject, newParent: GameObject?): GameObject {
        val copy = create(src.name, newParent, src.type)
        copy.tag = src.tag
        copy.groups.clear(); copy.groups.addAll(src.groups)
        copy.meta.clear(); copy.meta.putAll(src.meta)
        copy.active = src.active
        copy.visible = src.visible
        copy.locked = src.locked
        copy.order = src.order
        copy.layer = src.layer
        copy.x = src.x; copy.y = src.y
        copy.rotation = src.rotation
        copy.scaleX = src.scaleX; copy.scaleY = src.scaleY
        copy.pivotX = src.pivotX; copy.pivotY = src.pivotY
        copy.ui = src.ui?.copy()
        copy.components.clear()
        for (c in src.components) copy.add(c.copy())
        for (child in childrenOf(src)) copyInto(child, copy)
        return copy
    }

    /** Instantiate a deep copy detached from the hierarchy (prefabs, spawns). */
    fun instantiate(src: GameObject, worldX: Float? = null, worldY: Float? = null): GameObject {
        val copy = copyInto(src, null)
        copy.parent = null
        if (worldX != null && worldY != null) advanceSpawn(copy, worldX, worldY)
        structureRevision++
        return copy
    }

    private fun advanceSpawn(copy: GameObject, worldX: Float, worldY: Float) {
        val world = copy.computeWorld()
        copy.x += worldX - world.tx
        copy.y += worldY - world.ty
    }

    fun clearRuntimeState() {
        for (go in objects) for (c in go.components) c.resetRuntime()
    }

    // ------------------------------------------------------------------ transforms
    /** Refresh cached world matrices; parents are always visited before children. */
    fun updateTransforms() {
        for ((go, _) in hierarchy()) {
            val p = go.parent
            if (p == null) go.localMatrix(go.world)
            else go.world.setMul(p.world, go.localMatrix())
        }
    }

    /** World-space bounding box of a node (union of children when [includeChildren]). */
    fun bounds(go: GameObject, includeChildren: Boolean = false): Rect2 {
        var r = Rect2(go.world.tx - 0.5f, go.world.ty - 0.5f, 1f, 1f)
        val list = if (includeChildren) listOf(go) + descendants(go) else listOf(go)
        for (n in list) {
            val b = nodeBounds(n) ?: continue
            r = if (r.isEmpty) b else r.union(b)
        }
        return r
    }

    /** Bounds contributed by renderer components (used for selection, framing and gizmos). */
    fun nodeBounds(go: GameObject): Rect2? {
        val world = go.world
        var w = 0f
        var h = 0f
        var had = false
        go.getAny<Sprite2D>()?.let {
            val s = it.size(); w = s.first; h = s.second; had = true
        }
        if (!had) go.getAny<Label2D>()?.let {
            w = it.text.length * it.size * 0.55f
            h = it.size
            had = true
        }
        if (!had) go.getAny<Collider2D>()?.let {
            val b = it.bounds()
            w = b.first; h = b.second; had = true
        }
        if (!had) go.getAny<Camera2D>()?.let {
            w = 1f; h = 1f; had = true
        }
        if (!had) return null
        val sx = world.scaleX
        val sy = world.scaleY
        val width = w * sx
        val height = h * sy
        return Rect2(
            world.tx - width * go.pivotX,
            world.ty - height * go.pivotY,
            width, height
        )
    }
}
