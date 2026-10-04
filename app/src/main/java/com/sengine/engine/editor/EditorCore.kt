package com.sengine.engine.editor

import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.NodeType
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Scene
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.core.SignalConnection
import com.sengine.engine.json.JVal
import com.sengine.engine.json.Json
import com.sengine.engine.json.jarr
import com.sengine.engine.json.jobj
import com.sengine.engine.project.Atomic
import com.sengine.engine.project.Project
import com.sengine.engine.serialization.SceneFormat
import com.sengine.engine.tilemap.TileBrush
import com.sengine.engine.tilemap.TileLayer
import com.sengine.engine.tilemap.TileMapData
import java.io.File

// ---------------------------------------------------------------------------------------------
// Undo / redo
// ---------------------------------------------------------------------------------------------

/** A reversible editor operation. Commands receive the document so they can work across scenes. */
interface EditCommand {
    val label: String
    fun apply(doc: EditorDocument)
    fun revert(doc: EditorDocument)
}

/**
 * Undo stack with coalescing: dragging a gizmo pushes one command per gesture, typing in a field
 * merges into the previous command within [mergeWindowMs].
 */
class UndoStack {
    private val undoList = ArrayList<EditCommand>()
    private val redoList = ArrayList<EditCommand>()
    var limit = 200
    var mergeWindowMs = 600L

    @Volatile var revision = 0
        private set

    var lastPushTime = 0L
        private set

    val canUndo get() = undoList.isNotEmpty()
    val canRedo get() = redoList.isNotEmpty()
    val undoLabel get() = undoList.lastOrNull()?.label ?: ""
    val redoLabel get() = redoList.lastOrNull()?.label ?: ""
    val depth get() = undoList.size

    fun push(doc: EditorDocument, command: EditCommand, execute: Boolean = true, mergeable: Boolean = false) {
        if (execute) command.apply(doc)
        val now = System.currentTimeMillis()
        val last = undoList.lastOrNull()
        if (mergeable && last != null && last::class == command::class &&
            last.label == command.label && now - lastPushTime < mergeWindowMs
        ) {
            // coalesce by stacking: keep the original (before state) and the newest values
            val merged = mergeCommands(last, command)
            if (merged != null) {
                undoList[undoList.size - 1] = merged
                lastPushTime = now
                redoList.clear()
                revision++
                return
            }
        }
        undoList.add(command)
        if (undoList.size > limit) undoList.removeAt(0)
        redoList.clear()
        lastPushTime = now
        revision++
    }

    private fun mergeCommands(a: EditCommand, b: EditCommand): EditCommand? = when {
        a is PropertyCommand && b is PropertyCommand -> PropertyCommand(a.label, mergeEdits(a, b))
        else -> null
    }

    private fun mergeEdits(a: PropertyCommand, b: PropertyCommand): List<PropertyEdit> {
        val byKey = LinkedHashMap<String, PropertyEdit>()
        for (e in a.edits) byKey[e.key] = e
        for (e in b.edits) {
            val existing = byKey[e.key]
            byKey[e.key] = if (existing == null) e else existing.copy(newValue = e.newValue)
        }
        return byKey.values.toList()
    }

    fun undo(doc: EditorDocument): Boolean {
        val cmd = undoList.removeLastOrNull() ?: return false
        cmd.revert(doc)
        redoList.add(cmd)
        revision++
        return true
    }

    fun redo(doc: EditorDocument): Boolean {
        val cmd = redoList.removeLastOrNull() ?: return false
        cmd.apply(doc)
        undoList.add(cmd)
        revision++
        return true
    }

    fun clear() {
        undoList.clear()
        redoList.clear()
        revision++
    }

    fun history(): List<String> = undoList.map { it.label }.reversed()
}

/** Property addressed by node + optional component + property name. */
data class PropertyEdit(
    val nodeId: Long,
    val componentType: String?,
    val propName: String,
    val oldValue: JVal,
    val newValue: JVal
) {
    val key get() = "$nodeId|${componentType ?: ""}|$propName"
}

