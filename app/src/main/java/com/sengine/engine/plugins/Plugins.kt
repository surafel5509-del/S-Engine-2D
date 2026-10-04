package com.sengine.engine.plugins

import com.sengine.engine.core.Component
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.editor.EditorCommand
import com.sengine.engine.json.JVal
import com.sengine.engine.json.Json
import com.sengine.engine.project.Project

/**
 * Plugin API.
 *
 * A plugin is a plain Kotlin class registered in `project.json` (`plugins: ["com.example.MyPlugin"]`)
 * or shipped with the app. It receives a [PluginContext] on load and can extend the engine with
 * custom nodes, inspector widgets, editor panels, importers, commands and project settings — the
 * same extension points the built-in systems use.
 */
interface SEnginePlugin {
    val id: String
    val name: String
    val version: String
    val description: String get() = ""

    fun onLoad(ctx: PluginContext)
    fun onUnload() {}
}

/** Extension point descriptors. Everything an editor panel needs to be created by the shell. */
class PanelSpec(
    val id: String,
    val title: String,
    val defaultDock: String = "bottom",
    val icon: String = "",
    val factory: () -> Any
)

class ImporterSpec(
    val id: String,
    val extensions: List<String>,
    val description: String,
    val import: (Project, String, ByteArray) -> String?
)

class InspectorWidgetSpec(
    val propType: String,
    val priority: Int = 0,
    val create: (Any) -> Any
)

/** Passed to plugins at load time. Registration order defines precedence. */
class PluginContext(val appVersion: String) {
    val components = ArrayList<ComponentRegistry.ComponentInfo>()
    val panels = ArrayList<PanelSpec>()
    val importers = ArrayList<ImporterSpec>()
    val commands = ArrayList<EditorCommand>()
    val inspectorWidgets = ArrayList<InspectorWidgetSpec>()

    /** Extra settings sections contributed by the plugin (id → JSON defaults + apply callback). */
    val settingSections = LinkedHashMap<String, Pair<JVal.Obj, (JVal.Obj) -> Unit>>()

    fun registerComponent(type: String, category: String, description: String, factory: () -> Component) {
        val info = ComponentRegistry.ComponentInfo(type, category, description, factory)
        components.add(info)
        ComponentRegistry.register(info)
    }

    fun registerPanel(spec: PanelSpec) {
        panels.add(spec)
    }

    fun registerImporter(spec: ImporterSpec) {
        importers.add(spec)
    }

    fun registerCommand(command: EditorCommand) {
        commands.add(command)
    }

    fun registerInspectorWidget(spec: InspectorWidgetSpec) {
        inspectorWidgets.add(spec)
    }

    fun registerSettingSection(id: String, defaults: JVal.Obj, apply: (JVal.Obj) -> Unit) {
        settingSections[id] = defaults to apply
    }

    fun log(message: String) = PluginRegistry.log("[$appVersion] $message")
}

/** Loads plugins from the project settings and keeps the extension-point tables. */
object PluginRegistry {
    private val loaded = LinkedHashMap<String, SEnginePlugin>()
    val available = LinkedHashMap<String, () -> SEnginePlugin>()
    val logLines = ArrayList<String>()

    val context = PluginContext("1.0")

    fun registerFactory(id: String, factory: () -> SEnginePlugin) {
        available[id] = factory
    }

    fun loadFromProject(project: Project) {
        for (id in project.settings.plugins) {
            if (loaded.containsKey(id)) continue
            val factory = available[id]
            if (factory == null) {
                log("Plugin not found: $id")
                continue
            }
            runCatching {
                val plugin = factory()
                plugin.onLoad(context)
                loaded[id] = plugin
                log("Loaded plugin ${plugin.name} ${plugin.version}")
            }.onFailure { log("Plugin $id failed: ${it.message}") }
        }
    }

    fun unloadAll() {
        for (p in loaded.values) runCatching { p.onUnload() }
        loaded.clear()
    }

    fun log(message: String) {
        logLines.add(message)
        while (logLines.size > 200) logLines.removeAt(0)
    }

    fun loadedIds(): List<String> = loaded.keys.toList()

    fun manifestTemplate(id: String, name: String, author: String): String =
        Json.write(
            com.sengine.engine.json.jobj(
                "id" to id,
                "name" to name,
                "version" to "1.0.0",
                "author" to author,
                "engine" to "sengine-1.0",
                "entry" to "com.example.seplugin",
                "provides" to "components,panels,commands"
            )
        )
}
