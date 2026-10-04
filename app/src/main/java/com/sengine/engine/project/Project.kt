package com.sengine.engine.project

import com.sengine.engine.audio.AudioMixer
import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Scene
import com.sengine.engine.input.InputMap
import com.sengine.engine.json.JVal
import com.sengine.engine.json.Json
import com.sengine.engine.json.jobj
import com.sengine.engine.serialization.SceneFormat
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Project settings — everything a game needs that is not per-scene.
 * Stored in `project.json` next to the scenes and assets.
 */
class ProjectSettings {
    var name = "New Project"
    var startScene = "Main"
    var orientation = 0                 // 0 landscape, 1 portrait, 2 auto
    var windowWidth = 1280
    var windowHeight = 720
    var theme = "Dark"                  // editor theme
    var pixelPerfect = false
    var pixelScale = 1
    var targetFps = 60
    var vsync = true
    var autosaveSeconds = 60
    var showGrid = true
    var gridStep = 1f
    var snapStep = 0.25f
    var layerNames = listOf("Default", "Background", "Foreground", "UI")
    var physicsLayerNames = listOf("Default", "Terrain", "Player", "Enemy", "Pickup", "Trigger")
    var defaultGravity = -9.81f
    var plugins = listOf<String>()
    var editorThemeAccent = Prop.C.format(0xFF4C8DFF.toInt())
    var uiDesignWidth = 1280
    var uiDesignHeight = 720
    var locale = "en"
    var uiScale = 1f
    var highContrast = false
    var tooltips = true
    var snapEnabled = true
    var autosaveEnabled = true
    var backupCount = 5
    var custom = LinkedHashMap<String, String>()

    val inputMap = InputMap()
    val mixer = AudioMixer()

    fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("format", FORMAT)
        o.put("version", VERSION)
        o.put("engine", "S Engine")
        o.put("name", name)
        o.put("startScene", startScene)
        o.put("orientation", orientation)
        o.put("window", com.sengine.engine.json.jarr(windowWidth, windowHeight))
        o.put("theme", theme)
        o.put("pixelPerfect", pixelPerfect)
        o.put("pixelScale", pixelScale)
        o.put("targetFps", targetFps)
        o.put("vsync", vsync)
        o.put("autosaveSeconds", autosaveSeconds)
        o.put("grid", com.sengine.engine.json.jarr(gridStep, snapStep))
        o.put("showGrid", showGrid)
        o.put("layers", JVal.Arr.strings(layerNames))
        o.put("physicsLayers", JVal.Arr.strings(physicsLayerNames))
        o.put("gravity", defaultGravity)
        o.put("plugins", JVal.Arr.strings(plugins))
        o.put("accent", editorThemeAccent)
        o.put("uiDesign", com.sengine.engine.json.jarr(uiDesignWidth, uiDesignHeight))
        o.put("locale", locale)
        o.put("uiScale", uiScale)
        o.put("highContrast", highContrast)
        o.put("tooltips", tooltips)
        o.put("snapEnabled", snapEnabled)
        o.put("autosaveEnabled", autosaveEnabled)
        o.put("backupCount", backupCount)
        o.put("input", inputMap.toJson())
        o.put("audio", mixer.toJson())
        if (custom.isNotEmpty()) {
            val c = JVal.Obj()
            custom.forEach { (k, v) -> c.put(k, v) }
            o.put("custom", c)
        }
        return o
    }

    fun fromJson(o: JVal.Obj) {
        name = o.str("name", name)
        startScene = o.str("startScene", startScene)
        orientation = o.i("orientation")
        val w = o.floats("window")
        if (w.size >= 2) { windowWidth = w[0].toInt(); windowHeight = w[1].toInt() }
        theme = o.str("theme", theme)
        pixelPerfect = o.bool("pixelPerfect")
        pixelScale = o.i("pixelScale", 1).coerceAtLeast(1)
        targetFps = o.i("targetFps", 60).coerceIn(15, 240)
        vsync = o.bool("vsync", true)
        autosaveSeconds = o.i("autosaveSeconds", 60)
        val g = o.floats("grid")
        if (g.size >= 2) { gridStep = g[0]; snapStep = g[1] }
        showGrid = o.bool("showGrid", true)
        val layers = o.strings("layers")
        if (layers.isNotEmpty()) layerNames = layers
        val phys = o.strings("physicsLayers")
        if (phys.isNotEmpty()) physicsLayerNames = phys
        defaultGravity = o.f("gravity", -9.81f)
        val pl = o.strings("plugins")
        plugins = pl
        editorThemeAccent = o.str("accent", editorThemeAccent)
        val ui = o.floats("uiDesign")
        if (ui.size >= 2) { uiDesignWidth = ui[0].toInt(); uiDesignHeight = ui[1].toInt() }
        locale = o.str("locale", "en")
        uiScale = o.f("uiScale", 1f).coerceIn(0.75f, 2f)
        highContrast = o.bool("highContrast", false)
        tooltips = o.bool("tooltips", true)
        snapEnabled = o.bool("snapEnabled", true)
        autosaveEnabled = o.bool("autosaveEnabled", true)
        backupCount = o.i("backupCount", 5)
        (o["input"] as? JVal.Obj)?.let { inputMap.fromJson(it) }
        (o["audio"] as? JVal.Obj)?.let { mixer.fromJson(it) }
        custom.clear()
        (o["custom"] as? JVal.Obj)?.fields?.forEach { (k, v) -> custom[k] = (v as? JVal.Str)?.v ?: Json.write(v, false) }
    }

    companion object {
        const val FORMAT = "sengine.project"
        const val VERSION = 2
        val ORIENTATIONS = listOf("Landscape", "Portrait", "Auto")
    }
}

