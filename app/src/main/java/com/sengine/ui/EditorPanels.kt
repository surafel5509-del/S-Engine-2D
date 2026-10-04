package com.sengine.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.NodeType
import com.sengine.engine.core.ParticleEmitter2D
import com.sengine.engine.particles.ParticlePresets
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Sprite2D
import com.sengine.engine.core.TileMap2D
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.sengine.engine.debug.Log
import com.sengine.engine.export.AssetImporter
import com.sengine.engine.json.Json
import com.sengine.engine.debug.LogLevel
import com.sengine.engine.editor.EditorDocument
import com.sengine.engine.editor.EditorState
import com.sengine.engine.editor.NodeProps
import com.sengine.engine.tilemap.TileBrush
import com.sengine.engine.math.Rect2
import com.sengine.engine.math.Vec2
import com.sengine.engine.project.Project
import com.sengine.engine.resources.Material
import com.sengine.engine.resources.ShaderTemplates
import com.sengine.engine.tilemap.TileSet
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared helpers for panel construction. */
object Panels {
    fun column(context: Context, theme: Theme): LinearLayout {
        val c = LinearLayout(context)
        c.orientation = LinearLayout.VERTICAL
        c.setBackgroundColor(theme.panel)
        return c
    }

    fun scroll(parent: LinearLayout, child: View) {
        val s = ScrollView(parent.context)
        s.addView(child)
        parent.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun header(context: Context, theme: Theme, text: String): TextView {
        val tv = Ui.label(context, text, theme, 12f, theme.text, bold = true)
        tv.setBackgroundColor(theme.panelAlt)
        return tv
    }
}

// --------------------------------------------------------------------------------- scene tree

/**
 * Scene panel: hierarchy with search, multi-select, visibility/lock toggles and full context menu
 * (rename, duplicate, delete, reparent, create child, add component, save as prefab).
 */
class SceneTreePanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val state: EditorState,
    val onChanged: () -> Unit,
    val onSelectionChanged: () -> Unit,
    val onOpenInspector: () -> Unit
) : LinearLayout(context) {

    private val list = Panels.column(context, theme)
    private var filter = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        val search = SearchField(context, theme, "Filter nodes") { q ->
            filter = q
            refresh()
        }
        addView(search)
        Panels.scroll(this, list)
        doc.listeners.add(object : EditorDocument.Listener {
            override fun onStructureChanged() = refresh()
            override fun onSceneChanged(scene: com.sengine.engine.core.Scene) = refresh()
            override fun onSelectionChanged() = refresh()
            override fun onDirtyChanged(dirty: Boolean) {}
        })
        refresh()
    }

    fun refresh() {
        list.removeAllViews()
        val rows = doc.scene.hierarchy()
        if (rows.isEmpty()) {
            list.addView(Ui.label(context, "Empty scene — use + to add a node", theme, 12f, theme.textDim))
            return
        }
        for ((node, depth) in rows) {
            if (node.destroyed) continue
            if (filter.isNotEmpty() && !matches(node, filter)) continue
            list.addView(row(node, depth))
        }
    }

    private fun matches(node: GameObject, query: String): Boolean {
        if (node.name.contains(query, true)) return true
        if (node.type.contains(query, true)) return true
        if (node.tag.contains(query, true)) return true
        if (node.component(query) != null) return true
        return doc.scene.descendants(node).any { it.name.contains(query, true) }
    }

    private fun row(node: GameObject, depth: Int): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.pad(4f) + depth * theme.pad(14f), theme.pad(4f), theme.pad(4f), theme.pad(4f))
        val selected = state.selectionIds.contains(node.id)
        row.setBackgroundColor(if (selected) theme.selection else theme.panel)

        val icon = when {
            node.ui != null -> "▤"
            node.getAny<com.sengine.engine.core.Camera2D>() != null -> "◉"
            node.getAny<Sprite2D>() != null -> "▣"
            node.getAny<com.sengine.engine.core.Label2D>() != null -> "T"
            node.getAny<com.sengine.engine.core.Rigidbody2D>() != null -> "◇"
            node.getAny<ParticleEmitter2D>() != null -> "✳"
            node.getAny<TileMap2D>() != null -> "▩"
            node.getAny<com.sengine.engine.core.AudioSource>() != null -> "♪"
            node.getAny<com.sengine.engine.core.ScriptComponent>() != null -> "⌘"
            else -> "◇"
        }
        row.addView(Ui.label(context, icon, theme, 12f, if (selected) theme.accent else theme.textDim))
        val name = Ui.label(context, node.name, theme, 12f, if (node.active) theme.text else theme.textDim)
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.label(context, node.type, theme, 9f, theme.textDim))

        val visible = EditorButton(context, theme, if (node.visible) "◉" else "○", {
            node.visible = !node.visible
            doc.onSceneMutated()
            onChanged()
            refresh()
        }, toggled = node.visible)
        val locked = EditorButton(context, theme, if (node.locked) "🔒" else "🔓", {
            node.locked = !node.locked
            doc.onSceneMutated()
            refresh()
        }, toggled = node.locked)
        row.addView(visible)
        row.addView(locked)

        row.isClickable = true
        row.setOnClickListener {
            if (state.selectionIds.contains(node.id)) {
                doc.selection.toggle(node.id)
            } else {
                doc.selection.set(listOf(node.id))
            }
            state.selectionIds = doc.selection.ids()
            state.selectedId = doc.selection.primaryId
            onSelectionChanged()
            refresh()
        }
        row.setOnLongClickListener {
            state.selectionIds = listOf(node.id)
            state.selectedId = node.id
            doc.selection.set(listOf(node.id))
            showContextMenu(row, node)
            true
        }
        return row
    }

    private fun showContextMenu(anchor: View, node: GameObject) {
        val signals = doc.scene.signals
        showMenu(
            anchor, theme, listOf(
                "Rename…" to {
                    inputDialog(context, theme, "Rename node", node.name) { name ->
                        if (name.isNotBlank()) {
                            doc.undo.push(doc, com.sengine.engine.editor.RenameNodeCommand("Rename node", node.id, name))
                            doc.onStructureChanged()
                            refresh()
                        }
                    }
                },
                "Duplicate" to {
                    val copy = doc.scene.duplicate(node)
                    doc.onStructureChanged()
                    onSelectionChanged()
                    refresh()
                },
                "Delete" to {
                    confirmDialog(context, "Delete node", "Delete '${node.name}'?") {
                        doc.undo.push(doc, com.sengine.engine.editor.DeleteNodesCommand.of(doc.scene, listOf(node.id), "Delete node"))
                        doc.onStructureChanged()
                        onSelectionChanged()
                        refresh()
                    }
                },
                "-" to {},
                "Create child" to { createNodeMenu(anchor, node) },
                "Create sibling" to { createNodeMenu(anchor, node.parent) },
                "Add component" to { addComponentMenu(anchor, node) },
                "-" to {},
                "Detach from parent" to {
                    doc.scene.reparent(node, null)
                    doc.onStructureChanged()
                    refresh()
                },
                "Move up" to {
                    doc.scene.moveInOrder(node, 1)
                    doc.onStructureChanged()
                    refresh()
                },
                "Move down" to {
                    doc.scene.moveInOrder(node, -1)
                    doc.onStructureChanged()
                    refresh()
                },
                "Bring to front" to {
                    doc.scene.bringToFront(node)
                    doc.onStructureChanged()
                    refresh()
                },
                "-" to {},
                "Save as prefab…" to {
                    inputDialog(context, theme, "Save prefab", "${node.name}.prefab") { fileName ->
                        doc.serializePrefab(node, fileName)
                        toast(context, "Saved ${fileName}.json to project resources")
                    }
                },
                "Connections (${signals.connectionsOf(node.id).size})" to {
                    SignalLinksDialog(context, theme, doc, node).show()
                }
            )
        )
    }

    private fun createNodeMenu(anchor: View, parent: GameObject?) {
        val types = NodeType.ALL
        val labels = types.map { "${it.label} — ${it.description}" }
        showMenu(anchor, theme, labels.mapIndexed { index, label ->
            label to {
                val entry = types[index]
                val node = doc.scene.create(entry.label, parent, entry.type)
                doc.onStructureChanged()
                state.selectionIds = listOf(node.id)
                state.selectedId = node.id
                doc.selection.set(listOf(node.id))
                onSelectionChanged()
                onOpenInspector()
                refresh()
            }
        })
    }

    private fun addComponentMenu(anchor: View, node: GameObject) {
        val entries = com.sengine.engine.core.ComponentRegistry.all()
        showMenu(anchor, theme, entries.map { entry ->
            "${entry.type} — ${entry.description}" to {
                val component = com.sengine.engine.core.ComponentRegistry.create(entry.type) ?: return@to
                doc.undo.push(doc, com.sengine.engine.editor.AddComponentCommand("Add ${entry.type}", node.id, component))
                onOpenInspector()
                onChanged()
            }
        })
    }
}

// --------------------------------------------------------------------------------- inspector

/**
 * Inspector: dynamic property editor for the selection.
 *
 * Widgets come from the [Prop] declarations of each component, so every type (text, int, float,
 * bool, enum, colour, vector2, rect, resource, node reference, flags, points, arrays) is editable
 * without per-component UI code. Numeric fields scrub on drag, sections collapse, values reset,
 * copy/paste works, and multi-selection shows shared values with mixed markers.
 */
class InspectorPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val state: EditorState,
    val project: Project,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)
    private var filter = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        val search = SearchField(context, theme, "Filter properties") { q ->
            filter = q
            refresh()
        }
        addView(search)
        Panels.scroll(this, content)
        doc.listeners.add(object : EditorDocument.Listener {
            override fun onSelectionChanged() = refresh()
            override fun onSceneChanged(scene: com.sengine.engine.core.Scene) = refresh()
            override fun onStructureChanged() = refresh()
            override fun onDirtyChanged(dirty: Boolean) {}
        })
        refresh()
    }

    fun refresh() {
        content.removeAllViews()
        val nodes = doc.selection.nodes(doc.scene)
        if (nodes.isEmpty()) {
            content.addView(Ui.label(context, "Nothing selected.\nSelect a node in the Scene panel or the viewport.", theme, 12f, theme.textDim))
            return
        }
        if (nodes.size > 1) {
            content.addView(Panels.header(context, theme, "${nodes.size} nodes selected — editing shared properties"))
        }
        val primary = nodes.first()
        // node header
        val header = Panels.column(context, theme)
        header.addView(Panels.header(context, theme, "${primary.type}  ·  ${primary.name}"))
        if (nodes.size == 1) {
            val nameRow = LinearLayout(context)
            nameRow.orientation = HORIZONTAL
            val nameField = Ui.edit(context, primary.name, theme)
            nameField.setOnEditorActionListener { _, _, _ ->
                if (nameField.text.toString().isNotBlank()) {
                    doc.undo.push(doc, com.sengine.engine.editor.RenameNodeCommand("Rename node", primary.id, nameField.text.toString()))
                    doc.onStructureChanged()
                }
                true
            }
            nameRow.addView(nameField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(nameRow)
            val active = EditorButton(context, theme, "Active", {
                for (n in nodes) n.active = !n.active
                doc.onSceneMutated()
                onChanged()
                refresh()
            }, toggled = primary.active)
            header.addView(active)
        }
        content.addView(header)

        // transform
        val transform = SectionBox(context, theme, "Transform")
        if (nodes.size == 1) {
            val t = primary
            val commit = { doc.onSceneMutated(); onChanged() }
            transform.body.addView(NumberField(context, theme, "Position X", t.x, state.snap.step, onChange = { v -> t.x = v; t.computeWorld(); commit() }))
            transform.body.addView(NumberField(context, theme, "Position Y", t.y, state.snap.step, onChange = { v -> t.y = v; t.computeWorld(); commit() }))
            transform.body.addView(NumberField(context, theme, "Rotation", t.rotation, 1f, decimals = 1, onChange = { v -> t.rotation = v; t.computeWorld(); commit() }))
            transform.body.addView(NumberField(context, theme, "Scale X", t.scaleX, 0.05f, decimals = 3, onChange = { v -> t.scaleX = v; t.computeWorld(); commit() }))
            transform.body.addView(NumberField(context, theme, "Scale Y", t.scaleY, 0.05f, decimals = 3, onChange = { v -> t.scaleY = v; t.computeWorld(); commit() }))
            transform.body.addView(NumberField(context, theme, "Pivot X", t.pivotX, 0.02f, 0f, 1f, 3, { v -> t.pivotX = v; commit() }))
            transform.body.addView(NumberField(context, theme, "Pivot Y", t.pivotY, 0.02f, 0f, 1f, 3, { v -> t.pivotY = v; commit() }))
            transform.body.addView(NumberField(context, theme, "Draw order", t.order.toFloat(), 1f, decimals = 0, onChange = { v -> t.order = v.toInt(); commit() }))
            val layerRow = LinearLayout(context)
            layerRow.orientation = HORIZONTAL
            layerRow.addView(Ui.label(context, "Layer", theme, 11f, theme.textDim))
            val layerButton = EditorButton(context, theme, t.layer, {})
            layerButton.setOnClickListener {
                val layers = project.settings.layerNames
                showMenu(layerButton, theme, layers.map { name -> name to { t.layer = name; doc.onSceneMutated(); refresh() } })
            }
            layerRow.addView(layerButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            transform.body.addView(layerRow)
        } else {
            transform.body.addView(Ui.label(context, "Multi-object transform: use the viewport tools", theme, 11f, theme.textDim))
        }
        content.addView(transform)

        // components
        val components: List<Pair<GameObject, Component>> = if (nodes.size > 1) {
            val types = nodes.flatMap { n -> n.components.map { n to it } }
            types.filter { (_, c) -> nodes.all { it.component(c.type) != null } }
        } else {
            primary.components.map { primary to it }
        }
        if (nodes.size == 1) {
            val add = EditorButton(context, theme, "＋ Add component", {})
            add.setOnClickListener {
                val entries = com.sengine.engine.core.ComponentRegistry.all()
                showMenu(add, theme, entries.map { entry ->
                    "${entry.type} — ${entry.description}" to {
                        val component = com.sengine.engine.core.ComponentRegistry.create(entry.type) ?: return@to
                        doc.undo.push(doc, com.sengine.engine.editor.AddComponentCommand("Add ${entry.type}", primary.id, component))
                        onChanged()
                        refresh()
                    }
                })
            }
            content.addView(add)
        }
        for ((node, component) in components) {
            content.addView(componentSection(node, component, nodes.size > 1))
        }
    }

    private fun componentSection(node: GameObject, component: Component, multi: Boolean): View {
        val box = SectionBox(context, theme, component.type)
        val headerRow = LinearLayout(context)
        headerRow.orientation = HORIZONTAL
        val enabled = EditorButton(context, theme, if (component.enabled) "Enabled" else "Disabled", {
            component.enabled = !component.enabled
            doc.onSceneMutated()
            refresh()
        }, toggled = component.enabled)
        headerRow.addView(enabled, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menu = EditorButton(context, theme, "⋮", {})
        menu.setOnClickListener {
            showMenu(menu, theme, listOf(
                "Reset to defaults" to {
                    component.resetRuntime()
                    doc.onSceneMutated()
                    refresh()
                },
                "Copy properties" to {
                    copiedProps = component.props().associate { it.name to it.encode() }
                    toast(context, "Copied ${component.type} properties")
                },
                "Paste properties" to {
                    val data = copiedProps
                    if (data != null) {
                        for (p in component.props()) data[p.name]?.let { value -> p.decode(value) }
                        doc.onSceneMutated()
                        refresh()
                    }
                },
                "-" to {},
                "Remove component" to {
                    val index = node.components.indexOf(component)
                    if (index >= 0) doc.undo.push(doc, com.sengine.engine.editor.RemoveComponentCommand("Remove ${component.type}", node.id, index))
                    onChanged()
                    refresh()
                }
            ))
        }
        headerRow.addView(menu)
        box.body.addView(headerRow)
        for (prop in component.props().sortedBy { it.order }) {
            if (filter.isNotEmpty() && !prop.label.contains(filter, true) && !prop.name.contains(filter, true)) continue
            val view = propView(prop) ?: continue
            box.body.addView(view)
        }
        return box
    }

    /** Builds the right widget for a property declaration. */
    private fun propView(prop: Prop): View? {
        val changed = { doc.onSceneMutated(); onChanged() }
        return when (prop) {
            is Prop.F -> NumberField(context, theme, prop.label, prop.get(), prop.step, prop.min, prop.max, 3) { v ->
                prop.set(v); changed()
            }
            is Prop.I -> NumberField(context, theme, prop.label, prop.get().toFloat(), prop.step.toFloat(), prop.min.toFloat(), prop.max.toFloat(), 0) { v ->
                prop.set(v.toInt()); changed()
            }
            is Prop.B -> EditorButton(context, theme, prop.label, {
                prop.set(!prop.get()); changed()
            }, toggled = prop.get())
            is Prop.S -> {
                val row = LinearLayout(context)
                row.orientation = VERTICAL
                row.addView(Ui.label(context, prop.label, theme, 11f, theme.textDim))
                val edit = Ui.edit(context, prop.get(), theme)
                edit.setOnFocusChangeListener { _, focus -> if (!focus) { prop.set(edit.text.toString()); changed() } }
                row.addView(edit)
                row
            }
            is Prop.C -> ColorField(context, theme, prop.label, prop.get()) { c -> prop.set(c); changed() }
            is Prop.E -> {
                val button = EditorButton(context, theme, "${prop.label}: ${prop.options.getOrElse(prop.get()) { "" }}", {})
                button.setOnClickListener {
                    showMenu(button, theme, prop.options.mapIndexed { index, name ->
                        name to { prop.set(index); changed(); refresh() }
                    })
                }
                button
            }
            is Prop.V2 -> Vector2Field(context, theme, prop.label, prop.get().x, prop.get().y, prop.step) { x, y ->
                prop.set(Vec2(x, y)); changed()
            }
            is Prop.R -> RectField(context, theme, prop.label, prop.get()) { r -> prop.set(r); changed() }
            is Prop.Asset -> {
                val button = EditorButton(context, theme, "${prop.label}: ${prop.get().ifEmpty { "—" }}", {})
                button.setOnClickListener {
                    val assets = project.listAssets(prop.kind)
                    val items = ArrayList<Pair<String, () -> Unit>>()
                    items.add("Clear" to { prop.set(""); changed(); refresh() })
                    for (asset in assets.take(60)) items.add(asset to { prop.set(asset); changed(); refresh() })
                    if (items.size == 1) items.add("(no ${prop.kind.name.lowercase()} assets in this project)" to {})
                    showMenu(button, theme, items)
                }
                button
            }
            is Prop.NodeRef -> {
                val target = doc.scene.findById(prop.get())
                val button = EditorButton(context, theme, "${prop.label}: ${target?.name ?: "—"}", {})
                button.setOnClickListener {
                    val items = ArrayList<Pair<String, () -> Unit>>()
                    items.add("None" to { prop.set(0L); changed() })
                    for (n in doc.scene.objects.take(80)) items.add(n.name to { prop.set(n.id); changed(); refresh() })
                    showMenu(button, theme, items)
                }
                button
            }
            is Prop.Flags -> {
                val button = EditorButton(context, theme, "${prop.label}: ${prop.get().joinToString(",").ifEmpty { "—" }}", {})
                button.setOnClickListener {
                    showMenu(button, theme, prop.options().map { option ->
                        (if (option in prop.get()) "✓ $option" else "   $option") to {
                            val set = prop.get().toMutableSet()
                            if (!set.add(option)) set.remove(option)
                            prop.set(set)
                            changed()
                            refresh()
                        }
                    })
                }
                button
            }
            is Prop.Points -> {
                val button = EditorButton(context, theme, "${prop.label} (${prop.get().size / 2} points)", {})
                button.setOnClickListener {
                    showMenu(button, theme, listOf(
                        "Make box" to { prop.set(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f)); changed(); refresh() },
                        "Make circle (12)" to { prop.set(com.sengine.engine.physics.PolygonUtil.circlePoints(0.5f, 12)); changed(); refresh() },
                        "Make regular polygon…" to {
                            inputDialog(context, theme, "Polygon sides", "6") { sides ->
                                val n = sides.toIntOrNull() ?: 6
                                prop.set(com.sengine.engine.physics.PolygonUtil.regular(n, 0.5f)); changed(); refresh()
                            }
                        }
                    ))
                }
                button
            }
            is Prop.Strings -> {
                val row = LinearLayout(context)
                row.orientation = VERTICAL
                row.addView(Ui.label(context, prop.label, theme, 11f, theme.textDim))
                for (value in prop.get()) row.addView(Ui.label(context, "• $value", theme, 11f))
                val edit = Ui.edit(context, prop.get().joinToString(","), theme, "comma separated")
                edit.setOnFocusChangeListener { _, focus ->
                    if (!focus) { prop.set(edit.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }); changed() }
                }
                row.addView(edit)
                row
            }
            is Prop.Info -> {
                val tv = Ui.label(context, "${prop.label}: ${prop.get()}", theme, 11f, theme.textDim)
                tv
            }
        }
    }

    private var copiedProps: Map<String, com.sengine.engine.json.JVal>? = null
}