/** Generic property command — powers move, rotate, scale, inspector edits and multi-object edits. */
class PropertyCommand(override val label: String, val edits: List<PropertyEdit>) : EditCommand {
    override fun apply(doc: EditorDocument) = writeAll(doc, edits, useNew = true)
    override fun revert(doc: EditorDocument) = writeAll(doc, edits, useNew = false)

    private fun writeAll(doc: EditorDocument, edits: List<PropertyEdit>, useNew: Boolean) {
        for (e in edits) {
            val node = doc.scene.findById(e.nodeId) ?: continue
            val value = if (useNew) e.newValue else e.oldValue
            NodeProps.write(node, e.componentType, e.propName, value)
        }
        doc.onSceneMutated()
    }
}

/** Reads/writes node and component properties by name — the shared vocabulary of undo + inspector. */
object NodeProps {
    val NODE_PROPS = listOf(
        "name", "tag", "active", "visible", "locked", "layer", "order",
        "x", "y", "rotation", "scaleX", "scaleY", "pivotX", "pivotY"
    )

    fun read(node: GameObject, componentType: String?, propName: String): JVal {
        if (componentType == null) {
            return when (propName) {
                "name" -> JVal.Str(node.name)
                "tag" -> JVal.Str(node.tag)
                "active" -> JVal.Bool(node.active)
                "visible" -> JVal.Bool(node.visible)
                "locked" -> JVal.Bool(node.locked)
                "layer" -> JVal.Str(node.layer)
                "order" -> JVal.Num(node.order.toDouble())
                "x" -> JVal.Num(node.x.toDouble())
                "y" -> JVal.Num(node.y.toDouble())
                "rotation" -> JVal.Num(node.rotation.toDouble())
                "scaleX" -> JVal.Num(node.scaleX.toDouble())
                "scaleY" -> JVal.Num(node.scaleY.toDouble())
                "pivotX" -> JVal.Num(node.pivotX.toDouble())
                "pivotY" -> JVal.Num(node.pivotY.toDouble())
                "type" -> JVal.Str(node.type)
                "groups" -> JVal.Arr.strings(node.groups.toList())
                else -> JVal.Null
            }
        }
        val comp = node.components.firstOrNull { it.type == componentType } ?: return JVal.Null
        if (propName == "__enabled") return JVal.Bool(comp.enabled)
        if (propName == "__type") return JVal.Str(comp.type)
        return comp.prop(propName)?.encode() ?: JVal.Null
    }

    fun write(node: GameObject, componentType: String?, propName: String, value: JVal): Boolean {
        if (componentType == null) {
            when (propName) {
                "name" -> node.name = (value as? JVal.Str)?.v ?: return false
                "tag" -> node.tag = (value as? JVal.Str)?.v ?: return false
                "active" -> node.active = (value as? JVal.Bool)?.v ?: return false
                "visible" -> node.visible = (value as? JVal.Bool)?.v ?: return false
                "locked" -> node.locked = (value as? JVal.Bool)?.v ?: return false
                "layer" -> node.layer = (value as? JVal.Str)?.v ?: return false
                "order" -> node.order = (value as? JVal.Num)?.v?.toInt() ?: return false
                "x" -> node.x = (value as? JVal.Num)?.v?.toFloat() ?: return false
                "y" -> node.y = (value as? JVal.Num)?.v?.toFloat() ?: return false
                "rotation" -> node.rotation = (value as? JVal.Num)?.v?.toFloat() ?: return false
                "scaleX" -> node.scaleX = (value as? JVal.Num)?.v?.toFloat() ?: return false
                "scaleY" -> node.scaleY = (value as? JVal.Num)?.v?.toFloat() ?: return false
                "pivotX" -> node.pivotX = (value as? JVal.Num)?.v?.toFloat() ?: return false
                "pivotY" -> node.pivotY = (value as? JVal.Num)?.v?.toFloat() ?: return false
                "type" -> node.type = (value as? JVal.Str)?.v ?: return false
                "groups" -> {
                    node.groups.clear()
                    (value as? JVal.Arr)?.forEach { (it as? JVal.Str)?.let { s -> node.groups.add(s.v) } }
                }
                else -> return false
            }
            node.revision++
            return true
        }
        val comp = node.components.firstOrNull { it.type == componentType } ?: return false
        if (propName == "__enabled") {
            comp.enabled = (value as? JVal.Bool)?.v ?: return false
            return true
        }
        val p = comp.prop(propName) ?: return false
        p.decode(value)
        node.revision++
        return true
    }

