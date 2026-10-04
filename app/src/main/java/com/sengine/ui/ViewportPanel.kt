package com.sengine.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.sengine.engine.EditorOverlay
import com.sengine.engine.Engine
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Label2D
import com.sengine.engine.core.Sprite2D
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.editor.EditorDocument
import com.sengine.engine.editor.EditorState
import com.sengine.engine.editor.TileStroke
import com.sengine.engine.editor.ToolState
import com.sengine.engine.editor.TransformCommand
import com.sengine.engine.math.Rect2
import com.sengine.engine.math.Vec2
import com.sengine.engine.tilemap.TileBrush
import com.sengine.platform.android.AndroidTextRenderer
import com.sengine.platform.android.AndroidTextures
import com.sengine.platform.gl.GLRenderer2D
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The 2D viewport: the real engine frame plus every editor tool.
 *
 * Interaction (touch and mouse share one code path):
 *  - **Select** — tap a node to select it (children win over parents, locked/hidden nodes are
 *    skipped), tap empty space to deselect, drag to pan the view.
 *  - **Move / Rotate / Scale / Pivot** — drag the selection. Snapping, multi-selection and undo all
 *    work: one gesture is one undo step.
 *  - **Rectangle** — drag a marquee to select every node it touches.
 *  - **Tile paint** — paint / erase / fill / line / rect / pick with the brush from the TileMap panel.
 *  - **UI edit** — drag a control to move it, drag its bottom-right handle to resize it (anchors and
 *    offsets are written back, so the change is a real edit).
 *  - Two fingers: pinch zoom around the pinch point + pan. Wheel/middle-drag: same on desktop.
 *  - Right click / long press opens the node context menu; arrow keys nudge the selection.
 */
