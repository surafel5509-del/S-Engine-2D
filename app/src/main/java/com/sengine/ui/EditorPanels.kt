package com.sengine.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
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
import com.sengine.engine.debug.Log
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
    private var sortByDate = false

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        val top = LinearLayout(context)
        top.orientation = HORIZONTAL
        val up = EditorButton(context, theme, "↑", { folder = folder.substringBeforeLast('/', ""); refresh() }, icon = "")
        top.addView(up)
        val newFile = EditorButton(context, theme, "＋", {})
        newFile.setOnClickListener { createMenu(newFile) }
        top.addView(newFile)
        val import = EditorButton(context, theme, "⇩", {})
        import.setOnClickListener { onImportRequested?.invoke() }
        top.addView(import)
        val sort = EditorButton(context, theme, "⇅", {
            sortByDate = !sortByDate
            refresh()
        })
        top.addView(sort)
        top.addView(SearchField(context, theme, "Search assets") { q -> query = q; refresh() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(top)
        addView(Ui.label(context, "/assets/${'$'}{folder}".replace("${'$'}{folder}", ""), theme, 11f, theme.textDim))
        Panels.scroll(this, grid)
        refresh()
    }

    var onImportRequested: (() -> Unit)? = null

    private fun createMenu(anchor: View) {
        showMenu(anchor, theme, listOf(
            "New folder…" to {
                inputDialog(context, theme, "New folder", "folder") { name ->
                    File(doc.project.assetsDir, join(folder, name)).mkdirs()
                    refresh()
                }
            },
            "New scene…" to {
                inputDialog(context, theme, "New scene", "Level2") { name ->
                    val scene = com.sengine.engine.core.Scene(name)
                    scene.create("Camera", null, "Camera2D")
                    doc.project.saveScene(scene)
                    onChanged()
                    refresh()
                }
            },
            "New script…" to {
                inputDialog(context, theme, "New script", "player.js") { name ->
                    val fileName = if (name.endsWith(".js")) name else "$name.js"
                    doc.project.writeAsset(join(folder, fileName), com.sengine.engine.core.ScriptComponent.TEMPLATE)
                    refresh()
                }
            },
            "New shader…" to {
                inputDialog(context, theme, "New shader", "glow.material") { name ->
                    val material = Material(name.substringBefore('.'))
                    material.fragment = ShaderTemplates.GLOW
                    doc.project.writeAsset(join(folder, "$name.json"), com.sengine.engine.json.Json.write(material.toJson(), true))
                    refresh()
                }
            },
            "New material…" to {
                inputDialog(context, theme, "New material", "material") { name ->
                    val material = Material(name)
                    doc.project.writeAsset(join(folder, "$name.material.json"), com.sengine.engine.json.Json.write(material.toJson(), true))
                    refresh()
                }
            }
        ))
    }

    private fun join(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"

    fun refresh() {
        grid.removeAllViews()
        val assets = doc.project.listAssetsRecursive()
            .filter { it.startsWith(if (folder.isEmpty()) "" else "$folder/") }
            .filter { query.isEmpty() || it.contains(query, true) }
        val folders = assets.map { it.removePrefix(if (folder.isEmpty()) "" else "$folder/").substringBefore('/', "") }
            .filter { it.isNotEmpty() && it != it.substringBefore('/', "") }
            .distinct()
        if (folder.isNotEmpty() || true) {
            val direct = assets.filter { it.substringAfterLast('/').isNotEmpty() }
            val subFolders = HashSet<String>()
            for (a in direct) {
                val rel = a.removePrefix(if (folder.isEmpty()) "" else "$folder/")
                if (rel.contains('/')) subFolders.add(rel.substringBefore('/'))
            }
            for (f in subFolders) {
                val row = EditorButton(context, theme, "📁 $f", { folder = join(folder, f); refresh() })
                grid.addView(row)
            }
            val sorted = if (sortByDate) direct.sortedByDescending { doc.project.assetFile(it).lastModified() } else direct.sorted()
            for (a in sorted) {
                val rel = a.removePrefix(if (folder.isEmpty()) "" else "$folder/")
                if (rel.contains('/')) continue
                val kind = AssetKind.of(a) ?: AssetKind.OTHER
                val size = doc.project.assetSize(a)
                val row = LinearLayout(context)
                row.orientation = HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(Ui.label(context, icon(kind), theme, 14f, theme.accent))
                row.addView(Ui.label(context, rel, theme, 12f))
                row.addView(Ui.label(context, "${kind.name.lowercase()} · ${size / 1024}kB", theme, 10f, theme.textDim))
                row.isClickable = true
                row.setOnClickListener { onAssign(a, kind) }
                row.setOnLongClickListener {
                    showMenu(row, theme, listOf(
                        "Assign to selection" to { onAssign(a, kind) },
                        "Rename…" to {
                            inputDialog(context, theme, "Rename asset", rel) { newName ->
                                doc.project.renameAsset(a, join(folder, newName))
                                refresh()
                            }
                        },
                        "Duplicate" to {
                            val copy = doc.project.uniqueAssetName(a.substringBeforeLast('.'))
                            doc.project.copyAsset(a, copy)
                            refresh()
                        },
                        "Show in Output" to { Log.info("Assets", "${doc.project.assetFile(a).absolutePath} ($size bytes)") },
                        "Delete" to {
                            confirmDialog(context, "Delete asset", "Delete '$rel'?") {
                                doc.project.deleteAsset(a)
                                refresh()
                            }
                        }
                    ))
                    true
                }
                grid.addView(row)
            }
        }
        if (grid.childCount == 0) grid.addView(Ui.label(context, "No assets. Use ⇩ to import a PNG/JPG/WebP/audio/font file.", theme, 12f, theme.textDim))
    }

    private fun icon(kind: AssetKind): String = when (kind) {
        AssetKind.TEXTURE -> "🖼"
        AssetKind.SOUND -> "♪"
        AssetKind.FONT -> "A"
        AssetKind.SCRIPT -> "⌘"
        AssetKind.SCENE -> "▣"
        AssetKind.TILESET -> "▩"
        AssetKind.ANIMATION -> "▶"
        AssetKind.MATERIAL -> "✦"
        AssetKind.OTHER -> "▫"
        else -> "▫"
    }
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
                val n = doc.scene.create("TileMap", null, "TileMap2D")
                doc.onStructureChanged()
                onChanged()
                refresh()
            })
            content.addView(create)
            return
        }
        val tm = node.getAny<TileMap2D>()!!
        content.addView(Panels.header(context, theme, "${node.name} · ${tm.tileWidth}×${tm.tileHeight}px"))
        content.addView(NumberField(context, theme, "Tile width", tm.tileWidth.toFloat(), 1f, 1f, 256f, 0) { v -> tm.tileWidth = v.toInt(); onChanged() })
        content.addView(NumberField(context, theme, "Tile height", tm.tileHeight.toFloat(), 1f, 1f, 256f, 0) { v -> tm.tileHeight = v.toInt(); onChanged() })
        content.addView(NumberField(context, theme, "Pixels/unit", tm.pixelsPerUnit, 1f, 1f, 256f, 0) { v -> tm.pixelsPerUnit = v; onChanged() })

        val layers = tm.data.layers
        if (layers.isNotEmpty()) {
            content.addView(Ui.label(context, "Layer", theme, 11f, theme.textDim))
            for ((index, layer) in layers.withIndex()) {
                val b = EditorButton(context, theme, layer.name, {
                    state.tileBrush.layerIndex = index
                    refresh()
                }, toggled = state.tileBrush.layerIndex == index)
                content.addView(b)
            }
        }
        val addLayer = EditorButton(context, theme, "＋ Add layer", {
            tm.data.addLayer()
            onChanged()
            refresh()
        })
        content.addView(addLayer)

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
        content.addView(NumberField(context, theme, "Brush size", state.tileBrush.brushSize.toFloat(), 1f, 1f, 16f, 0) { v -> state.tileBrush.brushSize = v.toInt() })
        content.addView(NumberField(context, theme, "Tile id", state.tileBrush.tileId.toFloat(), 1f, 0f, 4096f, 0) { v -> state.tileBrush.tileId = v.toInt(); refresh() })

        val tilesets = doc.project.listAssets(AssetKind.TILESET)
        if (tilesets.isEmpty()) {
            content.addView(Ui.label(context, "No tileset assets. Create one in the FileSystem panel, then assign it on the TileMap2D component.", theme, 11f, theme.textDim))
        } else {
            content.addView(Ui.label(context, "Palette", theme, 11f, theme.textDim))
            val palette = LinearLayout(context)
            palette.orientation = LinearLayout.VERTICAL
            for (asset in tilesets) {
                val set = resources.tileset(asset) ?: continue
                content.addView(Ui.label(context, asset, theme, 11f, theme.textDim))
                var row: LinearLayout? = null
                for (id in 1..set.tiles.size.coerceAtMost(256)) {
                    if ((id - 1) % 8 == 0) {
                        row = LinearLayout(context)
                        row.orientation = LinearLayout.HORIZONTAL
                        palette.addView(row)
                    }
                    val b = EditorButton(context, theme, id.toString(), {
                        state.tileBrush.tileId = id
                        state.tileBrush.active = true
                        refresh()
                    }, toggled = state.tileBrush.tileId == id)
                    row?.addView(b, LinearLayout.LayoutParams(theme.dp(42f), ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
            content.addView(palette)
        }
        val hint = Ui.label(context, "Paint with the Tile tool (5) in the viewport. Collision and metadata come from the tileset.", theme, 11f, theme.textDim)
        content.addView(hint)
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
