package com.sengine.engine.core

import com.sengine.engine.animation.LoopMode
import com.sengine.engine.animation.SpriteFrames
import com.sengine.engine.math.Vec2

/** Render shapes available without any texture (fast path — no texture switch, batched). */
object RenderShape {
    const val RECT = 0
    const val CIRCLE = 1
    const val TRIANGLE = 2
    const val RING = 3
    val NAMES = listOf("Rectangle", "Circle", "Triangle", "Ring")
}

/**
 * Draws a texture, a sprite-sheet region, or a solid shape.
 *
 * Region coordinates are in pixels inside the source texture: `(0,0,0,0)` means "whole texture",
 * anything else is a sprite-sheet slice — the basis of the sprite editor, atlases and flip-books.
 */
class Sprite2D : Component(), SignalListener {
    override val type = Sprite2D.TYPE
    override val category = "Rendering"
    override val description = "Draws a texture region or a solid 2D shape"

    var shape = RenderShape.RECT
    var texture = ""
    var color = 0xFFFFFFFF.toInt()

    var regionX = 0; var regionY = 0; var regionW = 0; var regionH = 0

    /** Size in world units. 0 = derive from the texture (pixels / pixelsPerUnit) or 1. */
    var sizeX = 0f
    var sizeY = 0f
    var pixelsPerUnit = 100f

    var flipX = false
    var flipY = false
    var blend = 0                  // 0 alpha, 1 additive, 2 multiply
    var material = ""              // shader/material asset name
    var pixelSnap = false
    var zOffset = 0f
    var opacity = 1f
    var angleOffset = 0f

    /** Runtime: texture pixel size, filled by the renderer when the texture is loaded. */
    var textureWidth = 0
    var textureHeight = 0

    override fun props() = listOf(
        Prop.E("Shape", RenderShape.NAMES, { shape }, { shape = it }, section = "Appearance"),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }, section = "Appearance"),
        Prop.C("Color", { color }, { color = it }, section = "Appearance"),
        Prop.F("Opacity", { opacity }, { opacity = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f, section = "Appearance"),
        Prop.E("Blend", BLENDS, { blend }, { blend = it }, section = "Appearance"),
        Prop.Asset("Material", AssetKind.MATERIAL, { material }, { material = it }, tooltip = "2D shader material applied to this sprite", section = "Appearance"),
        Prop.I("Region X", { regionX }, { regionX = it }, 0, 100000, section = "Sprite Sheet"),
        Prop.I("Region Y", { regionY }, { regionY = it }, 0, 100000, section = "Sprite Sheet"),
        Prop.I("Region W", { regionW }, { regionW = it }, 0, 100000, section = "Sprite Sheet"),
        Prop.I("Region H", { regionH }, { regionH = it }, 0, 100000, section = "Sprite Sheet"),
        Prop.F("Size X", { sizeX }, { sizeX = it.coerceAtLeast(0f) }, 0.1f, 0f, 1000f, tooltip = "World size; 0 uses the texture size", section = "Layout"),
        Prop.F("Size Y", { sizeY }, { sizeY = it.coerceAtLeast(0f) }, 0.1f, 0f, 1000f, section = "Layout"),
        Prop.F("Pixels / Unit", { pixelsPerUnit }, { pixelsPerUnit = it.coerceAtLeast(1f) }, 1f, 1f, 4096f, section = "Layout"),
        Prop.B("Flip X", { flipX }, { flipX = it }, section = "Layout"),
        Prop.B("Flip Y", { flipY }, { flipY = it }, section = "Layout"),
        Prop.F("Angle Offset", { angleOffset }, { angleOffset = it }, 1f, -360f, 360f, section = "Layout"),
        Prop.B("Pixel Snap", { pixelSnap }, { pixelSnap = it }, tooltip = "Snap to the screen pixel grid when rendering", section = "Pixel Art"),
        Prop.Info("Texture Size", { if (textureWidth > 0) "${textureWidth}×${textureHeight}px" else "—" }, section = "Info"),
        Prop.Info("Draw Size", { "%.2f × %.2f units".format(size().first, size().second) }, section = "Info")
    )

    /** Effective draw size in local units (before node scale). */
    fun size(): Pair<Float, Float> {
        val hasRegion = regionW > 0 && regionH > 0
        val texW = if (hasRegion) regionW else textureWidth
        val texH = if (hasRegion) regionH else textureHeight
        val w = if (sizeX > 0f) sizeX else if (texW > 0) texW / pixelsPerUnit else 1f
        val h = if (sizeY > 0f) sizeY else if (texH > 0) texH / pixelsPerUnit else 1f
        return w to h
    }

    fun region(): FloatArray? {
        if (regionW <= 0 || regionH <= 0) return null
        return floatArrayOf(regionX.toFloat(), regionY.toFloat(), regionW.toFloat(), regionH.toFloat())
    }

    override fun onSignal(name: String, data: Any?): Boolean = false

    override fun resetRuntime() {
        // texture size is resolved by the renderer each frame; nothing to reset
    }

    companion object {
        const val TYPE = "Sprite2D"
        val BLENDS = listOf("Alpha", "Additive", "Multiply")
        const val SHAPE_RECT = 0
        const val SHAPE_CIRCLE = 1
        const val SHAPE_TRIANGLE = 2
    }
}