    fun capture(node: GameObject, componentType: String?, propNames: List<String>): List<PropertyEdit> =
        propNames.mapNotNull { name ->
            val v = read(node, componentType, name)
            if (v is JVal.Null) null else PropertyEdit(node.id, componentType, name, v, v)
        }
}

/** Move/rotate/scale a set of nodes (one command per gizmo gesture). */
class TransformCommand(
    override val label: String,
    private val edits: List<PropertyEdit>
) : EditCommand {
    private val delegate = PropertyCommand(label, edits)
    override fun apply(doc: EditorDocument) = delegate.apply(doc)
    override fun revert(doc: EditorDocument) = delegate.revert(doc)

    companion object {
        fun between(label: String, before: Map<Long, FloatArray>, after: Map<Long, FloatArray>): TransformCommand {
            val edits = ArrayList<PropertyEdit>()
            for ((id, a) in before) {
                val b = after[id] ?: continue
                for (i in 0 until 5) {
                    val prop = when (i) {
                        0 -> "x"; 1 -> "y"; 2 -> "rotation"; 3 -> "scaleX"; else -> "scaleY"
                    }
                    if (a[i] != b[i]) {
                        edits.add(PropertyEdit(id, null, prop, JVal.Num(a[i].toDouble()), JVal.Num(b[i].toDouble())))
                    }
                }
            }
            return TransformCommand(label, edits)
        }
    }
}

/** Create / delete / duplicate / reparent / rename / component operations. */
class CreateNodeCommand(
    override val label: String,
    private val nodeJson: JVal.Obj,
    private val parentId: Long,
    private val makeActive: Boolean = true
) : EditCommand {
    var createdId: Long = 0

    override fun apply(doc: EditorDocument) {
        val scene = doc.scene
        val node = scene.create(nodeJson.str("name", "Node"), parentId.takeIf { it > 0 }?.let { scene.findById(it) }, nodeJson.str("type", NodeType.NODE))
        val previousId = nodeJson.l("id")
        SceneFormat.applyObjectJson(node, nodeJson)
        createdId = node.id
        // keep component references stable by applying the recorded id mapping
        if (previousId != 0L && previousId != node.id) doc.remapIds[previousId] = node.id
        doc.onStructureChanged()
    }

    override fun revert(doc: EditorDocument) {
        val node = doc.scene.findById(createdId) ?: return
        doc.scene.remove(node)
        doc.onStructureChanged()
    }

    fun serialized(): JVal.Obj = nodeJson.deepCopy() as JVal.Obj
    val isActive get() = makeActive
}

