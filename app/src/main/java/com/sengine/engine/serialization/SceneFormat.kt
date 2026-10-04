package com.sengine.engine.serialization

import com.sengine.engine.core.Component
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SignalConnection
import com.sengine.engine.core.UnknownComponent
import com.sengine.engine.json.JVal
import com.sengine.engine.json.Json

/**
 * Versioned, human-readable scene document.
 *
 * The file format is intentionally explicit: every file carries `format` + `version`, migrations run
 * on load ([MIGRATIONS]), and unknown data is preserved (see [UnknownComponent]) so opening a file
 * written by a newer engine never destroys content.
 */
object SceneFormat {
    const val FORMAT = "sengine.scene"
    const val VERSION = 2

    // ------------------------------------------------------------------ writing
    fun toJson(scene: Scene): JVal.Obj {
        val root = JVal.Obj()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("name", scene.name)
        root.put("nextId", scene.nextId)

        val settings = JVal.Obj()
        settings.put("gravity", com.sengine.engine.json.jarr(scene.settings.gravityX, scene.settings.gravityY))
        settings.put("background", Prop.C.format(scene.settings.background))
        settings.put("pixelSnap", scene.settings.pixelSnap)
        settings.put("pixelPerfect", scene.settings.pixelPerfect)
        settings.put("ySort", scene.settings.ySort)
        settings.put("gridStep", scene.settings.gridStep)
        settings.put("snapStep", scene.settings.snapStep)
        settings.put("showGrid", scene.settings.showGrid)
        settings.put("physicsLayers", scene.settings.physicsLayers)
        if (scene.settings.customData.isNotEmpty()) settings.put("customData", scene.settings.customData)
        root.put("settings", settings)

        if (scene.meta.isNotEmpty()) {
            val meta = JVal.Obj()
            scene.meta.forEach { (k, v) -> meta.put(k, v) }
            root.put("meta", meta)
        }

        if (scene.signals.connections.isNotEmpty()) {
            root.put("connections", JVal.Arr().also { arr -> scene.signals.connections.forEach { arr.add(it.toJson()) } })
        }

        val objects = JVal.Arr()
        for (go in scene.objects) objects.add(objectToJson(go))
        root.put("objects", objects)
        return root
    }

    fun objectToJson(go: GameObject): JVal.Obj {
        val o = JVal.Obj()
        o.put("id", go.id)
        o.put("name", go.name)
        o.put("type", go.type)
        if (go.tag != "Untagged") o.put("tag", go.tag)
        if (go.groups.isNotEmpty()) o.put("groups", JVal.Arr.strings(go.groups.toList()))
        if (!go.active) o.put("active", false)
        if (!go.visible) o.put("visible", false)
        if (go.locked) o.put("locked", true)
        if (go.layer != "Default") o.put("layer", go.layer)

        val t = JVal.Obj()
        t.put("x", go.x); t.put("y", go.y)
        t.put("rotation", go.rotation)
        t.put("scale", com.sengine.engine.json.jarr(go.scaleX, go.scaleY))
        t.put("pivot", com.sengine.engine.json.jarr(go.pivotX, go.pivotY))
        t.put("order", go.order)
        o.put("transform", t)

        if (go.meta.isNotEmpty()) {
            val meta = JVal.Obj()
            go.meta.forEach { (k, v) -> meta.put(k, v) }
            o.put("meta", meta)
        }
        if (go.parent != null) o.put("parent", go.parent!!.id)

        go.ui?.let { u ->
            val ui = JVal.Obj()
            ui.put("control", u.controlType)
            ui.put("anchors", com.sengine.engine.json.jarr(u.anchorMinX, u.anchorMinY, u.anchorMaxX, u.anchorMaxY))
            ui.put("offsets", com.sengine.engine.json.jarr(u.offsetLeft, u.offsetTop, u.offsetRight, u.offsetBottom))
            ui.put("minSize", com.sengine.engine.json.jarr(u.minWidth, u.minHeight))
            ui.put("grow", com.sengine.engine.json.jarr(u.growHorizontal, u.growVertical))
            ui.put("text", u.text)
            ui.put("fontSize", u.fontSize)
            ui.put("textAlign", u.textAlign)
            ui.put("checked", u.checked)
            ui.put("value", u.value)
            ui.put("editable", u.editable)
            ui.put("placeholder", u.placeholder)
            ui.put("tabs", u.tabs)
            ui.put("activeTab", u.activeTab)
            ui.put("style", u.style)
            ui.put("spacing", u.spacing)
            ui.put("padding", u.padding)
            ui.put("scroll", com.sengine.engine.json.jarr(u.scrollX, u.scrollY))
            ui.put("visibleInPlay", u.visibleInPlay)
            if (u.onClick.isNotEmpty()) ui.put("onClick", u.onClick)
            if (u.onValueChanged.isNotEmpty()) ui.put("onValueChanged", u.onValueChanged)
            o.put("ui", ui)
        }

        val comps = JVal.Arr()
        for (c in go.components) comps.add(c.toJson())
        if (comps.isNotEmpty()) o.put("components", comps)
        return o
    }

