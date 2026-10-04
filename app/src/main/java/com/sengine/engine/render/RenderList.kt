package com.sengine.engine.render

import com.sengine.engine.math.Rect2

/**
 * Platform independent render data.
 *
 * The runtime fills a [RenderList] every frame; the platform renderer (OpenGL ES 2 on Android)
 * consumes it, sorts it once and issues batched draw calls. Keeping the display list in the core
 * means the same scene description drives the game view, the editor viewport and the UI editor
 * preview — and it can be inspected by tests and the profiler.
 */
object Primitive {
    const val RECT = 0
    const val CIRCLE = 1
    const val TRIANGLE = 2
    const val RING = 3
    const val SPRITE = 4
    const val TEXT = 5
    const val LINE = 6
    const val NINE_SLICE = 7
}

object BlendKind {
    const val ALPHA = 0
    const val ADDITIVE = 1
    const val MULTIPLY = 2
    const val PREMULTIPLIED = 3
}

/** A single drawable. Pooled — the render list never allocates after warm-up. */
class RenderItem {
    var primitive = Primitive.RECT
    var x = 0f; var y = 0f
    var width = 1f; var height = 1f
    var rotation = 0f
    var scaleX = 1f; var scaleY = 1f
    var pivotX = 0.5f; var pivotY = 0.5f
    var color = 0xFFFFFFFF.toInt()
    var blend = BlendKind.ALPHA

    /** Texture key (asset name). Empty means "no texture" (solid shape). */
    var texture = ""
    var region = Rect2.EMPTY        // pixels inside the texture; empty = whole texture
    var textureWidth = 0
    var textureHeight = 0

    /** Text data (primitive == TEXT). */
    var text = ""
    var fontSize = 16f
    var bold = false
    var align = 1
    var font = ""
    var outlineSize = 0f
    var outlineColor = 0
    var shadow = false
    var shadowColor = 0
    var shadowOffsetX = 0f
    var shadowOffsetY = 0f

    /** Sorting: layer name, z-order, then texture to maximise batching. */
    var layer = "Default"
    var order = 0
    var subOrder = 0

    /** Source node, for editor picking and the remote inspector. */
    var nodeId = 0L

    /** 0 = world space, 1 = screen/UI space. */
    var space = 0

    /** Scissor/clip rectangle in screen pixels; empty = no clip. */
    var clip = Rect2.EMPTY

    /** Shader material asset name; empty = default sprite shader. */
    var material = ""

    /** Free slot for material uniforms (u0..u3). */
    var u0 = 0f; var u1 = 0f; var u2 = 0f; var u3 = 0f

    fun reset() {
        primitive = Primitive.RECT
        x = 0f; y = 0f
        width = 1f; height = 1f
        rotation = 0f
        scaleX = 1f; scaleY = 1f
        pivotX = 0.5f; pivotY = 0.5f
        color = 0xFFFFFFFF.toInt()
        blend = BlendKind.ALPHA
        texture = ""
        region = Rect2.EMPTY
        textureWidth = 0; textureHeight = 0
        text = ""
        fontSize = 16f
        bold = false
        align = 1
        font = ""
        outlineSize = 0f
        outlineColor = 0
        shadow = false
        shadowColor = 0
        shadowOffsetX = 0f; shadowOffsetY = 0f
        layer = "Default"
        order = 0
        subOrder = 0
        nodeId = 0L
        space = 0
        clip = Rect2.EMPTY
        material = ""
        u0 = 0f; u1 = 0f; u2 = 0f; u3 = 0f
    }
}

/** Live render counters. Every number is measured, never estimated. */
class RenderStats {
    var drawCalls = 0
    var batches = 0
    var sprites = 0
    var texts = 0
    var shapes = 0
    var lines = 0
    var vertices = 0
    var textureSwitches = 0
    var atlasSwitches = 0
    var clippedDraws = 0
    var itemsSubmitted = 0
    var itemsCulled = 0

    fun reset() {
        drawCalls = 0; batches = 0; sprites = 0; texts = 0; shapes = 0; lines = 0
        vertices = 0; textureSwitches = 0; atlasSwitches = 0; clippedDraws = 0
        itemsSubmitted = 0; itemsCulled = 0
    }

    fun copyFrom(o: RenderStats) {
        drawCalls = o.drawCalls; batches = o.batches; sprites = o.sprites; texts = o.texts
        shapes = o.shapes; lines = o.lines; vertices = o.vertices
        textureSwitches = o.textureSwitches; atlasSwitches = o.atlasSwitches
        clippedDraws = o.clippedDraws; itemsSubmitted = o.itemsSubmitted; itemsCulled = o.itemsCulled
    }
}