class DeleteNodesCommand(
    override val label: String,
    private val nodes: List<Pair<JVal.Obj, Long>>   // serialized node + parent id
) : EditCommand {
    private val restoredIds = ArrayList<Long>()

    companion object {
        /** Snapshots the nodes (with their parents) so the delete can be undone exactly. */
        fun of(scene: Scene, ids: List<Long>, label: String = "Delete ${ids.size} node(s)"): DeleteNodesCommand {
            val list = ArrayList<Pair<JVal.Obj, Long>>(ids.size)
            for (id in ids) {
                val node = scene.findById(id) ?: continue
                list.add(SceneFormat.objectToJson(node) to (node.parent?.id ?: 0L))
            }
            return DeleteNodesCommand(label, list)
        }
    }

    override fun apply(doc: EditorDocument) {
        for ((json, _) in nodes) {
            val id = json.l("id")
            val node = doc.scene.findById(id) ?: continue
            doc.scene.remove(node)
        }
        doc.onStructureChanged()
    }

    override fun revert(doc: EditorDocument) {
        restoredIds.clear()
        for ((json, parentId) in nodes) {
            val parent = parentId.takeIf { it > 0 }?.let { doc.scene.findById(it) }
            val node = doc.scene.create(json.str("name", "Node"), parent, json.str("type", NodeType.NODE))
            SceneFormat.applyObjectJson(node, json)
            restoredIds.add(node.id)
        }
        doc.onStructureChanged()
    }
}

class ReparentCommand(
    override val label: String,
    private val nodeId: Long,
    private val newParentId: Long,
    private val oldParentId: Long,
    private val keepWorld: Boolean = true
) : EditCommand {
    override fun apply(doc: EditorDocument) {
        val node = doc.scene.findById(nodeId) ?: return
        doc.scene.reparent(node, newParentId.takeIf { it > 0 }?.let { doc.scene.findById(it) }, keepWorld)
        doc.onStructureChanged()
    }

    override fun revert(doc: EditorDocument) {
        val node = doc.scene.findById(nodeId) ?: return
        doc.scene.reparent(node, oldParentId.takeIf { it > 0 }?.let { doc.scene.findById(it) }, keepWorld)
        doc.onStructureChanged()
    }
}

class RenameNodeCommand(override val label: String, private val nodeId: Long, private val newName: String) : EditCommand {
    private var oldName = ""

    override fun apply(doc: EditorDocument) {
        val node = doc.scene.findById(nodeId) ?: return
        oldName = node.name
        node.name = newName
        doc.onStructureChanged()
    }

    override fun revert(doc: EditorDocument) {
        doc.scene.findById(nodeId)?.name = oldName
        doc.onStructureChanged()
    }
}

class AddComponentCommand(override val label: String, private val nodeId: Long, private val component: Component) : EditCommand {
    override fun apply(doc: EditorDocument) {
        val node = doc.scene.findById(nodeId) ?: return
        node.add(component.copy())
        doc.onStructureChanged()
    }

    override fun revert(doc: EditorDocument) {
        val node = doc.scene.findById(nodeId) ?: return
        node.components.removeAll { it.type == component.type }
        doc.onStructureChanged()
    }
}

class RemoveComponentCommand(override val label: String, private val nodeId: Long, private val index: Int) : EditCommand {
    private var removed: Component? = null

    override fun apply(doc: EditorDocument) {
        val node = doc.scene.findById(nodeId) ?: return
        if (index !in node.components.indices) return
        removed = node.components.removeAt(index)
        doc.onStructureChanged()
    }

    override fun revert(doc: EditorDocument) {
        val node = doc.scene.findById(nodeId) ?: return
        val c = removed ?: return
        val at = index.coerceIn(0, node.components.size)
        node.components.add(at, c)
        c.gameObject = node
        doc.onStructureChanged()
    }
}

