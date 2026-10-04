package com.sengine.engine.core

import com.sengine.engine.json.JVal
import com.sengine.engine.math.Affine
import com.sengine.engine.math.Vec2
import com.sengine.engine.ui.ControlComponent

/**
 * A node in the scene graph.
 *
 * Nodes own their transform (local position/rotation/scale/pivot), their components and their
 * children. The scene keeps nodes in a flat list for cache-friendly iteration, while the
 * parent/child links provide the hierarchy used by the scene tree, transforms and reparenting.
 */
class GameObject(var id: Long, var name: String) {

    /** Editor-facing node class ("Sprite2D", "Camera2D", "Node", …). */
    var type: String = NodeType.NODE

    var tag: String = "Untagged"

    /** Logical groups; group queries are index-backed by the scene. */
    val groups = LinkedHashSet<String>()

    /** Free-form metadata, editable in the inspector, readable from scripts. */
    val meta = LinkedHashMap<String, String>()

    var active: Boolean = true
    var visible: Boolean = true
    var locked: Boolean = false

    /** Draw order inside a layer. Higher values are drawn on top. */
    var order: Int = 0

    /** Render layer name — used for sorting, cameras and parallax. */
    var layer: String = "Default"

    var x = 0f
    var y = 0f
    var rotation = 0f
    var scaleX = 1f
    var scaleY = 1f

    /** Normalised pivot (0..1) — rotation/scale origin and anchor for renderer components. */
    var pivotX = 0.5f
    var pivotY = 0.5f

    var parent: GameObject? = null
    val components = mutableListOf<Component>()

    @Volatile var destroyed = false

    /** Cached world transform, refreshed by [Scene.updateTransforms]. */
    val world = Affine()

    /** Monotonic counter bumped on any structural/visual change (editor redraw heuristics). */
    @Volatile var revision: Int = 0

    var ui: UiProps? = null

    // ------------------------------------------------------------------ tree
    fun children(): List<GameObject> {
        val owner = ownerScene
        return if (owner != null) owner.childrenOf(this) else emptyList()
    }

    internal var ownerScene: Scene? = null

    fun child(name: String): GameObject? = children().firstOrNull { it.name == name }

    fun findDescendant(name: String): GameObject? {
        for (c in children()) {
            if (c.name == name) return c
            c.findDescendant(name)?.let { return it }
        }
        return null
    }

    fun depth(): Int {
        var d = 0
        var p = parent
        while (p != null) { d++; p = p.parent }
        return d
    }

    fun isActiveInHierarchy(): Boolean = active && !destroyed && (parent?.isActiveInHierarchy() ?: true)

    fun isVisibleInHierarchy(): Boolean = visible && active && !destroyed && (parent?.isVisibleInHierarchy() ?: true)

