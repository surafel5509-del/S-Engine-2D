package com.sengine.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.sengine.engine.EditorOverlay
import com.sengine.engine.Engine
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Label2D
import com.sengine.engine.core.Sprite2D
import com.sengine.engine.editor.EditorDocument
import com.sengine.engine.editor.EditorState
import com.sengine.engine.editor.ToolState
import com.sengine.engine.math.Vec2
import com.sengine.engine.render.Camera2DView
import com.sengine.engine.tilemap.TileBrush
import com.sengine.platform.android.AndroidTextRenderer
import com.sengine.platform.android.AndroidTextures
import com.sengine.platform.gl.GLRenderer2D
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The 2D viewport: a GL surface that renders the running engine and hosts every editor tool.
 *
 * Input model (touch and mouse are the same code path through [MotionEvent]):
 *  - one finger drag: tool action when a transform tool is active, otherwise pan
 *  - two fingers: pinch zoom + pan
 *  - tap: select the node under the cursor (respecting locked/hidden nodes)
 *  - long press: context menu (delegated to [onContextMenu])
 *  - mouse wheel maps to zoom
 *
 * The GL thread drives the engine: in EDIT mode a lightweight frame (animation preview, particles,
 * scripts idle) is ticked, in PLAY mode the game loop runs, and the same [GLRenderer2D] draws both.
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

    private val glView = GLSurfaceView(context)
    private val textures: AndroidTextures
    private val textRenderer: AndroidTextRenderer
    private val renderer: SceneGlRenderer

    private var dragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragNode: GameObject? = null
    private var dragStartValues = FloatArray(6)
    private var pinching = false
    private var pinchStartDistance = 0f
    private var pinchStartSize = 5f
    private var draggingGuide = -1

    init {
        textures = AndroidTextures { name -> doc.project.assetFile(name).takeIf { it.exists() } }
        textRenderer = AndroidTextRenderer(textures)
        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true
        renderer = SceneGlRenderer()
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
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

    /** Camera helpers used by the toolbar (focus, zoom presets). */
    fun focusSelection() {
        val nodes = state.selectionIds.mapNotNull { doc.scene.findById(it) }
        if (nodes.isEmpty()) return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (n in nodes) {
            val b = bounds(n)
            minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
            maxX = maxOf(maxX, b[0] + b[2]); maxY = maxOf(maxY, b[1] + b[3])
        }
        state.view.cx = (minX + maxX) * 0.5f
        state.view.cy = (minY + maxY) * 0.5f
        val spanX = maxX - minX
        val spanY = maxY - minY
        if (spanX > 0.001f || spanY > 0.001f) {
            val aspect = state.view.aspect.coerceAtLeast(0.2f)
            val neededY = (spanY * 0.5f) / 0.7f
            val neededX = (spanX * 0.5f) / aspect / 0.7f
            state.view.size = maxOf(neededY, neededX, 0.25f)
        }
        // camera pan limits keep the view near the scene
        val limit = 4096f
        state.view.cx = state.view.cx.coerceIn(-limit, limit)
        state.view.cy = state.view.cy.coerceIn(-limit, limit)
        onStatus?.invoke("Focused ${nodes.size} node(s)")
    }

    fun zoom(factor: Float) {
        state.view.size = (state.view.size / factor).coerceIn(0.05f, 8192f)
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = state.view
        val pointerCount = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x; dragStartY = event.y
                lastX = event.x; lastY = event.y
                dragging = false
                pinching = false
                dragNode = null
                beginDrag(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinching = true
                pinchStartDistance = pointerDistance(event)
                pinchStartSize = w.size
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && pointerCount >= 2) {
                    val d = pointerDistance(event)
                    if (pinchStartDistance > 1f) w.size = (pinchStartSize * pinchStartDistance / d).coerceIn(0.05f, 8192f)
                    val mx = (event.getX(0) + event.getX(1)) * 0.5f
                    val my = (event.getY(0) + event.getY(1)) * 0.5f
                    val dx = mx - lastX
                    val dy = my - lastY
                    w.cx -= dx / w.pixelsPerUnit
                    w.cy += dy / w.pixelsPerUnit
                    lastX = mx; lastY = my
                    updatePointer(event.x, event.y)
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!dragging && hypot(event.x - dragStartX, event.y - dragStartY) > theme.dp(6f)) dragging = true
                if (dragging) {
                    when (state.tool) {
                        ToolState.MOVE, ToolState.SCALE, ToolState.ROTATE -> {
                            if (dragNode != null) {
                                applyTransformDrag(dx, dy, event.x, event.y)
                            } else {
                                panCamera(dx, dy)
                            }
                        }
                        ToolState.PIVOT -> if (dragNode != null) applyTransformDrag(dx, dy, event.x, event.y) else panCamera(dx, dy)
                        ToolState.TILE -> paintTile(event.x, event.y, false)
                        else -> panCamera(dx, dy)
                    }
                }
                lastX = event.x; lastY = event.y
                updatePointer(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !pinching) {
                    if (state.tool == ToolState.TILE && dragNode == null) paintTile(event.x, event.y, false)
                    else tap(event.x, event.y)
                }
                endDrag()
                pinching = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                endDrag()
                pinching = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            zoom(if (scroll > 0f) 1.15f else 1f / 1.15f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun panCamera(dx: Float, dy: Float) {
        state.view.cx -= dx / state.view.pixelsPerUnit
        state.view.cy += dy / state.view.pixelsPerUnit
    }

    private fun updatePointer(sx: Float, sy: Float) {
        state.mouseWorldX = state.view.screenToWorldX(sx)
        state.mouseWorldY = state.view.screenToWorldY(sy)
        onStatus?.invoke(
            "x %.2f   y %.2f   zoom %.0f%%".format(state.mouseWorldX, state.mouseWorldY, 100f * state.pixelsPerUnit / 100f)
        )
    }

    private fun beginDrag(sx: Float, sy: Float) {
        val wx = state.view.screenToWorldX(sx)
        val wy = state.view.screenToWorldY(sy)
        // guide handle first (they sit above nodes)
        draggingGuide = -1
        for ((i, g) in state.guides.withIndex()) {
            if (abs(g[0] - wx) < 0.3f / state.view.pixelsPerUnit * 20f || abs(g[1] - wy) < 0.3f / state.view.pixelsPerUnit * 20f) {
                draggingGuide = i
                return
            }
        }
        if (state.tool == ToolState.TILE) return
        val node = nodeAt(wx, wy)
        if (node != null) {
            dragNode = node
            if (!state.selectionIds.contains(node.id)) {
                state.selectionIds = listOf(node.id)
                state.selectedId = node.id
                doc.selection.set(listOf(node.id))
                onSelectionChanged?.invoke()
            }
            dragStartValues = floatArrayOf(node.x, node.y, node.rotation, node.scaleX, node.scaleY, 0f)
        }
    }

    private fun endDrag() {
        if (dragNode != null && dragging) doc.onSceneMutated()
        dragNode = null
        draggingGuide = -1
        dragging = false
    }

    private fun applyTransformDrag(dx: Float, dy: Float, screenX: Float, screenY: Float) {
        val node = dragNode ?: return
        val w = state.view
        if (draggingGuide >= 0) {
            val g = state.guides[draggingGuide]
            g[0] += dx / w.pixelsPerUnit
            g[1] += dy / w.pixelsPerUnit
            g[2] += dx / w.pixelsPerUnit
            g[3] += dy / w.pixelsPerUnit
            return
        }
        when (state.tool) {
            ToolState.MOVE -> {
                var nx = dragStartValues[0] + dx / w.pixelsPerUnit
                var ny = dragStartValues[1] + dy / w.pixelsPerUnit
                if (state.snap.enabled) {
                    nx = state.snap.snap(nx)
                    ny = state.snap.snap(ny)
                }
                node.setPosition(nx, ny)
                onToolFinished?.invoke("Moved ${node.name}")
            }
            ToolState.ROTATE -> {
                val cx = w.worldToScreenX(node.world.tx)
                val cy = w.worldToScreenY(node.world.ty)
                val angle = Math.toDegrees(atan2((screenY - cy).toDouble(), (screenX - cx).toDouble())).toFloat() + 90f
                node.rotation = state.snap.snapAngle(angle)
                node.computeWorld()
                onToolFinished?.invoke("Rotated ${node.name} to %.1f°".format(node.rotation))
            }
            ToolState.SCALE -> {
                val startScale = if (dragStartValues[3] == 0f) 0.01f else dragStartValues[3]
                val factor = (1f + dx / theme.dp(180f)).coerceIn(0.01f, 100f)
                node.scaleX = dragStartValues[3] * factor
                node.scaleY = dragStartValues[4] * factor
                node.computeWorld()
                onToolFinished?.invoke("Scaled ${node.name} to %.2f".format(node.scaleX))
            }
            ToolState.PIVOT -> {
                val inv = node.computeWorld().inverted()
                if (inv != null) {
                    val lx = inv.mapX(state.mouseWorldX, state.mouseWorldY)
                    val ly = inv.mapY(state.mouseWorldX, state.mouseWorldY)
                    val p = node.getAny<Sprite2D>()
                    val bw = p?.size()?.first ?: 1f
                    val bh = p?.size()?.second ?: 1f
                    node.pivotX = (0.5f + lx / bw).coerceIn(0f, 1f)
                    node.pivotY = (0.5f + ly / bh).coerceIn(0f, 1f)
                    onToolFinished?.invoke("Pivot ${node.name}: %.2f, %.2f".format(node.pivotX, node.pivotY))
                }
            }
            else -> {}
        }
    }

    private fun paintTile(sx: Float, sy: Float, erase: Boolean) {
        val layer = doc.scene.objects.firstOrNull { it.getAny<com.sengine.engine.core.TileMap2D>() != null } ?: return
        val tm = layer.getAny<com.sengine.engine.core.TileMap2D>() ?: return
        val px = state.view.screenToWorldX(sx)
        val py = state.view.screenToWorldY(sy)
        val brush = state.tileBrush
        val tile = if (erase || brush.mode == TileBrush.Mode.ERASE) 0 else brush.tileId
        val size = brush.brushSize.coerceIn(1, 16)
        val (cx, cy) = tm.worldToCell(px, py)
        val half = size / 2
        val changed = tm.fillRect(
            brush.layerIndex.coerceAtLeast(0),
            cx - half, cy - half, cx - half + size - 1, cy - half + size - 1, tile
        )
        if (changed > 0) {
            onTilePainted?.invoke()
            doc.onSceneMutated()
        }
    }

    private fun tap(sx: Float, sy: Float) {
        val wx = state.view.screenToWorldX(sx)
        val wy = state.view.screenToWorldY(sy)
        val node = nodeAt(wx, wy)
        if (node == null) {
            if (!state.selectionIds.isEmpty()) {
                state.selectionIds = emptyList()
                state.selectedId = -1L
                doc.selection.clear()
                onSelectionChanged?.invoke()
            }
        } else {
            state.selectionIds = listOf(node.id)
            state.selectedId = node.id
            doc.selection.set(listOf(node.id))
            onSelectionChanged?.invoke()
            onStatus?.invoke("Selected ${node.name}")
        }
    }

    /** Topmost node whose bounds contain the point (editor order: later nodes are on top). */
    fun nodeAt(wx: Float, wy: Float): GameObject? {
        for (go in doc.scene.objects.asReversed()) {
            if (go.destroyed || !go.isVisibleInHierarchy() || go.locked) continue
            val inv = go.computeWorld().inverted() ?: continue
            val lx = inv.mapX(wx, wy)
            val ly = inv.mapY(wx, wy)
            val b = bounds(go)
            val world = go.world
            // local bounds: [x, y, w, h] in world units around the node origin (pivot honoured)
            val halfW = b[2] * 0.5f
            val halfH = b[3] * 0.5f
            val cx = (0.5f - go.pivotX) * b[2]
            val cy = (0.5f - go.pivotY) * b[3]
            if (lx >= cx - halfW && lx <= cx + halfW && ly >= cy - halfH && ly <= cy + halfH) return go
            // also allow selecting by collider even without a sprite
            val col = go.getAny<Collider2D>()
            if (col != null && world.scaleX != 0f && world.scaleY != 0f) {
                if (abs(lx - col.offsetX) <= col.width * 0.5f && abs(ly - col.offsetY) <= col.height * 0.5f) return go
            }
        }
        return null
    }

    /** World-space AABB of a node: x, y, w, h. */
    fun bounds(go: GameObject): FloatArray {
        val sprite = go.getAny<Sprite2D>()
        val label = go.getAny<Label2D>()
        val collider = go.getAny<Collider2D>()
        val w: Float
        val h: Float
        if (sprite != null) {
            val s = sprite.size()
            w = s.first * abs(go.world.scaleX)
            h = s.second * abs(go.world.scaleY)
        } else if (label != null) {
            w = (label.text.length.coerceAtLeast(1) * label.size * 0.6f) * abs(go.world.scaleX)
            h = (label.size * label.lineSpacing).coerceAtLeast(0.1f) * abs(go.world.scaleY)
        } else if (collider != null) {
            w = collider.width * abs(go.world.scaleX)
            h = collider.height * abs(go.world.scaleY)
        } else {
            w = 0.5f
            h = 0.5f
        }
        val cx = go.world.tx
        val cy = go.world.ty
        return floatArrayOf(cx - w * 0.5f, cy - h * 0.5f, w, h)
    }

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
                if (engine.mode == Engine.Mode.EDIT) {
                    view.copyFrom(engine.gameView)
                    // the editor camera is free; the game camera only seeds size/limits
                }
                val overlay = EditorOverlay()
                state.overlay(overlay)
                overlay.showGrid = state.showGrid
                overlay.gridStep = state.gridStep
                engine.overlay = overlay
                engine.buildRenderList(view, overlay, engine.mode == Engine.Mode.EDIT)
                r.frame(engine.renderList, view)
                engine.renderList.stats.drawCalls.let { engine.profiler.drawCalls = it }
                engine.profiler.batches = engine.renderList.stats.batches
                engine.profiler.sprites = engine.renderList.stats.sprites
                engine.profiler.texts = engine.renderList.stats.texts
                engine.profiler.shapes = engine.renderList.stats.shapes
            }
        }

        private fun hudWorld(): Vec2 = Vec2(state.view.cx, state.view.cy)
    }

    /** Applies a resolution preview (UI editor) by overriding the viewport aspect. */
    fun applyPreviewResolution(index: Int) {
        state.resolutionPreview = index
        val size = EditorState.PREVIEW_SIZES[index.coerceIn(0, EditorState.PREVIEW_SIZES.size - 1)]
        if (size.second > 0) {
            state.view.widthPx = size.second
            state.view.heightPx = size.third
        } else {
            state.view.widthPx = glView.width.coerceAtLeast(1)
            state.view.heightPx = glView.height.coerceAtLeast(1)
        }
    }

    fun tileMapNode(): GameObject? =
        doc.scene.objects.firstOrNull { it.getAny<com.sengine.engine.core.TileMap2D>() != null }

    fun cameraView(): Camera2DView = state.view

    val glSurface: View get() = glView
}
