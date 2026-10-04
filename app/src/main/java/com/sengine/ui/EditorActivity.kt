package com.sengine.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sengine.engine.Engine
import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.NodeType
import com.sengine.engine.core.Scene
import com.sengine.engine.editor.DeleteNodesCommand
import com.sengine.engine.editor.EditorDocument
import com.sengine.engine.editor.EditorState
import com.sengine.engine.editor.ToolState
import com.sengine.engine.project.Project
import com.sengine.platform.android.AndroidAudio
import com.sengine.platform.android.AndroidTextRenderer
import com.sengine.platform.android.AndroidTextures
import java.io.File

/**
 * The S Engine editor.
 *
 * Layout: top bar (`S ENGINE | Scene | Project | Editor | Debug | Search` + toolbar) · left dock
 * (Scene / FileSystem) · 2D viewport · right dock (Inspector) · bottom dock (Output, Debugger,
 * Profiler, Animation, Tilemap, Shader, Particles). Docks are resizable (drag a divider) and every
 * panel is a real editor: nodes, properties, assets, animation timeline, tile palette, shaders,
 * particle presets, output/debugger/profiler. All actions perform the real operation on the
 * project — there are no placeholder buttons.
 */
class EditorActivity : Activity(), EditorDocument.Listener {

    private lateinit var project: Project
    private lateinit var doc: EditorDocument
    private lateinit var engine: Engine
    private lateinit var theme: Theme
    private lateinit var viewport: ViewportPanel
    private lateinit var state: EditorState

    private var sceneTree: SceneTreePanel? = null
    private var inspector: InspectorPanel? = null
    private var assets: AssetPanel? = null
    private var console: ConsolePanel? = null
    private var animation: AnimationPanel? = null
    private var tilemap: TileMapPanel? = null
    private var shaders: ShaderPanel? = null
    private var particles: ParticlePanel? = null

    private lateinit var root: LinearLayout
    private lateinit var centerColumn: LinearLayout
    private lateinit var leftDock: LinearLayout
    private lateinit var rightDock: LinearLayout
    private lateinit var bottomDock: LinearLayout
    private lateinit var statusBar: TextView
    private lateinit var titleBar: TextView
    private var leftWidth = 240
    private var rightWidth = 300
    private var bottomHeight = 190
    private val handler = Handler(Looper.getMainLooper())
    private var autosaveTick = Runnable { autosave() }
    private var playing = false
    private var importedAssetKind: AssetKind = AssetKind.OTHER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        theme = AppState.theme
        val path = intent.getStringExtra("project")
        val sceneName = intent.getStringExtra("scene") ?: "Main"
        val dir = if (path != null) File(path) else AppState.manager().open(AppState.lastProjectName()).dir
        project = Project(dir)
        if (!File(dir, "project.json").exists()) project.saveMeta()
        project.settings.fromJson(com.sengine.engine.json.Json.parseObject(File(dir, "project.json").takeIf { it.exists() }?.readText() ?: "{}"))
        theme.apply(project.settings)
        Loc.language = project.settings.locale

        doc = EditorDocument(project, project.loadScene(sceneName))
        doc.openSceneNames = project.listScenes().toMutableList()
        doc.listeners.add(this)
        engine = Engine(project, doc.scene)
        state = EditorState()
        state.showGrid = project.settings.showGrid
        state.gridStep = project.settings.gridStep
        state.snap.step = project.settings.snapStep
        state.snap.enabled = project.settings.snapEnabled
        state.pixelsPerUnit = 100f