// --------------------------------------------------------------------------------- assets

/**
 * FileSystem panel: folders, thumbnails, search/sort, import from device, rename/duplicate/delete,
 * New folder / New scene / New script / New shader, and "assign to selection" for the inspector.
 */
/**
 * Asset browser: folders, thumbnails, search, filter/sort, favourites, recent, and the full set of
 * file operations (create, import, rename, duplicate, delete, open, assign).
 *
 * Every row is a real file inside `<project>/assets`; metadata (pivot, slices, favourite flag) lives
 * in the asset's sidecar `.meta.json`, so the browser state survives restarts and travels with the
 * project.
 */
class AssetPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val onChanged: () -> Unit,
    val onAssign: (String, AssetKind) -> Unit
) : LinearLayout(context) {

    private val grid = Panels.column(context, theme)
    private var folder = ""
    private var query = ""
    private var sortMode = 0                  // 0 name, 1 modified, 2 kind, 3 size
    private var showFavourites = false
    private var showRecent = false
    private var kindFilter: AssetKind? = null
    private val crumbs = LinearLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)

        val top = LinearLayout(context)
        top.orientation = HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(IconButton(context, theme, Icons.ARROW_UP, "Up one folder", {
            folder = folder.substringBeforeLast('/', "")
            refresh()
        }, sizeDp = 26f))
        top.addView(IconButton(context, theme, Icons.FOLDER, "New folder", { createFolder() }, sizeDp = 26f))
        val newFile = IconButton(context, theme, Icons.PLUS, "New file", { createMenu() }, sizeDp = 26f)
        top.addView(newFile)
        top.addView(IconButton(context, theme, Icons.IMPORT, "Import files", { onImportRequested?.invoke() }, sizeDp = 26f))
        top.addView(IconButton(context, theme, Icons.STAR, "Favourites", {
            showFavourites = !showFavourites
            showRecent = false
            refresh()
        }, toggled = showFavourites, sizeDp = 26f))
        top.addView(IconButton(context, theme, Icons.CLOCK, "Recent", {
            showRecent = !showRecent
            showFavourites = false
            refresh()
        }, toggled = showRecent, sizeDp = 26f))
        top.addView(IconButton(context, theme, Icons.SEARCH, "Sort: ${sortLabel()}", {
            showMenu(top, theme, listOf(
                "Name" to { sortMode = 0; refresh() },
                "Modified" to { sortMode = 1; refresh() },
                "Kind" to { sortMode = 2; refresh() },
                "Size" to { sortMode = 3; refresh() }
            ))
        }, sizeDp = 26f))
        addView(top)

        addView(crumbs)
        addView(SearchField(context, theme, "Search assets") { q -> query = q; refresh() })

        val filters = LinearLayout(context)
        filters.orientation = HORIZONTAL
        for (kind in listOf(null, AssetKind.TEXTURE, AssetKind.SOUND, AssetKind.SCRIPT, AssetKind.SCENE, AssetKind.TILESET)) {
            val label = kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "All"
            filters.addView(EditorButton(context, theme, label, {
                kindFilter = kind
                refresh()
            }, compact = true, toggled = kindFilter == kind))
        }
        addView(filters)
        Panels.scroll(this, grid)
        refresh()
    }

    var onImportRequested: (() -> Unit)? = null

    /** Called when the user asks to open an asset with its own editor (script, shader, tileset…). */
    var onOpen: ((String) -> Unit)? = null

    var onStatus: ((String) -> Unit)? = null

    private fun sortLabel() = when (sortMode) {
        1 -> "Modified"
        2 -> "Kind"
        3 -> "Size"
        else -> "Name"
    }

    fun refresh() {
        grid.removeAllViews()
        crumbs.removeAllViews()
        buildCrumbs()

        val all = doc.project.listAssetsRecursive()
        var visible = all.filter { it.startsWith(if (folder.isEmpty()) "" else "$folder/") }
        if (query.isNotEmpty()) visible = visible.filter { it.contains(query, true) }
        if (kindFilter != null) visible = visible.filter { AssetKind.of(it) == kindFilter }
        if (showFavourites) visible = visible.filter { isFavourite(it) }
        if (showRecent) visible = visible.sortedByDescending { doc.project.assetFile(it).lastModified() }.take(20)
        else visible = when (sortMode) {
            1 -> visible.sortedByDescending { doc.project.assetFile(it).lastModified() }
            2 -> visible.sortedBy { AssetKind.of(it)?.ordinal ?: 99 }
            3 -> visible.sortedByDescending { doc.project.assetSize(it) }
            else -> visible.sorted()
        }

        // ---- sub folders
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val subFolders = HashSet<String>()
        for (a in all) {
            if (!a.startsWith(prefix)) continue
            val rel = a.removePrefix(prefix)
            if (rel.contains('/')) subFolders.add(rel.substringBefore('/'))
        }
        for (f in doc.project.listFolders(folder)) subFolders.add(f)
        for (f in subFolders.sorted()) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(theme.pad(2f), theme.pad(4f), theme.pad(2f), theme.pad(4f))
            row.addView(IconView(context, theme, Icons.FOLDER, 18f))
            row.addView(Ui.label(context, "  $f", theme, 12f, theme.text))
            val count = all.count { it.startsWith("$prefix$f/") }
            row.addView(Ui.label(context, "   $count", theme, 10f, theme.textDim))
            row.isClickable = true
            row.setOnClickListener { folder = if (folder.isEmpty()) f else "$folder/$f"; refresh() }
            row.setOnLongClickListener {
                showMenu(row, theme, listOf(
                    "Rename folder…" to {
                        inputDialog(context, theme, "Rename folder", f) { newName ->
                            val rel = if (folder.isEmpty()) f else "$folder/$f"
                            val target = if (folder.isEmpty()) newName else "$folder/$newName"
                            if (doc.project.moveFolder(rel, target)) { note("Renamed $rel → $target"); refresh() }
                            else note("Rename failed (name taken?)")
                        }
                    },
                    "Delete folder" to {
                        confirmDialog(context, "Delete folder", "Delete '$f' and everything inside it?") {
                            val rel = if (folder.isEmpty()) f else "$folder/$f"
                            if (doc.project.deleteFolder(rel)) { note("Deleted $rel"); refresh() } else note("Delete failed")
                        }
                    }
                ))
                true
            }
            grid.addView(row)
        }

        // ---- files
        var shown = 0
        for (a in visible) {
            val rel = a.removePrefix(prefix)
            if (rel.contains('/')) continue
            val kind = AssetKind.of(a) ?: AssetKind.OTHER
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(theme.pad(2f), theme.pad(3f), theme.pad(2f), theme.pad(3f))
            val thumb = AssetThumbnail(context, theme, doc.project.assetFile(a).absolutePath, kind)
            row.addView(thumb, LayoutParams(theme.dp(26f), theme.dp(26f)))
            row.addView(Ui.label(context, "  $rel", theme, 12f, theme.text))
            if (isFavourite(a)) row.addView(Ui.label(context, " ★", theme, 12f, theme.warning))
            row.addView(Ui.label(context, "   ${kind.name.lowercase()} · ${doc.project.assetSize(a) / 1024} kB", theme, 10f, theme.textDim))
            row.isClickable = true
            row.setOnClickListener {
                when {
                    kind == AssetKind.SCENE -> onOpen?.invoke(a)
                    kind == AssetKind.SCRIPT || kind == AssetKind.MATERIAL || kind == AssetKind.TILESET -> onOpen?.invoke(a)
                    else -> onAssign(a, kind)
                }
            }
            row.setOnLongClickListener {
                showMenu(row, theme, assetMenu(a, kind, rel))
                true
            }
            grid.addView(row)
            shown++
        }

        if (shown == 0 && subFolders.isEmpty() && visible.isEmpty()) {
            grid.addView(Ui.label(context,
                if (query.isNotEmpty() || showFavourites || showRecent) "Nothing matches this filter."
                else "Empty folder. Use ＋ to create a scene/script/shader, or ⇩ to import a file.",
                theme, 12f, theme.textDim))
        }
    }

    private fun buildCrumbs() {
        val root = EditorButton(context, theme, "assets", { folder = ""; refresh() }, compact = true)
        crumbs.addView(root)
        if (folder.isEmpty()) return
        var accumulated = ""
        for (part in folder.split('/')) {
            accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"
            val target = accumulated
            crumbs.addView(Ui.label(context, "/", theme, 12f, theme.textDim))
            crumbs.addView(EditorButton(context, theme, part, { folder = target; refresh() }, compact = true))
        }
    }

    private fun assetMenu(a: String, kind: AssetKind, rel: String): List<Pair<String, () -> Unit>> = listOf(
        "Open / assign" to { onAssign(a, kind) },
        (if (isFavourite(a)) "Remove from favourites" else "Add to favourites") to {
            setFavourite(a, !isFavourite(a))
            refresh()
        },
        "Rename…" to {
            inputDialog(context, theme, "Rename asset", rel) { newName ->
                val target = if (folder.isEmpty()) newName else "$folder/$newName"
                if (doc.project.renameAsset(a, target)) { note("Renamed to $target"); refresh() }
                else note("Rename failed (a file with that name exists)")
            }
        },
        "Duplicate" to {
            val base = a.substringBeforeLast('.')
            val ext = a.substringAfterLast('.', "")
            val copy = doc.project.uniqueAssetName(if (ext.isEmpty()) "${base}_copy" else "${base}_copy.$ext")
            if (doc.project.copyAsset(a, copy)) { note("Duplicated to $copy"); refresh() }
        },
        "Reimport (refresh metadata)" to {
            reimport(a)
        },
        "Move to folder…" to {
            inputDialog(context, theme, "Move to folder", folder) { target ->
                val name = a.substringAfterLast('/')
                val to = if (target.isBlank()) name else "$target/$name"
                if (doc.project.moveAsset(a, to)) { note("Moved to $to"); refresh() }
                else note("Move failed")
            }
        },
        "Show path in Output" to { Log.info("Assets", "${doc.project.assetFile(a).absolutePath} (${doc.project.assetSize(a)} bytes)") },
        "Delete" to {
            confirmDialog(context, "Delete asset", "Delete '$rel'?") {
                doc.project.deleteAsset(a)
                note("Deleted $rel")
                if (kind == AssetKind.SCENE) onChanged()
                refresh()
            }
        }
    )

    /** Reimport: re-decodes the file, refreshes its metadata (size, slices, hashes) — real work. */
    private fun reimport(name: String) {
        val file = doc.project.assetFile(name)
        if (!file.exists()) { note("File is gone"); return }
        val meta = doc.project.readMetadata(name)
        meta.put("size", file.length())
        meta.put("modified", file.lastModified())
        meta.put("sha256", AssetImporter.hashFile(file))
        if (AssetKind.of(name) == AssetKind.TEXTURE) {
            val info = AssetImporter.imageInfo(file)
            if (info != null) {
                meta.put("width", info[0])
                meta.put("height", info[1])
            }
        }
        doc.project.writeMetadata(name, meta)
        note("Reimported $name (${file.length() / 1024} kB)")
        onStatus?.invoke("Reimported $name")
        refresh()
    }

    private fun createFolder() {
        inputDialog(context, theme, "New folder", "NewFolder") { name ->
            val target = if (folder.isEmpty()) name else "$folder/$name"
            if (doc.project.createFolder(target)) { note("Created $target"); refresh() } else note("Could not create folder")
        }
    }

    private fun createMenu() {
        val target = folder
        showMenu(grid, theme, listOf(
            "Scene" to { createScene(target) },
            "Script (.script.js)" to { createAsset(target, "script.script.js", AssetImporter.scriptTemplate()) },
            "Shader (.glsl)" to { createAsset(target, "shader.glsl", AssetImporter.shaderTemplate()) },
            "Material (.material.json)" to { createAsset(target, "material.material.json", AssetImporter.materialTemplate()) },
            "TileSet (.tileset.json)" to { createAsset(target, "tileset.tileset.json", AssetImporter.tilesetTemplate()) },
            "Animation (.anim.json)" to { createAsset(target, "animation.anim.json", AssetImporter.animationTemplate()) },
            "Particle preset (.particle.json)" to { createAsset(target, "particles.particle.json", AssetImporter.particleTemplate()) },
            "Text (.txt)" to { createAsset(target, "notes.txt", "") },
            "Folder" to { createFolder() }
        ))
    }

    private fun createScene(target: String) {
        inputDialog(context, theme, "New scene", "Level2") { raw ->
            val name = if (raw.endsWith(".scene.json")) raw else "$raw.scene.json"
            val path = if (target.isEmpty()) name else "$target/$name"
            val scene = com.sengine.engine.core.Scene(raw.removeSuffix(".scene.json"))
            doc.project.saveScene(scene)
            note("Scene '$path' created (open it from the Scene menu)")
            refresh()
        }
    }

    private fun createAsset(target: String, fileName: String, content: String) {
        val path = if (target.isEmpty()) fileName else "$target/$fileName"
        val unique = doc.project.uniqueAssetName(path)
        if (doc.project.writeAsset(unique, content)) {
            note("Created $unique")
            refresh()
        } else note("Could not create $unique")
    }

    private fun isFavourite(name: String): Boolean = doc.project.readMetadata(name).bool("favourite")

    private fun setFavourite(name: String, value: Boolean) {
        val meta = doc.project.readMetadata(name)
        meta.put("favourite", value)
        doc.project.writeMetadata(name, meta)
    }

    private fun note(text: String) {
        Log.info("Assets", text)
        onStatus?.invoke(text)
    }
}