/** Camera description handed to the renderer (world units + screen pixels). */
class Camera2DView {
    var cx = 0f
    var cy = 0f
    /** Half of the visible height in world units. */
    var size = 5f
    var widthPx = 1
    var heightPx = 1
    var pixelSnap = false
    var backgroundColor = 0xFF1B2533.toInt()
    var layerMask: Set<String> = emptySet()
    var ySort = false

    val aspect get() = widthPx.toFloat() / heightPx.coerceAtLeast(1)
    val halfWidth get() = size * aspect
    val halfHeight get() = size
    val pixelsPerUnit get() = heightPx / (2f * size)

    fun copyFrom(o: Camera2DView) {
        cx = o.cx; cy = o.cy; size = o.size
        widthPx = o.widthPx; heightPx = o.heightPx
        pixelSnap = o.pixelSnap
        backgroundColor = o.backgroundColor
        layerMask = o.layerMask
        ySort = o.ySort
    }

    fun screenToWorldX(sx: Float) = cx + (sx / widthPx * 2f - 1f) * halfWidth
    fun screenToWorldY(sy: Float) = cy + (1f - sy / heightPx * 2f) * halfHeight
    fun worldToScreenX(wx: Float) = (wx - cx + halfWidth) / (halfWidth * 2) * widthPx
    fun worldToScreenY(wy: Float) = (cy + halfHeight - wy) / (halfHeight * 2) * heightPx

    fun worldBounds(): Rect2 = Rect2(cx - halfWidth, cy - halfHeight, halfWidth * 2, halfHeight * 2)

    /** Visible world rect, snapped to whole pixels when pixel-perfect mode is on. */
    fun snappedBounds(): Rect2 {
        if (!pixelSnap || pixelsPerUnit <= 0f) return worldBounds()
        val ppu = pixelsPerUnit
        val left = kotlin.math.round((cx - halfWidth) * ppu) / ppu
        val bottom = kotlin.math.round((cy - halfHeight) * ppu) / ppu
        return Rect2(left, bottom, halfWidth * 2, halfHeight * 2)
    }
}

/**
 * Ordered collection of [RenderItem]s plus the camera it belongs to.
 * Items are sorted for batching: space → layer → z-order → texture → primitive.
 */
class RenderList {
    val items = ArrayList<RenderItem>(512)
    private var used = 0
    val camera = Camera2DView()
    val stats = RenderStats()

    /** Clear for a new frame, keeping the pooled items. */
    fun begin(backgroundColor: Int) {
        used = 0
        items.clear()
        camera.backgroundColor = backgroundColor
        stats.reset()
    }

    fun item(): RenderItem {
        if (used < pool.size) {
            val i = pool[used++]
            i.reset()
            items.add(i)
            return i
        }
        val i = RenderItem()
        pool.add(i)
        used++
        items.add(i)
        return i
    }

    private val pool = ArrayList<RenderItem>(512)

    fun isEmpty() = items.isEmpty()

    /** Sort so identical texture/material/blend runs stay contiguous (fewer draw calls). */
    fun sortForBatching() {
        items.sortWith(COMPARATOR)
    }

    fun populate(stats: RenderStats) {
        stats.itemsSubmitted = items.size
        stats.sprites = 0
        stats.texts = 0
        stats.shapes = 0
        for (i in items) {
            when (i.primitive) {
                Primitive.SPRITE -> stats.sprites++
                Primitive.TEXT -> stats.texts++
                Primitive.LINE -> stats.lines++
                else -> stats.shapes++
            }
        }
    }

    private companion object {
        val COMPARATOR = Comparator<RenderItem> { a, b ->
            var r = a.space.compareTo(b.space)
            if (r != 0) return@Comparator r
            r = a.layer.compareTo(b.layer)
            if (r != 0) return@Comparator r
            r = a.order.compareTo(b.order)
            if (r != 0) return@Comparator r
            r = a.subOrder.compareTo(b.subOrder)
            if (r != 0) return@Comparator r
            r = (a.material).compareTo(b.material)
            if (r != 0) return@Comparator r
            r = a.blend.compareTo(b.blend)
            if (r != 0) return@Comparator r
            r = a.texture.compareTo(b.texture)
            if (r != 0) return@Comparator r
            a.primitive.compareTo(b.primitive)
        }
    }
}
