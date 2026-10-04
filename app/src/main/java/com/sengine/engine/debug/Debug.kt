package com.sengine.engine.debug

import com.sengine.engine.Engine
import com.sengine.engine.core.AnimationPlayer
import com.sengine.engine.core.Area2D
import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.input.InputSystem
import com.sengine.engine.json.JVal
import com.sengine.engine.json.jarr
import com.sengine.engine.json.jobj
import com.sengine.engine.physics.PhysicsWorld
import com.sengine.engine.render.RenderStats
import com.sengine.engine.ui.ControlComponent
import java.util.concurrent.CopyOnWriteArrayList

// ---------------------------------------------------------------------------------------------
// Logging
// ---------------------------------------------------------------------------------------------

object LogLevel {
    const val DEBUG = 0
    const val INFO = 1
    const val WARN = 2
    const val ERROR = 3
    const val SCRIPT = 4

    fun label(level: Int) = when (level) {
        DEBUG -> "DEBUG"
        INFO -> "INFO"
        WARN -> "WARN"
        ERROR -> "ERROR"
        else -> "SCRIPT"
    }
}

class LogEntry(val level: Int, val tag: String, val message: String, val time: Long = System.currentTimeMillis()) {
    var count = 1
}

/**
 * Single log sink for engine, scripts, physics, audio and editor. Keeps a bounded ring buffer for
 * the Output/Debugger panels and notifies listeners (which is how the UI stays live without
 * polling).
 */
object Log {
    interface Listener {
        fun onEntry(entry: LogEntry) {}
        fun onCleared() {}
    }

    private val entries = ArrayDeque<LogEntry>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    var capacity = 500
    var minLevel = LogLevel.DEBUG

    var errorCount = 0
        private set
    var warningCount = 0
        private set

    fun add(level: Int, tag: String, message: String) {
        if (level < minLevel) return
        val last = entries.lastOrNull()
        if (last != null && last.message == message && last.level == level && last.tag == tag) {
            last.count++
            listeners.forEach { it.onEntry(last) }
            return
        }
        val entry = LogEntry(level, tag, message)
        entries.addLast(entry)
        when (level) {
            LogLevel.ERROR, LogLevel.SCRIPT -> errorCount++
            LogLevel.WARN -> warningCount++
        }
        while (entries.size > capacity) entries.removeFirst()
        listeners.forEach { it.onEntry(entry) }
    }

