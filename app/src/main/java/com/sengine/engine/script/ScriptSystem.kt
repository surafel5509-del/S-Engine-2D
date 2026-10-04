package com.sengine.engine.script

import com.sengine.engine.Engine
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SignalHub
import com.sengine.engine.debug.Log
import com.sengine.engine.physics.PhysicsWorld
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * JavaScript behaviour runtime (Mozilla Rhino, interpreted).
 *
 * Each Script component gets its own scope whose prototype chain ends at the shared global scope.
 * Scripts see the S Engine API (`self`, `input`, `scene`, `time`, `audio`, `ui`, `physics`, `store`,
 * helpers), and the engine dispatches the full lifecycle:
 * `start`, `update(dt)`, `physics_update(dt)`, `onCollision`, `onTrigger`, `onTriggerExit`,
 * `onTap`, `onSignal(name, data)`, `onDestroy`, `onStop`.
 *
 * Errors never crash the game: they are reported to [Log] with file + line and disable only the
 * offending instance.
 */
class ScriptSystem(val engine: Engine) : PhysicsWorld.Listener, SignalHub.Dispatcher {

    private class Instance(val go: GameObject, val comp: ScriptComponent, val scope: Scriptable) {
        var started = false
        var failed = false
        var file = ""
        val locals = HashMap<String, String>()
    }

    private var cx: Context? = null
    private var global: ScriptableObject? = null
    private var ownerThread: Thread? = null
    private val instances = ArrayList<Instance>()
    private val byNode = HashMap<Long, MutableList<Instance>>()
    private val wrappers = HashMap<Long, SObject>()
    private val compiled = HashMap<String, Script>()
    private val inputApi = SInput(engine)
    private var sceneApi: SScene? = null
    private var audioApi: SAudio? = null
    private var uiApi: SUi? = null
    private var physicsApi: SPhysics? = null
    private var resourceApi: SResources? = null
    private var storeApi: SStore? = null

    var signalDispatcher: SignalHub.Dispatcher
        get() = this
        set(_) {}

    val isRunning get() = cx != null
    val instanceCount get() = instances.size

    /** Scripts that threw — surfaced by the debugger panel and the remote inspector. */
    val failedCount: Int get() = instances.count { it.failed }

    fun failedNodes(): List<Long> = instances.filter { it.failed }.map { it.go.id }

    // ------------------------------------------------------------------ lifecycle
    fun begin() {
        end()
        val c = Context.enter()
        c.optimizationLevel = OPTIMIZATION_LEVEL
        c.languageVersion = Context.VERSION_ES6
        c.wrapFactory.isJavaPrimitiveWrap = false
        cx = c
        ownerThread = Thread.currentThread()
        val g = c.initStandardObjects()
        global = g
        inputApi.sync()
        val sceneApiLocal = SScene(engine, this)
        val audioApiLocal = SAudio(engine)
        val uiApiLocal = SUi(engine, this)
        val physicsApiLocal = SPhysics(engine, this)
        val resourceApiLocal = SResources(engine, this)
        val storeApiLocal = SStore(engine)
        sceneApi = sceneApiLocal
        audioApi = audioApiLocal
        uiApi = uiApiLocal
        physicsApi = physicsApiLocal
        resourceApi = resourceApiLocal
        storeApi = storeApiLocal
        put(g, "input", inputApi)
        put(g, "time", STime(engine))
        put(g, "scene", sceneApiLocal)
        put(g, "audio", audioApiLocal)
        put(g, "ui", uiApiLocal)
        put(g, "physics", physicsApiLocal)
        put(g, "resources", resourceApiLocal)
        put(g, "store", storeApiLocal)
        put(g, "console", SConsole(engine))
        c.evaluateString(g, PRELUDE, "sengine-prelude", 1, null)
        compiled.clear()
        for (go in engine.scene.objects.toList()) attach(go)
        startPending()
        Log.info("Script", "Runtime ready (${instances.size} script instances)")
    }

    fun end() {
        if (cx != null && Thread.currentThread() === ownerThread) {
            for (inst in instances.toList()) if (inst.started && !inst.failed) call(inst, "onStop")
            runCatching { Context.exit() }
        }
        cx = null
        global = null
        ownerThread = null
        instances.clear()
        byNode.clear()
        wrappers.clear()
        compiled.clear()
    }

    private fun put(scope: Scriptable, name: String, obj: Any) {
        ScriptableObject.putProperty(scope, name, Context.javaToJS(obj, scope))
    }

