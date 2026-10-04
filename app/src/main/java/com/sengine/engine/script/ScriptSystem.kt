package com.sengine.engine.script

import com.sengine.engine.Engine
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.physics.PhysicsWorld
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * JavaScript behaviour runtime backed by Mozilla Rhino (interpreted mode).
 * Every Script component gets its own scope whose prototype is the shared global scope.
 */
class ScriptSystem(val engine: Engine) : PhysicsWorld.Listener {

    private class Instance(val go: GameObject, val comp: ScriptComponent, val scope: Scriptable) {
        var started = false
        var failed = false
    }

    private var cx: Context? = null
    private var global: ScriptableObject? = null
    private var ownerThread: Thread? = null
    private val instances = ArrayList<Instance>()
    private val wrappers = HashMap<Long, SObject>()
    private val compiled = HashMap<String, Script>()
    private val inputApi = SInput(engine)

    val isRunning get() = cx != null

    fun begin() {
        end()
        val c = Context.enter()
        c.optimizationLevel = -1
        c.languageVersion = Context.VERSION_ES6
        // return Java strings / numbers / booleans as native JS values
        c.wrapFactory.isJavaPrimitiveWrap = false
        cx = c
        ownerThread = Thread.currentThread()
        val g = c.initStandardObjects()
        global = g
        inputApi.sync()
        put(g, "input", inputApi)
        put(g, "time", STime(engine))
        put(g, "scene", SScene(engine, this))
        put(g, "audio", SAudio(engine))
        put(g, "console", SConsole(engine))
        c.evaluateString(g, PRELUDE, "prelude", 1, null)
        compiled.clear()
        for (go in engine.scene.objects.toList()) attach(go)
        startPending()
    }

    fun end() {
        if (cx != null && Thread.currentThread() === ownerThread) {
            for (inst in instances.toList()) if (inst.started && !inst.failed) call(inst, "onStop")
            try { Context.exit() } catch (_: Exception) {}
        }
        cx = null
        global = null
        ownerThread = null
        instances.clear()
        wrappers.clear()
        compiled.clear()
    }

    private fun put(scope: Scriptable, name: String, obj: Any) {
        ScriptableObject.putProperty(scope, name, Context.javaToJS(obj, scope))
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

    /** Create script instances for a (newly spawned) object and its children. */
    fun attach(go: GameObject) {
        val c = cx ?: return
        val g = global ?: return
        for (comp in go.components) {
            if (comp !is ScriptComponent || comp.script.isBlank()) continue
            val script = compiled[comp.script] ?: run {
                val src = engine.project.readAsset(comp.script)
                if (src == null) {
                    engine.log(2, "Script not found: ${comp.script} (on ${go.name})")
                    null
                } else try {
                    c.compileString(src, comp.script, 1, null).also { compiled[comp.script] = it }
                } catch (e: RhinoException) {
                    engine.log(2, "${comp.script}:${e.lineNumber()} ${e.details()}")
                    null
                }
            } ?: continue
            val scope = c.newObject(g)
            scope.prototype = g
            scope.parentScope = null
            val self = Context.javaToJS(wrap(go), g)
            ScriptableObject.putProperty(scope, "self", self)
            ScriptableObject.putProperty(scope, "transform", self)
            ScriptableObject.putProperty(scope, "gameObject", self)
            applyParams(scope, comp.params)
            val inst = Instance(go, comp, scope)
            try {
                script.exec(c, scope)
                instances.add(inst)
            } catch (e: RhinoException) {
                engine.log(2, "${comp.script}:${e.lineNumber()} ${e.details()}")
            } catch (e: Exception) {
                engine.log(2, "${comp.script}: ${e.message}")
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

    private fun startPending() {
        for (inst in instances.toList()) {
            if (!inst.started && inst.go.isActiveInHierarchy() && inst.comp.enabled) {
                inst.started = true
                call(inst, "start")
            }
        }
    }

    fun update(dt: Float) {
        if (cx == null) return
        inputApi.sync()
        startPending()
        val dtArg = dt.toDouble()
        val input = engine.input
        var tapTarget: GameObject? = null
        if (input.tapped) tapTarget = engine.physics.overlapPoint(engine.scene, input.touchX, input.touchY)
        for (inst in instances.toList()) {
            if (inst.failed || !inst.started || inst.go.destroyed) continue
            if (!inst.go.isActiveInHierarchy() || !inst.comp.enabled) continue
            call(inst, "update", dtArg)
            if (tapTarget != null && tapTarget === inst.go) call(inst, "onTap")
        }
        val g = global ?: return
        val tick = g.get("__tick", g)
        if (tick is Function) {
            try { tick.call(cx, g, g, emptyArray()) } catch (e: RhinoException) {
                engine.log(2, "timer: line ${e.lineNumber()} ${e.details()}")
            } catch (e: Exception) { engine.log(2, "timer: ${e.message}") }
        }
    }

    private fun call(inst: Instance, fname: String, vararg args: Any?): Any? {
        val f = inst.scope.get(fname, inst.scope)
        if (f !is Function) return null
        return try {
            f.call(cx, inst.scope, inst.scope, arrayOf(*args))
        } catch (e: RhinoException) {
            engine.log(2, "${inst.comp.script}:${e.lineNumber()} in $fname(): ${e.details()}")
            inst.failed = true
            null
        } catch (e: Exception) {
            engine.log(2, "${inst.comp.script} in $fname(): ${e.message}")
            inst.failed = true
            null
        }
    }

    fun sendMessage(go: GameObject, fname: String, arg: Any?): Any? {
        var result: Any? = null
        for (inst in instances.toList()) {
            if (inst.go === go && !inst.failed) result = call(inst, fname, arg) ?: result
        }
        return result
    }

    private fun dispatch(go: GameObject, fname: String, other: GameObject) {
        if (cx == null) return
        val o = toJs(other)
        for (inst in instances.toList()) {
            if (inst.go === go && inst.started && !inst.failed && inst.comp.enabled) call(inst, fname, o)
        }
    }

    fun onDestroyed(go: GameObject) {
        for (inst in instances.toList()) if (inst.go === go) {
            if (inst.started && !inst.failed) call(inst, "onDestroy")
            instances.remove(inst)
        }
        wrappers.remove(go.id)
    }

    override fun onCollisionEnter(a: GameObject, b: GameObject) {
        dispatch(a, "onCollision", b); dispatch(b, "onCollision", a)
    }

    override fun onTriggerEnter(a: GameObject, b: GameObject) {
        dispatch(a, "onTrigger", b); dispatch(b, "onTrigger", a)
    }

    override fun onTriggerExit(a: GameObject, b: GameObject) {
        dispatch(a, "onTriggerExit", b); dispatch(b, "onTriggerExit", a)
    }

    companion object {
        const val PRELUDE = """
function log() { var s = []; for (var i = 0; i < arguments.length; i++) s.push(String(arguments[i])); console.log(s.join(' ')); }
function warn(m) { console.warn(String(m)); }
function error(m) { console.error(String(m)); }
function random(a, b) { if (a === undefined) return Math.random(); return a + Math.random() * (b - a); }
function randomInt(a, b) { return Math.floor(random(a, b + 1)); }
function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
function lerp(a, b, t) { return a + (b - a) * t; }
var __timers = [];
function after(sec, fn) { __timers.push({ t: time.time + sec, f: fn, every: 0 }); }
function every(sec, fn) { __timers.push({ t: time.time + sec, f: fn, every: sec }); }
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