/** Tile painting is one command per stroke, so a whole brush stroke undoes at once. */
class TilePaintCommand(
    override val label: String,
    private val nodeId: Long,
    private val layerName: String,
    private val cells: IntArray,        // x, y, before, after quadruples
    private val layersBefore: TileMapData? = null,
    private val layersAfter: TileMapData? = null
) : EditCommand {
    override fun apply(doc: EditorDocument) = write(doc, true)
    override fun revert(doc: EditorDocument) = write(doc, false)

    private fun write(doc: EditorDocument, forward: Boolean) {
        val node = doc.scene.findById(nodeId) ?: return
        val map = node.getAny<com.sengine.engine.core.TileMap2D>() ?: return
        val target: TileMapData? = if (layersAfter != null && layersBefore != null) (if (forward) layersAfter else layersBefore) else null
        if (target != null) {
            val layer = map.data.layer(layerName) ?: target.layer(layerName)
            if (layer != null) {
                layer.width = target.layer(layerName)?.width ?: layer.width
                layer.height = target.layer(layerName)?.height ?: layer.height
                layer.cells = (target.layer(layerName)?.cells ?: layer.cells).copyOf()
            }
            // structural changes (layer add/remove/resize) copy the full document
            map.data.layers.clear()
            for (l in target.layers) map.data.layers.add(l.copy())
        } else {
            val layer = map.data.layer(layerName) ?: return
            var i = 0
            while (i + 3 < cells.size) {
                layer.cells[layer.index(cells[i], cells[i + 1])] = if (forward) cells[i + 3] else cells[i + 2]
                i += 4
            }
        }
        doc.onStructureChanged()
    }
}

class ConnectSignalCommand(
    override val label: String,
    private val connection: SignalConnection
) : EditCommand {
    override fun apply(doc: EditorDocument) {
        doc.scene.signals.connect(
            doc.scene.findById(connection.sourceId) ?: return,
            connection.signal,
            doc.scene.findById(connection.targetId) ?: return,
            connection.method, connection.args
        )
        doc.onStructureChanged()
    }

    override fun revert(doc: EditorDocument) {
        doc.scene.signals.connectionRemove(connection)
        doc.onStructureChanged()
    }
}

class DisconnectSignalCommand(override val label: String, private val connection: SignalConnection) : EditCommand {
    private var snapshot: SignalConnection? = null

    override fun apply(doc: EditorDocument) {
        snapshot = connection.copy()
        doc.scene.signals.connectionRemove(connection)
        doc.onStructureChanged()
    }

    override fun revert(doc: EditorDocument) {
        val c = snapshot ?: return
        doc.scene.signals.connections.add(c)
        doc.onStructureChanged()
    }
}

private fun com.sengine.engine.core.SignalHub.connectionRemove(c: SignalConnection) {
    connections.removeAll {
        it.sourceId == c.sourceId && it.signal == c.signal && it.targetId == c.targetId && it.method == c.method
    }
}

// ---------------------------------------------------------------------------------------------
// Selection
// ---------------------------------------------------------------------------------------------

class Selection {
    private val ids = LinkedHashSet<Long>()

    @Volatile var revision = 0
        private set

    val primaryId: Long get() = ids.lastOrNull() ?: -1L
    val size get() = ids.size
    val isEmpty get() = ids.isEmpty()

    fun ids(): List<Long> = ids.toList()
    fun contains(id: Long) = ids.contains(id)

    fun select(id: Long) {
        if (ids.size == 1 && ids.contains(id)) return
        ids.clear()
        if (id > 0) ids.add(id)
        revision++
    }

    fun add(id: Long) {
        if (ids.add(id)) revision++
    }

    fun toggle(id: Long) {
        if (!ids.remove(id)) ids.add(id)
        revision++
    }

    fun set(list: Collection<Long>) {
        ids.clear()
        ids.addAll(list)
        revision++
    }

    fun clear() {
        if (ids.isEmpty()) return
        ids.clear()
        revision++
    }

    fun nodes(scene: Scene): List<GameObject> = ids.mapNotNull { scene.findById(it) }
}

// ---------------------------------------------------------------------------------------------
// Document
// ---------------------------------------------------------------------------------------------

/**
 * The open project + open scene + undo history + dirty tracking.
 * Every mutation goes through here so autosave, crash recovery and the profiler stay in sync.
 */
class EditorDocument(var project: Project, scene: Scene = Scene("Main")) {
    interface Listener {
        fun onSceneChanged(scene: Scene) {}
        fun onStructureChanged() {}
        fun onDirtyChanged(dirty: Boolean) {}
        fun onSelectionChanged() {}
    }

    var scene: Scene = scene
        private set

    var sceneName: String = scene.name
    var dirty = false
        private set

