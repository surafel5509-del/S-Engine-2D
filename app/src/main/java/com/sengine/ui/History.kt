package com.sengine.ui

import com.sengine.engine.Engine
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import org.json.JSONObject

/** Snapshot based undo / redo for the edited scene. */
class History(private val engine: Engine) {
    private val undoStack = ArrayDeque<Pair<String, Long>>()
    private val redoStack = ArrayDeque<Pair<String, Long>>()
    var dirty = false

    private fun snapshot(): String = synchronized(engine.lock) { SceneSerializer.toJson(engine.scene).toString() }

    fun record(selected: Long) {
        if (engine.mode != Engine.Mode.EDIT) return
        val s = snapshot()
        if (undoStack.lastOrNull()?.first == s) return
        undoStack.addLast(s to selected)
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        dirty = true
    }

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun undo(selected: Long): Pair<Scene, Long>? {
        val e = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(snapshot() to selected)
        dirty = true
        return SceneSerializer.fromJson(JSONObject(e.first)) to e.second
    }

    fun redo(selected: Long): Pair<Scene, Long>? {
        val e = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(snapshot() to selected)
        dirty = true
        return SceneSerializer.fromJson(JSONObject(e.first)) to e.second
    }

    fun clear() { undoStack.clear(); redoStack.clear() }
}