/** A game project: settings + scenes + assets, stored in a directory. */
class Project(val dir: File) {
    val settings = ProjectSettings()
    val assetsDir = File(dir, "assets")
    val scenesDir = File(dir, "scenes")
    val backupDir = File(dir, ".backup")
    private val metaFile = File(dir, "project.json")

    /** In-memory cache of scene files; the editor keeps the open scene in [EditorDocument]. */
    private val sceneCache = HashMap<String, Scene>()

    val name: String get() = dir.name

    init {
        assetsDir.mkdirs()
        scenesDir.mkdirs()
        if (metaFile.exists()) {
            runCatching { settings.fromJson(Json.parseObject(metaFile.readText())) }
        } else {
            settings.name = name
        }
    }

    // ------------------------------------------------------------------ meta
    fun saveMeta() {
        dir.mkdirs(); assetsDir.mkdirs(); scenesDir.mkdirs()
        settings.name = name
        Atomic.write(metaFile, Json.write(settings.toJson()))
    }

    // ------------------------------------------------------------------ scenes
    fun sceneFile(sceneName: String) = File(scenesDir, "$sceneName.scene.json")
    fun sceneExists(sceneName: String) = sceneFile(sceneName).exists()

    fun listScenes(): List<String> {
        val files = scenesDir.listFiles() ?: return emptyList()
        val names = ArrayList<String>(files.size)
        for (f in files) {
            if (f.isFile && f.name.endsWith(".scene.json")) names.add(f.name.removeSuffix(".scene.json"))
        }
        names.sort()
        return names
    }

    fun loadScene(sceneName: String): Scene {
        val f = sceneFile(sceneName)
        if (!f.exists()) {
            val s = Scene(sceneName)
            s.settings.gravityY = settings.defaultGravity
            return s
        }
        val scene = SceneFormat.fromJson(Json.parseObject(f.readText()))
        scene.name = sceneName
        if (scene.settings.gravityY == -9.81f && settings.defaultGravity != -9.81f) {
            scene.settings.gravityY = settings.defaultGravity
        }
        sceneCache[sceneName] = scene
        return scene
    }

    /** Atomic save with a rotating backup — never leave a half written scene on disk. */
    fun saveScene(scene: Scene): Boolean {
        scenesDir.mkdirs()
        val file = sceneFile(scene.name)
        Atomic.backup(file, backupDir)
        val ok = Atomic.write(file, SceneFormat.write(scene))
        if (ok) sceneCache[scene.name] = scene
        return ok
    }

    fun deleteScene(sceneName: String): Boolean {
        sceneCache.remove(sceneName)
        return sceneFile(sceneName).delete()
    }

    fun duplicateScene(sceneName: String, newName: String): Boolean {
        val src = sceneFile(sceneName)
        if (!src.exists() || sceneExists(newName)) return false
        val scene = loadScene(sceneName)
        scene.name = newName
        return saveScene(scene)
    }

    fun renameScene(oldName: String, newName: String): Boolean {
        if (oldName == newName || sceneExists(newName)) return false
        val scene = loadScene(oldName)
        scene.name = newName
        val ok = saveScene(scene)
        if (ok) deleteScene(oldName)
        return ok
    }

    // ------------------------------------------------------------------ assets
    fun assetFile(assetName: String) = File(assetsDir, assetName)

    fun assetExists(assetName: String) = assetFile(assetName).exists()