/**
 * Flip-book sprite animation driven by the real animation system: either a [SpriteFrames] range on a
 * sheet, or a named [com.sengine.engine.animation.Animation] asset for full timeline control.
 */
class AnimatedSprite2D : Component(), SignalListener {
    override val type = TYPE
    override val category = "Rendering"
    override val description = "Sprite-sheet flip-book animation with loop modes"

    /** Either "sheet:columns" (SpriteFrames) or "asset:<name>.anim.json". */
    var animationAsset = ""
    var mode = 0                     // 0 = Sprite Sheet, 1 = Animation Asset
    var spriteSheet = ""
    var columns = 4
    var frameCount = 4
    var fps = 8f
    var startFrame = 0
    var loopIndex = 1                // LoopMode ordinal
    var autoplay = true
    var playing = true
    var speed = 1f
    var color = 0xFFFFFFFF.toInt()
    var sizeX = 0f
    var sizeY = 0f
    var pixelsPerUnit = 100f
    var flipX = false
    var flipY = false
    var blend = 0
    var opacity = 1f

    // runtime
    var currentFrame = 0
    private var time = 0f
    var finished = false

    override fun props() = listOf(
        Prop.E("Mode", MODES, { mode }, { mode = it }, section = "Animation"),
        Prop.Asset("Animation", AssetKind.ANIMATION, { animationAsset }, { animationAsset = it }, section = "Animation"),
        Prop.Asset("Sheet", AssetKind.TEXTURE, { spriteSheet }, { spriteSheet = it }, section = "Sprite Sheet"),
        Prop.I("Columns", { columns }, { columns = it.coerceAtLeast(1) }, 1, 64, section = "Sprite Sheet"),
        Prop.I("Frame Count", { frameCount }, { frameCount = it.coerceAtLeast(1) }, 1, 512, section = "Sprite Sheet"),
        Prop.I("Start Frame", { startFrame }, { startFrame = it.coerceAtLeast(0) }, 0, 512, section = "Sprite Sheet"),
        Prop.F("FPS", { fps }, { fps = it.coerceAtLeast(0.1f) }, 1f, 0.1f, 120f, section = "Animation"),
        Prop.E("Loop", LoopMode.LABELS, { loopIndex }, { loopIndex = it }, section = "Animation"),
        Prop.F("Speed", { speed }, { speed = it }, 0.1f, 0.05f, 10f, section = "Animation"),
        Prop.B("Autoplay", { autoplay }, { autoplay = it }, section = "Animation"),
        Prop.C("Color", { color }, { color = it }, section = "Appearance"),
        Prop.F("Opacity", { opacity }, { opacity = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f, section = "Appearance"),
        Prop.E("Blend", Sprite2D.BLENDS, { blend }, { blend = it }, section = "Appearance"),
        Prop.F("Size X", { sizeX }, { sizeX = it.coerceAtLeast(0f) }, 0.1f, 0f, 1000f, section = "Layout"),
        Prop.F("Size Y", { sizeY }, { sizeY = it.coerceAtLeast(0f) }, 0.1f, 0f, 1000f, section = "Layout"),
        Prop.F("Pixels / Unit", { pixelsPerUnit }, { pixelsPerUnit = it.coerceAtLeast(1f) }, 1f, 1f, 4096f, section = "Layout"),
        Prop.B("Flip X", { flipX }, { flipX = it }, section = "Layout"),
        Prop.B("Flip Y", { flipY }, { flipY = it }, section = "Layout"),
        Prop.Info("Playing", { if (playing) "frame $currentFrame" else "stopped" }, section = "Info")
    )

    val loopMode: LoopMode get() = LoopMode.of(loopIndex)

    fun frames(): SpriteFrames = SpriteFrames(columns, frameCount, fps, startFrame, loopMode)

    fun size(): Pair<Float, Float> {
        val w = if (sizeX > 0f) sizeX else 1f
        val h = if (sizeY > 0f) sizeY else 1f
        return w to h
    }

    fun play(restart: Boolean = false) {
        playing = true
        finished = false
        if (restart) time = 0f
    }

    fun stop() {
        playing = false
    }

    fun gotoFrame(index: Int) {
        currentFrame = index.coerceIn(0, (frameCount - 1).coerceAtLeast(0))
        time = currentFrame / fps.coerceAtLeast(0.001f)
    }

    /** Advance the flip-book; called by the runtime (play mode) and by the editor preview. */
    fun advance(dt: Float) {
        if (!playing) return
        time += dt * speed
        val duration = frames().duration()
        when (loopMode) {
            LoopMode.ONCE -> {
                if (time >= duration) {
                    time = duration
                    playing = false
                    finished = true
                    owner()?.emit(Signals.ANIMATION_FINISHED)
                }
            }
            LoopMode.LOOP -> if (duration > 0f && time >= duration) time -= duration * kotlin.math.floor(time / duration)
            LoopMode.PING_PONG -> {
                val period = duration * 2f
                if (period > 0f && time >= period) time -= period * kotlin.math.floor(time / period)
            }
        }
        currentFrame = frames().frameAt(time)
    }

    private fun owner(): GameObject? = if (attached) gameObject else null

    override fun resetRuntime() {
        time = 0f
        currentFrame = startFrame
        finished = false
        playing = autoplay
    }

    override fun onSignal(name: String, data: Any?): Boolean {
        when (name) {
            "play" -> { play(true); return true }
            "stop" -> { stop(); return true }
            "pause" -> { playing = false; return true }
        }
        return false
    }

    companion object {
        const val TYPE = "AnimatedSprite2D"
        val MODES = listOf("Sprite Sheet", "Animation Asset")
    }
}

/** World-space text. Glyphs come from the font atlas built by the renderer (or a TTF asset). */
class Label2D : Component() {
    override val type = TYPE
    override val category = "Rendering"
    override val description = "Draws text in world space"