    fun write(scene: Scene, pretty: Boolean = true): String = Json.write(toJson(scene), pretty)

    // ------------------------------------------------------------------ reading
    fun fromJson(root: JVal.Obj): Scene {
        val migrated = migrate(root)
        val scene = Scene(migrated.str("name", "Main"))
        scene.fileVersion = VERSION
        scene.nextId = migrated.l("nextId", 1L).coerceAtLeast(1L)

        migrated["settings"]?.let { s ->
            val so = s as? JVal.Obj ?: JVal.Obj()
            val g = so.floats("gravity")
            if (g.size >= 2) {
                scene.settings.gravityX = g[0]; scene.settings.gravityY = g[1]
            }
            scene.settings.background = Prop.C.parse(so.str("background", "#FF1B2533"))
            scene.settings.pixelSnap = so.bool("pixelSnap")
            scene.settings.pixelPerfect = so.bool("pixelPerfect")
            scene.settings.ySort = so.bool("ySort")
            scene.settings.gridStep = so.f("gridStep", 1f)
            scene.settings.snapStep = so.f("snapStep", 0.25f)
            scene.settings.showGrid = so.bool("showGrid", true)
            scene.settings.physicsLayers = so.i("physicsLayers", 1)
            scene.settings.customData = so.str("customData")
        }
        migrated["meta"]?.let { m -> (m as? JVal.Obj)?.fields?.forEach { (k, v) -> scene.meta[k] = (v as? JVal.Str)?.v ?: "" } }

        val pendingParents = HashMap<GameObject, Long>()
        var maxId = 0L
        for (oj in migrated.objects("objects")) {
            val go = GameObject(oj.l("id", 1L), oj.str("name", "GameObject"))
            applyObjectJson(go, oj)
            if (oj.has("parent")) pendingParents[go] = oj.l("parent")
            scene.objects.add(go)
            go.ownerScene = scene
            maxId = maxOf(maxId, go.id)
        }
        for ((go, pid) in pendingParents) go.parent = scene.findById(pid)
        scene.nextId = maxOf(scene.nextId, maxId + 1)

        for (c in migrated.arr("connections")) {
            (c as? JVal.Obj)?.let { scene.signals.connections.add(SignalConnection.fromJson(it)) }
        }
        return scene
    }