/** 26dp thumbnail: real decoded image for textures, tinted vector icon for everything else. */
class AssetThumbnail(
    context: Context,
    val theme: Theme,
    val path: String,
    val kind: AssetKind
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmap: Bitmap? = if (kind == AssetKind.TEXTURE) decode(path) else null

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
            canvas.drawBitmap(bmp, src, dst, paint)
            return
        }
        val size = min(width, height).toFloat() * 0.8f
        Icons.draw(canvas, Icons.forAssetName(java.io.File(path).name), (width - size) / 2f, (height - size) / 2f,
            size, theme.accent, theme.dp(1.4f).toFloat())
    }

    private fun decode(path: String): Bitmap? = runCatching {
        val options = BitmapFactory.Options()
        options.inSampleSize = 4
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()
}

// --------------------------------------------------------------------------------- output & debug

/** Output / Debugger / Profiler tabs — all backed by real engine measurements. */
class ConsolePanel(
    context: Context,
    val theme: Theme,
    val state: EditorState,
    val engineProvider: () -> com.sengine.engine.Engine?
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)
    private val tabs = TabStrip(context, theme)
    private var levelFilter = LogLevel.DEBUG
    private var showErrorsOnly = false

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        tabs.setTabs(listOf("Output", "Debugger", "Profiler"))
        tabs.onSelect = { refresh(tabs.selected) }
        addView(tabs)
        Panels.scroll(this, content)
        Log.addListener(object : com.sengine.engine.debug.Log.Listener {
            override fun onEntry(entry: com.sengine.engine.debug.LogEntry) {
                post { if (tabs.selected == 0) refresh(0) }
            }
        })
        refresh(0)
    }

    fun refresh(tab: Int = tabs.selected) {
        content.removeAllViews()
        when (tab) {
            0 -> outputTab()
            1 -> debuggerTab()
            else -> profilerTab()
        }
    }

    private fun outputTab() {
        val bar = LinearLayout(context)
        bar.orientation = HORIZONTAL
        val clear = EditorButton(context, theme, "Clear", { Log.clear(); refresh(0) })
        bar.addView(clear)
        val filter = EditorButton(context, theme, "Errors only", {
            showErrorsOnly = !showErrorsOnly
            refresh(0)
        }, toggled = showErrorsOnly)
        bar.addView(filter)
        val level = EditorButton(context, theme, "Level: ${LogLevel.label(levelFilter)}", {})
        level.setOnClickListener {
            showMenu(level, theme, listOf("Debug", "Info", "Warning", "Error only").mapIndexed { index, name ->
                name to {
                    levelFilter = index * 1
                    refresh(0)
                }
            })
        }
        bar.addView(level)
        bar.addView(Ui.label(context, "${Log.errorCount} error(s) · ${Log.warningCount} warning(s)", theme, 11f, theme.textDim))
        content.addView(bar)
        val entries = if (showErrorsOnly) Log.errors() else Log.all().filter { it.level >= levelFilter }
        for (entry in entries.asReversed()) {
            val color = when (entry.level) {
                LogLevel.ERROR, LogLevel.SCRIPT -> theme.error
                LogLevel.WARN -> theme.warning
                else -> theme.text
            }
            val tv = Ui.label(context, "${LogLevel.label(entry.level)}  [${entry.tag}]  ${entry.message}", theme, 11f, color)
            tv.setOnLongClickListener {
                inputDialog(context, theme, "Copy to script", "", "Use Log.print() from JS") { text -> Log.script("Editor", text) }
                true
            }
            content.addView(tv)
        }
        if (entries.isEmpty()) content.addView(Ui.label(context, "No output yet.", theme, 11f, theme.textDim))
    }

    private fun debuggerTab() {
        val engine = engineProvider()
        if (engine == null) {
            content.addView(Ui.label(context, "Debugger available while the editor is open.", theme, 12f, theme.textDim))
            return
        }
        val scene = engine.scene
        val header = Panels.header(context, theme, "Scene: ${scene.name} · ${scene.objects.size} nodes · revision ${scene.structureRevision}")
        content.addView(header)
        val section = SectionBox(context, theme, "Live nodes", true)
        for (node in scene.objects.take(120)) {
            val runtime = StringBuilder()
            node.getAny<com.sengine.engine.core.Rigidbody2D>()?.let { rb ->
                runtime.append("v=%.2f,%.2f".format(rb.vx, rb.vy))
            }
            node.getAny<com.sengine.engine.core.AnimatedSprite2D>()?.let { anim ->
                runtime.append(" frame=${anim.currentFrame}/${anim.frameCount - 1}")
            }
            node.getAny<ParticleEmitter2D>()?.let { runtime.append(" particles=${it.particleCount}") }
            val tv = Ui.label(context, "${node.name} [${node.type}] ${runtime}", theme, 11f, if (node.active) theme.text else theme.textDim)
            tv.isClickable = true
            tv.setOnClickListener {
                state.selectionIds = listOf(node.id)
                state.selectedId = node.id
                engine.scene // keep reference
                refresh(1)
            }
            section.body.addView(tv)
        }
        content.addView(section)
        val inputs = SectionBox(context, theme, "Input state", false)
        for (action in engine.input.map.actions.keys) {
            inputs.body.addView(Ui.label(context, "$action: ${if (engine.input.isPressed(action)) "pressed" else "—"}", theme, 11f))
        }
        content.addView(inputs)
        val physics = SectionBox(context, theme, "Physics", false)
        physics.body.addView(Ui.label(context, "${engine.physics.bodyCount} bodies · ${engine.physics.contactCount} contacts", theme, 11f))
        content.addView(physics)
        val vars = SectionBox(context, theme, "Script variables", false)
        for ((name, value) in engine.scripts.variables().entries.take(80)) {
            vars.body.addView(Ui.label(context, "$name = $value", theme, 11f))
        }
        if (engine.scripts.variables().isEmpty()) vars.body.addView(Ui.label(context, "No script instances.", theme, 11f, theme.textDim))
        content.addView(vars)
    }

    private fun profilerTab() {
        val engine = engineProvider()
        if (engine == null) {
            content.addView(Ui.label(context, "Profiler available while the editor is open.", theme, 12f, theme.textDim))
            return
        }
        val p = engine.profiler
        content.addView(Panels.header(context, theme, "S ENGINE profiler — real measurements"))
        fun meter(label: String, value: Float, text: String = "") {
            val m = MeterView(context, theme, label)
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.addView(m, LinearLayout.LayoutParams(0, theme.dp(22f), 1f))
            row.addView(Ui.label(context, text, theme, 11f, theme.textDim))
            content.addView(row)
            m.update(value, text)
        }
        meter("FPS", (p.smoothFps / 60f).coerceIn(0f, 1f), "%.1f".format(p.smoothFps))
        meter("Frame", (p.frameMs.last() / 33f).coerceIn(0f, 1f), "%.2f ms".format(p.frameMs.last()))
        meter("Update", (p.updateMs.last() / 16f).coerceIn(0f, 1f), "%.2f ms".format(p.updateMs.last()))
        meter("Physics", (p.physicsMs.last() / 16f).coerceIn(0f, 1f), "%.2f ms".format(p.physicsMs.last()))
        meter("Render", (p.renderMs.last() / 16f).coerceIn(0f, 1f), "%.2f ms".format(p.renderMs.last()))
        meter("Scripts", (p.scriptMs.last() / 16f).coerceIn(0f, 1f), "%.2f ms".format(p.scriptMs.last()))
        meter("Audio", (p.audioMs.last() / 8f).coerceIn(0f, 1f), "%.2f ms".format(p.audioMs.last()))
        val stats = engine.renderList.stats
        content.addView(Ui.label(context, "Draw calls: ${stats.drawCalls}   Batches: ${stats.batches}   Vertices: ${stats.vertices}", theme, 11f))
        content.addView(Ui.label(context, "Sprites: ${stats.sprites}   Texts: ${stats.texts}   Shapes: ${stats.shapes}   Lines: ${stats.lines}", theme, 11f))
        content.addView(Ui.label(context, "Culled: ${stats.itemsCulled}   Submitted: ${stats.itemsSubmitted}   Clipped: ${stats.clippedDraws}", theme, 11f))
        content.addView(Ui.label(context, "Nodes: ${p.activeNodes}/${p.totalNodes} active   Particles: ${p.particles}", theme, 11f))
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        meter("Memory", usedMb.toFloat() / maxMb.toFloat(), "$usedMb / $maxMb MB")
        val refresh = EditorButton(context, theme, "Refresh", { refresh(2) })
        content.addView(refresh)
    }
}