    var text = "Label"
    var size = 0.5f
    var color = 0xFFFFFFFF.toInt()
    var align = 1                  // 0 left, 1 center, 2 right
    var bold = false
    var font = ""
    var lineSpacing = 1.1f
    var outlineSize = 0f
    var outlineColor = 0xFF000000.toInt()
    var shadow = false
    var shadowColor = 0x80000000.toInt()
    var shadowOffset = Vec2(0.05f, -0.05f)
    var opacity = 1f
    var pixelsPerUnit = 100f

    override fun props() = listOf(
        Prop.S("Text", { text }, { text = it }, multiline = true),
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 20f),
        Prop.C("Color", { color }, { color = it }),
        Prop.F("Opacity", { opacity }, { opacity = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.E("Align", listOf("Left", "Center", "Right"), { align }, { align = it }),
        Prop.B("Bold", { bold }, { bold = it }),
        Prop.Asset("Font", AssetKind.FONT, { font }, { font = it }),
        Prop.F("Line Spacing", { lineSpacing }, { lineSpacing = it.coerceAtLeast(0.5f) }, 0.05f, 0.5f, 3f, section = "Layout"),
        Prop.F("Outline", { outlineSize }, { outlineSize = it.coerceAtLeast(0f) }, 0.05f, 0f, 8f, section = "Effects"),
        Prop.C("Outline Color", { outlineColor }, { outlineColor = it }, section = "Effects"),
        Prop.B("Shadow", { shadow }, { shadow = it }, section = "Effects"),
        Prop.C("Shadow Color", { shadowColor }, { shadowColor = it }, section = "Effects"),
        Prop.V2("Shadow Offset", { shadowOffset }, { shadowOffset = it }, section = "Effects")
    )

    companion object {
        const val TYPE = "Label2D"
    }
}

/**
 * 2D camera. Supports zoom, follow target with smoothing, dead zone, world limits, pixel-perfect
 * snapping, layer visibility masks and multi-camera splitting through `viewport` rects.
 */
class Camera2D : Component() {
    override val type = TYPE
    override val category = "Rendering"
    override val description = "Viewport camera with smoothing, limits and pixel-perfect mode"

