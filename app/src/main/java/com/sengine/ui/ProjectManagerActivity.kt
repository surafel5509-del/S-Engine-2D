package com.sengine.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sengine.engine.project.Project
import com.sengine.engine.project.ProjectSettings
import com.sengine.engine.project.Template
import com.sengine.engine.project.Templates
import java.io.File

/**
 * Start screen / project manager.
 *
 * Everything here performs the real operation on disk: create (with template, window size,
 * orientation, theme and pixel-perfect settings), import from a `.zip`, open, duplicate, rename,
 * delete, export, plus Documentation and Examples. Recents are ordered by the timestamp written when
 * a project is opened.
 */
class ProjectManagerActivity : Activity() {

    private lateinit var theme: Theme
    private lateinit var root: LinearLayout
    private lateinit var recentBox: LinearLayout
    private var selectedTemplate: Template = Templates.all.first()
    private var createSettings = ProjectSettings()

    private val importRequest = 4201
    private val exportRequest = 4202

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        theme = AppState.theme
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
    }

    private fun rebuild() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.background)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        buildHeader()
        buildActions()
        buildRecentSection()
        buildFooter()
    }

    // ------------------------------------------------------------------ header

    private fun buildHeader() {
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(theme.pad(20f), theme.pad(22f), theme.pad(20f), theme.pad(10f))
        val mark = TextView(this)
        mark.text = "S"
        mark.setTextColor(theme.accent)
        mark.textSize = theme.textSize(44f)
        mark.setTypeface(mark.typeface, Typeface.BOLD)
        header.addView(mark)
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(theme.pad(14f), 0, 0, 0)
        val title = Ui.label(this, AppState.ENGINE_NAME, theme, 26f, theme.text, bold = true)
        val sub = Ui.label(this, "${AppState.ENGINE_TAGLINE}  ·  v${AppState.ENGINE_VERSION}", theme, 12f, theme.textDim)
        titles.addView(title)
        titles.addView(sub)
        header.addView(titles)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val settings = EditorButton(this, theme, "Settings", { openSettings() }, icon = "⚙")
        val lang = EditorButton(this, theme, Loc.t("language", "Language"), { chooseLanguage() }, icon = "🌐")
        val themeToggle = EditorButton(this, theme, if (theme.dark) "Light" else "Dark", {
            if (theme.dark) theme.applyLight() else theme.applyDark()
            AppState.saveTheme(theme)
            rebuild()
        }, icon = "◐")
        toolbarItem(settings, theme, "Editor settings: theme, UI scale, contrast, autosave")
        toolbarItem(lang, theme, "Interface language (English / አማርኛ / العربية)")
        toolbarItem(themeToggle, theme, "Toggle dark / light theme")
        header.addView(settings)
        header.addView(lang)
        header.addView(themeToggle)
        root.addView(header)
    }

    private fun buildActions() {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(theme.pad(20f), theme.pad(6f), theme.pad(20f), theme.pad(6f))
        val create = EditorButton(this, theme, Loc.t("create_project", "CREATE PROJECT"), { showCreateDialog() }, icon = "＋")
        val import = EditorButton(this, theme, Loc.t("import_project", "IMPORT PROJECT"), { pickImport() }, icon = "⇩")
        val examples = EditorButton(this, theme, Loc.t("examples", "Examples"), { showExamples() }, icon = "★")
        val docs = EditorButton(this, theme, Loc.t("documentation", "Documentation"), { showDocumentation() }, icon = "?")
        val openScene = EditorButton(this, theme, Loc.t("open_scene", "Open Scene"), { openLastScene() }, icon = "▶")
        for (b in listOf(create, import, examples, docs, openScene)) {
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = theme.pad(6f) }
            row.addView(b)
        }
        root.addView(row)
    }

    private fun buildRecentSection() {
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(theme.pad(20f), theme.pad(18f), theme.pad(20f), theme.pad(6f))
        head.addView(Ui.label(this, Loc.t("recent_projects", "RECENT PROJECTS"), theme, 14f, theme.text, bold = true))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(Ui.label(this, "long-press a project for more actions", theme, 11f, theme.textDim))
        root.addView(head)
        recentBox = LinearLayout(this)
        recentBox.orientation = LinearLayout.VERTICAL
        recentBox.setPadding(theme.pad(20f), 0, theme.pad(20f), theme.pad(10f))
        root.addView(recentBox)
    }

    private fun buildFooter() {
        val footer = LinearLayout(this)
        footer.orientation = LinearLayout.VERTICAL
        footer.setPadding(theme.pad(20f), theme.pad(18f), theme.pad(20f), theme.pad(24f))
        footer.addView(Ui.label(this, "Projects folder: ${AppState.projectsDir().absolutePath}", theme, 11f, theme.textDim))
        footer.addView(Ui.label(this, "S Engine — original 2D game engine and editor. No Godot/Unity code or branding.", theme, 11f, theme.textDim))
        root.addView(footer)
    }

    // ------------------------------------------------------------------ recents

    private fun refreshRecents() {
        recentBox.removeAllViews()
        val recents = AppState.manager().recent()
        if (recents.isEmpty()) {
            val empty = Ui.label(this, "No projects yet — create one or import a .zip to get started.", theme, 12f, theme.textDim)
            empty.setPadding(theme.pad(6f), theme.pad(18f), theme.pad(6f), theme.pad(18f))
            recentBox.addView(empty)
            return
        }
        for (r in recents) {
            val project = AppState.manager().open(r.name)
            recentBox.addView(projectCard(project, r.opened))
        }
    }

    private fun projectCard(project: Project, opened: Long): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(theme.pad(10f), theme.pad(10f), theme.pad(10f), theme.pad(10f))
        card.background = theme.rounded(theme.panel, 6f, theme.border)
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.bottomMargin = theme.pad(8f) }

        val thumb = ImageView(this)
        thumb.layoutParams = LinearLayout.LayoutParams(theme.dp(96f), theme.dp(54f))
        thumb.setBackgroundColor(theme.panelAlt)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        val thumbFile = File(project.dir, "thumbnail.png")
        if (thumbFile.exists()) {
            BitmapFactory.decodeFile(thumbFile.absolutePath)?.let { thumb.setImageBitmap(it) }
        }
        card.addView(thumb)

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(theme.pad(12f), 0, 0, 0)
        info.addView(Ui.label(this, project.name, theme, 15f, theme.text, bold = true))
        val sceneCount = project.listScenes().size
        val assetCount = project.listAssetsRecursive().size
        info.addView(Ui.label(this, "$sceneCount scene(s) · $assetCount asset(s) · start: ${project.settings.startScene}", theme, 11f, theme.textDim))
        info.addView(Ui.label(this, "${project.settings.windowWidth}×${project.settings.windowHeight} · ${if (project.settings.pixelPerfect) "Pixel perfect" else "Smooth"} · ${project.settings.theme}", theme, 11f, theme.textDim))
        if (opened > 0) info.addView(Ui.label(this, "opened ${timeAgo(opened)}", theme, 10f, theme.textDim))
        card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val open = EditorButton(this, theme, "Open", { openProject(project) }, icon = "▶")
        card.addView(open)
        card.isClickable = true
        card.setOnClickListener { openProject(project) }
        card.setOnLongClickListener {
            showMenu(card, theme, listOf(
                "Open" to { openProject(project) },
                "Open Scene…" to { showScenePicker(project) },
                "Duplicate" to {
                    val copy = AppState.manager().duplicate(project)
                    toast(this, if (copy != null) "Duplicated as ${copy.name}" else "Duplicate failed")
                    refreshRecents()
                },
                "Rename" to {
                    inputDialog(this, theme, "Rename project", project.name) { newName ->
                        val renamed = AppState.manager().rename(project, newName)
                        toast(this, if (renamed != null) "Renamed" else "Rename failed")
                        refreshRecents()
                    }
                },
                "Export .zip…" to { exportProject(project) },
                "Delete" to {
                    confirmDialog(this, "Delete project", "Delete '${project.name}' and all its files? This cannot be undone.") {
                        AppState.manager().delete(project)
                        refreshRecents()
                    }
                },
                "-" to {},
                "Project folder" to { toast(this, project.dir.absolutePath) }
            ))
            true
        }
        return card
    }

    private fun timeAgo(millis: Long): String {
        val diff = (System.currentTimeMillis() - millis) / 1000
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60} min ago"
            diff < 86400 -> "${diff / 3600} h ago"
            else -> "${diff / 86400} d ago"
        }
    }

    // ------------------------------------------------------------------ actions

    private fun openProject(project: Project) {
        AppState.currentProject = project
        AppState.rememberProject(project)
        AppState.manager().markRecent(project.name)
        val scene = AppState.pendingScene ?: project.settings.startScene
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra("project", project.dir.absolutePath)
        intent.putExtra("scene", scene)
        AppState.pendingScene = null
        startActivity(intent)
    }

    private fun showScenePicker(project: Project) {
        val scenes = project.listScenes().ifEmpty { listOf(project.settings.startScene) }
        AlertDialog.Builder(this)
            .setTitle("Open scene")
            .setItems(scenes.toTypedArray()) { _, which ->
                AppState.pendingScene = scenes[which]
                openProject(project)
            }
            .show()
    }

    private fun openLastScene() {
        val last = AppState.lastProjectName()
        if (last.isEmpty() || !AppState.manager().exists(last)) {
            toast(this, "No recent project")
            return
        }
        openProject(AppState.manager().open(last))
    }

    private fun showCreateDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(theme.pad(16f), theme.pad(8f), theme.pad(16f), 0)
        box.setBackgroundColor(theme.panel)

        val nameField = Ui.edit(this, "My Game", theme, "Project name")
        box.addView(Ui.label(this, "Name", theme, 12f, theme.textDim))
        box.addView(nameField)

        box.addView(Ui.label(this, "Location", theme, 12f, theme.textDim))
        box.addView(Ui.label(this, AppState.projectsDir().absolutePath, theme, 11f, theme.textDim))

        box.addView(Ui.label(this, "Template", theme, 12f, theme.textDim))
        val templateNames = Templates.all.map { "${it.name} — ${it.description}" }
        var templateIndex = 0
        val templateButton = EditorButton(this, theme, Templates.all[0].name, {}, icon = "▤")
        templateButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Template")
                .setItems(templateNames.toTypedArray()) { _, which ->
                    templateIndex = which
                    templateButton.setText(Templates.all[which].name)
                }
                .show()
        }
        box.addView(templateButton)

        box.addView(Ui.label(this, "Window size", theme, 12f, theme.textDim))
        val sizes = listOf("640 × 360" to intArrayOf(640, 360), "1280 × 720" to intArrayOf(1280, 720), "1920 × 1080" to intArrayOf(1920, 1080), "2560 × 1440" to intArrayOf(2560, 1440), "720 × 1280 (portrait)" to intArrayOf(720, 1280))
        var sizeIndex = 1
        var orientation = 0
        val sizeButton = EditorButton(this, theme, sizes[1].first, {}, icon = "▭")
        sizeButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Window size")
                .setItems(sizes.map { it.first }.toTypedArray()) { _, which ->
                    sizeIndex = which
                    sizeButton.setText(sizes[which].first)
                }
                .show()
        }
        box.addView(sizeButton)

        val orientationRow = LinearLayout(this)
        orientationRow.orientation = LinearLayout.HORIZONTAL
        orientationRow.gravity = Gravity.CENTER_VERTICAL
        val orientLabels = listOf("Landscape", "Portrait", "Auto")
        for ((i, label) in orientLabels.withIndex()) {
            val b = EditorButton(this, theme, label, {}, toggled = i == 0)
            b.setOnClickListener {
                orientation = i
                for (c in 0 until orientationRow.childCount) (orientationRow.getChildAt(c) as EditorButton).toggled = false
                b.toggled = true
                for (c in 0 until orientationRow.childCount) (orientationRow.getChildAt(c) as EditorButton).refresh()
            }
            orientationRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(Ui.label(this, "Orientation", theme, 12f, theme.textDim))
        box.addView(orientationRow)

        val themeRow = LinearLayout(this)
        themeRow.orientation = LinearLayout.HORIZONTAL
        var projectTheme = "Dark"
        for (label in listOf("Dark", "Light")) {
            val b = EditorButton(this, theme, label, {}, toggled = label == "Dark")
            b.setOnClickListener {
                projectTheme = label
                for (c in 0 until themeRow.childCount) (themeRow.getChildAt(c) as EditorButton).toggled = false
                b.toggled = true
                for (c in 0 until themeRow.childCount) (themeRow.getChildAt(c) as EditorButton).refresh()
            }
            themeRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(Ui.label(this, "Editor theme", theme, 12f, theme.textDim))
        box.addView(themeRow)

        val pixelPerfect = CheckBox(this)
        pixelPerfect.text = "Pixel perfect (nearest filtering, integer scaling)"
        pixelPerfect.setTextColor(theme.text)
        pixelPerfect.isChecked = false
        box.addView(pixelPerfect)

        AlertDialog.Builder(this)
            .setTitle(Loc.t("create_project", "Create project"))
            .setView(ScrollView(this).also { it.addView(box) })
            .setPositiveButton("Create") { _, _ ->
                val template = Templates.all[templateIndex]
                val project = AppState.manager().create(nameField.text.toString(), template)
                project.settings.windowWidth = sizes[sizeIndex].second[0]
                project.settings.windowHeight = sizes[sizeIndex].second[1]
                project.settings.orientation = if (orientation == 2) autoOrientation(sizes[sizeIndex].second) else orientation
                project.settings.theme = projectTheme
                project.settings.pixelPerfect = pixelPerfect.isChecked
                if (pixelPerfect.isChecked) {
                    project.settings.pixelScale = 1
                }
                val scenes = project.listScenes()
                if (scenes.isNotEmpty()) project.settings.startScene = scenes.first()
                project.saveMeta()
                AppState.pendingScene = project.settings.startScene
                openProject(project)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun autoOrientation(size: IntArray) = if (size[1] > size[0]) 1 else 0

    private fun pickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, importRequest)
    }

    private fun exportProject(project: Project) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "${project.name}.sengine.zip")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, exportRequest)
        pendingExport = project
    }

    private var pendingExport: Project? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            importRequest -> {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val project = AppState.manager().importZip(input, "Imported")
                        toast(this, "Imported '${project.name}'")
                        refreshRecents()
                        AppState.pendingScene = project.settings.startScene
                        openProject(project)
                    } ?: toast(this, "Could not read the selected file")
                } catch (e: Exception) {
                    AlertDialog.Builder(this).setTitle("Import failed").setMessage(e.message ?: "Unknown error").setPositiveButton("OK", null).show()
                }
            }
            exportRequest -> {
                val project = pendingExport
                pendingExport = null
                if (project == null) return
                try {
                    contentResolver.openOutputStream(uri)?.use { project.exportZip(it) }
                    toast(this, "Exported ${project.name}")
                } catch (e: Exception) {
                    toast(this, "Export failed: ${e.message}")
                }
            }
        }
    }

    private fun showExamples() {
        val names = Templates.all.map { it.name }.toTypedArray()
        val descriptions = Templates.all.map { it.description }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(Loc.t("examples", "Examples"))
            .setItems(names) { _, which ->
                val template = Templates.all[which]
                AlertDialog.Builder(this)
                    .setTitle(template.name)
                    .setMessage(descriptions[which] + "\n\nCreate a project from this template?")
                    .setPositiveButton("Create") { _, _ ->
                        val project = AppState.manager().create(template.name, template)
                        AppState.pendingScene = project.settings.startScene
                        openProject(project)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun showDocumentation() {
        val docs = listOf(
            "Getting Started" to "Create a project, open a scene, add nodes in the Scene panel, press ▶ to test.",
            "Panels" to "Scene tree, 2D viewport, Inspector, FileSystem, Animation, Output, Debugger and Profiler. Panels are dockable (long-press a tab), resizable (drag the dividers) and their layout is saved per project.",
            "Viewport" to "One-finger drag pans, pinch zooms, two-finger drag pans. Tools: Select, Move, Rotate, Scale, Pivot, Rectangle, Tile, UI. Toggle grid/snap/colliders in the toolbar.",
            "Scripting" to "Attach a Script node and write functions: start(), update(dt), physics_update(dt), onCollision(other), onTrigger(other), onTap(), onSignal(name, data). Use the exported properties table to edit them in the Inspector.",
            "Physics" to "Rigidbody2D (Static/Kinematic/Rigid/Character) + Collider2D (rectangle, circle, polygon). Use the layer/mask grid to control what collides with what.",
            "Rendering" to "Sprite2D with atlas regions, flip, tint, materials, blend modes, pixel snapping; Camera2D with zoom, limits, smoothing and pixel-perfect mode.",
            "Tilemap" to "Create a TileSet from a sprite sheet (grid slicing), add TileMap2D nodes, then paint with the tile tools.",
            "Localization" to "The editor ships English, Amharic and Arabic; switch in Settings. Text rendering handles non-Latin scripts through string textures."
        )
        val titles = docs.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(Loc.t("documentation", "Documentation"))
            .setItems(titles) { _, which ->
                AlertDialog.Builder(this).setTitle(docs[which].first).setMessage(docs[which].second).setPositiveButton("Close", null).show()
            }
            .show()
    }

    private fun chooseLanguage() {
        val codes = Loc.available.map { it.first }.toTypedArray()
        val names = Loc.available.map { it.second }.toTypedArray()
        val current = codes.indexOf(Loc.language).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Language")
            .setSingleChoiceItems(names, current) { dialog, which ->
                AppState.saveLocale(codes[which])
                dialog.dismiss()
                rebuild()
                refreshRecents()
            }
            .show()
    }

    private fun openSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(theme.pad(16f), theme.pad(8f), theme.pad(16f), 0)

        box.addView(Ui.label(this, "UI scaling — ${(theme.scale * 100).toInt()}%", theme, 12f, theme.textDim))
        val scale = android.widget.SeekBar(this)
        scale.max = 125
        scale.progress = ((theme.scale - 0.75f) * 100).toInt().coerceIn(0, 125)
        scale.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                theme.scale = (0.75f + progress / 100f).coerceIn(0.75f, 2f)
                AppState.saveTheme(theme)
            }
            override fun onStartTrackingTouch(seek: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seek: android.widget.SeekBar?) {}
        })
        box.addView(scale)

        val contrast = CheckBox(this)
        contrast.text = "High contrast"
        contrast.setTextColor(theme.text)
        contrast.isChecked = theme.highContrast
        box.addView(contrast)

        val tooltips = CheckBox(this)
        tooltips.text = "Tooltips"
        tooltips.setTextColor(theme.text)
        tooltips.isChecked = true
        box.addView(tooltips)

        AlertDialog.Builder(this)
            .setTitle(Loc.t("settings", "Settings"))
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                theme.highContrast = contrast.isChecked
                if (theme.highContrast) { if (theme.dark) theme.applyDark() else theme.applyLight() }
                AppState.saveTheme(theme)
                toast(this, "UI scale ${(theme.scale * 100).toInt()}%, high contrast ${if (theme.highContrast) "on" else "off"}")
                rebuild()
                refreshRecents()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