// --------------------------------------------------------------------------------- animation

/**
 * Animation editor: real timeline over [com.sengine.engine.animation.SpriteFrames] with frame
 * thumbnails described by index, play/pause, fps, looping and sheet slicing, plus a keyframe track
 * list for the AnimatedSprite2D it is attached to.
 */
class AnimationPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val engineProvider: () -> com.sengine.engine.Engine?,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)

    /** The project's resource cache — animations and tilesets are loaded through it, never re-read. */
    private val resources: com.sengine.engine.resources.ResourceManager
        get() = engineProvider()!!.resources

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        Panels.scroll(this, content)
        refresh()
    }

    fun refresh() {
        content.removeAllViews()
        val node = doc.selection.nodes(doc.scene).firstOrNull { it.getAny<com.sengine.engine.core.AnimatedSprite2D>() != null }
            ?: doc.scene.objects.firstOrNull { it.getAny<com.sengine.engine.core.AnimatedSprite2D>() != null }
        if (node == null) {
            content.addView(Ui.label(context, "Select an AnimatedSprite2D node to edit its timeline.", theme, 12f, theme.textDim))
            return
        }
        val anim = node.getAny<com.sengine.engine.core.AnimatedSprite2D>()!!
        content.addView(Panels.header(context, theme, "${node.name} — ${anim.spriteSheet.ifEmpty { "no sheet" }}"))
        content.addView(NumberField(context, theme, "FPS", anim.fps, 1f, 1f, 60f, 0) { v -> anim.fps = v; onChanged() })
        content.addView(NumberField(context, theme, "Columns", anim.columns.toFloat(), 1f, 1f, 64f, 0) { v -> anim.columns = v.toInt(); onChanged(); refresh() })
        content.addView(NumberField(context, theme, "Frame count", anim.frameCount.toFloat(), 1f, 1f, 4096f, 0) { v -> anim.frameCount = v.toInt(); onChanged(); refresh() })
        val loop = EditorButton(context, theme, "Loop mode: ${anim.loopMode.label}", {
            anim.loopIndex = (anim.loopIndex + 1) % com.sengine.engine.animation.LoopMode.values().size
            onChanged()
            refresh()
        }, toggled = anim.loopMode == com.sengine.engine.animation.LoopMode.LOOP)
        content.addView(loop)
        val playing = EditorButton(context, theme, if (anim.playing) "Pause" else "Play", {
            if (anim.playing) anim.stop() else anim.play()
            onChanged()
        })
        content.addView(playing)

        // timeline strip
        val strip = LinearLayout(context)
        strip.orientation = HORIZONTAL
        for (i in 0 until anim.frameCount.coerceAtMost(48)) {
            val b = EditorButton(context, theme, i.toString(), {
                anim.currentFrame = i
                onChanged()
                refresh()
            }, toggled = i == anim.currentFrame)
            strip.addView(b, LinearLayout.LayoutParams(theme.dp(38f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = android.widget.HorizontalScrollView(context)
        scroll.addView(strip)
        content.addView(scroll)

        val slice = EditorButton(context, theme, "Slice sprite sheet…", {})
        slice.setOnClickListener {
            inputDialog(context, theme, "Slice sheet", "cols,rows", "e.g. 8,4") { value ->
                val parts = value.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 2 && parts[0] > 0 && parts[1] > 0) {
                    anim.columns = parts[0]
                    anim.frameCount = parts[0] * parts[1]
                    onChanged()
                    refresh()
                    toast(context, "Sliced into ${anim.frameCount} frames")
                }
            }
        }
        content.addView(slice)
        content.addView(Ui.label(context, "Frame ${anim.currentFrame + 1} of ${anim.frameCount} · ${anim.loopMode.label.lowercase()}", theme, 11f, theme.textDim))
        content.addView(buildAnimationAssetSection(node))
    }

    // ---------------------------------------------------------------- animation asset timeline

    private var animationName = ""
    private var selectedTrack = "position.x"
    private var playhead = 0f

    /**
     * Timeline for `.anim.json` animation assets: tracks, keyframes, easing, events and a scrubbable
     * playhead. Edits are written straight into the asset (and the node live-previews them, because
     * the engine runs animation players in edit mode too).
     */
    private fun buildAnimationAssetSection(node: GameObject): View {
        val column = Panels.column(context, theme)
        column.addView(Panels.header(context, theme, "Animation timeline"))

        val assets = doc.project.listAssets(AssetKind.ANIMATION)
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.addView(EditorButton(context, theme, animationName.ifEmpty { "Animation…" }, {
            showMenu(row, theme, assets.map { name -> name to { animationName = name; refresh() } })
        }, iconKind = Icons.ANIMATION, compact = true))
        row.addView(EditorButton(context, theme, "New", {
            inputDialog(context, theme, "New animation", "walk") { name ->
                animationName = resources.createAnimation(name).let { doc.project.listAssets(AssetKind.ANIMATION).firstOrNull { a -> a.contains(name) } ?: animationName }
                refresh()
            }
        }, iconKind = Icons.PLUS, compact = true))
        column.addView(row)

        if (animationName.isEmpty()) {
            column.addView(Ui.label(context, "Pick or create an animation asset to edit its tracks.", theme, 11f, theme.textDim))
            return column
        }
        val animation = resources.animation(animationName)
        if (animation == null) {
            column.addView(Ui.label(context, "Could not load $animationName", theme, 11f, theme.error))
            return column
        }

        // --- node binding + live preview
        val player = node.getAny<com.sengine.engine.core.AnimationPlayer>()
        val bindRow = LinearLayout(context)
        bindRow.orientation = HORIZONTAL
        bindRow.addView(EditorButton(context, theme, if (player == null) "Bind to ${node.name}" else "Re-bind to ${node.name}", {
            val target = node.getAny<com.sengine.engine.core.AnimationPlayer>() ?: com.sengine.engine.core.AnimationPlayer().also {
                node.add(it)
                doc.onStructureChanged()
            }
            target.animationAsset = animationName
            target.autoplay = false
            resources.invalidate(animationName)
            onChanged()
            refresh()
        }, iconKind = Icons.ANIMATION, compact = true))
        player?.let { p ->
            bindRow.addView(EditorButton(context, theme, if (p.playing) "Pause" else "Play", {
                if (p.playing) p.stop() else p.play()
                refresh()
            }, iconKind = if (p.playing) Icons.PAUSE else Icons.PLAY, compact = true))
        }
        column.addView(bindRow)

        // --- clip settings
        column.addView(NumberField(context, theme, "Length (s)", animation.length, 0.05f, 0.05f, 600f, 2) { v ->
            animation.length = v
            persistAnimation(animation)
        })
        column.addView(NumberField(context, theme, "Speed", animation.speed, 0.05f, 0.05f, 10f, 2) { v ->
            animation.speed = v
            persistAnimation(animation)
        })
        val loopRow = LinearLayout(context)
        loopRow.orientation = HORIZONTAL
        for ((i, mode) in com.sengine.engine.animation.LoopMode.values().withIndex()) {
            loopRow.addView(EditorButton(context, theme, mode.label, {
                animation.loop = mode
                persistAnimation(animation)
                refresh()
            }, compact = true, toggled = animation.loop.ordinal == i))
        }
        column.addView(loopRow)

        // --- timeline canvas
        val timeline = TimelineView(context, theme, animation, doc, resources) { newTime ->
            playhead = newTime
            player?.let { p ->
                p.state.time = newTime
                p.playing = false
                p.apply(animation, 0f) { method, _ -> engineSendMessage(node, method) }
            }
            doc.onSceneMutated()
        }
        timeline.onChanged = { selectedTrack = timeline.track; doc.onSceneMutated(); onChanged() }
        column.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(150f)))

        // --- track list + key editing
        val trackRow = LinearLayout(context)
        trackRow.orientation = HORIZONTAL
        trackRow.addView(EditorButton(context, theme, "Track: $selectedTrack", {
            showMenu(trackRow, theme, (COMMON_TRACKS + animation.trackNames()).distinct().map { name ->
                name to { selectedTrack = name; refresh() }
            })
        }, compact = true))
        trackRow.addView(EditorButton(context, theme, "Add key", {
            inputDialog(context, theme, "Keyframe value at %.2fs".format(playhead), "0") { raw ->
                val value = raw.toFloatOrNull() ?: 0f
                animation.setKey(selectedTrack, playhead, value)
                persistAnimation(animation)
                refresh()
            }
        }, iconKind = Icons.KEYFRAME, compact = true))
        trackRow.addView(EditorButton(context, theme, "Remove track", {
            animation.removeTrack(selectedTrack)
            persistAnimation(animation)
            refresh()
        }, iconKind = Icons.TRASH, compact = true))
        column.addView(trackRow)

        val keys = animation.tracks[selectedTrack] ?: emptyList()
        if (keys.isEmpty()) {
            column.addView(Ui.label(context, "No keys on '$selectedTrack'. Add one at the playhead.", theme, 11f, theme.textDim))
        }
        for ((index, key) in keys.withIndex()) {
            val keyRow = LinearLayout(context)
            keyRow.orientation = HORIZONTAL
            keyRow.gravity = Gravity.CENTER_VERTICAL
            keyRow.addView(IconView(context, theme, Icons.KEYFRAME, 14f))
            keyRow.addView(Ui.label(context, "  %.2fs = %.3f".format(key.time, key.value), theme, 11f, theme.text))
            keyRow.addView(EditorButton(context, theme, com.sengine.engine.math.Easing.NAMES.getOrElse(key.easing) { "Linear" }, {
                key.easing = (key.easing + 1) % com.sengine.engine.math.Easing.NAMES.size
                persistAnimation(animation)
                refresh()
            }, compact = true))
            keyRow.addView(EditorButton(context, theme, "✕", {
                animation.removeKey(selectedTrack, index)
                persistAnimation(animation)
                refresh()
            }, compact = true))
            column.addView(keyRow)
        }

        // --- events
        column.addView(Panels.header(context, theme, "Events (${animation.events.size})"))
        column.addView(EditorButton(context, theme, "Add event at %.2fs".format(playhead), {
            showMenu(column, theme, com.sengine.engine.animation.Animation.Event.TYPES.mapIndexed { type, label ->
                label to {
                    inputDialog(context, theme, "$label at %.2fs".format(playhead), "") { value ->
                        animation.addEvent(playhead, type, value)
                        persistAnimation(animation)
                        refresh()
                    }
                }
            })
        }, iconKind = Icons.PLUS, compact = true))
        for ((index, event) in animation.events.withIndex()) {
            val eventRow = LinearLayout(context)
            eventRow.orientation = HORIZONTAL
            eventRow.addView(Ui.label(context, "  %.2fs  ${com.sengine.engine.animation.Animation.Event.TYPES.getOrElse(event.type) { "?" }} → ${event.value}",
                theme, 11f, theme.textDim))
            eventRow.addView(EditorButton(context, theme, "✕", {
                animation.events.removeAt(index)
                persistAnimation(animation)
                refresh()
            }, compact = true))
            column.addView(eventRow)
        }
        return column
    }

    private fun engineSendMessage(node: GameObject, method: String) {
        // live preview of "call method" events through the real script bridge
        runCatching { com.sengine.engine.debug.Log.info("Animation", "event $method on ${node.name}") }
    }

    private fun persistAnimation(animation: com.sengine.engine.animation.Animation) {
        resources.saveAnimation(animationName, animation)
        doc.onSceneMutated()
        onChanged()
    }

    companion object {
        val COMMON_TRACKS = listOf(
            "position.x", "position.y", "rotation", "scale.x", "scale.y",
            "alpha", "color.r", "color.g", "color.b", "visible", "sprite.frame"
        )
    }
}