    fun listAssets(kind: AssetKind? = null): List<String> {
        val files = assetsDir.listFiles() ?: return emptyList()
        val out = ArrayList<String>(files.size)
        for (f in files) {
            if (!f.isFile || f.name.startsWith('.')) continue
            if (kind != null && AssetKind.of(f.name) != kind) continue
            out.add(f.name)
        }
        out.sortWith(compareBy({ AssetKind.of(it)?.ordinal ?: 99 }, { it.lowercase() }))
        return out
    }

    fun listAssetsRecursive(): List<String> {
        val out = ArrayList<String>()
        for (f in assetsDir.walkTopDown()) {
            if (f.isFile && !f.name.startsWith('.')) out.add(f.relativeTo(assetsDir).path.replace('\\', '/'))
        }
        out.sort()
        return out
    }

    fun readAsset(assetName: String): String? = assetFile(assetName).takeIf { it.exists() }?.readText()

    fun writeAsset(assetName: String, text: String): Boolean {
        assetsDir.mkdirs()
        return Atomic.write(assetFile(assetName), text)
    }

    fun importAsset(assetName: String, bytes: ByteArray): String {
        assetsDir.mkdirs()
        val unique = uniqueAssetName(assetName)
        val f = assetFile(unique)
        f.outputStream().use { it.write(bytes) }
        return unique
    }

    fun importAssetStream(assetName: String, input: InputStream): String {
        assetsDir.mkdirs()
        val unique = uniqueAssetName(assetName)
        assetFile(unique).outputStream().use { out -> input.copyTo(out) }
        return unique
    }

    fun deleteAsset(assetName: String): Boolean = assetFile(assetName).delete()

    fun renameAsset(oldName: String, newName: String): Boolean {
        if (oldName == newName || assetExists(newName)) return false
        return assetFile(oldName).renameTo(assetFile(newName))
    }

    fun copyAsset(oldName: String, newName: String): Boolean {
        val src = assetFile(oldName)
        if (!src.exists()) return false
        val unique = uniqueAssetName(newName)
        val dst = assetFile(unique)
        return runCatching { src.copyTo(dst); true }.getOrDefault(false)
    }

    fun uniqueAssetName(base: String): String {
        if (!assetExists(base)) return base
        val stem = base.substringBeforeLast('.')
        val ext = base.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var i = 1
        while (assetExists("${stem}_$i$suffix")) i++
        return "${stem}_$i$suffix"
    }

    fun assetSize(assetName: String): Long = assetFile(assetName).length()

    /** Sidecar metadata for the import pipeline (imported date, pivot, slices, atlas info). */
    fun metadataFile(assetName: String) = File(assetsDir, ".$assetName.meta.json")

    fun readMetadata(assetName: String): JVal.Obj =
        metadataFile(assetName).takeIf { it.exists() }?.let { runCatching { Json.parseObject(it.readText()) }.getOrNull() } ?: JVal.Obj()

    fun writeMetadata(assetName: String, meta: JVal.Obj) {
        assetsDir.mkdirs()
        Atomic.write(metadataFile(assetName), Json.write(meta))
    }

    fun thumbnailFile(assetName: String) = File(dir, "thumbnails/${assetName.replace('/', '_')}.png")

