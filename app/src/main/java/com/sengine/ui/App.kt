package com.sengine.ui

import android.content.Context
import android.content.SharedPreferences
import com.sengine.engine.project.Project
import com.sengine.engine.project.ProjectManager
import java.io.File

/**
 * Application-level state shared by every Activity: where projects live, the last opened project,
 * editor preferences (theme, locale, UI scale) and the recently-used list.
 *
 * Preferences are stored in [SharedPreferences] so the editor reopens exactly as it was left, and
 * the project root lives in app storage — no special permissions, works on every Android version.
 */
object AppState {

    const val ENGINE_NAME = "S ENGINE"
    const val ENGINE_TAGLINE = "2D GAME ENGINE"
    const val ENGINE_VERSION = "1.0"
    const val PROJECT_FORMAT = 2

    private const val PREFS = "sengine_editor"

    private lateinit var prefs: SharedPreferences
    private lateinit var projectsRoot: File

    @Volatile var theme = Theme(dark = true)

    /** Currently open project (null while in the project manager). */
    @Volatile var currentProject: Project? = null

    /** Scene the editor should open for [currentProject]. */
    @Volatile var pendingScene: String? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        projectsRoot = File(base, "S-Engine/projects").apply { mkdirs() }
        Loc.language = prefs.getString("locale", "en") ?: "en"
        theme.highContrast = prefs.getBoolean("highContrast", false)
        theme.scale = prefs.getFloat("uiScale", 1f).coerceIn(0.75f, 2f)
        if (prefs.getString("theme", "dark") == "light") theme.applyLight() else theme.applyDark()
    }

    fun manager(): ProjectManager = ProjectManager(projectsRoot)

    fun projectsDir(): File = projectsRoot

    // ------------------------------------------------------------------ preferences

    fun saveTheme(t: Theme) {
        theme = t
        prefs.edit()
            .putString("theme", if (t.dark) "dark" else "light")
            .putFloat("uiScale", t.scale)
            .putBoolean("highContrast", t.highContrast)
            .apply()
    }

    fun saveLocale(code: String) {
        Loc.language = code
        prefs.edit().putString("locale", code).apply()
    }

    fun lastProjectName(): String = prefs.getString("lastProject", "") ?: ""

    fun rememberProject(project: Project?) {
        prefs.edit().putString("lastProject", project?.dir?.name ?: "").apply()
    }

    fun openSceneName(): String = prefs.getString("openScene", "Main") ?: "Main"

    fun rememberScene(name: String) {
        prefs.edit().putString("openScene", name).apply()
    }

    fun editorLayout(): String = prefs.getString("layout", "") ?: ""

    fun saveEditorLayout(json: String) {
        prefs.edit().putString("layout", json).apply()
    }

    /** Recently opened scene files, per project — the start screen and File menu use these. */
    fun recentScenes(): List<String> = (prefs.getString("recentScenes", "") ?: "")
        .split('\n').filter { it.isNotBlank() }.take(10)

    fun rememberSceneFile(projectName: String, sceneName: String) {
        val entry = "$projectName/$sceneName"
        val list = ArrayList(recentScenes())
        list.remove(entry)
        list.add(0, entry)
        prefs.edit().putString("recentScenes", list.take(10).joinToString("\n")).apply()
    }
}
