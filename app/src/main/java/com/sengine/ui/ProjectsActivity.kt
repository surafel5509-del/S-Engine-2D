package com.sengine.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.core.AssetKind
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import com.sengine.project.Templates
import java.text.DateFormat
import java.util.Date

class ProjectsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var exporting: Project? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val p = exporting ?: return@registerForActivityResult
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { ProjectManager.exportZip(p, it) }
            toast("Exported ${p.name}")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
        exporting = null
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importProject(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox().apply { setBackgroundColor(C.BG) }

        val header = hbox().apply {
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(C.HEADER)
        }
        val titleBox = vbox()
        titleBox.addView(label("S Engine", 28f, C.TEXT, true))
        titleBox.addView(label("2D game engine & editor for Android  •  v1.0", 13f, C.DIM))
        header.addView(titleBox, lp(0, WRAP, 1f))
        header.addView(button("Import") { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            lp(WRAP, WRAP).margins(0, 0, dp(8), 0))
        header.addView(button("+ New Project", C.ACCENT, 0xFFFFFFFF.toInt()) { newProjectDialog() })
        root.addView(header, lp(MATCH, WRAP))

        root.addView(label("PROJECTS", 12f, C.DIM, true).apply { setPadding(dp(20), dp(14), dp(20), dp(6)) })
        list = vbox().apply { setPadding(dp(14), 0, dp(14), dp(20)) }
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        setContentView(root)

        val prefs = getSharedPreferences("sengine", MODE_PRIVATE)
        if (!prefs.getBoolean("seeded", false)) {
            prefs.edit().putBoolean("seeded", true).apply()
            if (ProjectManager.list(this).isEmpty()) {
                ProjectManager.create(this, "Platformer Demo", Templates.all[1])
                ProjectManager.create(this, "Space Shooter", Templates.all[2])
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val projects = ProjectManager.list(this)
        if (projects.isEmpty()) {
            list.addView(label("No projects yet.\nTap “+ New Project” to create your first game.", 15f, C.DIM).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(60), 0, 0)
            }, lp(MATCH, WRAP))
        }
        for (p in projects) list.addView(card(p), lp(MATCH, WRAP).margins(0, dp(5), 0, dp(5)))
    }

    private fun card(p: Project): LinearLayout {
        val card = hbox().apply {
            background = round(C.PANEL, dp(10).toFloat())
            setPadding(dp(16), dp(12), dp(10), dp(12))
        }
        val icon = label(p.name.take(1).uppercase(), 22f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = round(colorFor(p.name), dp(10).toFloat())
        }
        card.addView(icon, lp(dp(48), dp(48)).margins(0, 0, dp(14), 0))
        val info = vbox()
        info.addView(label(p.name, 17f, C.TEXT, true))
        val scenes = p.listScenes().size
        val scripts = p.listAssets(AssetKind.SCRIPT).size
        val assets = p.listAssets().size
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.dir.lastModified()))
        info.addView(label("$scenes scene(s) • $scripts script(s) • $assets asset(s)\nModified $date", 12f, C.DIM))
        card.addView(info, lp(0, WRAP, 1f))
        card.addView(button("▶") { play(p) }, lp(WRAP, WRAP).margins(dp(4), 0, dp(4), 0))
        card.addView(button("Open", C.ACCENT, 0xFFFFFFFF.toInt()) { open(p) }, lp(WRAP, WRAP).margins(dp(4), 0, dp(4), 0))
        val more = button("⋮") { v ->
            val pm = PopupMenu(this, v)
            pm.menu.add("Rename"); pm.menu.add("Duplicate"); pm.menu.add("Export .zip"); pm.menu.add("Delete")
            pm.setOnMenuItemClickListener {
                when (it.title) {
                    "Rename" -> renameDialog(p)
                    "Duplicate" -> { ProjectManager.duplicate(this, p); refresh() }
                    "Export .zip" -> { exporting = p; exportLauncher.launch("${p.name}.zip") }
                    "Delete" -> confirmDelete(p)
                }
                true
            }
            pm.show()
        }
        card.addView(more)
        card.setOnClickListener { open(p) }
        return card
    }

    private fun colorFor(s: String): Int {
        val palette = intArrayOf(0xFF4C8DFF.toInt(), 0xFFE5534B.toInt(), 0xFF57AB5A.toInt(), 0xFFE0B341.toInt(), 0xFFAB47BC.toInt(), 0xFF26A69A.toInt())
        return palette[Math.abs(s.hashCode()) % palette.size]
    }

    private fun open(p: Project) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("project", p.name))
    }

    private fun play(p: Project) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("project", p.name))
    }

    private fun newProjectDialog() {
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val name = field("My Game")
        box.addView(label("Project name", 12f, C.DIM))
        box.addView(name, lp(MATCH, WRAP).margins(0, dp(4), 0, dp(12)))
        box.addView(label("Template", 12f, C.DIM))
        val group = RadioGroup(this)
        Templates.all.forEachIndexed { i, t ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = "${t.name}\n${t.description}"
                setTextColor(C.TEXT)
                textSize = 13f
                setPadding(dp(4), dp(6), 0, dp(6))
            })
        }
        group.check(1000)
        box.addView(group)
        MaterialAlertDialogBuilder(this)
            .setTitle("New Project")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Create") { _, _ ->
                val n = ProjectManager.sanitize(name.text.toString())
                when {
                    n.isBlank() -> toast("Invalid name")
                    ProjectManager.exists(this, n) -> toast("A project named \"$n\" already exists")
                    else -> {
                        val p = ProjectManager.create(this, n, Templates.all[group.checkedRadioButtonId - 1000])
                        open(p)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameDialog(p: Project) {
        val f: EditText = field(p.name)
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename project")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f, lp(MATCH, WRAP)) })
            .setPositiveButton("Rename") { _, _ ->
                val n = ProjectManager.sanitize(f.text.toString())
                if (n.isBlank() || ProjectManager.rename(this, p, n) == null) toast("Could not rename")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(p: Project) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${p.name}?")
            .setMessage("This permanently deletes all scenes, scripts and assets of this project.")
            .setPositiveButton("Delete") { _, _ -> ProjectManager.delete(p); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importProject(uri: Uri) {
        try {
            var display = "Imported"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) display = it.getString(0).substringBeforeLast('.')
            }
            val p = contentResolver.openInputStream(uri)!!.use { ProjectManager.importZip(this, it, display) }
            toast("Imported ${p.name}")
            refresh()
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