    /** Stops every instance and clears the compile cache (used by the editor's "Reload scripts"). */
    fun reload() {
        for (inst in instances.toList()) {
            if (inst.started) runCatching { call(inst, "onDestroy", *emptyArray<Any?>()) }
        }
        instances.clear()
        byNode.clear()
        wrappers.clear()
        compiled.clear()
        com.sengine.engine.debug.Log.info("Script", "Scripts reloaded")
    }

    /**
     * Compiles [source] the same way the runtime does and reports every problem it finds, with line
     * numbers. Used by the script editor's *Check* button, so a script that validates there is a
     * script that runs in play mode. The source is compiled in a throwaway Rhino context: nothing is
     * attached to the scene and no instance is created.
     */
    fun validate(source: String, onError: (Int, String) -> Unit = { _, _ -> }): List<String> {
        val problems = ArrayList<String>()
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            context.compileString(source, "script", 1, null)
        } catch (e: org.mozilla.javascript.RhinoException) {
            val line = e.lineNumber()
            val message = e.details()?.trim().orEmpty().ifEmpty { e.toString() }
            problems.add("line $line: $message")
            onError(line, message)
        } catch (e: Throwable) {
            val message = e.message ?: e.toString()
            problems.add("line 0: $message")
            onError(0, message)
        } finally {
            Context.exit()
        }
        return problems
    }

    /** Releases every script instance (called when a play session ends). */
    fun destroyAll() {
        for (inst in instances.toList()) {
            if (inst.started) runCatching { call(inst, "onDestroy", *emptyArray<Any?>()) }
        }
        instances.clear()
        byNode.clear()
        wrappers.clear()
    }

    fun wrap(go: GameObject): SObject = wrappers.getOrPut(go.id) { SObject(go, engine, this) }

    fun toJs(go: GameObject?): Any? {
        val g = global ?: return null
        return if (go == null) null else Context.javaToJS(wrap(go), g)
    }

    fun newArray(items: List<Any?>): Scriptable? {
        val c = cx ?: return null
        val g = global ?: return null
        return c.newArray(g, items.toTypedArray())
    }

    // ------------------------------------------------------------------ attaching
    /** Create script instances for a node (and, recursively, its children). */
    fun attach(go: GameObject) {
        val c = cx ?: return
        val g = global ?: return
        for (comp in go.components) {
            if (comp !is ScriptComponent || comp.script.isBlank() || !comp.enabled) continue
            val script = compiled[comp.script] ?: run {
                val src = engine.project.readAsset(comp.script)
                if (src == null) {
                    Log.error(comp.script, "Script asset not found (on ${go.name})")
                    null
                } else try {
                    c.compileString(src, comp.script, 1, null).also { compiled[comp.script] = it }
                } catch (e: RhinoException) {
                    Log.script(comp.script, "line ${e.lineNumber()}: ${e.details()}")
                    null
                } catch (e: Throwable) {
                    Log.script(comp.script, e.message ?: "compile error")
                    null
                }
            } ?: continue

            val scope = c.newObject(g)
            scope.prototype = g
            scope.parentScope = null
            val self = Context.javaToJS(wrap(go), g)
            ScriptableObject.putProperty(scope, "self", self)
            ScriptableObject.putProperty(scope, "node", self)
            ScriptableObject.putProperty(scope, "transform", self)
            ScriptableObject.putProperty(scope, "gameObject", self)
            applyParams(scope, comp.params)
            val inst = Instance(go, comp, scope)
            inst.file = comp.script
            try {
                script.exec(c, scope)
                instances.add(inst)
                byNode.getOrPut(go.id) { ArrayList() }.add(inst)
                collectLocals(inst)
            } catch (e: RhinoException) {
                Log.script(comp.script, "line ${e.lineNumber()}: ${e.details()}")
            } catch (e: Throwable) {
                Log.script(comp.script, e.message ?: "runtime error")
            }
        }
        for (child in engine.scene.childrenOf(go)) attach(child)
    }

    private fun applyParams(scope: Scriptable, params: String) {
        for (raw in params.split(',', '\n', ';')) {
            val kv = raw.split('=', limit = 2)
            if (kv.size != 2) continue
            val k = kv[0].trim()
            val v = kv[1].trim()
            if (k.isEmpty()) continue
            val value: Any = v.toDoubleOrNull() ?: when (v.lowercase()) {
                "true" -> true
                "false" -> false
                else -> v.trim('"', '\'')
            }
            ScriptableObject.putProperty(scope, k, value)
        }
    }

    /** Snapshot the script's top-level variables for the debugger's Variables view. */
    private fun collectLocals(inst: Instance) {
        inst.locals.clear()
        runCatching {
            for (id in inst.scope.getIds()) {
                val name = id as? String ?: continue
                if (name.startsWith("__")) continue
                if (name in BUILTIN_NAMES) continue
                val value = inst.scope.get(name, inst.scope)
                if (value === Scriptable.NOT_FOUND) continue
                inst.locals[name] = Context.toString(value).take(120)
            }
        }
    }

    private fun startPending() {
        for (inst in instances.toList()) {
            if (!inst.started && inst.go.isActiveInHierarchy() && inst.comp.enabled) {
                inst.started = true
                call(inst, "start")
            }
        }
    }

    fun beginFrame() {
        sceneApi?.sync()
    }

    fun endFrame() {}

    fun update(dt: Float) {
        if (cx == null) return
        inputApi.sync()
        sceneApi?.sync()
        startPending()
        val dtArg = dt.toDouble()
        val input = engine.input
        var tapTarget: GameObject? = null
        if (input.devices.tapPending) {
            val wx = engine.gameView.screenToWorldX(input.devices.touchX)
            val wy = engine.gameView.screenToWorldY(input.devices.touchY)
            tapTarget = engine.physics.overlapPoint(engine.scene, wx, wy)
        }
        for (inst in instances.toList()) {
            if (inst.failed || !inst.started || inst.go.destroyed) continue
            if (!inst.go.isActiveInHierarchy() || !inst.comp.enabled) continue
            call(inst, "update", dtArg)
            call(inst, "physics_update", dtArg)
            if (tapTarget != null && tapTarget === inst.go) call(inst, "onTap")
        }
        val g = global ?: return
        val tick = g.get("__tick", g)
        if (tick is Function) {
            try {
                tick.call(cx, g, g, emptyArray())
            } catch (e: RhinoException) {
                Log.script("timers", "line ${e.lineNumber()}: ${e.details()}")
            } catch (e: Throwable) {
                Log.script("timers", e.message ?: "timer error")
            }
        }
    }

    private fun call(inst: Instance, fname: String, vararg args: Any?): Any? {
        val f = inst.scope.get(fname, inst.scope)
        if (f !is Function) return null
        return try {
            f.call(cx, inst.scope, inst.scope, arrayOf(*args))
        } catch (e: RhinoException) {
            Log.script(inst.file, "line ${e.lineNumber()} in $fname(): ${e.details()}")
            inst.failed = true
            null
        } catch (e: Throwable) {
            Log.script(inst.file, "in $fname(): ${e.message}")
            inst.failed = true
            null
        }
    }

    fun hasMethod(go: GameObject, method: String): Boolean {
        for (inst in byNode[go.id] ?: return false) {
            if (inst.failed) continue
            if (inst.scope.get(method, inst.scope) is Function) return true
        }
        return false
    }

    /** Signal/script dispatch: returns the produced value (or null when no handler ran). */
    override fun dispatch(target: GameObject, method: String, data: Any?, args: String): Boolean {
        if (cx == null) return false
        var handled = false
        val js: Any? = if (data is GameObject) toJs(data) else data
        for (inst in (byNode[target.id] ?: return false).toList()) {
            if (inst.failed || !inst.comp.enabled) continue
            if (inst.scope.get(method, inst.scope) !is Function) continue
            call(inst, method, if (data == null && args.isBlank()) null else js)
            handled = true
        }
        return handled
    }

    fun sendMessage(go: GameObject, fname: String, arg: Any?): Any? {
        var result: Any? = null
        val js = if (arg is GameObject) toJs(arg) else arg
        for (inst in (byNode[go.id] ?: return null).toList()) {
            if (inst.failed) continue
            val r = call(inst, fname, js)
            if (r != null) result = r
        }
        return result
    }

    fun onDestroyed(go: GameObject) {
        val list = byNode.remove(go.id) ?: return
        for (inst in list) {
            if (inst.started && !inst.failed) call(inst, "onDestroy")
            instances.remove(inst)
        }
        wrappers.remove(go.id)
    }

    /** Variables of every running script (debugger Variables view). */
    fun variables(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (inst in instances) {
            if (inst.failed) continue
            collectLocals(inst)
            for ((k, v) in inst.locals) out["${inst.go.name}.$k"] = v
        }
        return out
    }

    fun scriptCountFor(go: GameObject) = byNode[go.id]?.size ?: 0

    // ------------------------------------------------------------------ physics callbacks
    private fun dispatchBoth(a: GameObject, b: GameObject, fname: String) {
        if (cx == null) return
        for (inst in (byNode[a.id] ?: emptyList()).toList()) {
            if (inst.started && !inst.failed && inst.comp.enabled) call(inst, fname, toJs(b))
        }
        for (inst in (byNode[b.id] ?: emptyList()).toList()) {
            if (inst.started && !inst.failed && inst.comp.enabled) call(inst, fname, toJs(a))
        }
    }

    override fun onCollisionEnter(a: GameObject, b: GameObject) {
        a.emit(com.sengine.engine.core.Signals.COLLISION, b)
        b.emit(com.sengine.engine.core.Signals.COLLISION, a)
        dispatchBoth(a, b, "onCollision")
    }

    override fun onCollisionExit(a: GameObject, b: GameObject) = dispatchBoth(a, b, "onCollisionExit")

    override fun onTriggerEnter(a: GameObject, b: GameObject) {
        a.emit(com.sengine.engine.core.Signals.TRIGGER, b)
        b.emit(com.sengine.engine.core.Signals.TRIGGER, a)
        dispatchBoth(a, b, "onTrigger")
    }

    override fun onTriggerExit(a: GameObject, b: GameObject) = dispatchBoth(a, b, "onTriggerExit")

    override fun onAreaEnter(area: GameObject, other: GameObject) {
        area.emit(com.sengine.engine.core.Signals.ENTERED, other)
        other.emit(com.sengine.engine.core.Signals.ENTERED, area)
    }

    override fun onAreaExit(area: GameObject, other: GameObject) {
        area.emit(com.sengine.engine.core.Signals.EXITED, other)
        other.emit(com.sengine.engine.core.Signals.EXITED, area)
    }

    companion object {
        /** Rhino interpreted mode gives the fastest startup and the best Android compatibility. */
        const val OPTIMIZATION_LEVEL = -1

        val BUILTIN_NAMES = setOf(
            "self", "node", "transform", "gameObject", "input", "time", "scene", "audio",
            "ui", "physics", "resources", "store", "console", "Math", "JSON", "arguments"
        )

        /** Helpers injected into the global scope before any script runs. */
        val PRELUDE = """
function log() { var s = []; for (var i = 0; i < arguments.length; i++) s.push(String(arguments[i])); console.log(s.join(' ')); }
function print() { log.apply(null, arguments); }
function warn(m) { console.warn(String(m)); }
function error(m) { console.error(String(m)); }
function random(a, b) { if (a === undefined) return Math.random(); return a + Math.random() * (b - a); }
function randomInt(a, b) { return Math.floor(random(a, b + 1)); }
function randomFloat(a, b) { return random(a, b); }
function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
function lerp(a, b, t) { return a + (b - a) * t; }
function sign(v) { return v < 0 ? -1 : (v > 0 ? 1 : 0); }
function abs(v) { return Math.abs(v); }
function min(a, b) { return Math.min(a, b); }
function max(a, b) { return Math.max(a, b); }
function floor(v) { return Math.floor(v); }
function ceil(v) { return Math.ceil(v); }
function round(v) { return Math.round(v); }
function sin(v) { return Math.sin(v); }
function cos(v) { return Math.cos(v); }
function sqrt(v) { return Math.sqrt(v); }
function distance(ax, ay, bx, by) { var dx = ax - bx, dy = ay - by; return Math.sqrt(dx * dx + dy * dy); }
function deg2rad(d) { return d * Math.PI / 180; }
function rad2deg(r) { return r * 180 / Math.PI; }
function angleTo(x1, y1, x2, y2) { return Math.atan2(y2 - y1, x2 - x1) * 180 / Math.PI; }
function moveTowards(current, target, step) { if (current < target) return Math.min(current + step, target); return Math.max(current - step, target); }
function choose() { return arguments[Math.floor(Math.random() * arguments.length)]; }
var __timers = [];
function after(sec, fn) { __timers.push({ t: time.time + sec, f: fn, every: 0 }); }
function every(sec, fn) { __timers.push({ t: time.time + sec, f: fn, every: sec }); }
function clearTimers() { __timers = []; }
function __tick() {
  var now = time.time;
  for (var i = __timers.length - 1; i >= 0; i--) {
    var tm = __timers[i];
    if (now >= tm.t) { if (tm.every > 0) tm.t += tm.every; else __timers.splice(i, 1); tm.f(); }
  }
}
"""
    }
}