        buildUi()
        installRecovery()
        titleBar.text = title()
        handler.postDelayed(autosaveTick, 30_000)
    }

    // ------------------------------------------------------------------ ui construction

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.background)
        root.addView(buildTopBar())

        val main = LinearLayout(this)
        main.orientation = LinearLayout.HORIZONTAL
        main.setBackgroundColor(theme.background)

        leftDock = LinearLayout(this)
        leftDock.orientation = LinearLayout.VERTICAL
        leftDock.setBackgroundColor(theme.panel)
        val rightSplit = Splitter(this, theme, true) { delta -> rightWidth = (rightWidth - delta).coerceIn(180, 720); applyDockSizes() }
        centerColumn = LinearLayout(this)
        centerColumn.orientation = LinearLayout.VERTICAL
        rightDock = LinearLayout(this)
        rightDock.orientation = LinearLayout.VERTICAL
        rightDock.setBackgroundColor(theme.panel)

        main.addView(leftDock, LinearLayout.LayoutParams(theme.dp(leftWidth.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT))
        val leftSplit = Splitter(this, theme, true) { delta -> leftWidth = (leftWidth + delta).coerceIn(140, 560); applyDockSizes() }
        main.addView(leftSplit)
        main.addView(centerColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        main.addView(rightSplit)
        main.addView(rightDock, LinearLayout.LayoutParams(theme.dp(rightWidth.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        statusBar = Ui.label(this, "Ready", theme, 11f, theme.textDim)
        statusBar.setBackgroundColor(theme.panelAlt)
        root.addView(statusBar)

        setContentView(root)

        viewport = ViewportPanel(this, doc, engine, theme, state)
        viewport.onStatus = { text -> statusBar.text = text }
        viewport.onSelectionChanged = { refreshPanels() }
        viewport.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        centerColumn.addView(buildViewportToolbar())
        centerColumn.addView(viewport)

        val bottomSplit = Splitter(this, theme, false) { delta -> bottomHeight = (bottomHeight - delta).coerceIn(90, 620); applyDockSizes() }
        centerColumn.addView(bottomSplit)
        bottomDock = LinearLayout(this)
        bottomDock.orientation = LinearLayout.VERTICAL
        bottomDock.setBackgroundColor(theme.panel)
        bottomDock.addView(buildBottomTabs())
        centerColumn.addView(bottomDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(bottomHeight.toFloat())))

        leftDock.addView(buildLeftDock())
        rightDock.addView(buildRightDock())
        applyDockSizes()
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(theme.panel)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.pad(6f), theme.pad(4f), theme.pad(6f), theme.pad(4f))

        titleBar = Ui.label(this, "S ENGINE", theme, 14f, theme.accent, bold = true)
        titleBar.setOnClickListener { showSceneMenu(titleBar) }
        row.addView(titleBar)

        fun menuButton(name: String, onClick: (View) -> Unit): View {
            lateinit var button: EditorButton
            button = EditorButton(this, theme, name, { onClick(button) })
            button.setPadding(theme.pad(8f), theme.pad(2f), theme.pad(8f), theme.pad(2f))
            return button
        }
        row.addView(menuButton("Scene") { showSceneMenu(it) })
        row.addView(menuButton("Project") { showProjectMenu(it) })
        row.addView(menuButton("Editor") { showEditorMenu(it) })
        row.addView(menuButton("Debug") { showDebugMenu(it) })
        val search = EditorButton(this, theme, "Search", { showCommandPalette() }, icon = "⌕")
        row.addView(search)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val run = EditorButton(this, theme, if (playing) "Stop" else "Run", {
            if (playing) stopPlay() else startPlay()
        }, icon = if (playing) "■" else "▶")
        row.addView(run)
        bar.addView(row)

        // toolbar strip
        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.setPadding(theme.pad(6f), theme.pad(4f), theme.pad(6f), theme.pad(4f))
        tools.setBackgroundColor(theme.panelAlt)
        for (tool in ToolState.ALL) {
            val b = EditorButton(this, theme, tool.label, {
                state.tool = tool
                toast(this, "${tool.label} tool (${tool.shortcut})")
                refreshToolButtons()
            }, toggled = state.tool == tool, icon = tool.icon)
            tools.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val toggles = ArrayList<Pair<String, () -> Unit>>()
        toggles.add("Grid" to { state.showGrid = !state.showGrid })
        toggles.add("Snap" to { state.snap.enabled = !state.snap.enabled })
        toggles.add("Colliders" to { state.showColliders = !state.showColliders })
        toggles.add("Guides" to { state.showGuides = !state.showGuides })
        toggles.add("Physics" to { state.showPhysicsDebug = !state.showPhysicsDebug })
        toggles.add("UI bounds" to { state.showUIBounds = !state.showUIBounds })
        for ((name, action) in toggles) {
            val b = EditorButton(this, theme, name, {
                action()
                refreshToolButtons()
            }, toggled = when (name) {
                "Grid" -> state.showGrid
                "Snap" -> state.snap.enabled
                "Colliders" -> state.showColliders
                "Guides" -> state.showGuides
                "Physics" -> state.showPhysicsDebug
                else -> state.showUIBounds
            })
            tools.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toolbarItem(b, theme, "Toggle $name overlay")
        }
        val focus = EditorButton(this, theme, "Focus", { viewport.focusSelection() }, icon = "◎")
        tools.addView(focus)
        bar.addView(tools)
        toolbarItem(search, theme, "Command palette (Ctrl+Shift+P)")
        toolbarItem(run, theme, "Run the project in play mode (F5) / stop (F6)")
        return bar
    }

    private fun refreshToolButtons() {
        viewport.requestRender()
    }

    private fun buildViewportToolbar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setBackgroundColor(theme.panel)
        row.setPadding(theme.pad(4f), theme.pad(3f), theme.pad(4f), theme.pad(3f))
        row.addView(Ui.label(this, doc.sceneName, theme, 12f, theme.textDim))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val preview = EditorButton(this, theme, "Resolution: ${EditorState.PREVIEW_SIZES[state.resolutionPreview].first}", {})
        preview.setOnClickListener {
            showMenu(preview, theme, EditorState.PREVIEW_SIZES.mapIndexed { index, size ->
                size.first to {
                    viewport.applyPreviewResolution(index)
                    preview.setText("Resolution: ${size.first}")
                }
            })
        }
        row.addView(preview)
        val zoomIn = EditorButton(this, theme, "＋", { viewport.zoom(1.25f) })
        val zoomOut = EditorButton(this, theme, "－", { viewport.zoom(0.8f) })
        row.addView(zoomOut)
        row.addView(zoomIn)
        return row
    }

    private fun buildLeftDock(): View {
        val tabs = TabStrip(this, theme)
        tabs.setTabs(listOf("Scene", "FileSystem", "Animation", "TileMap"))
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.addView(tabs)
        val stack = FrameLayout(this)
        container.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tree = SceneTreePanel(this, doc, theme, state, { refreshPanels() }, { refreshPanels() }, { refreshPanels() })
        sceneTree = tree
        val assetPanel = AssetPanel(this, doc, theme, { refreshPanels() }, { name, kind -> assignAsset(name, kind) })
        assetPanel.onImportRequested = { pickAsset() }
        assets = assetPanel
        animation = AnimationPanel(this, doc, theme) { refreshPanels() }
        tilemap = TileMapPanel(this, doc, theme, state, engine.resources) { refreshPanels() }

        stack.addView(tree)
        stack.addView(assetPanel)
        stack.addView(animation!!)
        stack.addView(tilemap!!)
        tabs.onSelect = { index ->
            for (i in 0 until stack.childCount) stack.getChildAt(i).visibility = if (i == index) View.VISIBLE else View.GONE
            when (index) {
                0 -> sceneTree?.refresh()
                1 -> assets?.refresh()
                2 -> animation?.refresh()
                3 -> tilemap?.refresh()
            }
        }
        stack.getChildAt(1).visibility = View.GONE
        stack.getChildAt(2).visibility = View.GONE
        stack.getChildAt(3).visibility = View.GONE
        return container
    }

    private fun buildRightDock(): View {
        val inspectorPanel = InspectorPanel(this, doc, theme, state, project) { refreshPanels() }
        inspector = inspectorPanel
        val particlePanel = ParticlePanel(this, doc, theme) { refreshPanels() }
        particles = particlePanel
        val shaderPanel = ShaderPanel(this, doc, theme) { refreshPanels() }
        shaders = shaderPanel
        val tabs = TabStrip(this, theme)
        tabs.setTabs(listOf("Inspector", "Particles", "Shaders"))
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.addView(tabs)
        val stack = FrameLayout(this)
        container.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        stack.addView(inspectorPanel)
        stack.addView(particlePanel)
        stack.addView(shaderPanel)
        stack.getChildAt(1).visibility = View.GONE
        stack.getChildAt(2).visibility = View.GONE
        tabs.onSelect = { index ->
            for (i in 0 until stack.childCount) stack.getChildAt(i).visibility = if (i == index) View.VISIBLE else View.GONE
            when (index) {
                0 -> inspectorPanel.refresh()
                1 -> particlePanel.refresh()
                2 -> shaderPanel.refresh()
            }
        }
        return container
    }

    private fun buildBottomTabs(): View {
        val panel = ConsolePanel(this, theme, state) { engine }
        console = panel
        val tabs = TabStrip(this, theme)
        tabs.setTabs(listOf("Output", "Debugger", "Profiler"))
        tabs.onSelect = { index -> panel.refresh(index) }
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.addView(tabs)
        container.addView(panel)
        return container
    }

    private fun applyDockSizes() {
        leftDock.layoutParams = LinearLayout.LayoutParams(theme.dp(leftWidth.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT)
        rightDock.layoutParams = LinearLayout.LayoutParams(theme.dp(rightWidth.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT)
        bottomDock.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(bottomHeight.toFloat()))
        leftDock.requestLayout()
        rightDock.requestLayout()
        bottomDock.requestLayout()
    }

    // ------------------------------------------------------------------ menus

    private fun showSceneMenu(anchor: View) {
        val scenes = project.listScenes()
        showMenu(anchor, theme, listOf(
            "New Scene…" to {
                inputDialog(this, theme, "New scene", "Level2") { name ->
                    val scene = Scene(name)
                    scene.create("Camera", null, "Camera2D")
                    project.saveScene(scene)
                    doc.openSceneNames = project.listScenes().toMutableList()
                    openScene(name)
                }
            },
            "Open Scene…" to {
                showMenu(anchor, theme, scenes.map { name -> name to { openScene(name) } })
            },
            "-" to {},
            "Save Scene" to { saveScene() },
            "Save Scene As…" to {
                inputDialog(this, theme, "Save scene as", doc.sceneName) { name ->
                    if (doc.saveAs(name)) {
                        doc.openSceneNames = project.listScenes().toMutableList()
                        toast(this, "Saved $name")
                        titleBar.text = title()
                    }
                }
            },
            "Duplicate Scene" to {
                inputDialog(this, theme, "Duplicate scene", "${doc.sceneName} Copy") { name ->
                    project.duplicateScene(doc.sceneName, name)
                    doc.openSceneNames = project.listScenes().toMutableList()
                    toast(this, "Duplicated to $name")
                }
            },
            "Delete Scene" to {
                confirmDialog(this, "Delete scene", "Delete '${doc.sceneName}'?") {
                    project.deleteScene(doc.sceneName)
                    doc.openSceneNames = project.listScenes().toMutableList()
                    val next = doc.openSceneNames.firstOrNull()
                    if (next != null) openScene(next) else {
                        doc.replaceScene(Scene("Main"))
                        titleBar.text = title()
                    }
                }
            },
            "-" to {},
            "Set as start scene" to {
                project.settings.startScene = doc.sceneName
                project.saveMeta()
                toast(this, "${doc.sceneName} is now the start scene")
            },
            "Scene Settings…" to { showSceneSettings() }
        ))
    }

    private fun showProjectMenu(anchor: View) {
        showMenu(anchor, theme, listOf(
            "Project Settings…" to { showProjectSettings() },
            "Reload project" to {
                project.settings.fromJson(com.sengine.engine.json.Json.parseObject(File(project.dir, "project.json").readText()))
                theme.apply(project.settings)
                toast(this, "Project settings reloaded")
            },
            "-" to {},
            "Export project (.zip)…" to { exportProject() },
            "Import file into assets…" to { pickAsset() },
            "-" to {},
            "Close editor" to {
                saveScene()
                finish()
            },
            "Back to Project Manager" to {
                saveScene()
                startActivity(Intent(this, ProjectManagerActivity::class.java))
                finish()
            }
        ))
    }

    private fun showEditorMenu(anchor: View) {
        showMenu(anchor, theme, listOf(
            "Undo ${doc.undo.undoLabel}" to { if (doc.undo.undo(doc)) refreshPanels() },
            "Redo ${doc.undo.redoLabel}" to { if (doc.undo.redo(doc)) refreshPanels() },
            "-" to {},
            "Add node…" to { showAddNodeMenu(anchor) },
            "Duplicate selection" to { duplicateSelection() },
            "Delete selection" to { deleteSelection() },
            "-" to {},
            "Toggle grid" to { state.showGrid = !state.showGrid; refreshToolButtons() },
            "Toggle snapping" to { state.snap.enabled = !state.snap.enabled; refreshToolButtons() },
            "Snap step…" to {
                inputDialog(this, theme, "Snap step", state.snap.step.toString()) { value ->
                    value.toFloatOrNull()?.let { state.snap.step = it.coerceAtLeast(0.001f) }
                }
            },
            "Grid step…" to {
                inputDialog(this, theme, "Grid step", state.gridStep.toString()) { value ->
                    value.toFloatOrNull()?.let { state.gridStep = it.coerceAtLeast(0.01f) }
                }
            },
            "Add guide (horizontal)" to { state.guides.add(floatArrayOf(-100f, state.mouseWorldY, 100f, state.mouseWorldY)) },
            "Add guide (vertical)" to { state.guides.add(floatArrayOf(state.mouseWorldX, -100f, state.mouseWorldX, 100f)) },
            "Clear guides" to { state.guides.clear() },
            "-" to {},
            "Save workspace layout" to {
                AppState.saveEditorLayout("$leftWidth|$rightWidth|$bottomHeight")
                toast(this, "Layout saved")
            },
            "Restore workspace layout" to {
                val saved = AppState.editorLayout()
                if (saved.isNotEmpty()) {
                    val parts = saved.split('|').mapNotNull { it.toIntOrNull() }
                    if (parts.size == 3) {
                        leftWidth = parts[0]; rightWidth = parts[1]; bottomHeight = parts[2]
                        applyDockSizes()
                    }
                }
            },
            "Editor settings…" to { showEditorSettings() }
        ))
    }

    private fun showDebugMenu(anchor: View) {
        showMenu(anchor, theme, listOf(
            "Run (F5)" to { startPlay() },
            "Pause" to { engine.pause() },
            "Step frame" to { engine.stepFrame() },
            "Stop (F6)" to { stopPlay() },
            "-" to {},
            "Clear output" to { com.sengine.engine.debug.Log.clear(); console?.refresh(0) },
            "Query overlaps at selection" to {
                val node = doc.scene.findById(state.selectedId)
                if (node != null) {
                    val hit = engine.physics.overlapPoint(doc.scene, node.world.tx, node.world.ty)
                    com.sengine.engine.debug.Log.info("Debug", "Overlap query at ${node.name}: ${hit?.name ?: "none"}")
                }
            },
            "Toggle physics debug draw" to { state.showPhysicsDebug = !state.showPhysicsDebug },
            "Reload scripts" to {
                engine.scripts.reload()
                toast(this, "Scripts reloaded")
            }
        ))
    }

    private fun showAddNodeMenu(anchor: View) {
        val entries = NodeType.ALL
        showMenu(anchor, theme, entries.map { entry ->
            "${entry.label} — ${entry.description}" to {
                val node = doc.scene.create(entry.label, null, entry.defaultComponent ?: NodeType.NODE)
                state.selectionIds = listOf(node.id)
                state.selectedId = node.id
                doc.selection.set(listOf(node.id))
                doc.onStructureChanged()
                refreshPanels()
            }
        })
    }

    private fun showSceneSettings() {
        val scene = doc.scene
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(theme.pad(16f), theme.pad(8f), theme.pad(16f), 0)
        box.addView(NumberField(this, theme, "Gravity X", scene.settings.gravityX, 0.1f, onChange = { v -> scene.settings.gravityX = v; doc.onSceneMutated() }))
        box.addView(NumberField(this, theme, "Gravity Y", scene.settings.gravityY, 0.1f, onChange = { v -> scene.settings.gravityY = v; doc.onSceneMutated() }))
        box.addView(ColorField(this, theme, "Background", scene.settings.background) { c -> scene.settings.background = c; doc.onSceneMutated() })
        box.addView(EditorButton(this, theme, "Pixel snap", { scene.settings.pixelSnap = !scene.settings.pixelSnap }, toggled = scene.settings.pixelSnap))
        box.addView(EditorButton(this, theme, "Pixel perfect", { scene.settings.pixelPerfect = !scene.settings.pixelPerfect }, toggled = scene.settings.pixelPerfect))
        box.addView(EditorButton(this, theme, "Y sort", { scene.settings.ySort = !scene.settings.ySort }, toggled = scene.settings.ySort))
        box.addView(NumberField(this, theme, "Grid step", scene.settings.gridStep, 0.1f, onChange = { v -> scene.settings.gridStep = v; state.gridStep = v }))
        android.app.AlertDialog.Builder(this).setTitle("Scene settings").setView(box).setPositiveButton("OK") { _, _ ->
            doc.onSceneMutated()
        }.show()
    }

    private fun showProjectSettings() {
        val settings = project.settings
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(theme.pad(16f), theme.pad(8f), theme.pad(16f), 0)
        box.addView(NumberField(this, theme, "Window width", settings.windowWidth.toFloat(), 1f, 64f, 8192f, 0) { v -> settings.windowWidth = v.toInt() })
        box.addView(NumberField(this, theme, "Window height", settings.windowHeight.toFloat(), 1f, 64f, 8192f, 0) { v -> settings.windowHeight = v.toInt() })
        box.addView(NumberField(this, theme, "Target FPS", settings.targetFps.toFloat(), 1f, 10f, 240f, 0) { v -> settings.targetFps = v.toInt() })
        box.addView(NumberField(this, theme, "Default gravity", settings.defaultGravity, 0.1f, onChange = { v -> settings.defaultGravity = v }))
        box.addView(NumberField(this, theme, "Autosave seconds", settings.autosaveSeconds.toFloat(), 5f, 0f, 3600f, 0) { v -> settings.autosaveSeconds = v.toInt() })
        box.addView(NumberField(this, theme, "UI design width", settings.uiDesignWidth.toFloat(), 1f, 64f, 8192f, 0) { v -> settings.uiDesignWidth = v.toInt() })
        box.addView(NumberField(this, theme, "UI design height", settings.uiDesignHeight.toFloat(), 1f, 64f, 8192f, 0) { v -> settings.uiDesignHeight = v.toInt() })
        box.addView(EditorButton(this, theme, "Pixel perfect", { settings.pixelPerfect = !settings.pixelPerfect }, toggled = settings.pixelPerfect))
        box.addView(EditorButton(this, theme, "Vertical sync", { settings.vsync = !settings.vsync }, toggled = settings.vsync))
        box.addView(EditorButton(this, theme, if (settings.theme == "Dark") "Theme: Dark" else "Theme: Light", {
            settings.theme = if (settings.theme == "Dark") "Light" else "Dark"
            if (settings.theme == "Light") theme.applyLight() else theme.applyDark()
        }))
        android.app.AlertDialog.Builder(this).setTitle("Project settings").setView(box).setPositiveButton("Save") { _, _ ->
            project.saveMeta()
            toast(this, "Project settings saved")
        }.show()
    }

    private fun showEditorSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(theme.pad(16f), theme.pad(8f), theme.pad(16f), 0)
        box.addView(Ui.label(this, "UI scale: ${(theme.scale * 100).toInt()}%", theme, 12f, theme.textDim))
        val scale = android.widget.SeekBar(this)
        scale.max = 125
        scale.progress = ((theme.scale - 0.75f) * 100).toInt().coerceIn(0, 125)
        scale.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                theme.scale = (0.75f + progress / 100f).coerceIn(0.75f, 2f)
                AppState.saveTheme(theme)
                project.settings.uiScale = theme.scale
                project.saveMeta()
            }
            override fun onStartTrackingTouch(seek: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seek: android.widget.SeekBar?) {}
        })
        box.addView(scale)
        val contrast = android.widget.CheckBox(this)
        contrast.text = "High contrast"
        contrast.setTextColor(theme.text)
        contrast.isChecked = theme.highContrast
        box.addView(contrast)
        val lang = EditorButton(this, theme, "Language: ${Loc.available.first { it.first == Loc.language }.second}", {
            val codes = Loc.available.map { it.first }.toTypedArray()
            val names = Loc.available.map { it.second }.toTypedArray()
            android.app.AlertDialog.Builder(this).setTitle("Language").setSingleChoiceItems(names, codes.indexOf(Loc.language).coerceAtLeast(0)) { dialog, which ->
                AppState.saveLocale(codes[which])
                project.settings.locale = codes[which]
                project.saveMeta()
                dialog.dismiss()
                toast(this, "Language: ${names[which]}")
            }.show()
        })
        box.addView(lang)
        android.app.AlertDialog.Builder(this)
            .setTitle("Editor settings")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                theme.highContrast = contrast.isChecked
                if (theme.dark) theme.applyDark() else theme.applyLight()
                AppState.saveTheme(theme)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ actions

    private fun openScene(name: String) {
        saveScene()
        doc.openScene(name)
        state.selectionIds = emptyList()
        state.selectedId = -1L
        project.settings.startScene = project.settings.startScene.ifEmpty { name }
        engine.post { engine.replaceScene(doc.scene) }
        AppState.rememberScene(name)
        AppState.rememberSceneFile(project.name, name)
        titleBar.text = title()
        refreshPanels()
        com.sengine.engine.debug.Log.info("Editor", "Opened scene $name")
    }

    private fun saveScene() {
        if (doc.save()) {
            titleBar.text = title()
            com.sengine.engine.debug.Log.info("Editor", "Saved ${doc.sceneName}")
        }
    }

    private fun exportProject() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "${project.name}.sengine.zip")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 5001)
    }

    private fun pickAsset() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 5002)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            5001 -> try {
                contentResolver.openOutputStream(uri)?.use { project.exportZip(it) }
                toast(this, "Exported ${project.name}")
            } catch (e: Exception) {
                toast(this, "Export failed: ${e.message}")
            }
            5002 -> try {
                val name = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}"
                contentResolver.openInputStream(uri)?.use { project.importAsset(name, it.readBytes()) }
                assets?.refresh()
                toast(this, "Imported $name")
            } catch (e: Exception) {
                toast(this, "Import failed: ${e.message}")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return uri.lastPathSegment
        cursor.use {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) return it.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun assignAsset(name: String, kind: AssetKind) {
        val node = doc.scene.findById(state.selectedId)
        if (node == null) {
            toast(this, "Select a node first to assign $name")
            return
        }
        var assigned = false
        for (component in node.components) {
            for (prop in component.props()) {
                if (prop is com.sengine.engine.core.Prop.Asset && prop.kind == kind) {
                    prop.set(name)
                    assigned = true
                }
            }
        }
        if (assigned) {
            doc.onSceneMutated()
            refreshPanels()
            toast(this, "Assigned $name to ${node.name}")
        } else {
            when (kind) {
                AssetKind.SCENE -> openScene(name.substringBeforeLast('.'))
                AssetKind.TILESET -> {
                    val tm = node.getAny<com.sengine.engine.core.TileMap2D>()
                    if (tm != null) {
                        tm.tileSetAsset = name
                        doc.onSceneMutated()
                        toast(this, "Tileset assigned to ${node.name}")
                    }
                }
                else -> toast(this, "No ${kind.name.lowercase()} slot on ${node.name}")
            }
        }
    }

    private fun duplicateSelection() {
        val nodes = doc.selection.nodes(doc.scene)
        if (nodes.isEmpty()) return
        val copies = nodes.map { doc.scene.duplicate(it) }
        doc.onStructureChanged()
        doc.selection.set(copies.map { it.id })
        state.selectionIds = copies.map { it.id }
        state.selectedId = copies.lastOrNull()?.id ?: -1L
        refreshPanels()
    }

    private fun deleteSelection() {
        val ids = doc.selection.ids()
        if (ids.isEmpty()) return
        doc.undo.push(doc, DeleteNodesCommand.of(doc.scene, ids))
        state.selectionIds = emptyList()
        state.selectedId = -1L
        refreshPanels()
    }

    // ------------------------------------------------------------------ play mode

    private fun startPlay() {
        if (playing) return
        if (project.settings.autosaveEnabled && doc.dirty) saveScene()
        playing = true
        engine.play()
        val intent = Intent(this, GameActivity::class.java)
        intent.putExtra("project", project.dir.absolutePath)
        intent.putExtra("scene", doc.sceneName)
        intent.putExtra("embedded", false)
        startActivity(intent)
        engine.pause()
    }

    private fun stopPlay() {
        playing = false
        engine.stop()
        refreshPanels()
    }

    override fun onResume() {
        super.onResume()
        viewport.onResume()
        if (playing) {
            // returned from the play-test activity
            playing = false
            engine.stop()
        }
        viewport.requestRender()
    }

    override fun onPause() {
        super.onPause()
        viewport.onPause()
        saveScene()
        if (doc.dirty) doc.autosave()
        AppState.saveEditorLayout("$leftWidth|$rightWidth|$bottomHeight")
        project.settings.gridStep = state.gridStep
        project.settings.snapStep = state.snap.step
        project.settings.showGrid = state.showGrid
        project.settings.snapEnabled = state.snap.enabled
        project.saveMeta()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autosaveTick)
        viewport.dispose()
        doc.listeners.remove(this)
    }

    private fun autosave() {
        if (doc.dirty && project.settings.autosaveEnabled) {
            doc.autosave()
            statusBar.text = "Autosaved at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"
        }
        handler.postDelayed(autosaveTick, (project.settings.autosaveSeconds.coerceAtLeast(15)) * 1000L)
    }

    private fun installRecovery() {
        if (!doc.hasRecovery()) return
        confirmDialog(this, "Recover unsaved work", "An autosave from a previous session was found for '${doc.sceneName}'. Recover it?") {
            if (doc.recoverFromAutosave()) {
                engine.post { engine.replaceScene(doc.scene) }
                refreshPanels()
                toast(this, "Recovered ${doc.sceneName}")
            }
        }
    }

    private fun title(): String {
        return "${if (doc.dirty) "*" else ""}${doc.sceneName} — ${project.name} — S ENGINE"
    }

    private fun refreshPanels() {
        sceneTree?.refresh()
        inspector?.refresh()
        viewport.requestRender()
    }

    // ------------------------------------------------------------------ command palette & keys

    private fun showCommandPalette() {
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Ui.withAlpha(theme.background, 0.85f))
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(theme.panel)
        box.setPadding(theme.pad(10f), theme.pad(10f), theme.pad(10f), theme.pad(10f))
        val search = SearchField(this, theme, "Type a command…") { query -> /* filtering below */ }
        var query = ""
        search.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { query = s?.toString() ?: "" }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        box.addView(search)
        val list = Panels.column(this, theme)
        val commands = buildCommands()
        fun render() {
            list.removeAllViews()
            for ((name, action) in commands.filter { query.isEmpty() || it.first.contains(query, true) }.take(12)) {
                val row = Ui.label(this, name, theme, 13f)
                row.setPadding(theme.pad(12f), theme.pad(10f), theme.pad(12f), theme.pad(10f))
                row.isClickable = true
                row.setOnClickListener {
                    overlay.visibility = View.GONE
                    overlay.removeAllViews()
                    action()
                }
                list.addView(row)
            }
        }
        search.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { render() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        render()
        val scroll = android.widget.ScrollView(this)
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(280f)))
        overlay.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        overlay.isClickable = true
        overlay.setOnClickListener { overlay.visibility = View.GONE }
        (root as ViewGroup).addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        search.edit.requestFocus()
    }

    private fun buildCommands(): List<Pair<String, () -> Unit>> = listOf(
        "Create Node…" to { showAddNodeMenu(root) },
        "Open Scene…" to { showSceneMenu(root) },
        "Save (Ctrl+S)" to { saveScene() },
        "Run (F5)" to { startPlay() },
        "Stop (F6)" to { stopPlay() },
        "Focus Selection (F)" to { viewport.focusSelection() },
        "Toggle Grid" to { state.showGrid = !state.showGrid; refreshToolButtons() },
        "Toggle Snap" to { state.snap.enabled = !state.snap.enabled; refreshToolButtons() },
        "Open Settings" to { showEditorSettings() },
        "Open Project Settings" to { showProjectSettings() },
        "Open Profiler" to { console?.refresh(2) },
        "Open Debugger" to { console?.refresh(1) },
        "Undo (Ctrl+Z)" to { if (doc.undo.undo(doc)) refreshPanels() },
        "Redo (Ctrl+Shift+Z)" to { if (doc.undo.redo(doc)) refreshPanels() },
        "Delete Selection (Del)" to { deleteSelection() },
        "Duplicate Selection (Ctrl+D)" to { duplicateSelection() },
        "Import Asset…" to { pickAsset() },
        "Export Project…" to { exportProject() },
        "Save Workspace Layout" to { AppState.saveEditorLayout("$leftWidth|$rightWidth|$bottomHeight") },
        "Back to Project Manager" to { startActivity(Intent(this, ProjectManagerActivity::class.java)); finish() }
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        when (keyCode) {
            KeyEvent.KEYCODE_S -> {
                if (ctrl || event.isMetaPressed) { saveScene(); return true }
            }
            KeyEvent.KEYCODE_Z -> {
                if (ctrl || event.isMetaPressed) {
                    if (event.isShiftPressed) { if (doc.undo.redo(doc)) refreshPanels() }
                    else if (doc.undo.undo(doc)) refreshPanels()
                    return true
                }
            }
            KeyEvent.KEYCODE_Y -> {
                if (ctrl) { if (doc.undo.redo(doc)) refreshPanels(); return true }
            }
            KeyEvent.KEYCODE_D -> {
                if (ctrl) { duplicateSelection(); return true }
            }
            KeyEvent.KEYCODE_P -> {
                if (ctrl && event.isShiftPressed) { showCommandPalette(); return true }
            }
            KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_R -> { startPlay(); return true }
            KeyEvent.KEYCODE_F6 -> { stopPlay(); return true }
            KeyEvent.KEYCODE_F -> { viewport.focusSelection(); return true }
            KeyEvent.KEYCODE_T -> { state.tool = ToolState.SCALE; return true }
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> { deleteSelection(); return true }
            KeyEvent.KEYCODE_E -> { state.tool = ToolState.ROTATE; return true }
            KeyEvent.KEYCODE_W -> { state.tool = ToolState.MOVE; return true }
            KeyEvent.KEYCODE_Q -> { state.tool = ToolState.SELECT; return true }
            KeyEvent.KEYCODE_ESCAPE -> { engine.stop(); playing = false; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ document listener

    override fun onSceneChanged(scene: Scene) {
        titleBar.text = title()
    }

    override fun onStructureChanged() {
        refreshPanels()
        titleBar.text = title()
    }

    override fun onDirtyChanged(dirty: Boolean) {
        titleBar.text = title()
    }

    override fun onSelectionChanged() {
        inspector?.refresh()
        viewport.requestRender()
    }
}