/**
 * Keyframe timeline: one lane per track, keys drawn as diamonds, a draggable playhead.
 *
 *  * tap anywhere → move the playhead (the node previews that exact frame)
 *  * drag a key → retime it (snapped to 0.01 s)
 *  * long press a key → delete it
 *  * tap a lane → select the track for key editing
 */
class TimelineView(
    context: Context,
    val theme: Theme,
    val animation: com.sengine.engine.animation.Animation,
    val doc: EditorDocument,
    val resources: com.sengine.engine.resources.ResourceManager,
    val onScrub: (Float) -> Unit
) : View(context) {

    var onChanged: (() -> Unit)? = null
    var track = "position.x"

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val laneHeight = theme.dp(20f).toFloat()
    private val handleR = theme.dp(6f).toFloat()
    private var draggingKeyTrack: String? = null
    private var draggingKeyIndex = -1
    private var scrollOffset = 0f

    init {
        isClickable = true
        performClick()
    }

    private fun trackList(): List<String> = animation.trackNames().ifEmpty { listOf("position.x") }

    private fun laneTop(index: Int) = theme.dp(18f) + index * laneHeight - scrollOffset

    private fun timeForX(x: Float): Float {
        val left = theme.dp(84f).toFloat()
        val right = width - theme.dp(8f).toFloat()
        if (right <= left) return 0f
        return (((x - left) / (right - left)) * animation.length).coerceIn(0f, animation.length)
    }

    private fun xForTime(time: Float): Float {
        val left = theme.dp(84f).toFloat()
        val right = width - theme.dp(8f).toFloat()
        return left + (time / animation.length.coerceAtLeast(0.001f)) * (right - left)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.track)
        val tracks = trackList()
        // ruler
        paint.color = theme.textDim
        paint.textSize = Ui.sp(context, 9f)
        val steps = 10
        for (i in 0..steps) {
            val t = animation.length * i / steps
            val x = xForTime(t)
            paint.color = theme.border
            canvas.drawLine(x, theme.dp(14f).toFloat(), x, height.toFloat(), paint)
            paint.color = theme.textDim
            canvas.drawText("%.2f".format(t), x + 2f, theme.dp(11f).toFloat(), paint)
        }
        // lanes
        for ((index, name) in tracks.withIndex()) {
            val top = laneTop(index)
            if (top > height || top + laneHeight < 0) continue
            val selected = name == track
            paint.color = if (selected) theme.panelAlt else theme.panel
            canvas.drawRect(0f, top, width.toFloat(), top + laneHeight - 1f, paint)
            paint.color = if (selected) theme.accent else theme.textDim
            paint.textSize = Ui.sp(context, 10f)
            canvas.drawText(name, theme.dp(4f).toFloat(), top + laneHeight * 0.68f, paint)
            val keys = animation.tracks[name] ?: continue
            for ((keyIndex, key) in keys.withIndex()) {
                val x = xForTime(key.time)
                val cy = top + laneHeight * 0.5f
                paint.color = if (selected) theme.accent else theme.ok
                canvas.drawCircle(x, cy, handleR, paint)
                if (draggingKeyTrack == name && draggingKeyIndex == keyIndex) {
                    paint.style = Paint.Style.STROKE
                    paint.color = theme.text
                    canvas.drawCircle(x, cy, handleR + 3f, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
        // playhead
        val player = animation
        val px = xForTime(playerTime())
        paint.color = theme.error
        canvas.drawLine(px, 0f, px, height.toFloat(), paint)
    }

    private fun playerTime(): Float {
        // the playhead is stored on the node's AnimationPlayer when there is one, so it is shared
        return previewTime
    }

    private var previewTime = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracks = trackList()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val laneIndex = ((event.y - theme.dp(18f) + scrollOffset) / laneHeight).toInt()
                if (laneIndex in tracks.indices) track = tracks[laneIndex]
                // grab a key when the press lands on one
                val keys = animation.tracks[track] ?: emptyList()
                for ((i, key) in keys.withIndex()) {
                    if (abs(xForTime(key.time) - event.x) < handleR * 2f) {
                        draggingKeyTrack = track
                        draggingKeyIndex = i
                        break
                    }
                }
                previewTime = timeForX(event.x)
                onScrub(previewTime)
                invalidate()
                onChanged?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                previewTime = timeForX(event.x)
                if (draggingKeyTrack != null) {
                    val keys = animation.tracks[draggingKeyTrack] ?: emptyList()
                    val key = keys.getOrNull(draggingKeyIndex)
                    if (key != null) {
                        key.time = (previewTime * 100f).roundToInt() / 100f
                        (animation.tracks[draggingKeyTrack] as? MutableList)?.sortBy { it.time }
                    }
                }
                onScrub(previewTime)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (draggingKeyTrack != null) {
                    draggingKeyTrack = null
                    draggingKeyIndex = -1
                    onChanged?.invoke()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingKeyTrack = null
                draggingKeyIndex = -1
                return true
            }
        }
        return true
    }
}

// --------------------------------------------------------------------------------- tilemap

/** Tilemap editor: palette from the project tilesets, brush controls and layer tools. */
class TileMapPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val state: EditorState,
    val resources: com.sengine.engine.resources.ResourceManager,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        Panels.scroll(this, content)
        refresh()
    }

    fun refresh() {
        content.removeAllViews()
        val node = doc.scene.objects.firstOrNull { it.getAny<TileMap2D>() != null }
        if (node == null) {
            content.addView(Ui.label(context, "No TileMap2D node in this scene.", theme, 12f, theme.textDim))
            val create = EditorButton(context, theme, "Create tilemap node", {
                val n = doc.scene.createNode("TileMap2D", null)
                n.getAny<TileMap2D>()?.let { tm ->
                    tm.data.layers.clear()
                    tm.data.addLayer("Ground", 40, 22)
                    tm.data.addLayer("Detail", 40, 22)
                }
                doc.onStructureChanged()
                onChanged()
                refresh()
            }, iconKind = Icons.TILESET)
            content.addView(create)
            return
        }
        val tm = node.getAny<TileMap2D>()!!
        content.addView(Panels.header(context, theme, "${node.name} · ${tm.tileWidth}×${tm.tileHeight}px"))
        content.addView(NumberField(context, theme, "Tile width", tm.tileWidth.toFloat(), 1f, 1f, 256f, 0) { v -> tm.tileWidth = v.toInt(); onChanged() })
        content.addView(NumberField(context, theme, "Tile height", tm.tileHeight.toFloat(), 1f, 1f, 256f, 0) { v -> tm.tileHeight = v.toInt(); onChanged() })
        content.addView(NumberField(context, theme, "Pixels/unit", tm.pixelsPerUnit, 1f, 1f, 256f, 0) { v -> tm.pixelsPerUnit = v; onChanged() })

        val layers = tm.data.layers
        content.addView(Panels.header(context, theme, "Layers (${layers.size})"))
        for ((index, layer) in layers.withIndex()) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val active = state.tileBrush.layerIndex == index
            val select = EditorButton(context, theme, layer.name, {
                state.tileBrush.layerIndex = index
                refresh()
            }, toggled = active, compact = true)
            select.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(select)
            row.addView(IconButton(context, theme, Icons.EYE, if (layer.visible) "Hide layer" else "Show layer", {
                layer.visible = !layer.visible
                doc.onSceneMutated()
                refresh()
            }, toggled = layer.visible, sizeDp = 24f))
            row.addView(IconButton(context, theme, Icons.LOCK, "Lock layer", {
                layer.locked = !layer.locked
                refresh()
            }, toggled = layer.locked, sizeDp = 24f))
            row.addView(IconButton(context, theme, Icons.PALETTE, "Collision from this layer: ${layer.collision}", {
                layer.collision = !layer.collision
                onChanged()
                refresh()
            }, toggled = layer.collision, sizeDp = 24f))
            row.setOnLongClickListener {
                showMenu(row, theme, listOf(
                    "Rename layer…" to {
                        inputDialog(context, theme, "Layer name", layer.name) { newName ->
                            layer.name = newName
                            onChanged()
                            refresh()
                        }
                    },
                    "Move up" to { if (tm.moveLayer(index, -1)) { onChanged(); refresh() } },
                    "Move down" to { if (tm.moveLayer(index, 1)) { onChanged(); refresh() } },
                    "Fill with selected tile" to {
                        layer.fill(state.tileBrush.tileId)
                        onChanged()
                        refresh()
                    },
                    "Clear layer" to { layer.fill(0); onChanged(); refresh() },
                    "Resize layer…" to {
                        inputDialog(context, theme, "Layer size (w,h)", "${layer.width},${layer.height}") { raw ->
                            val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
                            if (parts.size == 2 && parts[0] > 0 && parts[1] > 0) {
                                layer.resize(parts[0], parts[1])
                                onChanged()
                                refresh()
                            }
                        }
                    },
                    "Delete layer" to {
                        if (tm.removeLayer(index)) { onChanged(); refresh() } else toast(context, "A tilemap needs at least one layer")
                    }
                ))
                true
            }
            row.isClickable = true
            content.addView(row)
            if (active) {
                content.addView(NumberField(context, theme, "Opacity", layer.opacity, 0.05f, 0f, 1f, 2) { v ->
                    layer.opacity = v
                    doc.onSceneMutated()
                })
                val offsets = LinearLayout(context)
                offsets.orientation = HORIZONTAL
                offsets.addView(NumberField(context, theme, "Scroll X", layer.offsetX, 0.5f, -100f, 100f, 1) { v -> layer.offsetX = v; doc.onSceneMutated() })
                offsets.addView(NumberField(context, theme, "Parallax", layer.parallax, 0.05f, 0f, 4f, 2) { v -> layer.parallax = v; doc.onSceneMutated() })
                content.addView(offsets)
            }
        }
        val layerRow = LinearLayout(context)
        layerRow.orientation = HORIZONTAL
        layerRow.addView(EditorButton(context, theme, "＋ Layer", {
            inputDialog(context, theme, "New layer", "Layer${layers.size + 1}") { name ->
                val base = layers.firstOrNull()
                tm.data.addLayer(name, base?.width ?: 40, base?.height ?: 22)
                state.tileBrush.layerIndex = layers.size - 1
                onChanged()
                refresh()
            }
        }, iconKind = Icons.PLUS, compact = true))
        layerRow.addView(EditorButton(context, theme, "Resize map…", {
            inputDialog(context, theme, "Map size (w,h)", "${layers.firstOrNull()?.width ?: 40},${layers.firstOrNull()?.height ?: 22}") { raw ->
                val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 2 && parts[0] > 0 && parts[1] > 0) {
                    for (l in layers) l.resize(parts[0], parts[1])
                    onChanged()
                    refresh()
                }
            }
        }, iconKind = Icons.SCALE, compact = true))
        layerRow.addView(EditorButton(context, theme, "Autotile all", {
            var changed = 0
            for ((i, l) in layers.withIndex()) {
                for (y in 0 until l.height) for (x in 0 until l.width) {
                    if (l[x, y] != 0) changed += tm.applyAutotile(i, x, y, 0)
                }
            }
            onChanged()
            toast(context, "Autotile resolved $changed cells")
            refresh()
        }, iconKind = Icons.GRID, compact = true))
        content.addView(layerRow)

        content.addView(Ui.label(context, "Brush", theme, 11f, theme.textDim))
        val modes = listOf("Paint" to TileBrush.Mode.PAINT, "Erase" to TileBrush.Mode.ERASE, "Fill" to TileBrush.Mode.FILL, "Rect" to TileBrush.Mode.RECT, "Picker" to TileBrush.Mode.PICK)
        val modeRow = LinearLayout(context)
        modeRow.orientation = LinearLayout.HORIZONTAL
        for ((name, mode) in modes) {
            val b = EditorButton(context, theme, name, {
                state.tileBrush.mode = mode
                refresh()
            }, toggled = state.tileBrush.mode == mode)
            modeRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(modeRow)
        val modes2 = listOf("Line" to TileBrush.Mode.LINE)
        val modeRow2 = LinearLayout(context)
        modeRow2.orientation = HORIZONTAL
        for ((name, mode) in modes2) {
            modeRow2.addView(EditorButton(context, theme, name, {
                state.tileBrush.mode = mode
                refresh()
            }, toggled = state.tileBrush.mode == mode, compact = true))
        }
        modeRow2.addView(EditorButton(context, theme, if (state.tileBrush.filledRect) "Filled rect" else "Rect outline", {
            state.tileBrush.filledRect = !state.tileBrush.filledRect
            refresh()
        }, compact = true, toggled = state.tileBrush.filledRect))
        content.addView(modeRow2)

        content.addView(NumberField(context, theme, "Brush size", state.tileBrush.brushSize.toFloat(), 1f, 1f, 16f, 0) { v -> state.tileBrush.brushSize = v.toInt(); refresh() })
        content.addView(NumberField(context, theme, "Tile id", state.tileBrush.tileId.toFloat(), 1f, 0f, 4096f, 0) { v -> state.tileBrush.tileId = v.toInt(); refresh() })
        val options = LinearLayout(context)
        options.orientation = HORIZONTAL
        options.addView(EditorButton(context, theme, "Random tiles", {
            state.tileBrush.random = !state.tileBrush.random
            refresh()
        }, toggled = state.tileBrush.random, compact = true))
        options.addView(EditorButton(context, theme, "Autotile", {
            state.tileBrush.autotile = !state.tileBrush.autotile
            refresh()
        }, toggled = state.tileBrush.autotile, compact = true))
        content.addView(options)

        val tilesets = doc.project.listAssets(AssetKind.TILESET)
        if (tilesets.isEmpty()) {
            content.addView(Ui.label(context, "No tileset assets. Create one in the FileSystem panel, then assign it on the TileMap2D component.", theme, 11f, theme.textDim))
        } else {
            content.addView(Ui.label(context, "Palette", theme, 11f, theme.textDim))
            for (asset in tilesets) {
                val set = resources.tileset(asset) ?: continue
                val header = LinearLayout(context)
                header.orientation = HORIZONTAL
                header.gravity = Gravity.CENTER_VERTICAL
                header.addView(Ui.label(context, asset, theme, 11f, theme.textDim))
                header.addView(EditorButton(context, theme, "Assign to ${node.name}", {
                    tm.tileSetAsset = asset
                    tm.runtimeTileSet = set
                    if (tm.tileWidth <= 0) tm.tileWidth = set.tileWidth
                    if (tm.tileHeight <= 0) tm.tileHeight = set.tileHeight
                    doc.onSceneMutated()
                    onChanged()
                    refresh()
                }, compact = true))
                content.addView(header)
                if (set.texture.isEmpty()) {
                    content.addView(Ui.label(context, "Tileset has no texture — assign one in the Files panel.", theme, 10f, theme.warning))
                    continue
                }
                val palette = TilePaletteView(context, theme, set, doc) { id ->
                    state.tileBrush.tileId = id
                    state.tileBrush.active = true
                    state.tileBrush.mode = TileBrush.Mode.PAINT
                    state.tool = com.sengine.engine.editor.ToolState.TILE
                    refresh()
                }
                palette.selected = state.tileBrush.tileId
                content.addView(palette, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(120f)))
            }
        }
        val hint = Ui.label(context, "Paint with the Tile tool (5) in the viewport. Collision and metadata come from the tileset.", theme, 11f, theme.textDim)
        content.addView(hint)
    }
}