    val undo = UndoStack()
    val selection = Selection()
    val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /** Old → new id map produced by undo/redo of created nodes (used to fix references). */
    val remapIds = HashMap<Long, Long>()

    var lastSavedAt = 0L
        private set

    var openSceneNames: MutableList<String> = ArrayList()

    fun openScene(name: String) {
        val loaded = project.loadScene(name)
        scene = loaded
        sceneName = name
        undo.clear()
        selection.clear()
        dirty = false
        remapIds.clear()
        lastSavedAt = System.currentTimeMillis()
        listeners.forEach { it.onSceneChanged(scene) }
        notifySelection()
    }

    fun replaceScene(newScene: Scene, markDirty: Boolean = true) {
        scene = newScene
        sceneName = newScene.name
        if (markDirty) setDirty(true)
        listeners.forEach { it.onSceneChanged(scene) }
        notifySelection()
    }

    fun save(): Boolean {
        val ok = project.saveScene(scene)
        if (ok) {
            setDirty(false)
            lastSavedAt = System.currentTimeMillis()
        }
        return ok
    }

    fun saveAs(name: String): Boolean {
        scene.name = name
        sceneName = name
        return save()
    }

    fun setDirty(value: Boolean) {
        if (dirty == value) return
        dirty = value
        listeners.forEach { it.onDirtyChanged(value) }
    }

    fun onSceneMutated() {
        setDirty(true)
        listeners.forEach { it.onStructureChanged() }
    }

    fun onStructureChanged() {
        scene.structureRevision++
        setDirty(true)
        listeners.forEach { it.onStructureChanged() }
    }

    fun notifySelection() = listeners.forEach { it.onSelectionChanged() }

    /** Autosave: writes a recovery copy so a crash never loses more than the last interval. */
    fun autosave(): Boolean {
        if (!dirty) return false
        val file = recoveryFile()
        file.parentFile?.mkdirs()
        return Atomic.write(file, SceneFormat.write(scene))
    }

    /**
     * Saves a node (with its children) as a reusable prefab JSON asset. Prefabs can be dropped back
     * into any scene through the Scene panel or instantiated from scripts.
     */
    fun serializePrefab(node: GameObject, fileName: String): Boolean {
        val json = com.sengine.engine.serialization.SceneFormat.objectToJson(node)
        val name = if (fileName.endsWith(".json")) fileName else "$fileName.json"
        return project.writeAsset("prefabs/$name", com.sengine.engine.json.Json.write(json, true))
    }

    /** Instantiates a prefab asset into the current scene, returning the created nodes. */
    fun instantiatePrefab(assetName: String, parent: GameObject? = null): GameObject? {
        val text = project.readAsset(assetName) ?: return null
        return runCatching {
            val created = scene.create("Prefab", parent, com.sengine.engine.core.NodeType.NODE)
            com.sengine.engine.serialization.SceneFormat.applyObjectJson(created, Json.parseObject(text))
            if (parent != null) scene.reparent(created, parent)
            scene.structureRevision++
            onStructureChanged()
            created
        }.getOrNull()
    }

    fun recoveryFile() = File(File(project.dir, ".recovery"), "$sceneName.autosave.json")

    fun hasRecovery(): Boolean = recoveryFile().exists()

    fun recoverFromAutosave(): Boolean {
        val f = recoveryFile()
        if (!f.exists()) return false
        val restored = SceneFormat.fromJson(Json.parseObject(f.readText()))
        restored.name = sceneName
        scene = restored
        setDirty(true)
        listeners.forEach { it.onSceneChanged(scene) }
        return true
    }

    fun clearRecovery() {
        recoveryFile().delete()
    }
}

// ---------------------------------------------------------------------------------------------
// Editor settings, workspaces and the command registry
// ---------------------------------------------------------------------------------------------

