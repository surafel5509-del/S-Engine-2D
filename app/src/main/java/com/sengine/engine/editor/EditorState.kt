package com.sengine.engine.editor

import com.sengine.engine.math.M
import com.sengine.engine.math.Vec2
import com.sengine.engine.render.Camera2DView

/** Viewport tools. Every tool has a real implementation in the viewport controller. */
enum class ToolState(val label: String, val icon: String, val shortcut: String) {
    SELECT("Select", "▣", "Q"),
    MOVE("Move", "✥", "W"),
    ROTATE("Rotate", "⟳", "E"),
    SCALE("Scale", "⤢", "R"),
    PIVOT("Pivot", "⊕", "T"),
    RECT("Rectangle", "▭", "4"),
    TILE("Tile Paint", "▩", "5"),
    UI("UI Edit", "▤", "6");

    companion object {
        val ALL = values().toList()
        fun of(index: Int) = ALL[index.coerceIn(0, ALL.size - 1)]
    }
}

/** Snapping configuration shared by the viewport, the UI editor and the tile painter. */
class SnapSettings {
    var enabled = true
    var step = 0.25f
    var gridStep = 1f
    var snapToGrid = true
    var snapToObjects = false
    var snapAngle = 15f
    var snapEnabledForRotation = true

    fun snap(v: Float): Float = if (enabled && snapToGrid) M.snap(v, step) else v
    fun snapVec(v: Vec2) = Vec2(snap(v.x), snap(v.y))
    fun snapAngle(v: Float): Float =
        if (enabled && snapEnabledForRotation && snapAngle > 0f) M.snap(v, snapAngle) else v
}

/**
 * Viewport editor state. Lives in the core (not the Android view) so tools, gizmos, overlays and
 * the tile brush all share exactly one source of truth — the same state a future desktop build
 * would use.
 */
class EditorState {
    val view = Camera2DView()
    val snap = SnapSettings()
    val tileBrush = TileBrushState()

    @Volatile var tool = ToolState.SELECT
    @Volatile var activeAxis = 0            // 0 none, 1 x, 2 y, 3 free/plane
    @Volatile var transformSpace = 0        // 0 world, 1 local
    @Volatile var showGrid = true
    @Volatile var showColliders = true
    @Volatile var showCameras = true
    @Volatile var showTileGrid = true
    @Volatile var showUIBounds = true
    @Volatile var showAudioAreas = false
    @Volatile var showPhysicsDebug = false
    @Volatile var showPixelGrid = false
    @Volatile var showGuides = true
    @Volatile var pixelPerfectPreview = false
    @Volatile var gridStep = 1f
    @Volatile var pixelsPerUnit = 100f
    @Volatile var selectedId = -1L
    @Volatile var selectionIds: List<Long> = emptyList()
    @Volatile var hoveredTile = -1
    @Volatile var mouseWorldX = 0f
    @Volatile var mouseWorldY = 0f
    @Volatile var statusText = ""
    @Volatile var resolutionPreview = 0     // index into PREVIEW_SIZES, 0 = fit viewport
    @Volatile var frameSelectedPending = false

    /** Marquee rectangle in world space while a rectangular selection is in progress. */
    @Volatile var selectionRect: FloatArray? = null

    /** Node under the mouse pointer (hover highlight drawn by the overlay). */
    @Volatile var hoveredNodeId = -1L

    /** True while a multi-select modifier (Shift/Ctrl) is held — touch users use long press instead. */
    @Volatile var multiSelect = false

    /** Guides are grabbable in the viewport; the brush marks the tool that owns the paint cursor. */
    @Volatile var hoveredTileX = 0
    @Volatile var hoveredTileY = 0

    /** Free-floating guides drawn in the viewport (world space segments). */
    val guides = ArrayList<FloatArray>()

    var activeTileLayer = 0

    fun gizmoLength(): Float {
        val scale = (view.heightPx / 1080f).coerceAtLeast(0.45f)
        return 80f * scale / view.pixelsPerUnit.coerceAtLeast(0.0001f)
    }

    fun overlay(overlay: com.sengine.engine.EditorOverlay) {
        overlay.showGrid = showGrid
        overlay.gridStep = gridStep
        overlay.snapStep = snap.step
        overlay.showColliders = showColliders
        overlay.showCameras = showCameras
        overlay.showTileGrid = showTileGrid
        overlay.showUIBounds = showUIBounds
        overlay.showAudioAreas = showAudioAreas
        overlay.drawPhysicsDebug = showPhysicsDebug
        overlay.showPixelGrid = showPixelGrid
        overlay.tool = tool.ordinal
        overlay.activeAxis = activeAxis
        overlay.selectionIds = selectionIds
        overlay.primaryId = selectedId
        overlay.gizmoScale = (view.size / 6f).coerceIn(0.4f, 4f)
        overlay.pixelGridStep = 1f / pixelsPerUnit.coerceAtLeast(1f)
        overlay.guides = if (showGuides) guides else emptyList()
        overlay.selectionRect = selectionRect
        overlay.hoveredId = hoveredNodeId
        overlay.showCanvasFrame = true
        overlay.canvasWidth = view.widthPx.toFloat() / view.pixelsPerUnit.coerceAtLeast(0.0001f)
        overlay.canvasHeight = view.heightPx.toFloat() / view.pixelsPerUnit.coerceAtLeast(0.0001f)
    }

    companion object {
        /** Resolution previews offered by the UI editor / viewport. */
        val PREVIEW_SIZES = listOf(
            Triple("Fit", 0, 0),
            Triple("16:9 720p", 1280, 720),
            Triple("16:9 1080p", 1920, 1080),
            Triple("Portrait 9:16", 720, 1280),
            Triple("Square", 1080, 1080),
            Triple("Pixel 240p", 426, 240),
            Triple("Tablet 4:3", 1024, 768)
        )
    }
}
