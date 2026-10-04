package com.sengine.project

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ProjectManager {
    fun root(ctx: Context) = File(ctx.filesDir, "projects").also { it.mkdirs() }

    fun list(ctx: Context): List<Project> =
        (root(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .map { Project(it) }
            .sortedByDescending { it.dir.lastModified() }

    fun open(ctx: Context, name: String) = Project(File(root(ctx), name))

    fun sanitize(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _\\-]"), "").take(40)

    fun exists(ctx: Context, name: String) = File(root(ctx), name).exists()

    fun create(ctx: Context, name: String, template: Templates.Template): Project {
        val p = Project(File(root(ctx), name))
        p.saveMeta()
        template.build(p)
        p.saveMeta()
        return p
    }

    fun delete(p: Project) = p.dir.deleteRecursively()

    fun rename(ctx: Context, p: Project, newName: String): Project? {
        val target = File(root(ctx), newName)
        if (target.exists()) return null
        return if (p.dir.renameTo(target)) Project(target) else null
    }

    fun duplicate(ctx: Context, p: Project): Project {
        var n = "${p.name} Copy"
        var i = 2
        while (exists(ctx, n)) n = "${p.name} Copy ${i++}"
        val target = File(root(ctx), n)
        p.dir.copyRecursively(target)
        return Project(target)
    }

    fun exportZip(p: Project, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            p.dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(p.dir).path.replace('\\', '/')
                zip.putNextEntry(ZipEntry("${p.name}/$rel"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Imports a project zip exported by S Engine. Returns the new project. */
    fun importZip(ctx: Context, input: InputStream, fallbackName: String): Project {
        val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}")
        tmp.mkdirs()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val f = File(tmp, e.name)
                if (!f.canonicalPath.startsWith(tmp.canonicalPath)) continue // zip-slip guard
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        // project root is the folder containing project.json
        val metaDir = tmp.walkTopDown().firstOrNull { it.name == "project.json" }?.parentFile
            ?: throw IllegalArgumentException("Not an S Engine project (project.json missing)")
        var name = sanitize(if (metaDir == tmp) fallbackName else metaDir.name).ifBlank { "Imported" }
        val base = name
        var i = 2
        while (exists(ctx, name)) name = "$base ${i++}"
        val target = File(root(ctx), name)
        metaDir.copyRecursively(target)
        tmp.deleteRecursively()
        return Project(target)
    }
}