class EditorSettings {
    var theme = "Dark"
    var accent = 0xFF4C8DFF.toInt()
    var uiScale = 1f
    var showGrid = true
    var showColliders = true
    var showGuides = true
    var showRulers = false
    var snapEnabled = true
    var snapStep = 0.25f
    var gridStep = 1f
    var autosaveSeconds = 60
    var saveBeforePlay = true
    var touchTools = true
    var workspaces = LinkedHashMap<String, WorkspaceLayout>()

    fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("theme", theme)
        o.put("accent", Prop.C.format(accent))
        o.put("uiScale", uiScale)
        o.put("showGrid", showGrid)
        o.put("showColliders", showColliders)
        o.put("showGuides", showGuides)
        o.put("showRulers", showRulers)
        o.put("snap", jarr(snapEnabled, snapStep))
        o.put("grid", gridStep)
        o.put("autosave", autosaveSeconds)
        o.put("saveBeforePlay", saveBeforePlay)
        o.put("workspaces", JVal.Obj().also { w -> workspaces.forEach { (k, v) -> w.put(k, v.toJson()) } })
        return o
    }

    fun fromJson(o: JVal.Obj) {
        theme = o.str("theme", theme)
        accent = Prop.C.parse(o.str("accent", "#FF4C8DFF"))
        uiScale = o.f("uiScale", 1f).coerceIn(0.75f, 2f)
        showGrid = o.bool("showGrid", true)
        showColliders = o.bool("showColliders", true)
        showGuides = o.bool("showGuides", true)
        showRulers = o.bool("showRulers", false)
        val s = o.arr("snap")
        if (s.size >= 2) {
            snapEnabled = (s[0] as? JVal.Bool)?.v == true
            snapStep = (s[1] as? JVal.Num)?.v?.toFloat() ?: snapStep
        }
        gridStep = o.f("grid", gridStep)
        autosaveSeconds = o.i("autosave", autosaveSeconds)
        saveBeforePlay = o.bool("saveBeforePlay", true)
        workspaces.clear()
        (o["workspaces"] as? JVal.Obj)?.fields?.forEach { (k, v) -> workspaces[k] = WorkspaceLayout.fromJson(k, v as? JVal.Obj ?: JVal.Obj()) }
    }
}

/** Docked panel placement in a workspace. */
class PanelLayout(var id: String, var dock: String = "left", var visible: Boolean = true, var weight: Float = 0.25f, var order: Int = 0, var tabbedWith: String = "") {
    fun toJson(): JVal.Obj = jobj("dock" to dock, "visible" to visible, "weight" to weight, "order" to order, "tab" to tabbedWith)

    companion object {
        fun fromJson(id: String, o: JVal.Obj) = PanelLayout(id, o.str("dock", "left"), o.bool("visible", true), o.f("weight", 0.25f), o.i("order"), o.str("tab"))
    }
}

/** A saved layout of the editor: which panels exist, where they dock and their sizes. */
class WorkspaceLayout(var name: String = "Default", val panels: LinkedHashMap<String, PanelLayout> = LinkedHashMap()) {
    fun panel(id: String, dock: String = "left"): PanelLayout = panels.getOrPut(id) { PanelLayout(id, dock) }

    fun toJson(): JVal.Obj = jobj("panels" to JVal.Obj().also { o -> panels.forEach { (k, v) -> o.put(k, v.toJson()) } })

    companion object {
        fun fromJson(name: String, o: JVal.Obj): WorkspaceLayout {
            val w = WorkspaceLayout(name)
            (o["panels"] as? JVal.Obj)?.fields?.forEach { (k, v) ->
                w.panels[k] = PanelLayout.fromJson(k, v as? JVal.Obj ?: JVal.Obj())
            }
            return w
        }

        /** Docks: left column, centre viewport, right inspector, bottom tabs. */
        fun default(): WorkspaceLayout {
            val w = WorkspaceLayout("Default")
            w.panel("scene_tree", "left").weight = 0.26f
            w.panel("filesystem", "left").weight = 0.74f
            w.panel("inspector", "right").weight = 1f
            w.panel("output", "bottom")
            w.panel("animation", "bottom").tabbedWith = "output"
            w.panel("tilemap", "bottom").tabbedWith = "output"
            w.panel("particles", "bottom").tabbedWith = "output"
            w.panel("profiler", "bottom").tabbedWith = "output"
            w.panel("debugger", "bottom").tabbedWith = "output"
            w.panel("audio", "bottom").tabbedWith = "output"
            return w
        }

        fun uiEditing(): WorkspaceLayout {
            val w = default()
            w.panel("ui_editor", "bottom").visible = true
            return w
        }
    }
}

