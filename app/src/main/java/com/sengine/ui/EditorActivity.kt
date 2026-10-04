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
    private var spriteEditor: SpriteEditorPanel? = null
    private var audioPanel: AudioPanel? = null
    private var scriptEditor: ScriptEditorPanel? = null
    private lateinit var audio: com.sengine.platform.android.AndroidAudio
    private var exportPanel: ExportPanel? = null
    private var inputPanel: InputMapPanel? = null

    /** Panel containers, rebuilt on orientation change (portrait stacks everything under the view). */
    private var dockTabs = ArrayList<TabStrip>()
    private var dockStack: FrameLayout? = null
    private lateinit var root: LinearLayout
    private lateinit var centerColumn: LinearLayout
    private lateinit var leftDock: LinearLayout
    private lateinit var rightDock: LinearLayout
    private lateinit var bottomDock: LinearLayout
    private lateinit var statusBar: TextView
    private lateinit var titleBar: TextView
    private lateinit var toolLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var runButton: EditorButton
    private var leftWidth = 250
    private var rightWidth = 310
    private var bottomHeight = 190
    private var landscape = true
    private val handler = Handler(Looper.getMainLooper())
    private var autosaveTick = Runnable { autosave() }
    private var playing = false
    private var importedAssetKind: AssetKind = AssetKind.OTHER
    private val toolButtons = ArrayList<Pair<IconButton, () -> Boolean>>()

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
        // the editor previews audio through the same mixer the game uses
        audio = com.sengine.platform.android.AndroidAudio(this) { name ->
            project.assetFile(name).takeIf { it.exists() }
        }
        engine.audio.backend = audio
        state = EditorState()
        state.showGrid = project.settings.showGrid
        state.gridStep = project.settings.gridStep
        state.snap.step = project.settings.snapStep
        state.snap.enabled = project.settings.snapEnabled
        state.pixelsPerUnit = 100f

        val saved = AppState.editorLayout()
        val parts = saved.split('|').mapNotNull { it.toIntOrNull() }
        if (parts.size >= 3) {
            leftWidth = parts[0]; rightWidth = parts[1]; bottomHeight = parts[2]
        }

        landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        buildPanels()
        buildUi()
        installRecovery()
        titleBar.text = title()
        // frame the scene the moment the viewport has a size, so the editor never opens on empty sky
        viewport.post { viewport.frameScene() }
        handler.postDelayed(autosaveTick, 30_000)
    }

    // ------------------------------------------------------------------ ui construction

    /** Creates every panel once; [buildUi] decides where they live for the current orientation. */
    private fun buildPanels() {
        val refresh = { refreshPanels() }
        sceneTree = SceneTreePanel(this, doc, theme, state, refresh, refresh, { showInspector() })
        val assetPanel = AssetPanel(this, doc, theme, refresh, { name, kind -> assignAsset(name, kind) })
        assetPanel.onImportRequested = { pickAsset() }
        assetPanel.onOpen = { name -> openAssetInEditor(name) }
        assetPanel.onStatus = { text -> if (::statusBar.isInitialized) statusBar.text = text else toast(this, text) }
        assets = assetPanel
        animation = AnimationPanel(this, doc, theme, { engine }, refresh)
        tilemap = TileMapPanel(this, doc, theme, state, engine.resources, refresh)
        spriteEditor = SpriteEditorPanel(this, doc, theme, refresh)
        inspector = InspectorPanel(this, doc, theme, state, project, refresh)
        particles = ParticlePanel(this, doc, theme, refresh)
        shaders = ShaderPanel(this, doc, theme, refresh)
        audioPanel = AudioPanel(this, doc, theme, engine, refresh)
        scriptEditor = ScriptEditorPanel(this, doc, theme, engine, refresh)
        exportPanel = ExportPanel(this, doc, theme, refresh)
        inputPanel = InputMapPanel(this, doc, theme, { engine }, refresh)
    }

    private fun buildUi() {
        landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        dockTabs.clear()
        toolButtons.clear()
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.background)
        root.addView(buildTopBar())

        val before = System.currentTimeMillis()
        if (landscape) buildLandscape() else buildPortrait()
        android.util.Log.i("SEngine", "editor layout built in ${System.currentTimeMillis() - before} ms (landscape=$landscape)")

        statusBar = Ui.label(this, "Ready", theme, 11f, theme.textDim)
        statusBar.isSingleLine = true
        statusBar.ellipsize = android.text.TextUtils.TruncateAt.END
        statusBar.setBackgroundColor(theme.panelAlt)
        statusBar.setPadding(theme.pad(8f), theme.pad(3f), theme.pad(8f), theme.pad(3f))
        root.addView(statusBar)
        setContentView(root)

        viewport = ViewportPanel(this, doc, engine, theme, state)
        viewport.onStatus = { text -> statusBar.text = text }
        viewport.onSelectionChanged = { refreshPanels(); updateStatus() }
        viewport.onToolFinished = { text -> statusBar.text = text }
        viewport.onZoomChanged = { updateStatus() }
        viewport.onContextMenu = { sx, sy -> showViewportContextMenu(sx, sy) }
        centerColumn.addView(buildViewportToolbar(), 0)
        centerColumn.addView(viewport, 1,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        applyDockSizes()
        refreshToolButtons()
        updateStatus()
    }

    /** Landscape: three docks around the viewport (scene/files left, inspector right, console bottom). */
    private fun buildLandscape() {
        val main = LinearLayout(this)
        main.orientation = LinearLayout.HORIZONTAL
        main.setBackgroundColor(theme.background)

        leftDock = LinearLayout(this)
        leftDock.orientation = LinearLayout.VERTICAL
        leftDock.setBackgroundColor(theme.panel)
        centerColumn = LinearLayout(this)
        centerColumn.orientation = LinearLayout.VERTICAL
        rightDock = LinearLayout(this)
        rightDock.orientation = LinearLayout.VERTICAL
        rightDock.setBackgroundColor(theme.panel)

        main.addView(leftDock, LinearLayout.LayoutParams(theme.dp(leftWidth.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT))
        main.addView(Splitter(this, theme, true) { delta -> leftWidth = (leftWidth + delta).coerceIn(170, 620); applyDockSizes() })
        main.addView(centerColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        main.addView(Splitter(this, theme, true) { delta -> rightWidth = (rightWidth - delta).coerceIn(220, 760); applyDockSizes() })
        main.addView(rightDock, LinearLayout.LayoutParams(theme.dp(rightWidth.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        leftDock.addView(dockPanel(
            listOf("Scene" to Icons.SCENE, "Files" to Icons.FOLDER, "Sprite" to Icons.CROP, "Anim" to Icons.ANIMATION, "Tiles" to Icons.TILESET),
            listOf(sceneTree, assets, spriteEditor, animation, tilemap)
        ))
        rightDock.addView(dockPanel(
            listOf("Inspector" to Icons.SETTINGS, "Script" to Icons.SCRIPT, "Input Map" to Icons.SELECT,
                "Particles" to Icons.PARTICLES, "Shaders" to Icons.SHADER, "Audio" to Icons.SOUND, "Export" to Icons.PACKAGE),
            listOf(inspector, scriptEditor, inputPanel, particles, shaders, audioPanel, exportPanel)
        ))
        bottomDock = LinearLayout(this)
        bottomDock.orientation = LinearLayout.VERTICAL
        bottomDock.setBackgroundColor(theme.panel)
        val bottomSplit = Splitter(this, theme, false) { delta -> bottomHeight = (bottomHeight - delta).coerceIn(120, 700); applyDockSizes() }
        centerColumn.addView(bottomSplit)
        bottomDock.addView(dockPanel(
            listOf("Output" to Icons.CONSOLE, "Debugger" to Icons.DEBUG, "Profiler" to Icons.PROFILE),
            listOf(console)
        ))
        centerColumn.addView(bottomDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(bottomHeight.toFloat())))
    }

    /**
     * Portrait (or a narrow window): the viewport keeps the top half and every panel lives in one
     * scrollable tab strip underneath, so nothing is squeezed into an unreadable column.
     */
    private fun buildPortrait() {
        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setBackgroundColor(theme.background)
        leftDock = LinearLayout(this)
        leftDock.visibility = View.GONE
        rightDock = LinearLayout(this)
        rightDock.visibility = View.GONE
        centerColumn = LinearLayout(this)
        centerColumn.orientation = LinearLayout.VERTICAL
        main.addView(leftDock)
        main.addView(rightDock)
        main.addView(centerColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(main, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomDock = LinearLayout(this)
        bottomDock.orientation = LinearLayout.VERTICAL
        bottomDock.setBackgroundColor(theme.panel)
        val bottomSplit = Splitter(this, theme, false) { delta -> bottomHeight = (bottomHeight - delta).coerceIn(160, 900); applyDockSizes() }
        centerColumn.addView(bottomSplit)
        bottomDock.addView(dockPanel(
            listOf(
                "Scene" to Icons.SCENE, "Files" to Icons.FOLDER, "Inspector" to Icons.SETTINGS,
                "Sprite" to Icons.CROP, "Anim" to Icons.ANIMATION, "Tiles" to Icons.TILESET,
                "Particles" to Icons.PARTICLES, "Shaders" to Icons.SHADER, "Audio" to Icons.SOUND,
                "Script" to Icons.SCRIPT, "Input Map" to Icons.SELECT, "Export" to Icons.PACKAGE,
                "Output" to Icons.CONSOLE, "Debugger" to Icons.DEBUG, "Profiler" to Icons.PROFILE
            ),
            listOf(sceneTree, assets, inspector, spriteEditor, animation, tilemap, particles, shaders, audioPanel,
                scriptEditor, inputPanel, exportPanel, console)
        ))
        bottomDock.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(300f))
        centerColumn.addView(bottomDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Tabs + stack for a group of panels. The stack keeps every panel alive and just toggles visibility. */
    private fun dockPanel(names: List<Pair<String, Int>>, panels: List<View?>): View {
        val tabs = TabStrip(this, theme)
        val stack = FrameLayout(this)
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(theme.panel)
        container.addView(tabs)
        container.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val real = ArrayList<View>()
        for (p in panels) {
            if (p == null) continue
            detach(p)
            stack.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            p.visibility = View.GONE
            real.add(p)
        }
        stack.addView(Ui.label(this, "No panel here yet.", theme, 12f, theme.textDim).apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        tabs.setTabItems(names)
        tabs.onSelect = { index ->
            for (i in 0 until stack.childCount) stack.getChildAt(i).visibility = if (i == index) View.VISIBLE else View.GONE
            when (val panel = real.getOrNull(index.coerceIn(0, (real.size - 1).coerceAtLeast(0)))) {
                is SceneTreePanel -> panel.refresh()
                is AssetPanel -> panel.refresh()
                is InspectorPanel -> panel.refresh()
                is SpriteEditorPanel -> panel.refresh()
                is AnimationPanel -> panel.refresh()
                is TileMapPanel -> panel.refresh()
                is ParticlePanel -> panel.refresh()
                is ShaderPanel -> panel.refresh()
                is AudioPanel -> panel.refresh()
                is ScriptEditorPanel -> {}
                is ExportPanel -> panel.refresh()
                is InputMapPanel -> panel.refresh()
                is ConsolePanel -> panel.refresh()
            }
        }
        tabs.select(0)
        tabs.onSelect?.invoke(0)
        dockTabs.add(tabs)
        container.tag = stack
        if (dockStack == null) dockStack = stack
        return container
    }

    private fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(theme.panel)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.pad(6f), theme.pad(4f), theme.pad(6f), theme.pad(4f))

        titleBar = Ui.label(this, "S ENGINE", theme, 13f, theme.accent, bold = true)
        titleBar.isSingleLine = true
        titleBar.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        titleBar.setOnClickListener { showSceneMenu(titleBar) }
        row.addView(titleBar)

        fun menuButton(name: String, icon: Int, onClick: (View) -> Unit): View {
            lateinit var button: EditorButton
            button = EditorButton(this, theme, name, { onClick(button) }, iconKind = icon, compact = true)
            button.setPadding(theme.pad(6f), theme.pad(2f), theme.pad(6f), theme.pad(2f))
            return button
        }
        row.addView(menuButton("Scene", Icons.SCENE) { showSceneMenu(it) })
        row.addView(menuButton("Project", Icons.FOLDER) { showProjectMenu(it) })
        row.addView(menuButton("Editor", Icons.SETTINGS) { showEditorMenu(it) })
        row.addView(menuButton("Debug", Icons.DEBUG) { showDebugMenu(it) })
        val search = EditorButton(this, theme, "Search", { showCommandPalette() }, iconKind = Icons.SEARCH, compact = true)
        row.addView(search)
        toolbarItem(search, theme, "Command palette (Ctrl+Shift+P)")
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val save = IconButton(this, theme, Icons.SAVE, "Save scene", { saveScene() })
        toolbarItem(save, theme, "Save scene (Ctrl+S)")
        row.addView(save)
        val undo = IconButton(this, theme, Icons.UNDO, "Undo", { if (doc.undo.undo(doc)) refreshPanels() })
        toolbarItem(undo, theme, "Undo (Ctrl+Z)")
        row.addView(undo)
        val redo = IconButton(this, theme, Icons.REDO, "Redo", { if (doc.undo.redo(doc)) refreshPanels() })
        toolbarItem(redo, theme, "Redo (Ctrl+Shift+Z)")
        row.addView(redo)
        row.addView(View(this), LinearLayout.LayoutParams(theme.dp(6f), 1))

        runButton = EditorButton(this, theme, if (playing) "Stop" else "Run", {
            if (playing) stopPlay() else startPlay()
        }, iconKind = if (playing) Icons.STOP else Icons.PLAY, minWidthDp = 76f)
        toolbarItem(runButton, theme, "Run the project in play mode (F5) / stop (F6)")
        row.addView(runButton)
        bar.addView(row)

        bar.addView(buildToolStrip())
        return bar
    }

    private fun buildToolStrip(): View {
        val scroll = android.widget.HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.setBackgroundColor(theme.panelAlt)
        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.gravity = Gravity.CENTER_VERTICAL
        tools.setPadding(theme.pad(6f), theme.pad(4f), theme.pad(6f), theme.pad(4f))

        tools.addView(Ui.label(this, "", theme, 12f, theme.text).also { toolLabel = it })

        for (tool in ToolState.ALL) {
            val b = IconButton(this, theme, Icons.forTool(tool), tool.label, {
                state.tool = tool
                viewport.cancelTool()
                refreshToolButtons()
                updateStatus()
            })
            toolbarItem(b, theme, "${tool.label} tool (${tool.shortcut})")
            tools.addView(b)
            toolButtons.add(b to { state.tool == tool })
        }
        tools.addView(divider())

        val toggles: List<Triple<Int, String, () -> Unit>> = listOf(
            Triple(Icons.GRID, "Grid", { state.showGrid = !state.showGrid }),
            Triple(Icons.SNAP, "Snapping", { state.snap.enabled = !state.snap.enabled }),
            Triple(Icons.COLLIDER, "Colliders", { state.showColliders = !state.showColliders }),
            Triple(Icons.CAMERA, "Cameras", { state.showCameras = !state.showCameras }),
            Triple(Icons.GUIDE, "Guides", { state.showGuides = !state.showGuides }),
            Triple(Icons.TILESET, "Tile grid", { state.showTileGrid = !state.showTileGrid }),
            Triple(Icons.PIXEL_GRID, "Pixel grid", { state.showPixelGrid = !state.showPixelGrid }),
            Triple(Icons.PHYSICS, "Physics debug", { state.showPhysicsDebug = !state.showPhysicsDebug }),
            Triple(Icons.UI_EDIT, "UI bounds", { state.showUIBounds = !state.showUIBounds }),
            Triple(Icons.SOUND, "Audio areas", { state.showAudioAreas = !state.showAudioAreas })
        )
        for ((icon, name, action) in toggles) {
            val b = IconButton(this, theme, icon, "Toggle $name overlay", { action(); refreshToolButtons() })
            toolbarItem(b, theme, "Toggle $name overlay")
            tools.addView(b)
            toolButtons.add(b to { when (name) {
                "Grid" -> state.showGrid
                "Snapping" -> state.snap.enabled
                "Colliders" -> state.showColliders
                "Cameras" -> state.showCameras
                "Guides" -> state.showGuides
                "Tile grid" -> state.showTileGrid
                "Pixel grid" -> state.showPixelGrid
                "Physics debug" -> state.showPhysicsDebug
                "UI bounds" -> state.showUIBounds
                else -> state.showAudioAreas
            } })
        }
        tools.addView(divider())
        val focus = IconButton(this, theme, Icons.FOCUS, "Focus selection", { viewport.focusSelection() })
        toolbarItem(focus, theme, "Focus selection in the viewport (F)")
        tools.addView(focus)
        val frameAll = IconButton(this, theme, Icons.WORLD, "Frame whole scene", { viewport.frameScene() })
        toolbarItem(frameAll, theme, "Frame the whole scene")
        tools.addView(frameAll)

        scroll.addView(tools, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(theme.border)
        v.layoutParams = LinearLayout.LayoutParams(theme.pad(1f), theme.dp(22f)).apply {
            marginStart = theme.pad(6f)
            marginEnd = theme.pad(6f)
        }
        return v
    }

    private fun refreshToolButtons() {
        for ((button, isOn) in toolButtons) {
            button.toggled = isOn()
            button.refresh()
        }
        toolLabel.text = "  ${state.tool.label}"
        toolLabel.setTextColor(theme.accent)
        viewport.requestRender()
        updateStatus()
    }

    private fun updateStatus() {
        if (!::statusBar.isInitialized) return
        val selected = state.selectionIds.size
        val zoom = viewport.zoomPercent()
        statusBar.text = "%s · %s · zoom %.0f%% · x %.2f  y %.2f%s".format(
            doc.sceneName + if (doc.dirty) " *" else "",
            if (selected == 0) "no selection" else "$selected selected",
            zoom, state.mouseWorldX, state.mouseWorldY,
            if (state.snap.enabled) " · snap ${state.snap.step}" else ""
        )
        zoomLabel.text = "%.0f%%".format(zoom)
    }

    private fun buildViewportToolbar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundColor(theme.panel)
        row.setPadding(theme.pad(4f), theme.pad(3f), theme.pad(4f), theme.pad(3f))
        val name = Ui.label(this, doc.sceneName, theme, 12f, theme.textDim)
        name.isSingleLine = true
        row.addView(name)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val zoomOut = IconButton(this, theme, Icons.ZOOM_OUT, "Zoom out", { viewport.zoomAtCenter(0.8f) })
        toolbarItem(zoomOut, theme, "Zoom out")
        row.addView(zoomOut)
        zoomLabel = Ui.label(this, "100%", theme, 12f, theme.text)
        zoomLabel.gravity = Gravity.CENTER
        zoomLabel.setPadding(theme.pad(6f), 0, theme.pad(6f), 0)
        zoomLabel.isClickable = true
        zoomLabel.setOnClickListener { viewport.resetZoom() }
        toolbarItem(zoomLabel, theme, "Zoom (tap to reset to 100%)")
        row.addView(zoomLabel)
        val zoomIn = IconButton(this, theme, Icons.ZOOM_IN, "Zoom in", { viewport.zoomAtCenter(1.25f) })
        toolbarItem(zoomIn, theme, "Zoom in")
        row.addView(zoomIn)
        val fit = IconButton(this, theme, Icons.FOCUS, "Frame scene", { viewport.frameScene() })
        toolbarItem(fit, theme, "Frame the whole scene")
        row.addView(fit)

        val preview = EditorButton(this, theme, EditorState.PREVIEW_SIZES[state.resolutionPreview].first, {}, iconKind = Icons.SCALE, compact = true)
        preview.setOnClickListener {
            showMenu(preview, theme, EditorState.PREVIEW_SIZES.mapIndexed { index, size ->
                size.first to {
                    viewport.applyPreviewResolution(index)
                    preview.setText(size.first)
                    updateStatus()
                }
            })
        }
        toolbarItem(preview, theme, "Resolution preview")
        row.addView(preview)
        return row
    }

    private fun applyDockSizes() {
        if (landscape) {
            leftDock.layoutParams = LinearLayout.LayoutParams(theme.dp(leftWidth.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT)
            rightDock.layoutParams = LinearLayout.LayoutParams(theme.dp(rightWidth.toFloat()), ViewGroup.LayoutParams.MATCH_PARENT)
            bottomDock.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(bottomHeight.toFloat()))
        } else {
            bottomDock.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
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
            "Export APK / Android project…" to { selectDockTab("Export"); exportPanel?.refresh() },
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
            "Panels ▸ Sprite editor" to { selectDockTab("Sprite") },
            "Panels ▸ Animation timeline" to { selectDockTab("Anim") },
            "Panels ▸ Tilemap" to { selectDockTab("Tiles") },
            "Panels ▸ Script editor" to { selectDockTab("Script") },
            "Panels ▸ Particles" to { selectDockTab("Particles") },
            "Panels ▸ Audio" to { selectDockTab("Audio") },
            "Panels ▸ Export" to { selectDockTab("Export") },
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

    /** Writes the live snapshot (nodes, transforms, physics, profiler) to the clipboard. */
    private fun copyRemoteSnapshot() {
        val snapshot = com.sengine.engine.debug.RemoteInspector.snapshot(engine)
        val text = com.sengine.engine.json.Json.write(snapshot, true)
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("S Engine snapshot", text))
        toast(this, "Remote snapshot copied (${text.length} chars)")
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
            },
            "Copy remote snapshot" to { copyRemoteSnapshot() },
            "Reload scripts" to { engine.scripts.reload(); toast(this, "Scripts reloaded") },
            "Log scene tree" to {
                com.sengine.engine.debug.Log.info("Debugger", com.sengine.engine.debug.RemoteInspector.treeText(engine, 80))
                console?.refresh(1)
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

    /**
     * The manifest handles rotation/size changes in-place, so the docks are reassembled here instead
     * of letting Android recreate the activity: the document, undo history and GL state survive.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasLandscape = landscape
        val nowLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (wasLandscape == nowLandscape) return
        runCatching {
            viewport.dispose()
            buildUi()
            applyDockSizes()
            refreshToolButtons()
            updateStatus()
            viewport.post { viewport.requestRender() }
        }.onFailure { e ->
            com.sengine.engine.debug.Log.error("Editor", "Layout rebuild failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        runCatching { audio.release() }
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

    /** Brings the Inspector tab to the front (used by the scene tree's "open inspector" action). */
    private fun showInspector() {
        selectDockTab("Inspector")
    }

    /** Selects a panel tab by name across every dock strip. */
    private fun selectDockTab(name: String) {
        for (tabs in dockTabs) {
            val index = tabs.indexOf(name)
            if (index >= 0) {
                tabs.select(index)
                return
            }
        }
    }

    /** Opens an asset with the editor that owns its type. */
    private fun openAssetInEditor(name: String) {
        when (AssetKind.of(name)) {
            AssetKind.SCENE -> openScene(name)
            AssetKind.SCRIPT -> {
                selectDockTab("Script")
                scriptEditor?.load(name)
            }
            AssetKind.MATERIAL -> {
                selectDockTab("Shaders")
                shaders?.refresh()
            }
            AssetKind.TILESET -> {
                selectDockTab("Tiles")
                tilemap?.refresh()
            }
            else -> {
                selectDockTab("Sprite")
                spriteEditor?.refresh()
            }
        }
        statusBar.text = "Opened $name"
    }

    /** Long-press / right-click menu for the node under the cursor in the viewport. */
    private fun showViewportContextMenu(sx: Float, sy: Float) {
        val wx = state.view.screenToWorldX(sx)
        val wy = state.view.screenToWorldY(sy)
        val node = viewport.nodeAt(wx, wy)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (node != null) {
            items.add("Select ${node.name}" to { viewport.focusNode(node.id) })
            items.add("Rename '${node.name}'…" to {
                inputDialog(this, theme, "Rename node", node.name) { newName ->
                    doc.undo.push(doc, com.sengine.engine.editor.RenameNodeCommand("Rename node", node.id, newName))
                    refreshPanels()
                }
            })
            items.add("Duplicate" to { duplicateSelection() })
            items.add((if (node.visible) "Hide" else "Show") to {
                node.visible = !node.visible
                doc.onSceneMutated()
                refreshPanels()
            })
            items.add((if (node.locked) "Unlock" else "Lock") to {
                node.locked = !node.locked
                refreshPanels()
            })
            items.add("Focus" to { viewport.focusNode(node.id) })
            items.add("Delete" to { deleteSelection() })
            items.add("-" to {})
        }
        items.add("Frame scene" to { viewport.frameScene() })
        items.add("Reset zoom" to { viewport.resetZoom() })
        items.add("Add node here…" to {
            val created = doc.scene.createNode(com.sengine.engine.core.NodeType.SPRITE, null)
            created.setPosition(wx, wy)
            doc.onStructureChanged()
            viewport.focusNode(created.id)
            refreshPanels()
        })
        showMenu(viewport, theme, items)
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
        "Export APK / project" to { selectDockTab("Export"); exportPanel?.refresh() },
        "Export Project (.zip)…" to { exportProject() },
        "Open Sprite Editor" to { selectDockTab("Sprite"); spriteEditor?.refresh() },
        "Open Animation Timeline" to { selectDockTab("Anim"); animation?.refresh() },
        "Open Tilemap Editor" to { selectDockTab("Tiles"); tilemap?.refresh() },
        "Open Script Editor" to { selectDockTab("Script") },
        "Open Particle Editor" to { selectDockTab("Particles"); particles?.refresh() },
        "Open Audio Panel" to { selectDockTab("Audio"); audioPanel?.refresh() },
        "Open Input Map" to { selectDockTab("Input Map"); inputPanel?.refresh() },
        "Reset Zoom to 100%" to { viewport.setZoomPercent(100f); updateStatus() },
        "Frame Scene" to { viewport.frameScene() },
        "Tile brush: paint" to { state.tool = ToolState.TILE; state.tileBrush.mode = com.sengine.engine.tilemap.TileBrush.Mode.PAINT; refreshToolButtons() },
        "Tile brush: erase" to { state.tool = ToolState.TILE; state.tileBrush.mode = com.sengine.engine.tilemap.TileBrush.Mode.ERASE; refreshToolButtons() },
        "Tile brush: fill" to { state.tool = ToolState.TILE; state.tileBrush.mode = com.sengine.engine.tilemap.TileBrush.Mode.FILL; refreshToolButtons() },
        "Save Workspace Layout" to { AppState.saveEditorLayout("$leftWidth|$rightWidth|$bottomHeight") },
        "Back to Project Manager" to { startActivity(Intent(this, ProjectManagerActivity::class.java)); finish() }
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // "press any key to rebind" in the Input Map panel takes priority while it is capturing
        if (inputPanel?.onKeyCaptured(keyCode) == true) return true
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
            KeyEvent.KEYCODE_T -> { state.tool = ToolState.SCALE; viewport.cancelTool(); refreshToolButtons(); return true }
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> { deleteSelection(); return true }
            KeyEvent.KEYCODE_E -> { state.tool = ToolState.ROTATE; viewport.cancelTool(); refreshToolButtons(); return true }
            KeyEvent.KEYCODE_W -> { state.tool = ToolState.MOVE; viewport.cancelTool(); refreshToolButtons(); return true }
            KeyEvent.KEYCODE_Q -> { state.tool = ToolState.SELECT; viewport.cancelTool(); refreshToolButtons(); return true }
            KeyEvent.KEYCODE_Y -> { state.tool = ToolState.PIVOT; viewport.cancelTool(); refreshToolButtons(); return true }
            KeyEvent.KEYCODE_5 -> { state.tool = ToolState.TILE; viewport.cancelTool(); refreshToolButtons(); return true }
            KeyEvent.KEYCODE_6 -> { state.tool = ToolState.RECT; viewport.cancelTool(); refreshToolButtons(); return true }
            KeyEvent.KEYCODE_7 -> { state.tool = ToolState.UI; viewport.cancelTool(); refreshToolButtons(); return true }
            KeyEvent.KEYCODE_G -> {
                if (ctrl) { state.showGrid = !state.showGrid; refreshToolButtons(); return true }
            }
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> { viewport.zoomAtCenter(1.25f); updateStatus(); return true }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { viewport.zoomAtCenter(0.8f); updateStatus(); return true }
            KeyEvent.KEYCODE_0 -> { viewport.resetZoom(); updateStatus(); return true }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (playing) { stopPlay() } else { viewport.cancelTool() }
                return true
            }
        }
        if (!ctrl && !event.isAltPressed) {
            // tools declare their own shortcut, so adding a tool automatically gives it a key
            val label = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            for (tool in ToolState.ALL) {
                if (tool.shortcut.equals(label, ignoreCase = true)) {
                    state.tool = tool
                    viewport.cancelTool()
                    refreshToolButtons()
                    return true
                }
            }
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