@SuppressLint("ViewConstructor")
class ViewportPanel(
    context: Context,
    val doc: EditorDocument,
    val engine: Engine,
    val theme: Theme,
    val state: EditorState = EditorState()
) : FrameLayout(context) {

    var onStatus: ((String) -> Unit)? = null
    var onContextMenu: ((Float, Float) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null
    var onToolFinished: ((String) -> Unit)? = null
    var onTilePainted: (() -> Unit)? = null
    var onZoomChanged: (() -> Unit)? = null

    private val glView = GLSurfaceView(context)
    private val textures: AndroidTextures
    private val textRenderer: AndroidTextRenderer
    private val renderer: SceneGlRenderer

    // gesture state
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false
    private var pinching = false
    private var pinchStartDistance = 0f
    private var pinchAnchor = Vec2(0f, 0f)
    private var pinchStartSize = 5f
    private var draggingGuide = -1
    private var uiResizing = false
    private var marqueeFrom = Vec2(0f, 0f)
    private var marqueeTo = Vec2(0f, 0f)
    private var tileStrokeStart: Pair<Int, Int>? = null
    private var tileStroke: TileStroke? = null
    private var tileStrokeSize = 0
    private var transformBefore: Map<Long, FloatArray> = HashMap()
    private var transformActive = false
    private var gestureChangedScene = false
    private var uiEditStart = FloatArray(6)
    private var surfaceReady = false
    private var framedOnce = false

    init {
        textures = AndroidTextures { name -> doc.project.assetFile(name).takeIf { it.exists() } }
        textRenderer = AndroidTextRenderer(textures)
        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true
        renderer = SceneGlRenderer()
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.isFocusable = true
        glView.isFocusableInTouchMode = true
        addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        state.view.pixelSnap = doc.project.settings.pixelPerfect
        doc.listeners.add(object : EditorDocument.Listener {
            override fun onSceneChanged(scene: com.sengine.engine.core.Scene) { publish() }
            override fun onStructureChanged() { publish() }
            override fun onSelectionChanged() { publish() }
            override fun onDirtyChanged(dirty: Boolean) {}
        })
    }

    fun publish() {
        engine.post { engine.replaceScene(doc.scene) }
    }

    fun onResume() = glView.onResume()
    fun onPause() = glView.onPause()

    fun dispose() {
        textures.releaseAll()
        textRenderer.invalidate()
    }

    fun requestRender() = glView.requestRender()

    // ------------------------------------------------------------------ camera

    /** Screen pixels per world unit — "zoom 100%" means sprites authored at 100 ppu draw 1:1. */
    fun zoomPercent(): Float = state.view.pixelsPerUnit

    /**
     * Zooms while keeping the world point under ([screenX], [screenY]) fixed, so zooming never
     * slides the scene out from under the cursor.
     */
    fun zoomAround(factor: Float, screenX: Float, screenY: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        val view = state.view
        if (view.widthPx <= 1 || view.heightPx <= 1) return
        val wx = view.screenToWorldX(screenX)
        val wy = view.screenToWorldY(screenY)
        val newSize = (view.size / factor).coerceIn(MIN_SIZE, MAX_SIZE)
        if (newSize == view.size) return
        view.size = newSize
        view.cx = wx - (screenX / view.widthPx * 2f - 1f) * view.halfWidth
        view.cy = wy - (1f - screenY / view.heightPx * 2f) * view.halfHeight
        afterCameraChange()
    }

    fun zoomAtCenter(factor: Float) =
        zoomAround(factor, state.view.widthPx * 0.5f, state.view.heightPx * 0.5f)

    /** 100% = design pixels map 1:1 to screen pixels. */
    fun setZoomPercent(percent: Float) {
        val target = percent.coerceIn(2f, 100000f)
        val current = state.view.pixelsPerUnit
        if (current <= 0.0001f) return
        zoomAtCenter(target / current)
    }

    /** Back to the project's 1:1 zoom (used by the zoom readout in the viewport toolbar). */
    fun resetZoom() {
        state.view.size = defaultViewSize()
        afterCameraChange()
    }

    private fun defaultViewSize(): Float =
        max(doc.project.settings.windowHeight, 64) / 2f / 100f

    private fun afterCameraChange() {
        onZoomChanged?.invoke()
        requestRender()
    }

    /** Frames the whole scene (or the design resolution when the scene is empty). */
    fun frameScene() {
        val view = state.view
        if (glView.width > 0 && glView.height > 0) {
            view.widthPx = glView.width
            view.heightPx = glView.height
        }
        if (view.widthPx <= 1) { framedOnce = false; return }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var count = 0
        for (go in doc.scene.objects) {
            val b = worldBounds(go) ?: continue
            minX = min(minX, b[0]); minY = min(minY, b[1])
            maxX = max(maxX, b[0] + b[2]); maxY = max(maxY, b[1] + b[3])
            count++
        }
        if (count == 0) {
            val w = doc.project.settings.windowWidth / 100f
            val h = doc.project.settings.windowHeight / 100f
            minX = -w / 2; minY = -h / 2; maxX = w / 2; maxY = h / 2
            view.cx = 0f; view.cy = 0f
        } else {
            view.cx = (minX + maxX) * 0.5f
            view.cy = (minY + maxY) * 0.5f
        }
        val spanX = max(maxX - minX, 0.5f)
        val spanY = max(maxY - minY, 0.5f)
        val aspect = view.aspect.coerceAtLeast(0.2f)
        view.size = max(spanY * 0.5f / 0.85f, spanX * 0.5f / aspect / 0.85f).coerceIn(MIN_SIZE, MAX_SIZE)
        framedOnce = true
        afterCameraChange()
        onStatus?.invoke("Framed $count node(s)")
    }

    fun focusSelection() {
        val ids = state.selectionIds
        if (ids.isEmpty()) {
            frameScene()
            return
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var found = false
        for (id in ids) {
            val node = doc.scene.findById(id) ?: continue
            val b = worldBounds(node) ?: floatArrayOf(node.world.tx, node.world.ty, 0.2f, 0.2f)
            minX = min(minX, b[0]); minY = min(minY, b[1])
            maxX = max(maxX, b[0] + b[2]); maxY = max(maxY, b[1] + b[3])
            found = true
        }
        if (!found) return
        val view = state.view
        view.cx = (minX + maxX) * 0.5f
        view.cy = (minY + maxY) * 0.5f
        val spanX = max(maxX - minX, 0.5f)
        val spanY = max(maxY - minY, 0.5f)
        val aspect = view.aspect.coerceAtLeast(0.2f)
        view.size = max(spanY * 0.5f / 0.75f, spanX * 0.5f / aspect / 0.75f).coerceIn(MIN_SIZE, MAX_SIZE)
        afterCameraChange()
        onStatus?.invoke("Focused ${ids.size} node(s)")
    }

    /** Selects a node (scene tree click) and pans to it when it is off screen. */
    fun focusNode(id: Long) {
        selectSingle(id, announce = true)
        val node = doc.scene.findById(id) ?: return
        val view = state.view
        val marginX = view.halfWidth * 0.2f
        val marginY = view.halfHeight * 0.2f
        val inside = node.world.tx > view.cx - view.halfWidth + marginX &&
            node.world.tx < view.cx + view.halfWidth - marginX &&
            node.world.ty > view.cy - view.halfHeight + marginY &&
            node.world.ty < view.cy + view.halfHeight - marginY
        if (!inside) {
            view.cx = node.world.tx
            view.cy = node.world.ty
        }
        afterCameraChange()
    }

    /** Cancels any in-flight gesture (tool switched mid-drag, back button, …). */
    fun cancelTool() {
        dragging = false
        panning = false
        pinching = false
        draggingGuide = -1
        uiResizing = false
        tileStrokeStart = null
        tileStroke = null
        transformActive = false
        state.selectionRect = null
        requestRender()
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val view = state.view
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = event.x; dragStartY = event.y
                lastX = event.x; lastY = event.y
                dragging = false; pinching = false; panning = false
                beginGesture(event.x, event.y)
                updatePointer(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinching = true
                panning = false
                dragging = false
                draggingGuide = -1
                tileStrokeStart = null
                tileStroke = null
                state.selectionRect = null
                pinchStartDistance = pointerDistance(event)
                pinchStartSize = view.size
                pinchAnchor = Vec2((event.getX(0) + event.getX(1)) * 0.5f, (event.getY(0) + event.getY(1)) * 0.5f)
                lastX = pinchAnchor.x; lastY = pinchAnchor.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && event.pointerCount >= 2) {
                    val d = pointerDistance(event)
                    if (pinchStartDistance > 1f && d > 1f) {
                        val target = (pinchStartSize * pinchStartDistance / d).coerceIn(MIN_SIZE, MAX_SIZE)
                        if (view.size > 0.0001f && target > 0.0001f) zoomAround(view.size / target, pinchAnchor.x, pinchAnchor.y)
                    }
                    val mx = (event.getX(0) + event.getX(1)) * 0.5f
                    val my = (event.getY(0) + event.getY(1)) * 0.5f
                    panCamera(mx - lastX, my - lastY)
                    lastX = mx; lastY = my
                    updatePointer(event.x, event.y)
                    return true
                }
                val dxPix = event.x - lastX
                val dyPix = event.y - lastY
                if (!dragging && hypot(event.x - dragStartX, event.y - dragStartY) > theme.dp(5f)) dragging = true
                if (dragging) {
                    if (!applyDrag(dxPix, dyPix, event)) panning = true
                    if (panning) panCamera(dxPix, dyPix)
                }
                lastX = event.x; lastY = event.y
                updatePointer(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !pinching) tap(event.x, event.y)
                else if (state.selectionRect != null) commitMarquee()
                endGesture()
                pinching = false
                panning = false
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                endGesture()
                cancelTool()
                return true
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (scroll != 0f) {
                        zoomAround(if (scroll > 0f) 1.12f else 1f / 1.12f, event.x, event.y)
                        return true
                    }
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    hoverAt(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_BUTTON_PRESS -> {
                    if (event.actionButton == MotionEvent.BUTTON_SECONDARY) {
                        val wx = state.view.screenToWorldX(event.x)
                        val wy = state.view.screenToWorldY(event.y)
                        nodeAt(wx, wy)?.let { selectNode(it, additive = false) }
                        onContextMenu?.invoke(event.x, event.y)
                        return true
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Arrow keys nudge the selection (with Shift: one grid step at a time). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = if (event.isShiftPressed) max(state.snap.step, 0.25f) else max(state.snap.step / 4f, 0.01f)
        val dx = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> -step
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> step
            else -> 0f
        }
        val dy = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> -step
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> step
            else -> 0f
        }
        if (dx == 0f && dy == 0f) return super.onKeyDown(keyCode, event)
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return super.onKeyDown(keyCode, event)
        val before = snapshotTransforms(nodes)
        for (node in nodes) node.translate(dx, dy)
        doc.undo.push(doc, TransformCommand.between("Nudge", before, snapshotTransforms(nodes)), execute = false)
        doc.onSceneMutated()
        onStatus?.invoke("Nudged ${nodes.size} node(s) by $step")
        requestRender()
        return true
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun panCamera(dx: Float, dy: Float) {
        val ppu = state.view.pixelsPerUnit
        if (ppu <= 0.0001f) return
        state.view.cx -= dx / ppu
        state.view.cy += dy / ppu
        requestRender()
    }

    private fun updatePointer(sx: Float, sy: Float) {
        if (state.view.widthPx <= 1) return
        state.mouseWorldX = state.view.screenToWorldX(sx)
        state.mouseWorldY = state.view.screenToWorldY(sy)
        onStatus?.invoke("x %.2f   y %.2f   zoom %.0f%%   %s".format(
            state.mouseWorldX, state.mouseWorldY, zoomPercent(), state.tool.label))
    }

    private fun hoverAt(sx: Float, sy: Float) {
        val node = nodeAt(state.view.screenToWorldX(sx), state.view.screenToWorldY(sy))
        val id = node?.id ?: -1L
        if (id != state.hoveredNodeId) {
            state.hoveredNodeId = id
            requestRender()
        }
    }

    private fun selectedNodes(): List<GameObject> = state.selectionIds.mapNotNull { doc.scene.findById(it) }

    private fun snapshotTransforms(nodes: List<GameObject>): Map<Long, FloatArray> {
        val out = HashMap<Long, FloatArray>(nodes.size)
        for (n in nodes) out[n.id] = floatArrayOf(n.x, n.y, n.rotation, n.scaleX, n.scaleY)
        return out
    }

    private fun selectionCenter(nodes: List<GameObject>): Vec2 {
        var x = 0f; var y = 0f
        for (n in nodes) { x += n.world.tx; y += n.world.ty }
        val count = nodes.size.coerceAtLeast(1)
        return Vec2(x / count, y / count)
    }

    // ------------------------------------------------------------------ gestures

    private fun beginGesture(sx: Float, sy: Float) {
        val wx = state.view.screenToWorldX(sx)
        val wy = state.view.screenToWorldY(sy)
        state.hoveredNodeId = -1L
        draggingGuide = -1
        uiResizing = false
        tileStrokeStart = null
        tileStroke = null
        transformActive = false
        gestureChangedScene = false

        if (state.tool == ToolState.TILE) {
            beginTileGesture(wx, wy)
            return
        }
        if (state.showGuides && grabGuide(wx, wy)) return

        if (state.tool == ToolState.UI) {
            val ui = uiNodeAt(sx, sy, wx, wy)
            if (ui != null) {
                selectNode(ui, state.multiSelect)
                val control = ui.getAny<com.sengine.engine.ui.ControlComponent>()
                if (control != null) {
                    uiResizing = isResizeHandle(ui, control.rect, sx, sy)
                    uiEditStart = floatArrayOf(
                        control.ui.offsetLeft, control.ui.offsetTop, control.ui.offsetRight, control.ui.offsetBottom,
                        ui.x, ui.y
                    )
                    transformBefore = snapshotTransforms(selectedNodes())
                    transformActive = true
                    onStatus?.invoke(if (uiResizing) "Resizing ${ui.name}" else "Moving ${ui.name}")
                    return
                }
            }
        }

        val node = nodeAt(wx, wy)
        if (node != null) {
            if (!state.selectionIds.contains(node.id)) selectNode(node, state.multiSelect)
            val nodes = selectedNodes()
            if (nodes.isNotEmpty() && state.tool != ToolState.SELECT && state.tool != ToolState.RECT) {
                transformBefore = snapshotTransforms(nodes)
                transformActive = true
            }
            return
        }
        if (state.multiSelect) return   // keep the selection when shift-clicking empty space
        clearSelection()
    }

    /** Returns true when the drag was consumed by the active tool. */
    private fun applyDrag(dxPix: Float, dyPix: Float, event: MotionEvent): Boolean {
        if (draggingGuide >= 0) {
            val g = state.guides.getOrNull(draggingGuide) ?: return true
            val dx = dxPix / state.view.pixelsPerUnit
            val dy = dyPix / state.view.pixelsPerUnit
            if (abs(g[2] - g[0]) < 0.0001f) { g[0] += dx; g[2] += dx } else { g[1] += dy; g[3] += dy }
            gestureChangedScene = true
            requestRender()
            return true
        }
        if (state.tool == ToolState.TILE) {
            paintTileAt(event.x, event.y, event.actionMasked)
            return true
        }
        if (state.tool == ToolState.RECT) {
            marqueeTo = Vec2(state.view.screenToWorldX(event.x), state.view.screenToWorldY(event.y))
            marqueeFrom = Vec2(state.view.screenToWorldX(dragStartX), state.view.screenToWorldY(dragStartY))
            state.selectionRect = floatArrayOf(
                min(marqueeFrom.x, marqueeTo.x), min(marqueeFrom.y, marqueeTo.y),
                abs(marqueeTo.x - marqueeFrom.x), abs(marqueeTo.y - marqueeFrom.y)
            )
            requestRender()
            return true
        }
        val nodes = selectedNodes()
        if (nodes.isEmpty() || !transformActive) return false
        gestureChangedScene = true
        when (state.tool) {
            ToolState.MOVE -> moveSelection(nodes, event.x, event.y)
            ToolState.ROTATE -> rotateSelection(nodes, event.x, event.y)
            ToolState.SCALE -> scaleSelection(nodes, dxPix, dyPix)
            ToolState.PIVOT -> pivotSelection(nodes)
            ToolState.UI -> editUiControl(nodes, event.x, event.y)
            else -> return false
        }
        return true
    }

    private fun moveSelection(nodes: List<GameObject>, screenX: Float, screenY: Float) {
        val view = state.view
        var dx = view.screenToWorldX(screenX) - view.screenToWorldX(dragStartX)
        var dy = view.screenToWorldY(screenY) - view.screenToWorldY(dragStartY)
        if (state.activeAxis == 2) dx = 0f
        if (state.activeAxis == 1) dy = 0f
        for (node in nodes) {
            val start = transformBefore[node.id] ?: continue
            var nx = start[0] + dx
            var ny = start[1] + dy
            if (state.snap.enabled) {
                nx = state.snap.snap(nx)
                ny = state.snap.snap(ny)
            }
            node.setPosition(nx, ny)
        }
        val primary = nodes.firstOrNull { it.id == state.selectedId } ?: nodes.first()
        doc.onSceneMutated()
        onStatus?.invoke("Move: %.2f, %.2f".format(primary.x, primary.y))
    }

    private fun rotateSelection(nodes: List<GameObject>, screenX: Float, screenY: Float) {
        val view = state.view
        val pivot = selectionCenter(nodes)
        val px = view.worldToScreenX(pivot.x)
        val py = view.worldToScreenY(pivot.y)
        val startAngle = Math.toDegrees(atan2((dragStartY - py).toDouble(), (dragStartX - px).toDouble())).toFloat()
        var angle = Math.toDegrees(atan2((screenY - py).toDouble(), (screenX - px).toDouble())).toFloat()
        var delta = angle - startAngle
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        angle = startAngle + delta
        if (state.snap.enabled && state.snap.snapEnabledForRotation) angle = state.snap.snapAngle(angle)
        for (node in nodes) {
            val start = transformBefore[node.id] ?: continue
            node.rotation = start[2] + (angle - startAngle)
            node.computeWorld()
        }
        doc.onSceneMutated()
        onStatus?.invoke("Rotate: %.1f°".format(nodes.first().rotation))
    }

    private fun scaleSelection(nodes: List<GameObject>, dxPix: Float, dyPix: Float) {
        val perPixel = 1f / max(theme.dp(160f).toFloat(), 1f)
        val factor = (1f + (dxPix + dyPix) * 0.5f * perPixel).coerceIn(0.02f, 50f)
        for (node in nodes) {
            val start = transformBefore[node.id] ?: continue
            node.scaleX = (start[3] * factor).coerceAtLeast(0.001f)
            node.scaleY = (start[4] * factor).coerceAtLeast(0.001f)
            node.computeWorld()
        }
        doc.onSceneMutated()
        onStatus?.invoke("Scale: ×%.2f".format(nodes.first().scaleX))
    }

    private fun pivotSelection(nodes: List<GameObject>) {
        for (node in nodes) {
            val inv = node.computeWorld().inverted() ?: continue
            val lx = inv.mapX(state.mouseWorldX, state.mouseWorldY)
            val ly = inv.mapY(state.mouseWorldX, state.mouseWorldY)
            val size = localSize(node)
            node.pivotX = (0.5f + lx / size.first).coerceIn(-2f, 3f)
            node.pivotY = (0.5f + ly / size.second).coerceIn(-2f, 3f)
        }
        doc.onSceneMutated()
        val n = nodes.first()
        onStatus?.invoke("Pivot: %.3f, %.3f".format(n.pivotX, n.pivotY))
    }

    /** Moves/resizes a UI control by writing its anchors and offsets — a real layout edit. */
    private fun editUiControl(nodes: List<GameObject>, screenX: Float, screenY: Float) {
        val node = nodes.firstOrNull() ?: return
        val control = node.getAny<com.sengine.engine.ui.ControlComponent>() ?: return
        val ui = control.ui
        val scale = uiScale()
        val dx = (screenX - dragStartX) / scale.first
        val dy = (screenY - dragStartY) / scale.second
        if (uiResizing) {
            ui.offsetRight = uiEditStart[2] + dx
            ui.offsetBottom = uiEditStart[3] + dy
            if (state.snap.enabled) {
                ui.offsetRight = state.snap.snap(ui.offsetRight * 100f) / 100f
                ui.offsetBottom = state.snap.snap(ui.offsetBottom * 100f) / 100f
            }
        } else {
            node.setPosition(uiEditStart[4] + dx / 100f, uiEditStart[5] - dy / 100f)
            ui.offsetLeft = uiEditStart[0] + dx
            ui.offsetTop = uiEditStart[1] + dy
            if (state.snap.enabled) {
                ui.offsetLeft = state.snap.snap(ui.offsetLeft * 100f) / 100f
                ui.offsetTop = state.snap.snap(ui.offsetTop * 100f) / 100f
            }
        }
        // layout immediately so the control follows the cursor instead of a frame later
        com.sengine.engine.ui.UiLayout.layout(doc.scene, engine.ui.designWidth, engine.ui.designHeight)
        doc.onSceneMutated()
        onStatus?.invoke("UI ${node.name}: offsets %.0f, %.0f → %.0f, %.0f".format(
            ui.offsetLeft, ui.offsetTop, ui.offsetRight, ui.offsetBottom))
    }

    /** Design-space → screen pixels scale used by the UI system. */
    private fun uiScale(): Pair<Float, Float> {
        val view = state.view
        return (view.widthPx / engine.ui.designWidth.coerceAtLeast(1f)) to
            (view.heightPx / engine.ui.designHeight.coerceAtLeast(1f))
    }

    private fun isResizeHandle(node: GameObject, rect: Rect2, sx: Float, sy: Float): Boolean {
        val scale = uiScale()
        val right = rect.right * scale.first
        val bottom = rect.bottom * scale.second
        val tolerance = theme.dp(16f).toFloat()
        return abs(sx - right) < tolerance && abs(sy - bottom) < tolerance
    }

    private fun grabGuide(wx: Float, wy: Float): Boolean {
        val tolerance = 12f / state.view.pixelsPerUnit.coerceAtLeast(0.0001f)
        for ((i, g) in state.guides.withIndex()) {
            val vertical = abs(g[2] - g[0]) < 0.0001f
            val hit = if (vertical) abs(g[0] - wx) < tolerance else abs(g[1] - wy) < tolerance
            if (hit) {
                draggingGuide = i
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ tile painting

    private fun beginTileGesture(wx: Float, wy: Float) {
        val tm = tileMapNode() ?: return
        val brush = state.tileBrush
        val layer = brush.layerIndex.coerceIn(0, max(tm.data.layers.size - 1, 0))
        val (cx, cy) = tm.worldToCell(wx, wy)
        if (brush.mode == TileBrush.Mode.PICK) {
            val tile = tm.currentTile(layer, cx, cy)
            if (tile > 0) {
                brush.tileId = tile
                onStatus?.invoke("Picked tile $tile")
            }
            return
        }
        tileStrokeStart = cx to cy
        strokeLayer = layer
        tileStroke = newStroke(tm, layer)
        strokeMap = tm
        paintTileAt(wx, wy, MotionEvent.ACTION_MOVE, alreadyWorld = true)
    }

    private var strokeLayer = 0
    private var strokeMap: TileMap2D? = null

    private fun paintTileAt(sx: Float, sy: Float, action: Int, alreadyWorld: Boolean = false) {
        val tm = strokeMap ?: tileMapNode() ?: return
        val brush = state.tileBrush
        val layerIndex = if (strokeMap != null) strokeLayer else brush.layerIndex.coerceIn(0, max(tm.data.layers.size - 1, 0))
        val layer = tm.data.layers.getOrNull(layerIndex) ?: return
        if (tileStroke == null) {
            tileStroke = newStroke(tm, layerIndex)
            strokeLayer = layerIndex
            strokeMap = tm
                val (fx, fy) = tm.worldToCell(state.view.screenToWorldX(dragStartX), state.view.screenToWorldY(dragStartY))
            tileStrokeStart = fx to fy
        }
        val wx: Float
        val wy: Float
        if (alreadyWorld) {
            wx = sx; wy = sy
        } else {
            wx = state.view.screenToWorldX(sx)
            wy = state.view.screenToWorldY(sy)
        }
        val (cx, cy) = tm.worldToCell(wx, wy)
        val tile = tileForBrush(brush.mode)
        val before = if (layer.inBounds(cx, cy)) layer[cx, cy] else 0
        var changed = 0
        when (brush.mode) {
            TileBrush.Mode.ERASE -> changed = tm.paintBrush(layerIndex, cx, cy, 0, brush.brushSize)
            TileBrush.Mode.RECT -> {
                val start = tileStrokeStart ?: (cx to cy)
                // repaint from the captured stroke each move: cheap enough for a rect preview
                restoreStrokeLayer(tm, layerIndex)
                changed = tm.drawRect(layerIndex, start.first, start.second, cx, cy, tile, brush.filledRect)
            }
            TileBrush.Mode.LINE -> {
                val start = tileStrokeStart ?: (cx to cy)
                restoreStrokeLayer(tm, layerIndex)
                changed = tm.drawLine(layerIndex, start.first, start.second, cx, cy, tile, brush.brushSize, brush.random)
            }
            TileBrush.Mode.FILL -> changed = tm.floodFill(layerIndex, cx, cy, tile, brush.random)
            else -> changed = tm.paintBrush(layerIndex, cx, cy, tile, brush.brushSize, brush.random)
        }
        if (brush.autotile && changed > 0) {
            changed += tm.applyAutotile(layerIndex, cx, cy, radius = max(brush.brushSize, 1))
        }
        if (changed > 0) {
            val after = if (layer.inBounds(cx, cy)) layer[cx, cy] else 0
            tileStroke?.record(layer.index(cx, cy), before, after)
            tileStrokeSize += changed
            state.hoveredTileX = cx
            state.hoveredTileY = cy
            gestureChangedScene = true
            onTilePainted?.invoke()
            doc.onSceneMutated()
            onStatus?.invoke("Painted ${layer.name} at $cx, $cy")
        }
        requestRender()
    }

    /** Restores the layer to its pre-stroke state so RECT/LINE previews do not smear. */
    private fun restoreStrokeLayer(tm: TileMap2D, layerIndex: Int) {
        val snapshot = preStrokeCells ?: return
        val layer = tm.data.layers.getOrNull(layerIndex) ?: return
        if (snapshot.size != layer.cells.size) return
        System.arraycopy(snapshot, 0, layer.cells, 0, snapshot.size)
    }

    private var preStrokeCells: IntArray? = null

    private fun newStroke(tm: TileMap2D, layerIndex: Int): TileStroke {
        val layer = tm.data.layers.getOrNull(layerIndex)
        preStrokeCells = layer?.cells?.copyOf()
        return TileStroke(tm.gameObject.id, layer?.name ?: "", layer?.width ?: 1)
    }

    private fun tileForBrush(mode: TileBrush.Mode): Int {
        val brush = state.tileBrush
        if (mode == TileBrush.Mode.ERASE) return 0
        return brush.tileId.coerceAtLeast(1)
    }

    // ------------------------------------------------------------------ selection

    private fun tap(sx: Float, sy: Float) {
        val wx = state.view.screenToWorldX(sx)
        val wy = state.view.screenToWorldY(sy)
        if (state.tool == ToolState.TILE) {
            paintTileAt(sx, sy, MotionEvent.ACTION_DOWN)
            return
        }
        if (state.tool == ToolState.UI) {
            val ui = uiNodeAt(sx, sy, wx, wy)
            if (ui != null) {
                selectNode(ui, state.multiSelect)
                return
            }
        }
        val node = nodeAt(wx, wy)
        if (node == null) {
            if (!state.multiSelect) clearSelection()
        } else {
            selectNode(node, state.multiSelect)
        }
    }

    private fun commitMarquee() {
        val rect = state.selectionRect ?: return
        state.selectionRect = null
        if (rect[2] < 0.01f && rect[3] < 0.01f) return
        val hits = ArrayList<Long>()
        for (go in doc.scene.objects) {
            if (go.destroyed || go.locked || !go.visible || !go.isVisibleInHierarchy()) continue
            val b = worldBounds(go) ?: continue
            if (b[0] < rect[0] + rect[2] && b[0] + b[2] > rect[0] &&
                b[1] < rect[1] + rect[3] && b[1] + b[3] > rect[1]
            ) hits.add(go.id)
        }
        state.selectionIds = hits
        state.selectedId = hits.firstOrNull() ?: -1L
        doc.selection.set(hits)
        onSelectionChanged?.invoke()
        onStatus?.invoke("Selected ${hits.size} node(s)")
        requestRender()
    }

    private fun selectNode(node: GameObject, additive: Boolean) {
        if (additive && state.selectionIds.isNotEmpty()) {
            val ids = ArrayList(state.selectionIds)
            if (ids.contains(node.id)) ids.remove(node.id) else ids.add(node.id)
            state.selectionIds = ids
            state.selectedId = if (ids.contains(node.id)) node.id else (ids.lastOrNull() ?: -1L)
        } else {
            state.selectionIds = listOf(node.id)
            state.selectedId = node.id
        }
        doc.selection.set(state.selectionIds)
        onSelectionChanged?.invoke()
        onStatus?.invoke("Selected ${node.name} (${node.type}) · double-tap to focus")
        requestRender()
    }

    private fun selectSingle(id: Long, announce: Boolean = false) {
        state.selectionIds = listOf(id)
        state.selectedId = id
        doc.selection.set(listOf(id))
        if (announce) doc.scene.findById(id)?.let { onStatus?.invoke("Selected ${it.name} (${it.type})") }
        onSelectionChanged?.invoke()
        requestRender()
    }

    private fun clearSelection() {
        if (state.selectionIds.isEmpty()) return
        state.selectionIds = emptyList()
        state.selectedId = -1L
        doc.selection.clear()
        onSelectionChanged?.invoke()
        requestRender()
    }

    private fun endGesture() {
        val stroke = tileStroke
        val map = strokeMap
        if (stroke != null && map != null && stroke.size > 0) {
            stroke.toCommand("Paint tiles")?.let { doc.undo.push(doc, it, execute = false) }
        }
        if (transformActive && gestureChangedScene) {
            val nodes = selectedNodes()
            val after = snapshotTransforms(nodes)
            val changed = nodes.any { node ->
                val before = transformBefore[node.id]
                before != null && (before[0] != node.x || before[1] != node.y || before[2] != node.rotation ||
                    before[3] != node.scaleX || before[4] != node.scaleY)
            }
            if (changed) {
                doc.undo.push(doc, TransformCommand.between(toolUndoLabel(), transformBefore, after), execute = false)
            }
            doc.onSceneMutated()
            onToolFinished?.invoke("${state.tool.label} applied")
        }
        if (draggingGuide >= 0 && gestureChangedScene) {
            doc.onSceneMutated()
            onToolFinished?.invoke("Guide moved")
        }
        tileStroke = null
        tileStrokeSize = 0
        tileStrokeStart = null
        strokeMap = null
        preStrokeCells = null
        transformActive = false
        transformBefore = HashMap()
        draggingGuide = -1
        uiResizing = false
        gestureChangedScene = false
        state.selectionRect = null
        doc.notifySelection()
        requestRender()
    }

    private fun toolUndoLabel(): String = when (state.tool) {
        ToolState.MOVE -> "Move nodes"
        ToolState.ROTATE -> "Rotate nodes"
        ToolState.SCALE -> "Scale nodes"
        ToolState.PIVOT -> "Change pivot"
        ToolState.UI -> "Edit UI layout"
        else -> "Edit nodes"
    }

    // ------------------------------------------------------------------ hit testing

    /** Topmost node under the point: children first (they draw above their parents). */
    fun nodeAt(wx: Float, wy: Float): GameObject? {
        val candidates = doc.scene.objects.filter { !it.destroyed && it.visible && it.isVisibleInHierarchy() && !it.locked }
        var best: GameObject? = null
        var bestDepth = -1
        for (go in candidates) {
            if (!hitTest(go, wx, wy)) continue
            val d = go.depth() + if (go.parent == null) 0 else 1
            if (d >= bestDepth) {
                bestDepth = d
                best = go
            }
        }
        return best
    }

    private fun hitTest(go: GameObject, wx: Float, wy: Float): Boolean {
        val world = go.computeWorld()
        val inv = world.inverted() ?: return false
        val lx = inv.mapX(wx, wy)
        val ly = inv.mapY(wx, wy)
        val size = localSize(go)
        val minX = -go.pivotX * size.first
        val minY = -go.pivotY * size.second
        val padX = max(size.first * 0.05f, 0.02f)
        val padY = max(size.second * 0.05f, 0.02f)
        if (lx >= minX - padX && lx <= minX + size.first + padX &&
            ly >= minY - padY && ly <= minY + size.second + padY) return true
        val col = go.getAny<Collider2D>() ?: return false
        return when (col.shape) {
            com.sengine.engine.core.ColliderShape.CIRCLE ->
                hypot(lx - col.offsetX, ly - col.offsetY) <= col.radius.coerceAtLeast(0.05f)
            else -> abs(lx - col.offsetX) <= col.width * 0.5f && abs(ly - col.offsetY) <= col.height * 0.5f
        }
    }

    /** Hit test for UI controls: they live in design space, drawn in screen space. */
    private fun uiNodeAt(sx: Float, sy: Float, wx: Float, wy: Float): GameObject? {
        for (go in doc.scene.objects.asReversed()) {
            if (go.destroyed || go.locked || !go.visible) continue
            val control = go.getAny<com.sengine.engine.ui.ControlComponent>() ?: continue
            val r = control.rect
            if (r.width <= 0f || r.height <= 0f) continue
            val scale = uiScale()
            if (sx >= r.x * scale.first && sx <= r.right * scale.first &&
                sy >= r.y * scale.second && sy <= r.bottom * scale.second) return go
        }
        return null
    }

    private fun localSize(node: GameObject): Pair<Float, Float> {
        node.getAny<Sprite2D>()?.let { s ->
            val size = s.size()
            return max(size.first, 0.0001f) to max(size.second, 0.0001f)
        }
        node.getAny<Label2D>()?.let { l ->
            val w = (l.text.lineSequence().maxOfOrNull { it.length } ?: 1).coerceAtLeast(1) * l.size * 0.58f
            return max(w, 0.01f) to max(l.size * l.lineSpacing, 0.01f)
        }
        node.getAny<com.sengine.engine.ui.ControlComponent>()?.let { c ->
            return max(c.rect.width / 100f, 0.01f) to max(c.rect.height / 100f, 0.01f)
        }
        node.getAny<Collider2D>()?.let { col ->
            return if (col.shape == com.sengine.engine.core.ColliderShape.CIRCLE)
                max(col.radius * 2f, 0.01f) to max(col.radius * 2f, 0.01f)
            else max(col.width, 0.01f) to max(col.height, 0.01f)
        }
        val tm = node.getAny<TileMap2D>()
        if (tm != null) {
            val layer = tm.data.layers.firstOrNull()
            if (layer != null) {
                return layer.width * tm.tileWidth / tm.pixelsPerUnit to layer.height * tm.tileHeight / tm.pixelsPerUnit
            }
        }
        return 0.5f to 0.5f
    }

    /** World-space bounds of a node (pivot aware) or null when the node has no size at all. */
    fun worldBounds(go: GameObject): FloatArray? {
        val world = go.computeWorld()
        val hasVisual = go.getAny<Sprite2D>() != null || go.getAny<Label2D>() != null ||
            go.getAny<Collider2D>() != null || go.getAny<com.sengine.engine.ui.ControlComponent>() != null ||
            go.getAny<TileMap2D>() != null || go.getAny<com.sengine.engine.core.ParticleEmitter2D>() != null ||
            go.getAny<com.sengine.engine.core.Camera2D>() != null
        if (!hasVisual) return null
        val size = localSize(go)
        val isUi = go.getAny<com.sengine.engine.ui.ControlComponent>() != null
        if (isUi) {
            // UI nodes are drawn in screen space; report their screen rect converted back to world
            val control = go.getAny<com.sengine.engine.ui.ControlComponent>()!!
            val scale = uiScale()
            return floatArrayOf(
                state.view.screenToWorldX(control.rect.x * scale.first),
                state.view.screenToWorldY(control.rect.bottom * scale.second),
                control.rect.width * scale.first / state.view.pixelsPerUnit.coerceAtLeast(0.0001f),
                control.rect.height * scale.second / state.view.pixelsPerUnit.coerceAtLeast(0.0001f)
            )
        }
        val width = size.first * abs(world.scaleX)
        val height = size.second * abs(world.scaleY)
        return floatArrayOf(
            world.tx - width * go.pivotX,
            world.ty - height * go.pivotY,
            max(width, 0.01f), max(height, 0.01f)
        )
    }

    /** Rough AABB used by panels that only need a size. */
    fun bounds(go: GameObject): FloatArray = worldBounds(go) ?: floatArrayOf(go.world.tx, go.world.ty, 0.4f, 0.4f)

    fun tileMapNode(): TileMap2D? =
        selectedNodes().firstNotNullOfOrNull { it.getAny<TileMap2D>() }
            ?: doc.scene.objects.firstNotNullOfOrNull { it.getAny<TileMap2D>() }

    // ------------------------------------------------------------------ rendering

    private inner class SceneGlRenderer : GLSurfaceView.Renderer {
        private var renderer: GLRenderer2D? = null
        private var lastTime = System.nanoTime()

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            renderer = GLRenderer2D(textures, textRenderer).apply {
                materialSource = { name -> engine.resources.material(name) }
                onShaderError = { name, log -> com.sengine.engine.debug.Log.error("Shader", "$name: $log") }
            }
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
            renderer?.surfaceChanged(width, height)
            state.view.widthPx = width
            state.view.heightPx = height
            surfaceReady = true
            if (!framedOnce) post { frameScene() }
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            val r = renderer ?: return
            val now = System.nanoTime()
            val dt = ((now - lastTime) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            lastTime = now
            synchronized(engine.lock) {
                engine.tick(dt)
                val view = state.view
                view.widthPx = r.widthPx
                view.heightPx = r.heightPx
                engine.updateCameraView()
                // Play mode uses the game camera; edit mode keeps the editor's free camera.
                val active = if (engine.mode == Engine.Mode.EDIT) view else engine.gameView.also {
                    it.widthPx = view.widthPx
                    it.heightPx = view.heightPx
                }
                val overlay = EditorOverlay()
                state.overlay(overlay)
                engine.overlay = overlay
                engine.buildRenderList(active, overlay, engine.mode == Engine.Mode.EDIT)
                r.frame(engine.renderList, active)
                engine.profiler.drawCalls = engine.renderList.stats.drawCalls
                engine.profiler.batches = engine.renderList.stats.batches
                engine.profiler.sprites = engine.renderList.stats.sprites
                engine.profiler.texts = engine.renderList.stats.texts
                engine.profiler.shapes = engine.renderList.stats.shapes
            }
        }
    }

    /** Applies a resolution preview (UI editor) by overriding the viewport size. */
    fun applyPreviewResolution(index: Int) {
        state.resolutionPreview = index.coerceIn(0, EditorState.PREVIEW_SIZES.size - 1)
        val size = EditorState.PREVIEW_SIZES[state.resolutionPreview]
        if (size.second > 0) {
            state.view.widthPx = size.second
            state.view.heightPx = size.third
            engine.ui.designWidth = size.second.toFloat()
            engine.ui.designHeight = size.third.toFloat()
        } else {
            state.view.widthPx = glView.width.coerceAtLeast(1)
            state.view.heightPx = glView.height.coerceAtLeast(1)
            engine.ui.designWidth = doc.project.settings.uiDesignWidth.toFloat()
            engine.ui.designHeight = doc.project.settings.uiDesignHeight.toFloat()
        }
        com.sengine.engine.ui.UiLayout.layout(doc.scene, engine.ui.designWidth, engine.ui.designHeight)
        afterCameraChange()
    }

    companion object {
        private const val MIN_SIZE = 0.002f
        private const val MAX_SIZE = 20000f
    }
}