/** A command palette entry. [run] performs the real operation. */
class EditorCommand(
    val id: String,
    val title: String,
    val category: String,
    val shortcut: String = "",
    val keywords: String = "",
    val run: () -> Unit
)

/** Command registry + fuzzy search used by the command palette and the shortcuts panel. */
class CommandRegistry {
    private val commands = LinkedHashMap<String, EditorCommand>()

    fun register(cmd: EditorCommand) {
        commands[cmd.id] = cmd
    }

    fun register(id: String, title: String, category: String, shortcut: String = "", keywords: String = "", run: () -> Unit) =
        register(EditorCommand(id, title, category, shortcut, keywords, run))

    fun all(): List<EditorCommand> = commands.values.toList()

    fun byId(id: String) = commands[id]

    fun search(query: String, limit: Int = 12): List<EditorCommand> {
        if (query.isBlank()) return all().take(limit)
        val q = query.lowercase().trim()
        val scored = ArrayList<Pair<EditorCommand, Int>>()
        for (c in commands.values) {
            val title = c.title.lowercase()
            val hay = "$title ${c.category.lowercase()} ${c.keywords.lowercase()}"
            var score = 0
            if (title == q) score = 1000
            else if (title.startsWith(q)) score = 900 - title.length
            else if (title.contains(q)) score = 700 - title.length
            else if (fuzzyMatch(hay, q)) score = 500 - title.length
            if (score > 0) scored.add(c to score)
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun fuzzyMatch(text: String, query: String): Boolean {
        var ti = 0
        for (ch in query) {
            val idx = text.indexOf(ch, ti)
            if (idx < 0) return false
            ti = idx + 1
        }
        return true
    }

    fun clear() = commands.clear()
}

/** Brush state of the tilemap editor (shared with the viewport tools). */
class TileBrushState {
    var active = false
    var mode = TileBrush.Mode.PAINT
    var tileId = 1
    var brushSize = 1
    var layerIndex = 0
    var random = false
    var autotile = false
    var terrainGroup = ""
    var filledRect = true
}

/**
 * Records the cells a brush stroke touches so the whole stroke becomes one undo step.
 * Cells are stored as `(x, y, before, after)` quadruples — the same shape [TilePaintCommand] uses.
 */
class TileStroke(private val nodeId: Long, private val layerName: String, private val layerWidth: Int) {
    private val changes = LinkedHashMap<Int, IntArray>()

    fun record(index: Int, before: Int, after: Int) {
        if (before == after) return
        val existing = changes[index]
        if (existing == null) changes[index] = intArrayOf(before, after) else existing[1] = after
    }

    val size get() = changes.size

    fun toCommand(label: String): TilePaintCommand? {
        if (changes.isEmpty()) return null
        val cells = IntArray(changes.size * 4)
        var i = 0
        for ((index, values) in changes) {
            if (values[0] == values[1]) continue
            cells[i] = index % layerWidth
            cells[i + 1] = index / layerWidth
            cells[i + 2] = values[0]
            cells[i + 3] = values[1]
            i += 4
        }
        if (i == 0) return null
        return TilePaintCommand(label, nodeId, layerName, cells.copyOf(i))
    }
}

/** Snapshot helper used before a stroke starts (undo of a whole gesture). */
class StrokeSnapshot(val layer: TileLayer?, val cells: IntArray, val data: TileMapData?)