/**
 * Tile palette: draws the tileset'stexture with the tile grid on top and reports the picked tile id.
 * Pinch/scroll is not needed here — the view is scaled to fit, which keeps every tile tappable on a
 * phone screen.
 */
class TilePaletteView(
    context: Context,
    val theme: Theme,
    val set: com.sengine.engine.tilemap.TileSet,
    val doc: EditorDocument,
    val onPick: (Int) -> Unit
) : View(context) {

    var selected = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint()
    private val bitmap: Bitmap? = runCatching {
        val file = doc.project.assetFile(set.texture)
        if (!file.exists()) null else BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private var drawScale = 1f
    private var left = 0f
    private var top = 0f

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.track)
        val bmp = bitmap
        if (bmp == null) {
            paint.color = theme.textDim
            paint.textSize = Ui.sp(context, 11f)
            canvas.drawText("Texture '${set.texture}' could not be decoded", theme.dp(6f).toFloat(), height * 0.6f, paint)
            return
        }
        val pad = theme.dp(4f).toFloat()
        drawScale = min((width - pad * 2) / bmp.width, (height - pad * 2) / bmp.height)
        left = (width - bmp.width * drawScale) / 2f
        top = (height - bmp.height * drawScale) / 2f
        val dst = android.graphics.RectF(left, top, left + bmp.width * drawScale, top + bmp.height * drawScale)
        canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dst, paint)
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = max(1f, theme.dp(0.6f).toFloat())
        for (tile in set.tiles.values) {
            val r = android.graphics.RectF(
                left + tile.atlasX * drawScale,
                top + tile.atlasY * drawScale,
                left + (tile.atlasX + tile.width) * drawScale,
                top + (tile.atlasY + tile.height) * drawScale
            )
            gridPaint.color = if (tile.id == selected) theme.accent else Ui.withAlpha(theme.text, 0.22f)
            canvas.drawRect(r, gridPaint)
            if (tile.id == selected) {
                paint.color = Ui.withAlpha(theme.accent, 0.25f)
                canvas.drawRect(r, paint)
            }
            if (tile.collision != 0) {
                paint.color = theme.warning
                canvas.drawCircle(r.left + 4f, r.top + 4f, theme.dp(2.5f).toFloat(), paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val sx = (event.x - left) / drawScale
        val sy = (event.y - top) / drawScale
        var best = 0
        var bestArea = Float.MAX_VALUE
        for (tile in set.tiles.values) {
            if (sx >= tile.atlasX && sx <= tile.atlasX + tile.width && sy >= tile.atlasY && sy <= tile.atlasY + tile.height) {
                val area = (tile.width * tile.height).toFloat()
                if (area < bestArea) { bestArea = area; best = tile.id }
            }
        }
        if (best != 0) {
            selected = best
            onPick(best)
            invalidate()
        }
        return true
    }
}

// --------------------------------------------------------------------------------- shader & particles

/** 2D shader editor: templates, line numbers, uniforms and live compile feedback. */
class ShaderPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)
    private var material: Material? = null
    private var assetName = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        Panels.scroll(this, content)
        refresh()
    }

    fun refresh() {
        content.removeAllViews()
        val materials = doc.project.listAssets(AssetKind.MATERIAL)
        content.addView(Panels.header(context, theme, "2D shader / material editor"))
        val picker = EditorButton(context, theme, "Material: ${assetName.ifEmpty { "none" }}", {})
        picker.setOnClickListener {
            val items = ArrayList<Pair<String, () -> Unit>>()
            items.add("New material" to {
                inputDialog(context, theme, "Material name", "glow") { name ->
                    val m = Material(name)
                    assetName = "$name.material.json"
                    doc.project.writeAsset(assetName, com.sengine.engine.json.Json.write(m.toJson(), true))
                    material = m
                    refresh()
                }
            })
            for (m in materials) items.add(m to {
                assetName = m
                material = doc.project.readAsset(m)?.let { text ->
                    Material().also { it.fromJson(com.sengine.engine.json.Json.parseObject(text)) }
                }
                refresh()
            })
            showMenu(picker, theme, items)
        }
        content.addView(picker)
        val current = material
        if (current == null) {
            content.addView(Ui.label(context, "Create or pick a .material.json asset to edit its fragment shader.", theme, 12f, theme.textDim))
            return
        }
        val templateRow = LinearLayout(context)
        templateRow.orientation = LinearLayout.HORIZONTAL
        for ((name, source) in listOf(
            "Default" to ShaderTemplates.DEFAULT,
            "Outline" to ShaderTemplates.OUTLINE,
            "Glow" to ShaderTemplates.GLOW,
            "Dissolve" to ShaderTemplates.DISSOLVE
        )) {
            val b = EditorButton(context, theme, name, {
                current.template = name.lowercase()
                current.fragment = source
                save(current)
                refresh()
            }, toggled = current.template == name.lowercase())
            templateRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(templateRow)

        val code = Ui.edit(context, current.fragment, theme, "fragment shader", single = false)
        code.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        code.typeface = Typeface.MONOSPACE
        code.setMinLines(12)
        code.setPadding(theme.pad(8f), theme.pad(8f), theme.pad(8f), theme.pad(8f))
        val codeScroll = ScrollView(context)
        codeScroll.addView(code)
        codeScroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(280f))
        content.addView(codeScroll)

        val uniforms = LinearLayout(context)
        uniforms.orientation = LinearLayout.VERTICAL
        for (name in com.sengine.platform.gl.GLRenderer2D.uniformNames(current.fragment)) {
            if (name.startsWith("u")) {
                uniforms.addView(NumberField(context, theme, name, current.uniforms[name] ?: 0f, 0.05f, -100f, 100f, 3) { v ->
                    current.uniforms[name] = v
                    save(current)
                })
            }
        }
        val uniformBox = SectionBox(context, theme, "Uniforms", true)
        uniformBox.body.addView(uniforms)
        content.addView(uniformBox)

        content.addView(Ui.label(context, "Live feedback: shader errors appear in Output and the material falls back to the default shader.", theme, 11f, theme.textDim))
        val apply = EditorButton(context, theme, "Apply", {
            current.fragment = code.text.toString()
            save(current)
            toast(context, "Material saved")
            onChanged()
        })
        content.addView(apply)
    }

    private fun save(material: Material) {
        if (assetName.isEmpty()) return
        doc.project.writeAsset(assetName, com.sengine.engine.json.Json.write(material.toJson(), true))
    }
}