    var size = 5f
    var zoom = 1f
    var background = 0xFF1B2533.toInt()
    var follow = ""
    var followMode = 0             // 0 = smooth, 1 = instant, 2 = none
    var smoothing = 5f
    var offsetX = 0f
    var offsetY = 0f
    var deadZone = Vec2(0.4f, 0.3f)
    var pixelPerfect = false
    var snapToPixels = false
    var current = true
    var priority = 0

    var limitEnabled = false
    var limitX = -100f
    var limitY = -100f
    var limitW = 200f
    var limitH = 200f

    /** Culling/visibility mask — a camera only draws layers listed here (comma separated). */
    var layers = ""

    override fun props() = listOf(
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.1f) }, 0.1f, 0.1f, 500f, tooltip = "Half of the visible height in world units"),
        Prop.F("Zoom", { zoom }, { zoom = it.coerceAtLeast(0.05f) }, 0.05f, 0.05f, 10f),
        Prop.C("Background", { background }, { background = it }),
        Prop.S("Follow Target", { follow }, { follow = it }, tooltip = "Name of the node this camera follows"),
        Prop.E("Follow Mode", FOLLOW_MODES, { followMode }, { followMode = it }),
        Prop.F("Smoothing", { smoothing }, { smoothing = it.coerceAtLeast(0f) }, 0.5f, 0f, 60f),
        Prop.V2("Offset", { Vec2(offsetX, offsetY) }, { offsetX = it.x; offsetY = it.y }),
        Prop.V2("Dead Zone", { deadZone }, { deadZone = it }),
        Prop.B("Pixel Perfect", { pixelPerfect }, { pixelPerfect = it }, tooltip = "Snap the camera to whole screen pixels (crisp pixel art)"),
        Prop.B("Current", { current }, { current = it }),
        Prop.I("Priority", { priority }, { priority = it }, -100, 100),
        Prop.B("Use Limits", { limitEnabled }, { limitEnabled = it }, section = "Limits"),
        Prop.R("Limits", { com.sengine.engine.math.Rect2(limitX, limitY, limitW, limitH) }, { limitX = it.x; limitY = it.y; limitW = it.width; limitH = it.height }, section = "Limits"),
        Prop.S("Layer Mask", { layers }, { layers = it }, tooltip = "Comma separated layer names; empty draws every layer", section = "Layers")
    )

    /** Half-width for a given aspect ratio. */
    fun halfWidth(aspect: Float) = size * aspect / zoom.coerceAtLeast(0.001f)

    fun halfHeight() = size / zoom.coerceAtLeast(0.001f)

    companion object {
        const val TYPE = "Camera2D"
        val FOLLOW_MODES = listOf("Smooth", "Instant", "None")
    }
}