    fun isAncestorOf(other: GameObject): Boolean {
        var p = other.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    // ------------------------------------------------------------------ components
    fun <T : Component> add(c: T): T {
        c.gameObject = this
        components.add(c)
        revision++
        return c
    }

    inline fun <reified T : Component> get(): T? = components.firstOrNull { it is T && it.enabled } as T?
    inline fun <reified T : Component> getAny(): T? = components.firstOrNull { it is T } as T?

    fun component(type: String): Component? = components.firstOrNull { it.type.equals(type, true) }

    fun remove(c: Component): Boolean {
        val ok = components.remove(c)
        if (ok) revision++
        return ok
    }

    fun removeAll(type: String): Int {
        val n = components.count { it.type.equals(type, true) }
        components.removeAll { it.type.equals(type, true) }
        if (n > 0) revision++
        return n
    }

    fun hasComponent(type: String) = component(type) != null

    // ------------------------------------------------------------------ transforms
    fun localMatrix(out: Affine = Affine()): Affine = out.setTRS(x, y, rotation, scaleX, scaleY)

    /** Scratch matrix so world updates allocate nothing (called every frame per node). */
    private val worldScratch = Affine()

    /**
     * Recomputes [world] from the parent chain and returns it. The result is cached in [world] so
     * [worldPosition], [setWorldPosition] and the editor gizmos can read it without recomputing.
     */
    fun computeWorld(): Affine {
        val p = parent ?: return localMatrix(world)
        val parentWorld = p.computeWorld()
        localMatrix(worldScratch)
        return world.setMul(parentWorld, worldScratch)
    }

    val worldPosition: Vec2 get() = Vec2(world.tx, world.ty)
    val worldRotation: Float get() = world.rotationDeg

    fun setWorldPosition(wx: Float, wy: Float) {
        val p = parent
        if (p == null) {
            x = wx; y = wy
        } else {
            val inv = p.computeWorld().inverted() ?: return
            x = inv.mapX(wx, wy); y = inv.mapY(wx, wy)
        }
        revision++
    }

    fun translate(dx: Float, dy: Float) {
        x += dx; y += dy
        revision++
    }

    fun translateWorld(dx: Float, dy: Float) {
        setWorldPosition(world.tx + dx, world.ty + dy)
    }

    fun rotate(deg: Float) {
        rotation = com.sengine.engine.math.M.wrap(rotation + deg, -360f, 360f)
        revision++
    }

    /** Move/set helpers used by the viewport tools and scripts. */
    fun setPosition(px: Float, py: Float) {
        x = px; y = py; revision++
    }

    // ------------------------------------------------------------------ signals
    /** Emit a signal from this node; connected handlers run immediately. */
    fun emit(signal: String, data: Any? = null) {
        ownerScene?.signals?.emit(this, signal, data, ownerScene)
    }

    fun connect(signal: String, target: GameObject, method: String, args: String = "") {
        ownerScene?.signals?.connect(this, signal, target, method, args)
    }

    // ------------------------------------------------------------------ meta helpers
    fun metaOr(key: String, def: String): String = meta[key] ?: def
    fun metaInt(key: String, def: Int = 0) = meta[key]?.toIntOrNull() ?: def
    fun metaFloat(key: String, def: Float = 0f) = meta[key]?.toFloatOrNull() ?: def
    fun metaBool(key: String, def: Boolean = false) = meta[key]?.toBooleanStrictOrNull() ?: def

    /** Compact hierarchy path, e.g. `Player/Sprite`. */
    fun path(): String {
        val sb = StringBuilder(name)
        var p = parent
        while (p != null) {
            sb.insert(0, "${p.name}/")
            p = p.parent
        }
        return sb.toString()
    }

    override fun toString() = "GameObject(#$id ${path()})"

    // ------------------------------------------------------------------ ui attachment
    /** Control-node properties; only meaningful for nodes that render through the UI layer. */
    class UiProps {
        var controlType: String = "Panel"
        var anchorMinX = 0f; var anchorMinY = 0f
        var anchorMaxX = 0f; var anchorMaxY = 0f
        var offsetLeft = 0f; var offsetTop = 0f; var offsetRight = 120f; var offsetBottom = 40f
        var minWidth = 0f; var minHeight = 0f
        var growHorizontal = 0; var growVertical = 0
        var visibleInPlay = true
        var scrollX = false; var scrollY = false
        var spacing = 4f
        var padding = 0f
        var text = ""
        var fontSize = 16f
        var textAlign = 1
        var checked = false
        var value = 0.5f
        var editable = true
        var placeholder = ""
        var tabs = ""
        var activeTab = 0
        var style = "Default"
        var onClick = ""
        var onValueChanged = ""

        fun copy(): UiProps = UiProps().also { d ->
            d.controlType = controlType
            d.anchorMinX = anchorMinX; d.anchorMinY = anchorMinY
            d.anchorMaxX = anchorMaxX; d.anchorMaxY = anchorMaxY
            d.offsetLeft = offsetLeft; d.offsetTop = offsetTop
            d.offsetRight = offsetRight; d.offsetBottom = offsetBottom
            d.minWidth = minWidth; d.minHeight = minHeight
            d.growHorizontal = growHorizontal; d.growVertical = growVertical
            d.visibleInPlay = visibleInPlay; d.scrollX = scrollX; d.scrollY = scrollY
            d.spacing = spacing; d.padding = padding
            d.text = text; d.fontSize = fontSize; d.textAlign = textAlign
            d.checked = checked; d.value = value; d.editable = editable
            d.placeholder = placeholder; d.tabs = tabs; d.activeTab = activeTab; d.style = style
            d.onClick = onClick; d.onValueChanged = onValueChanged
        }
    }
}

/** Node classes offered by the editor "Create Node" menu (2D only). */
object NodeType {
    const val NODE = "Node"
    const val NODE2D = "Node2D"
    const val SPRITE = "Sprite2D"
    const val ANIMATED_SPRITE = "AnimatedSprite2D"
    const val LABEL = "Label2D"
    const val CAMERA = "Camera2D"
    const val TILEMAP = "TileMap"
    const val PARTICLES = "Particles2D"
    const val COLLIDER = "CollisionShape2D"
    const val AREA = "Area2D"
    const val RAYCAST = "RayCast2D"
    const val AUDIO = "AudioStream2D"
    const val CONTROL = "Control"
    const val LIGHTS_UNUSED = "Unused"

    data class Entry(val type: String, val label: String, val defaultComponent: String?, val description: String)

    val ALL = listOf(
        Entry(NODE, "Node", null, "Empty container node — group children under it"),
        Entry(NODE2D, "Node2D", null, "Node with a transform and a visible marker"),
        Entry(SPRITE, "Sprite2D", Sprite2D.TYPE, "Draws a texture, sprite-sheet region or solid shape"),
        Entry(ANIMATED_SPRITE, "AnimatedSprite2D", AnimatedSprite2D.TYPE, "Flip-book sprite animation with a real timeline"),
        Entry(LABEL, "Label2D", Label2D.TYPE, "Draws text in world space"),
        Entry(CAMERA, "Camera2D", Camera2D.TYPE, "Viewport camera with smoothing, limits and pixel-perfect mode"),
        Entry(TILEMAP, "TileMap", TileMap2D.TYPE, "Paints tile layers from a TileSet"),
        Entry(PARTICLES, "Particles2D", ParticleEmitter2D.TYPE, "GPU-friendly 2D particle emitter with presets"),
        Entry(COLLIDER, "CollisionShape2D", Collider2D.TYPE, "Box / circle / polygon collision shape"),
        Entry(AREA, "Area2D", Area2D.TYPE, "Trigger volume with enter/exit signals"),
        Entry(RAYCAST, "RayCast2D", RayCast2D.TYPE, "Ray query with hit reporting"),
        Entry(AUDIO, "AudioStream2D", AudioSource.TYPE, "Positional or non-positional sound source"),
        Entry(CONTROL, "Control", ControlComponent.TYPE, "UI node — label, button, panel, slider, …")
    )

    /** Component type implied by a node type (used for the editor's icon/menu preview). */
    fun componentFor(type: String): String? = ALL.firstOrNull { it.type == type }?.defaultComponent
}