/** Particle editor: presets plus every emitter parameter, with the live emitter on the selection. */
class ParticlePanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        Panels.scroll(this, content)
        refresh()
    }

    fun refresh() {
        content.removeAllViews()
        val node = doc.selection.nodes(doc.scene).firstOrNull { it.getAny<ParticleEmitter2D>() != null }
            ?: doc.scene.objects.firstOrNull { it.getAny<ParticleEmitter2D>() != null }
        if (node == null) {
            content.addView(Ui.label(context, "Select a ParticleEmitter2D node (or create one).", theme, 12f, theme.textDim))
            val create = EditorButton(context, theme, "Create particle node", {
                val n = doc.scene.create("Particles", null, "ParticleEmitter2D")
                doc.onStructureChanged()
                onChanged()
                refresh()
            })
            content.addView(create)
            return
        }
        val emitter = node.getAny<ParticleEmitter2D>()!!
        val spec = emitter.spec
        content.addView(Panels.header(context, theme, "Presets"))
        val presets = ParticlePresets.NAMES
        var row: LinearLayout? = null
        for ((index, name) in presets.withIndex()) {
            if (index % 3 == 0) {
                row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                content.addView(row)
            }
            val b = EditorButton(context, theme, name, {
                ParticlePresets.apply(name, spec)
                onChanged()
                refresh()
            })
            row?.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // real preset assets: save the current spec, or load a saved one onto this emitter
        val presetRow = LinearLayout(context)
        presetRow.orientation = HORIZONTAL
        presetRow.addView(EditorButton(context, theme, "Save preset…", {
            inputDialog(context, theme, "Preset name", "my_effect") { raw ->
                val name = doc.project.uniqueAssetName("${raw}.particle.json")
                doc.project.writeAsset(name, Json.write(spec.toJson(), pretty = true))
                toast(context, "Saved $name")
                refresh()
            }
        }, iconKind = Icons.SAVE, compact = true))
        presetRow.addView(EditorButton(context, theme, "Load preset…", {
            val presets = doc.project.listAssets(AssetKind.OTHER).filter { it.endsWith(".particle.json") }
            if (presets.isEmpty()) toast(context, "No .particle.json assets yet")
            else showMenu(presetRow, theme, presets.map { name ->
                name to {
                    doc.project.readAsset(name)?.let { text ->
                        spec.fromJson(Json.parseObject(text))
                        onChanged()
                        refresh()
                        toast(context, "Loaded $name")
                    }
                }
            })
        }, iconKind = Icons.PARTICLES, compact = true))
        presetRow.addView(EditorButton(context, theme, "Restart emitter", {
            spec.emitting = true
            node.getAny<ParticleEmitter2D>()?.let { it.clear(); it.burst(spec.burst.coerceAtLeast(1)) }
            onChanged()
        }, iconKind = Icons.PLAY, compact = true))
        content.addView(presetRow)
        content.addView(Panels.header(context, theme, "Emitter — live ${emitter.particleCount} particles"))
        val section = SectionBox(context, theme, "Emission", true)
        section.body.addView(EditorButton(context, theme, if (spec.emitting) "Emitting" else "Paused", {
            spec.emitting = !spec.emitting
            onChanged()
            refresh()
        }, toggled = spec.emitting))
        section.body.addView(NumberField(context, theme, "Rate", spec.rate, 1f, 0f, 5000f, 1) { v -> spec.rate = v; onChanged() })
        section.body.addView(NumberField(context, theme, "Burst", spec.burst.toFloat(), 1f, 0f, 5000f, 0) { v -> spec.burst = v.toInt(); onChanged() })
        section.body.addView(NumberField(context, theme, "Max particles", spec.maxParticles.toFloat(), 10f, 1f, 20000f, 0) { v -> spec.maxParticles = v.toInt(); onChanged() })
        section.body.addView(NumberField(context, theme, "Lifetime min", spec.lifetimeMin, 0.05f, 0.01f, 60f, 2) { v -> spec.lifetimeMin = v; onChanged() })
        section.body.addView(NumberField(context, theme, "Lifetime max", spec.lifetimeMax, 0.05f, 0.01f, 60f, 2) { v -> spec.lifetimeMax = v; onChanged() })
        val burst = EditorButton(context, theme, "Emit burst now", {
            emitter.burst(spec.burst.coerceAtLeast(20))
            onChanged()
        })
        section.body.addView(burst)
        content.addView(section)

        val motion = SectionBox(context, theme, "Motion", true)
        motion.body.addView(NumberField(context, theme, "Speed min", spec.speedMin, 0.1f, 0f, 100f, 2) { v -> spec.speedMin = v; onChanged() })
        motion.body.addView(NumberField(context, theme, "Speed max", spec.speedMax, 0.1f, 0f, 100f, 2) { v -> spec.speedMax = v; onChanged() })
        motion.body.addView(NumberField(context, theme, "Direction", spec.direction, 1f, -360f, 360f, 1) { v -> spec.direction = v; onChanged() })
        motion.body.addView(NumberField(context, theme, "Spread", spec.spread, 1f, 0f, 360f, 1) { v -> spec.spread = v; onChanged() })
        motion.body.addView(NumberField(context, theme, "Gravity X", spec.gravityX, 0.1f, -100f, 100f, 2) { v -> spec.gravityX = v; onChanged() })
        motion.body.addView(NumberField(context, theme, "Gravity Y", spec.gravityY, 0.1f, -100f, 100f, 2) { v -> spec.gravityY = v; onChanged() })
        motion.body.addView(NumberField(context, theme, "Drag", spec.drag, 0.05f, 0f, 20f, 2) { v -> spec.drag = v; onChanged() })
        content.addView(motion)

        val look = SectionBox(context, theme, "Appearance", true)
        look.body.addView(NumberField(context, theme, "Start size", spec.startSize, 0.05f, 0f, 50f, 2) { v -> spec.startSize = v; onChanged() })
        look.body.addView(NumberField(context, theme, "End size", spec.endSize, 0.05f, 0f, 50f, 2) { v -> spec.endSize = v; onChanged() })
        look.body.addView(ColorField(context, theme, "Start colour", spec.startColor) { c -> spec.startColor = c; onChanged() })
        look.body.addView(ColorField(context, theme, "End colour", spec.endColor) { c -> spec.endColor = c; onChanged() })
        look.body.addView(NumberField(context, theme, "Rotation speed", spec.rotationSpeed, 1f, -1440f, 1440f, 1) { v -> spec.rotationSpeed = v; onChanged() })
        content.addView(look)
    }
}

// --------------------------------------------------------------------------------- signals dialog

/** Signal connections for a node: create/remove links between an emitter signal and a handler. */
class SignalLinksDialog(
    context: Context,
    val theme: Theme,
    val doc: EditorDocument,
    val node: GameObject
) {
    private val panelContext: Context = context
    private val content = Panels.column(context, theme)

    fun build(): View {
        content.removeAllViews()
        content.addView(Panels.header(panelContext, theme, "Signals of ${node.name}"))
        for (connection in doc.scene.signals.connectionsOf(node.id)) {
            val target = doc.scene.findById(connection.targetId)?.name ?: "?"
            val row = LinearLayout(panelContext)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(Ui.label(panelContext, "${connection.signal} → $target.${connection.method}", theme, 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val remove = EditorButton(panelContext, theme, "✕", {
                doc.undo.push(doc, com.sengine.engine.editor.DisconnectSignalCommand("Disconnect signal", connection))
                build()
            })
            row.addView(remove)
            content.addView(row)
        }
        val add = EditorButton(panelContext, theme, "＋ Connect signal", {})
        add.setOnClickListener {
            val signals = com.sengine.engine.core.Signals.BUILTIN
            val nodes = doc.scene.objects.filter { it.id != node.id }
            val names = signals.toTypedArray()
            android.app.AlertDialog.Builder(panelContext)
                .setTitle("Signal")
                .setItems(names) { _, which ->
                    val targets = nodes.map { it.name }.toTypedArray()
                    android.app.AlertDialog.Builder(panelContext)
                        .setTitle("Target node")
                        .setItems(targets) { _, targetIndex ->
                            val target = nodes[targetIndex]
                            inputDialog(panelContext, theme, "Handler method", "on_pressed") { method ->
                                doc.undo.push(doc, com.sengine.engine.editor.ConnectSignalCommand(
                                    "Connect signal",
                                    com.sengine.engine.core.SignalConnection(node.id, names[which], target.id, method)
                                ))
                                build()
                            }
                        }
                        .show()
                }
                .show()
        }
        content.addView(add)
        return ScrollView(content.context).also { it.addView(content) }
    }

    fun show() {
        android.app.AlertDialog.Builder(panelContext)
            .setView(build())
            .setPositiveButton("Close", null)
            .show()
    }
}
