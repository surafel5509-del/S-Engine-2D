package com.sengine.engine.render

enum class Tool { HAND, MOVE, ROTATE, SCALE }

/** State shared between the editor UI thread and the GL thread. */
class EditorState {
    val view = View2D()
    @Volatile var selectedId = -1L
    @Volatile var tool = Tool.MOVE
    @Volatile var showGrid = true
    @Volatile var showColliders = true
    @Volatile var activeAxis = 0 // 0 none, 1 x, 2 y, 3 free

    /** Gizmo length in world units. */
    fun gizmoLength() = 90f * (view.heightPx / 1080f).coerceAtLeast(0.6f) / view.pixelsPerUnit
}
