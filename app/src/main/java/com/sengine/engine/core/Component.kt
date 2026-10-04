package com.sengine.engine.core

import com.sengine.engine.json.JVal
import com.sengine.engine.json.Json
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable

/**
 * A behaviour attached to a [GameObject].
 *
 * Serialization is fully driven by [props], so adding a property to a component automatically adds
 * it to the Inspector, the scene file, prefabs, undo and multi-object editing.
 */
abstract class Component {
    lateinit var gameObject: GameObject
    open var enabled = true

    /** True once the component has been attached to a node (set by [GameObject.add]). */
    val attached: Boolean get() = this::gameObject.isInitialized

    /** Stable type key used in scene files, the inspector list and the node creation menu. */
    abstract val type: String

    /** Short description shown as tooltip in the node/add-component menus. */
    open val description: String get() = ""

    /** Category used to group components in the "Add Component" menu. */
    open val category: String get() = "General"

    /** Properties shown in the inspector and saved to disk. */
    abstract fun props(): List<Prop>

    /** Props that hold runtime-only state and should not be written to disk. */
    protected open fun transientProps(): List<Prop> = emptyList()

    // ------------------------------------------------------------ lifecycle
    /** Called when play mode starts (or when the node is spawned in play mode). */
    open fun onStart() {}

    /** Per-frame update in play mode. */
    open fun onUpdate(dt: Float) {}

    /** Fixed-step physics update (60 Hz). */
    open fun onPhysicsUpdate(dt: Float) {}

    /** Called when play mode stops. */
    open fun onStop() {}

    /** Editor-only draw data (gizmos/overlays) is produced by the renderer, not here. */
    open fun onEditorDraw() {}

    /** Reset transient runtime fields so the editor scene can be replayed. */
    open fun resetRuntime() {}

    /** Deep copy for prefabs / node duplication (props only — subclass state must be in props). */
    open fun copy(): Component {
        val c = ComponentRegistry.create(type) ?: error("Unknown component $type")
        c.enabled = enabled
        c.copyPropsFrom(this)
        return c
    }

    /** Copy all persisted prop values from [other] (same type). */
    fun copyPropsFrom(other: Component) {
        val src = other.props().filter { it !is Prop.Info }
        decodeProps(src.encodeAll())
    }

    // ------------------------------------------------------------ serialization
    open fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("type", type)
        o.put("enabled", enabled)
        val values = props().filter { it !is Prop.Info }.encodeAll()
        values.fields.forEach { (k, v) -> o.fields[k] = v }
        return o
    }

    fun decodeProps(o: JVal.Obj) {
        props().filter { it !is Prop.Info }.decodeAll(o)
    }

    fun fromJson(o: JVal.Obj) {
        enabled = o.bool("enabled", true)
        decodeProps(o)
    }

    /** Find a property by name (inspector search, scripts, undo). */
    fun prop(name: String): Prop? = props().firstOrNull { it.name.equals(name, true) || it.label.equals(name, true) }

    fun writeProp(name: String, value: JVal): Boolean {
        val p = prop(name) ?: return false
        p.decode(value)
        return true
    }

    fun debugString(): String = "${type}${if (enabled) "" else " (disabled)"}"
}

/** Asset categories used by import pipeline, asset browser filtering and property widgets. */
enum class AssetKind(val extensions: List<String>, val label: String) {
    TEXTURE(listOf("png", "jpg", "jpeg", "webp", "bmp", "gif"), "Textures"),
    SCRIPT(listOf("js"), "Scripts"),
    SOUND(listOf("wav", "ogg", "mp3", "m4a", "aac", "flac"), "Audio"),
    FONT(listOf("ttf", "otf"), "Fonts"),
    SHADER(listOf("shader"), "Shaders"),
    ANIMATION(listOf("anim.json", "anim"), "Animations"),
    TILESET(listOf("tileset.json", "tileset"), "TileSets"),
    PARTICLE(listOf("particles.json", "fx"), "Particles"),
    SCENE(listOf("scene.json"), "Scenes"),
    MATERIAL(listOf("material.json", "mat"), "Materials"),
    THEME(listOf("theme.json"), "Themes"),
    OTHER(emptyList(), "Other");

    companion object {
        /** Longest-suffix match first so "hero.anim.json" is an animation, not JSON. */
        fun of(fileName: String): AssetKind? {
            val lower = fileName.lowercase()
            return values().reversed().firstOrNull { k ->
                k.extensions.isNotEmpty() && k.extensions.any { lower.endsWith(".$it") || lower.substringAfterLast('.', "") == it }
            }
        }
    }
}

/**
 * Global registry mapping component type ids to factories.
 * Plugins and scripts can extend this at runtime through [register].
 */
object ComponentRegistry {
    private val factories = LinkedHashMap<String, () -> Component>()
    private val descriptions = HashMap<String, ComponentInfo>()

    data class ComponentInfo(
        val type: String,
        val category: String,
        val description: String,
        val factory: () -> Component
    )

    fun register(
        type: String,
        category: String = "General",
        description: String = "",
        factory: () -> Component
    ) {
        factories[type] = factory
        descriptions[type] = ComponentInfo(type, category, description, factory)
    }

    fun register(info: ComponentInfo) = register(info.type, info.category, info.description, info.factory)

    fun create(type: String): Component? = factories[type]?.invoke()

    fun types(): List<String> = factories.keys.toList()

    fun all(): List<ComponentInfo> = descriptions.values.toList()

    fun byCategory(): Map<String, List<ComponentInfo>> = all().groupBy { it.category }

    init {
        BuiltinComponents.registerAll()
    }
}

/** Escapes/unescapes helper used by script properties and editor text fields. */
object ScriptUtil {
    fun toJsString(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Scriptable -> value.toString()
        is Function -> value.toString()
        else -> value.toString()
    }

    fun pretty(value: JVal): String = Json.write(value, false)
}