    /** Applies everything except id and parent. */
    fun applyObjectJson(go: GameObject, o: JVal.Obj) {
        go.name = o.str("name", go.name)
        go.type = o.str("type", com.sengine.engine.core.NodeType.NODE)
        go.tag = o.str("tag", "Untagged")
        go.groups.clear()
        go.groups.addAll(o.strings("groups"))
        go.active = o.bool("active", true)
        go.visible = o.bool("visible", true)
        go.locked = o.bool("locked", false)
        go.layer = o.str("layer", "Default")

        val t = o["transform"] as? JVal.Obj ?: JVal.Obj()
        go.x = t.f("x"); go.y = t.f("y")
        go.rotation = t.f("rotation")
        val scale = t.floats("scale")
        go.scaleX = scale.getOrElse(0) { 1f }
        go.scaleY = scale.getOrElse(1) { 1f }
        val pivot = t.floats("pivot")
        go.pivotX = pivot.getOrElse(0) { 0.5f }
        go.pivotY = pivot.getOrElse(1) { 0.5f }
        go.order = t.i("order")

        go.meta.clear()
        (o["meta"] as? JVal.Obj)?.fields?.forEach { (k, v) -> go.meta[k] = (v as? JVal.Str)?.v ?: Json.write(v, false) }

        (o["ui"] as? JVal.Obj)?.let { ui -> go.ui = readUi(ui) }

        go.components.clear()
        for (cj in o.objects("components")) {
            val type = cj.str("type", "")
            if (type.isBlank()) continue
            val comp = ComponentRegistry.create(type) ?: UnknownComponent(type, cj)
            comp.fromJson(cj)
            go.add(comp)
        }
    }

    private fun readUi(ui: JVal.Obj): GameObject.UiProps {
        val u = GameObject.UiProps()
        u.controlType = ui.str("control", "Panel")
        val a = ui.floats("anchors")
        if (a.size >= 4) {
            u.anchorMinX = a[0]; u.anchorMinY = a[1]; u.anchorMaxX = a[2]; u.anchorMaxY = a[3]
        }
        val off = ui.floats("offsets")
        if (off.size >= 4) {
            u.offsetLeft = off[0]; u.offsetTop = off[1]; u.offsetRight = off[2]; u.offsetBottom = off[3]
        }
        val ms = ui.floats("minSize")
        if (ms.size >= 2) { u.minWidth = ms[0]; u.minHeight = ms[1] }
        val g = ui.ints("grow")
        if (g.size >= 2) { u.growHorizontal = g[0]; u.growVertical = g[1] }
        u.text = ui.str("text")
        u.fontSize = ui.f("fontSize", 16f)
        u.textAlign = ui.i("textAlign", 1)
        u.checked = ui.bool("checked")
        u.value = ui.f("value", 0.5f)
        u.editable = ui.bool("editable", true)
        u.placeholder = ui.str("placeholder")
        u.tabs = ui.str("tabs")
        u.activeTab = ui.i("activeTab")
        u.style = ui.str("style", "Default")
        u.spacing = ui.f("spacing", 4f)
        u.padding = ui.f("padding")
        val sc = ui.arr("scroll")
        if (sc.size >= 2) { u.scrollX = (sc[0] as? JVal.Bool)?.v == true; u.scrollY = (sc[1] as? JVal.Bool)?.v == true }
        u.visibleInPlay = ui.bool("visibleInPlay", true)
        u.onClick = ui.str("onClick")
        u.onValueChanged = ui.str("onValueChanged")
        return u
    }

    // ------------------------------------------------------------------ migration
    /**
     * Migration registry. Each entry upgrades a document from version `n` to `n + 1`.
     * Add new steps at the end; never mutate old ones so old files keep loading identically.
     */
    private val MIGRATIONS: Map<Int, (JVal.Obj) -> JVal.Obj> = mapOf(
        0 to { root -> migrateLegacyCompProps(root) },
        1 to { root -> migrateV1ToV2(root) }
    )

    fun detectVersion(root: JVal.Obj): Int =
        if (root.has("version")) root.i("version", 1) else if (root.has("format")) 1 else 0

    fun migrate(root: JVal.Obj): JVal.Obj {
        var version = detectVersion(root)
        var doc = root
        while (version < VERSION) {
            val step = MIGRATIONS[version] ?: break
            doc = step(doc)
            version++
        }
        doc.put("version", VERSION)
        if (!doc.has("format")) doc.put("format", FORMAT)
        return doc
    }

    /** v0: first published snapshot — components stored props with a "type" key only, no version. */
    private fun migrateLegacyCompProps(root: JVal.Obj): JVal.Obj {
        root.put("format", FORMAT)
        root.put("version", 1)
        return root
    }