    fun debug(tag: String, message: String) = add(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = add(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = add(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = add(LogLevel.ERROR, tag, message)
    fun script(tag: String, message: String) = add(LogLevel.SCRIPT, tag, message)

    fun all(): List<LogEntry> = entries.toList()
    fun last(n: Int): List<LogEntry> = entries.toList().takeLast(n)
    fun errors() = entries.filter { it.level == LogLevel.ERROR || it.level == LogLevel.SCRIPT }
    fun warnings() = entries.filter { it.level == LogLevel.WARN }

    fun clear() {
        entries.clear()
        errorCount = 0
        warningCount = 0
        listeners.forEach { it.onCleared() }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }
}

// ---------------------------------------------------------------------------------------------
// Profiler — every number measured, never estimated
// ---------------------------------------------------------------------------------------------

class StatRing(val capacity: Int = 120) {
    private val values = FloatArray(capacity)
    private var head = 0
    private var count = 0

    fun push(v: Float) {
        values[head] = v
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    fun last(): Float = if (count == 0) 0f else values[(head - 1 + capacity) % capacity]

    fun max(): Float {
        var m = 0f
        for (i in 0 until count) m = maxOf(m, values[i])
        return m
    }

    fun avg(): Float {
        if (count == 0) return 0f
        var s = 0f
        for (i in 0 until count) s += values[i]
        return s / count
    }

    /** Copy oldest → newest for graph drawing. */
    fun toArray(): FloatArray {
        val out = FloatArray(count)
        val start = (head - count + capacity) % capacity
        for (i in 0 until count) out[i] = values[(start + i) % capacity]
        return out
    }

    fun clear() {
        head = 0; count = 0
    }
}

/**
 * Frame profiler. Phases are measured with `System.nanoTime` around the real work in the engine
 * loop, counters come from the systems themselves (physics bodies, render batches, particles…).
 */
class Profiler {
    val frameMs = StatRing()
    val updateMs = StatRing()
    val physicsMs = StatRing()
    val renderMs = StatRing()
    val scriptMs = StatRing()
    val audioMs = StatRing()
    val uiMs = StatRing()
    val frameCount = StatRing(240)

    var fps = 0f
        private set
    var smoothFps = 0f
        private set
    var samples = 0L
        private set

    // counters (filled by the engine each frame)
    var drawCalls = 0
    var batches = 0
    var sprites = 0
    var texts = 0
    var shapes = 0
    var activeNodes = 0
    var totalNodes = 0
    var visibleNodes = 0
    var particles = 0
    var bodies = 0
    var contacts = 0
    var tileColliders = 0
    var audioVoices = 0
    var scripts = 0
    var uiControls = 0
    var textureCount = 0
    var memoryMB = 0f
    var javaHeapMB = 0f
    var gcCount = 0L

    private var frameStart = 0L
    private var phaseStart = 0L
    private var fpsAccum = 0f
    private var fpsFrames = 0

    fun beginFrame() {
        frameStart = System.nanoTime()
        phaseStart = frameStart
    }

    fun beginPhase() {
        phaseStart = System.nanoTime()
    }

    /** End the current phase and record it in [ring]. */
    fun endPhase(ring: StatRing) {
        val now = System.nanoTime()
        ring.push((now - phaseStart) / 1_000_000f)
        phaseStart = now
    }

    fun endFrame(dt: Float) {
        val now = System.nanoTime()
        frameMs.push((now - frameStart) / 1_000_000f)
        samples++
        fpsAccum += dt
        fpsFrames++
        if (fpsAccum >= 0.5f) {
            fps = fpsFrames / fpsAccum
            smoothFps = if (smoothFps == 0f) fps else smoothFps + (fps - smoothFps) * 0.4f
            fpsAccum = 0f
            fpsFrames = 0
            sampleMemory()
        }
        frameCount.push(frameMs.last())
    }

    private fun sampleMemory() {
        val rt = Runtime.getRuntime()
        javaHeapMB = (rt.totalMemory() - rt.freeMemory()) / 1048576f
        memoryMB = javaHeapMB
        gcCount++
    }

    fun statSummary(): String =
        "FPS %.1f  frame %.2fms  update %.2f  physics %.2f  render %.2f  script %.2f".format(
            smoothFps, frameMs.last(), updateMs.last(), physicsMs.last(), renderMs.last(), scriptMs.last()
        )

    fun reset() {
        frameMs.clear(); updateMs.clear(); physicsMs.clear(); renderMs.clear()
        scriptMs.clear(); audioMs.clear(); uiMs.clear(); frameCount.clear()
        samples = 0
    }

    fun applyRenderStats(stats: RenderStats) {
        drawCalls = stats.drawCalls
        batches = stats.batches
        sprites = stats.sprites
        texts = stats.texts
        shapes = stats.shapes
    }
}

// ---------------------------------------------------------------------------------------------
// Remote inspector — read the live game state safely while it runs
// ---------------------------------------------------------------------------------------------

/**
 * Builds a read-only snapshot of the running scene for the Debugger's "Remote" tab: node tree,
 * transforms, visibility, physics state, animation state and UI rectangles. Snapshots are plain
 * data, so inspecting a running game can never mutate it.
 */
object RemoteInspector {

    class NodeSnapshot(
        val id: Long,
        val name: String,
        val type: String,
        val depth: Int,
        val active: Boolean,
        val visible: Boolean,
        val x: Float,
        val y: Float,
        val worldX: Float,
        val worldY: Float,
        val rotation: Float,
        val scaleX: Float,
        val scaleY: Float,
        val children: Int,
        val componentTypes: List<String>,
        val summary: String
    )

    fun tree(scene: Scene): List<NodeSnapshot> {
        val out = ArrayList<NodeSnapshot>(scene.objects.size)
        for ((go, depth) in scene.hierarchy()) {
            out.add(snapshot(go, depth, scene))
        }
        return out
    }

    private fun snapshot(go: GameObject, depth: Int, scene: Scene): NodeSnapshot {
        val rb = go.getAny<Rigidbody2D>()
        val anim = go.getAny<AnimationPlayer>()
        val control = go.getAny<ControlComponent>()
        val parts = StringBuilder()
        rb?.let { parts.append("v=").append("%.2f,%.2f".format(it.vx, it.vy)).append(if (it.grounded) " grounded" else "") }
        anim?.let { parts.append(if (parts.isEmpty()) "" else " · ").append("anim=").append(it.animationAsset).append(" t=").append("%.2f".format(it.state.time)) }
        control?.let { parts.append(if (parts.isEmpty()) "" else " · ").append("ui=").append(it.ui.controlType).append(" ").append("%.0f×%.0f".format(it.rect.width, it.rect.height)) }
        go.getAny<ParticleEmitter2D>()?.let { parts.append(if (parts.isEmpty()) "" else " · ").append("particles=").append(it.particleCount) }
        go.getAny<AudioSource>()?.let { if (it.playing) parts.append(if (parts.isEmpty()) "" else " · ").append("audio") }
        return NodeSnapshot(
            go.id, go.name, go.type, depth, go.active, go.visible,
            go.x, go.y, go.world.tx, go.world.ty, go.rotation, go.scaleX, go.scaleY,
            scene.childrenOf(go).size,
            go.components.map { it.type },
            parts.toString()
        )
    }

    /** Detailed property dump for one node (inspector style, but read-only). */
    fun details(scene: Scene, nodeId: Long): List<Pair<String, String>> {
        val go = scene.findById(nodeId) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        out.add("Node" to "${go.name} (#${go.id}, ${go.type})")
        out.add("Path" to go.path())
        out.add("Transform" to "x=%.3f y=%.3f rot=%.1f scale=%.2f,%.2f".format(go.x, go.y, go.rotation, go.scaleX, go.scaleY))
        out.add("World" to "x=%.3f y=%.3f rot=%.1f".format(go.world.tx, go.world.ty, go.world.rotationDeg))
        out.add("State" to "active=${go.active} visible=${go.visible} locked=${go.locked} layer=${go.layer} z=${go.order}")
        if (go.groups.isNotEmpty()) out.add("Groups" to go.groups.joinToString(", "))
        if (go.meta.isNotEmpty()) out.add("Metadata" to go.meta.entries.joinToString(", ") { "${it.key}=${it.value}" })
        for (c in go.components) {
            val text = StringBuilder()
            for (p in c.props()) {
                if (p is com.sengine.engine.core.Prop.Info) {
                    text.append(p.label).append(": ").append(p.get()).append("  ")
                }
            }
            out.add((c.type + if (c.enabled) "" else " (disabled)") to text.toString().trim())
        }
        return out
    }

    // ------------------------------------------------------------------ structured snapshots

    /**
     * Serializable snapshot of a running game: engine stats, the whole node tree and runtime state
     * (physics, animation, particles, UI rects). The editor shows it and can copy it into a bug
     * report or write it next to the log — inspecting a game never mutates it.
     */
    fun snapshot(engine: Engine, includeProps: Boolean = true, maxNodes: Int = 2000): JVal.Obj {
        val scene = engine.scene
        val nodes = JVal.Arr()
        var count = 0
        for (go in scene.objects) {
            if (count >= maxNodes) break
            nodes.add(nodeJson(go, includeProps))
            count++
        }
        return jobj(
            "mode" to engine.mode.name,
            "time" to engine.time,
            "frame" to engine.frame,
            "fps" to engine.fps,
            "scene" to scene.name,
            "revision" to scene.structureRevision.toLong(),
            "nodeCount" to scene.objects.size,
            "truncated" to (count < scene.objects.size),
            "stats" to stats(engine),
            "nodes" to nodes
        )
    }

    /** Runtime state of a single node, in the same shape as one entry of [snapshot]. */
    fun nodeJson(go: GameObject, includeProps: Boolean = true): JVal.Obj {
        val components = JVal.Arr()
        for (c in go.components) {
            val props = JVal.Obj()
            if (includeProps) {
                for (p in c.props()) {
                    if (p is com.sengine.engine.core.Prop.Info) continue
                    runCatching { p.encode() }.getOrNull()?.let { props.fields[p.name] = it }
                }
            }
            components.add(jobj("type" to c.type, "enabled" to c.enabled, "props" to props))
        }

        val runtime = JVal.Obj()
        go.getAny<Rigidbody2D>()?.let { rb ->
            runtime.put("physics", jobj(
                "vx" to rb.vx, "vy" to rb.vy, "grounded" to rb.grounded, "onWall" to rb.onWall,
                "onCeiling" to rb.onCeiling, "sleeping" to rb.sleeping,
                "layer" to rb.collisionLayer, "mask" to rb.collisionMask
            ))
        }
        go.getAny<com.sengine.engine.core.AnimatedSprite2D>()?.let { a ->
            runtime.put("animation", jobj(
                "asset" to a.animationAsset, "frame" to a.currentFrame, "playing" to a.playing,
                "finished" to a.finished, "fps" to a.fps
            ))
        }
        go.getAny<ParticleEmitter2D>()?.let { p ->
            runtime.put("particles", jobj("count" to p.particleCount, "preset" to p.preset))
        }
        go.getAny<Area2D>()?.let { area -> runtime.put("area", jobj("overlapping" to area.overlapping.size)) }
        go.getAny<ControlComponent>()?.let { control ->
            runtime.put("ui", jobj(
                "type" to control.ui.controlType,
                "rect" to jarr(control.rect.x, control.rect.y, control.rect.width, control.rect.height)
            ))
        }

        val groups = JVal.Arr()
        go.groups.forEach { groups.add(JVal.Str(it)) }
        val world = go.computeWorld()
        return jobj(
            "id" to go.id,
            "name" to go.name,
            "type" to go.type,
            "parent" to (go.parent?.id ?: 0L),
            "depth" to depthOf(go),
            "tag" to go.tag,
            "groups" to groups,
            "active" to go.active,
            "activeInHierarchy" to go.isActiveInHierarchy(),
            "visible" to go.visible,
            "locked" to go.locked,
            "layer" to go.layer,
            "order" to go.order,
            "transform" to jobj(
                "x" to go.x, "y" to go.y, "rotation" to go.rotation,
                "scaleX" to go.scaleX, "scaleY" to go.scaleY,
                "pivotX" to go.pivotX, "pivotY" to go.pivotY
            ),
            "world" to jobj("x" to world.tx, "y" to world.ty, "rotation" to world.rotationDeg),
            "components" to components,
            "runtime" to runtime,
            "children" to go.children().size
        )
    }

    /** Finds a node inside a snapshot by id (or by name, when [id] is 0). */
    fun findNode(snapshot: JVal.Obj, id: Long, name: String = ""): JVal.Obj? {
        for (item in snapshot.arr("nodes")) {
            val o = item as? JVal.Obj ?: continue
            if (id != 0L && o.l("id") == id) return o
            if (id == 0L && name.isNotEmpty() && o.str("name") == name) return o
        }
        return null
    }

    /** Engine, renderer and memory numbers — the values the profiler tab plots. */
    fun stats(engine: Engine): JVal.Obj {
        val p = engine.profiler
        val runtime = Runtime.getRuntime()
        return jobj(
            "fps" to p.smoothFps,
            "frameMs" to p.frameMs.last(),
            "updateMs" to p.updateMs.last(),
            "physicsMs" to p.physicsMs.last(),
            "renderMs" to p.renderMs.last(),
            "scriptMs" to p.scriptMs.last(),
            "audioMs" to p.audioMs.last(),
            "drawCalls" to engine.renderList.stats.drawCalls,
            "batches" to engine.renderList.stats.batches,
            "sprites" to engine.renderList.stats.sprites,
            "vertices" to engine.renderList.stats.vertices,
            "itemsCulled" to engine.renderList.stats.itemsCulled,
            "activeNodes" to p.activeNodes,
            "totalNodes" to p.totalNodes,
            "visibleNodes" to p.visibleNodes,
            "bodies" to p.bodies,
            "contacts" to p.contacts,
            "particles" to p.particles,
            "audioVoices" to p.audioVoices,
            "scriptInstances" to engine.scripts.instanceCount,
            "scriptErrors" to engine.scripts.failedCount,
            "memoryUsed" to (runtime.totalMemory() - runtime.freeMemory()),
            "memoryMax" to runtime.maxMemory(),
            "input" to jobj(
                "moveX" to engine.input.moveX,
                "moveY" to engine.input.moveY,
                "actions" to engine.input.snapshot().size
            )
        )
    }

    /** Compact human readable tree with one runtime summary per node (Debugger panel). */
    fun treeText(engine: Engine, maxNodes: Int = 200): String {
        val sb = StringBuilder()
        sb.append("mode=").append(engine.mode.name).append(" scene=").append(engine.scene.name)
            .append(" nodes=").append(engine.scene.objects.size).append('\n')
        var shown = 0
        for (go in engine.scene.objects) {
            if (shown++ >= maxNodes) {
                sb.append("  … ").append(engine.scene.objects.size - maxNodes).append(" more\n")
                break
            }
            repeat(depthOf(go).coerceAtMost(8)) { sb.append("  ") }
            if (!go.active) sb.append("(disabled) ")
            sb.append(go.name).append(" [").append(go.type).append(']')
            go.getAny<Rigidbody2D>()?.let { rb ->
                sb.append(" v=%.2f,%.2f".format(rb.vx, rb.vy))
                if (rb.grounded) sb.append(" grounded")
                if (rb.sleeping) sb.append(" sleeping")
            }
            go.getAny<com.sengine.engine.core.AnimatedSprite2D>()?.let { a ->
                sb.append(" frame=").append(a.currentFrame).append(if (a.playing) " playing" else " paused")
            }
            go.getAny<ParticleEmitter2D>()?.let { p -> sb.append(" particles=").append(p.particleCount) }
            if (!go.visible) sb.append(" (hidden)")
            sb.append('\n')
        }
        val s = engine.renderList.stats
        sb.append("fps=%.1f draw=%d batches=%d nodes=%d bodies=%d particles=%d".format(
            engine.profiler.smoothFps, s.drawCalls, s.batches,
            engine.profiler.activeNodes, engine.profiler.bodies, engine.profiler.particles
        ))
        return sb.toString()
    }

    private fun depthOf(go: GameObject): Int {
        var d = 0
        var p = go.parent
        while (p != null) { d++; p = p.parent }
        return d
    }
}

// ---------------------------------------------------------------------------------------------
// Debugger model — errors, variables, input & physics state
// ---------------------------------------------------------------------------------------------

class DebuggerModel {
    class Variable(val name: String, val value: String, val scope: String)

    val variables = ArrayList<Variable>()

    fun refreshVariables(scriptGlobals: Map<String, String>, engineVars: Map<String, String>) {
        variables.clear()
        engineVars.forEach { (k, v) -> variables.add(Variable(k, v, "engine")) }
        scriptGlobals.forEach { (k, v) -> variables.add(Variable(k, v, "script")) }
    }

    fun inputState(input: InputSystem): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (a in input.map.actions.values) {
            if (a.value != 0f || a.pressed) out.add(a.name to "%.2f".format(a.value) + if (a.justPressed) " (just pressed)" else "")
        }
        val d = input.devices
        out.add("touch" to if (d.touchActive) "%.0f,%.0f  count=%d".format(d.touchX, d.touchY, d.touchCount) else "idle")
        if (d.gamepadConnected) out.add("gamepad" to d.axes.joinToString(", ") { "%.2f".format(it) })
        return out
    }

    fun physicsState(physics: PhysicsWorld, scene: Scene): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("bodies" to physics.bodyCount.toString())
        out.add("tile colliders" to physics.tileColliderCount.toString())
        out.add("contacts" to physics.contactCount.toString())
        out.add("gravity" to "%.2f, %.2f".format(scene.settings.gravityX, scene.settings.gravityY))
        var grounded = 0
        var dynamicBodies = 0
        for (go in scene.objects) {
            val rb = go.getAny<Rigidbody2D>() ?: continue
            if (rb.grounded) grounded++
            if (rb.bodyType == 0) dynamicBodies++
        }
        out.add("dynamic" to dynamicBodies.toString())
        out.add("grounded" to grounded.toString())
        return out
    }

    fun sceneStats(scene: Scene): List<Pair<String, String>> {
        var components = 0
        var tileMaps = 0
        var particles = 0
        for (go in scene.objects) {
            components += go.components.size
            if (go.getAny<TileMap2D>() != null) tileMaps++
            particles += go.getAny<ParticleEmitter2D>()?.particleCount ?: 0
        }
        return listOf(
            "nodes" to scene.objects.size.toString(),
            "components" to components.toString(),
            "tile maps" to tileMaps.toString(),
            "particles" to particles.toString(),
            "signals" to scene.signals.connections.size.toString(),
            "structure rev" to scene.structureRevision.toString()
        )
    }

    /** Errors and warnings captured from scripts and the engine. */
    fun problems(): List<LogEntry> = Log.all().filter { it.level >= LogLevel.WARN }
}
