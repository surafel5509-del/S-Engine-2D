package com.sengine.engine.core

import com.sengine.engine.json.JVal

/**
 * Editor-visible signal bus.
 *
 * Nodes declare signals (e.g. `Button.pressed`, `Player.died`, `Enemy.defeated`) and connect them to
 * handler methods on other nodes. Connections are part of the scene file, are editable in the
 * Signal panel, and are dispatched to script instances first, then to components implementing
 * [SignalListener].
 */
interface SignalListener {
    fun onSignal(name: String, data: Any?): Boolean
}

/** A single `source.signal -> target.method` connection. */
data class SignalConnection(
    var sourceId: Long,
    var signal: String,
    var targetId: Long,
    var method: String,
    var args: String = ""
) {
    fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("source", sourceId)
        o.put("signal", signal)
        o.put("target", targetId)
        o.put("method", method)
        if (args.isNotEmpty()) o.put("args", args)
        return o
    }

    companion object {
        fun fromJson(o: JVal.Obj) = SignalConnection(
            o.l("source"), o.str("signal"), o.l("target"), o.str("method"), o.str("args")
        )
    }
}

/** Standard signal names shipped with the engine so the editor can offer sensible defaults. */
object Signals {
    const val PRESSED = "pressed"
    const val TOGGLED = "toggled"
    const val VALUE_CHANGED = "value_changed"
    const val SUBMITTED = "submitted"
    const val ENTERED = "entered"
    const val EXITED = "exited"
    const val COLLISION = "collision"
    const val TRIGGER = "trigger"
    const val DIED = "died"
    const val DEFEATED = "defeated"
    const val SCORE_CHANGED = "score_changed"
    const val ANIMATION_FINISHED = "animation_finished"
    const val TIMER_TIMEOUT = "timeout"
    const val CUSTOM = "custom"

    val BUILTIN = listOf(
        PRESSED, TOGGLED, VALUE_CHANGED, SUBMITTED,
        ENTERED, EXITED, COLLISION, TRIGGER,
        DIED, DEFEATED, SCORE_CHANGED, ANIMATION_FINISHED, TIMER_TIMEOUT
    )
}

/**
 * Holds every connection in a scene plus the runtime dispatch. Node [GameObject.emit] routes here.
 * A dispatcher is installed by the runtime (script system + component listeners). */
class SignalHub {
    val connections = ArrayList<SignalConnection>()

    @Volatile
    var dispatcher: Dispatcher? = null

    interface Dispatcher {
        /** Returns true when at least one handler consumed the signal. */
        fun dispatch(target: GameObject, method: String, data: Any?, args: String): Boolean
    }

    fun connect(source: GameObject, signal: String, target: GameObject, method: String, args: String = "") {
        if (signal.isBlank() || method.isBlank()) return
        disconnect(source.id, signal, target.id, method)
        connections.add(SignalConnection(source.id, signal, target.id, method, args))
    }

    fun disconnect(sourceId: Long, signal: String, targetId: Long, method: String) {
        connections.removeAll { it.sourceId == sourceId && it.signal == signal && it.targetId == targetId && it.method == method }
    }

    fun connectionsFor(sourceId: Long, signal: String): List<SignalConnection> =
        connections.filter { it.sourceId == sourceId && it.signal == signal }

    fun connectionsOf(sourceId: Long): List<SignalConnection> = connections.filter { it.sourceId == sourceId }
    fun connectionsTo(targetId: Long): List<SignalConnection> = connections.filter { it.targetId == targetId }

    fun removeNode(id: Long) {
        connections.removeAll { it.sourceId == id || it.targetId == id }
    }

    fun clear() = connections.clear()

    /** Deliver a signal. [scene] resolves the target nodes. */
    fun emit(source: GameObject, signal: String, data: Any?, scene: Scene?): Boolean {
        val direct = source.components.filterIsInstance<SignalListener>()
        var handled = false
        for (l in direct) if (l.onSignal(signal, data)) handled = true
        val d = dispatcher
        val list = connectionsFor(source.id, signal)
        if (list.isEmpty()) return handled
        for (c in list) {
            val target = scene?.findById(c.targetId) ?: continue
            if (!target.isActiveInHierarchy()) continue
            var ok = false
            for (comp in target.components) {
                if (comp is SignalListener && comp.enabled) ok = comp.onSignal(c.method, data) || ok
            }
            if (d != null) ok = d.dispatch(target, c.method, data, c.args) || ok
            handled = handled || ok
        }
        return handled
    }

    fun snapshot(): List<SignalConnection> = connections.map { it.copy() }
}