    /** v1 → v2: transform fields move into a `transform` object, `order` becomes z-index, meta added. */
    /**
     * Field names used by older S Engine builds, mapped onto the current property names.
     * Without this, projects saved before the 1.0 format would load with default component values.
     */
    private val LEGACY_FIELDS: Map<String, Map<String, String>> = mapOf(
        com.sengine.engine.core.Sprite2D.TYPE to mapOf(
            "shape" to "Shape", "color" to "Color", "texture" to "Texture",
            "flipX" to "Flip X", "flipY" to "Flip Y"
        ),
        com.sengine.engine.core.Label2D.TYPE to mapOf(
            "text" to "Text", "size" to "Size", "color" to "Color", "align" to "Align", "bold" to "Bold"
        ),
        com.sengine.engine.core.Camera2D.TYPE to mapOf(
            "size" to "Size", "background" to "Background", "follow" to "Follow Target", "smoothing" to "Smoothing"
        ),
        com.sengine.engine.core.Rigidbody2D.TYPE to mapOf(
            "bodyType" to "Body Type", "mass" to "Mass", "gravityScale" to "Gravity Scale", "drag" to "Drag",
            "friction" to "Friction", "startVx" to "Start Velocity X", "startVy" to "Start Velocity Y"
        ),
        com.sengine.engine.core.Collider2D.TYPE to mapOf(
            "shape" to "Shape", "width" to "Width", "height" to "Height", "radius" to "Radius",
            "offsetX" to "Offset X", "offsetY" to "Offset Y", "isTrigger" to "Is Trigger"
        ),
        com.sengine.engine.core.ParticleEmitter2D.TYPE to mapOf(
            "emitting" to "Emitting", "rate" to "Rate", "lifetime" to "Lifetime", "speed" to "Speed",
            "direction" to "Direction", "spread" to "Spread", "startSize" to "Start Size",
            "endSize" to "End Size", "startColor" to "Start Color", "endColor" to "End Color",
            "gravity" to "Gravity", "maxParticles" to "Max Particles"
        ),
        com.sengine.engine.core.ScriptComponent.TYPE to mapOf("script" to "Script", "params" to "Params"),
        com.sengine.engine.core.AudioSource.TYPE to mapOf("clip" to "Clip")
    )

    private fun migrateV1ToV2(root: JVal.Obj): JVal.Obj {
        val objects = root.arr("objects")
        for (item in objects) {
            val o = item as? JVal.Obj ?: continue
            if (o.has("transform")) continue
            val t = JVal.Obj()
            t.put("x", o.f("x")); t.put("y", o.f("y"))
            t.put("rotation", o.f("rotation"))
            t.put("scale", com.sengine.engine.json.jarr(o.f("scaleX", 1f), o.f("scaleY", 1f)))
            t.put("pivot", com.sengine.engine.json.jarr(0.5f, 0.5f))
            t.put("order", o.i("order"))
            o.put("transform", t)

            // legacy component names → current ones (kept in the migration so old projects load)
            val comps = o.arr("components")
            for (c in comps) {
                val co = c as? JVal.Obj ?: continue
                when (co.str("type")) {
                    "SpriteRenderer" -> co.put("type", com.sengine.engine.core.Sprite2D.TYPE)
                    "TextRenderer" -> co.put("type", com.sengine.engine.core.Label2D.TYPE)
                    "ParticleEmitter" -> co.put("type", com.sengine.engine.core.ParticleEmitter2D.TYPE)
                }
                // property keys were renamed over time: carry the old values over to the new names
                val table = LEGACY_FIELDS[co.str("type")] ?: continue
                for ((oldKey, newKey) in table) {
                    if (oldKey == newKey || !co.has(oldKey)) continue
                    val value = co[oldKey] ?: continue
                    if (!co.has(newKey)) co.fields[newKey] = value
                    co.fields.remove(oldKey)
                }
            }
        }
        val settings = root["settings"] as? JVal.Obj
        if (settings == null) {
            val s = JVal.Obj()
            s.put("gravity", com.sengine.engine.json.jarr(root.f("gravityX"), root.f("gravityY", -9.81f)))
            root.put("settings", s)
        }
        return root
    }
}

/** Convenience: node definitions used by prefab-aware duplication and saved selections. */
data class PrefabData(val nodeJson: JVal.Obj, val name: String)