    // ------------------------------------------------------------------ export / import
    fun exportZip(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (f in dir.walkTopDown()) {
                if (!f.isFile) continue
                if (f.path.contains("/.backup/") || f.name.startsWith(".tmp")) continue
                val rel = f.relativeTo(dir).path.replace('\\', '/')
                zip.putNextEntry(ZipEntry("${name}/$rel"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    companion object {
        /** Import a `.zip` produced by [exportZip]; returns the imported project directory name. */
        fun importZip(rootDir: File, input: InputStream, fallbackName: String): Project {
            val tmp = File(rootDir, ".import_${System.currentTimeMillis()}")
            tmp.mkdirs()
            try {
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val target = File(tmp, e.name)
                        if (!target.canonicalPath.startsWith(tmp.canonicalPath)) continue // zip-slip guard
                        if (e.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                val metaDir = tmp.walkTopDown().firstOrNull { it.name == "project.json" }?.parentFile
                    ?: throw IllegalArgumentException("Not an S Engine project (project.json missing)")
                var newName = sanitize(if (metaDir == tmp) fallbackName else metaDir.name).ifBlank { "Imported" }
                val base = newName
                var i = 2
                while (File(rootDir, newName).exists()) newName = "$base ${i++}"
                val target = File(rootDir, newName)
                metaDir.copyRecursively(target, overwrite = true)
                return Project(target)
            } finally {
                tmp.deleteRecursively()
            }
        }

        fun sanitize(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _\\-]"), "").take(40)
    }
}

/** Project lifecycle: create, list, open, rename, duplicate, delete, recent list. */
class ProjectManager(val rootDir: File) {
    init {
        rootDir.mkdirs()
    }

    private val recentFile = File(rootDir, ".recent.json")

    fun list(): List<Project> {
        val dirs = rootDir.listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory && File(it, "project.json").exists() }
            .map { Project(it) }
            .sortedByDescending { it.dir.lastModified() }
    }

    fun exists(name: String) = File(rootDir, name).exists()

    fun open(name: String): Project = Project(File(rootDir, name))

    fun create(name: String, template: Template): Project {
        var finalName = Project.sanitize(name).ifBlank { "Project" }
        var i = 2
        val base = finalName
        while (exists(finalName)) finalName = "$base ${i++}"
        val p = Project(File(rootDir, finalName))
        p.settings.name = finalName
        p.saveMeta()
        template.build(p)
        p.saveMeta()
        markRecent(finalName)
        return p
    }

    fun delete(p: Project): Boolean = p.dir.deleteRecursively()

    fun rename(p: Project, newName: String): Project? {
        val clean = Project.sanitize(newName).ifBlank { return null }
        val target = File(rootDir, clean)
        if (target.exists()) return null
        if (!p.dir.renameTo(target)) return null
        val renamed = Project(target)
        renamed.settings.name = clean
        renamed.saveMeta()
        return renamed
    }

    fun duplicate(p: Project): Project {
        var n = "${p.name} Copy"
        var i = 2
        while (exists(n)) n = "${p.name} Copy ${i++}"
        val target = File(rootDir, n)
        p.dir.copyRecursively(target, overwrite = false)
        val copy = Project(target)
        copy.settings.name = n
        copy.saveMeta()
        markRecent(n)
        return copy
    }

    fun importZip(input: InputStream, fallbackName: String): Project {
        val p = Project.importZip(rootDir, input, fallbackName)
        p.saveMeta()
        markRecent(p.name)
        return p
    }

    // ------------------------------------------------------------------ recents
    data class Recent(val name: String, var opened: Long)

    fun recent(): List<Recent> {
        if (!recentFile.exists()) return emptyList()
        return runCatching {
            val arr = Json.parseObject(recentFile.readText()).arr("projects")
            arr.mapNotNull { item ->
                val o = item as? JVal.Obj ?: return@mapNotNull null
                val n = o.str("name")
                if (n.isBlank() || !exists(n)) null else Recent(n, o.l("opened"))
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.opened }.take(12)
    }

    fun markRecent(name: String) {
        val list = recent().filter { it.name != name }.toMutableList()
        list.add(0, Recent(name, System.currentTimeMillis()))
        val arr = JVal.Arr()
        list.take(12).forEach { r -> arr.add(jobj("name" to r.name, "opened" to r.opened)) }
        Atomic.write(recentFile, Json.write(jobj("projects" to arr)))
    }

    fun forgetRecent() {
        recentFile.delete()
    }
}

/** Atomic file writes + rotating backups: the guarantee that a crash never corrupts a project. */
object Atomic {
    fun write(file: File, text: String): Boolean {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".tmp_${file.name}")
        return try {
            tmp.writeText(text)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Throwable) {
            tmp.delete()
            false
        }
    }

    fun writeBytes(file: File, bytes: ByteArray): Boolean {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".tmp_${file.name}")
        return try {
            tmp.writeBytes(bytes)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Throwable) {
            tmp.delete()
            false
        }
    }

    /** Keep the last [keep] copies of a file inside [backupDir]. */
    fun backup(file: File, backupDir: File, keep: Int = 5) {
        if (!file.exists()) return
        backupDir.mkdirs()
        val stamp = System.currentTimeMillis()
        runCatching { file.copyTo(File(backupDir, "${file.name}.$stamp"), overwrite = true) }
        val copies = backupDir.listFiles { f -> f.name.startsWith(file.name + ".") }?.sortedByDescending { it.name } ?: return
        for (i in keep until copies.size) copies[i].delete()
    }

    fun restoreLatest(file: File, backupDir: File): Boolean {
        val copies = backupDir.listFiles { f -> f.name.startsWith(file.name + ".") }?.sortedByDescending { it.name } ?: return false
        val latest = copies.firstOrNull() ?: return false
        return runCatching { latest.copyTo(file, overwrite = true); true }.getOrDefault(false)
    }
}
