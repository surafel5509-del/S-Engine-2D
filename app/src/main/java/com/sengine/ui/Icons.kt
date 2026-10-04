package com.sengine.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Vector icons drawn with [Canvas] instead of font glyphs or bitmaps.
 *
 * Why not text glyphs: the emoji/symbol range renders differently (or not at all) depending on the
 * device font, which is exactly how tool buttons ended up showing empty boxes. Drawing the paths
 * ourselves guarantees identical, crisp icons on every Android version and screen density, and lets
 * the icons follow the theme colour.
 *
 * All icons are authored inside a 0..1 unit box and scaled to the requested size, so one definition
 * serves a 16dp toolbar button and a 200dp empty-state illustration.
 */
object Icons {

    // Tool icons
    const val SELECT = 0
    const val MOVE = 1
    const val ROTATE = 2
    const val SCALE = 3
    const val PIVOT = 4
    const val RECT = 5
    const val TILE = 6
    const val UI_EDIT = 7

    // Overlays
    const val GRID = 8
    const val SNAP = 9
    const val COLLIDER = 10
    const val CAMERA = 11
    const val GUIDE = 12
    const val PHYSICS = 13
    const val PIXEL_GRID = 14

    // Actions
    const val PLAY = 15
    const val STOP = 16
    const val PAUSE = 17
    const val STEP = 18
    const val SAVE = 19
    const val UNDO = 20
    const val REDO = 21
    const val SEARCH = 22
    const val PLUS = 23
    const val MINUS = 24
    const val CLOSE = 25
    const val CHECK = 26
    const val TRASH = 27
    const val RENAME = 28
    const val COPY = 29
    const val IMPORT = 30
    const val EXPORT = 31
    const val FOLDER = 32
    const val FILE = 33
    const val IMAGE = 34
    const val SOUND = 35
    const val SCRIPT = 36
    const val SHADER = 37
    const val ANIMATION = 38
    const val TILESET = 39
    const val PARTICLES = 40
    const val SCENE = 41
    const val PREFAB = 42
    const val MATERIAL = 43
    const val STAR = 44
    const val CLOCK = 45
    const val EYE = 46
    const val LOCK = 47
    const val FOCUS = 48
    const val ZOOM_IN = 49
    const val ZOOM_OUT = 50
    const val LAYERS = 51
    const val SETTINGS = 52
    const val PALETTE = 53
    const val DEBUG = 54
    const val PROFILE = 55
    const val CONSOLE = 56
    const val NODE = 57
    const val SPRITE = 58
    const val TEXT = 59
    const val CROP = 60
    const val GRID_SLICE = 61
    const val FLIP = 62
    const val CHEVRON_DOWN = 63
    const val CHEVRON_RIGHT = 64
    const val ARROW_UP = 65
    const val UPLOAD = 66
    const val KEYFRAME = 67
    const val WAVE = 68
    const val SPEAKER = 69
    const val PACKAGE = 70
    const val PLUGIN = 71
    const val TEXTURE = 72
    const val WORLD = 73

    /** Icon used for a viewport tool, kept next to the tools themselves. */
    fun forTool(tool: com.sengine.engine.editor.ToolState): Int = when (tool) {
        com.sengine.engine.editor.ToolState.SELECT -> SELECT
        com.sengine.engine.editor.ToolState.MOVE -> MOVE
        com.sengine.engine.editor.ToolState.ROTATE -> ROTATE
        com.sengine.engine.editor.ToolState.SCALE -> SCALE
        com.sengine.engine.editor.ToolState.PIVOT -> PIVOT
        com.sengine.engine.editor.ToolState.RECT -> RECT
        com.sengine.engine.editor.ToolState.TILE -> TILE
        com.sengine.engine.editor.ToolState.UI -> UI_EDIT
    }

    /** Icon for an asset kind / file extension, used by the browser and the scene tree. */
    fun forAssetName(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "jpg", "jpeg", "webp", "bmp", "gif" -> IMAGE
            "wav", "ogg", "mp3", "m4a", "aac", "flac" -> SOUND
            "js" -> SCRIPT
            "glsl", "frag", "vert" -> SHADER
            "anim", "animation" -> ANIMATION
            "tileset" -> TILESET
            "particle", "particles" -> PARTICLES
            "material" -> MATERIAL
            "prefab" -> PREFAB
            "scene" -> SCENE
            "json" -> if (name.contains("scene")) SCENE else FILE
            else -> FILE
        }
    }

    /** Icon for a node type in the scene tree. */
    fun forNodeType(type: String): Int = when {
        type.startsWith("Sprite") || type == "Node2D" -> SPRITE
        type.startsWith("AnimatedSprite") -> ANIMATION
        type.startsWith("Label") -> TEXT
        type.startsWith("Camera") -> CAMERA
        type.startsWith("TileMap") -> TILESET
        type.startsWith("Particles") -> PARTICLES
        type.startsWith("Collision") -> COLLIDER
        type.startsWith("Area") -> COLLIDER
        type.startsWith("RayCast") -> PHYSICS
        type.startsWith("AudioStream") || type.startsWith("Audio") -> SOUND
        type.startsWith("Control") -> UI_EDIT
        type.startsWith("Script") -> SCRIPT
        type.startsWith("AnimationPlayer") -> ANIMATION
        else -> NODE
    }

    fun forComponent(type: String): Int = when (type) {
        "Sprite2D" -> SPRITE
        "AnimatedSprite2D" -> ANIMATION
        "Label2D" -> TEXT
        "Camera2D" -> CAMERA
        "TileMap" -> TILESET
        "Particles2D" -> PARTICLES
        "Rigidbody2D", "Area2D", "RayCast2D" -> PHYSICS
        "CollisionShape2D" -> COLLIDER
        "AudioStream2D" -> SOUND
        "Script" -> SCRIPT
        "Control" -> UI_EDIT
        "AnimationPlayer" -> KEYFRAME
        "Timer" -> CLOCK
        "SignalEmitter" -> WAVE
        else -> NODE
    }

    // ------------------------------------------------------------------ painting

    fun draw(canvas: Canvas, kind: Int, left: Float, top: Float, size: Float, color: Int, strokeWidth: Float = 0f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (strokeWidth > 0f) strokeWidth else (size * 0.09f).coerceAtLeast(1.2f)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val stroke = p.strokeWidth
        // local helper: maps unit coordinates to the icon box, inset by half a stroke
        val inset = stroke * 0.5f
        val s = size - stroke
        val x: (Float) -> Float = { u -> left + inset + u * s }
        val y: (Float) -> Float = { v -> top + inset + v * s }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = canvas.drawLine(x(x0), y(y0), x(x1), y(y1), p)
        fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = canvas.drawRect(x(x0), y(y0), x(x1), y(y1), p)
        fun fillRect(x0: Float, y0: Float, x1: Float, y1: Float) {
            val old = p.style
            p.style = Paint.Style.FILL
            canvas.drawRect(x(x0), y(y0), x(x1), y(y1), p)
            p.style = old
        }
        fun circle(cx: Float, cy: Float, r: Float) = canvas.drawCircle(x(cx), y(cy), r * s * 0.5f, p)
        fun fillCircle(cx: Float, cy: Float, r: Float) {
            val old = p.style
            p.style = Paint.Style.FILL
            canvas.drawCircle(x(cx), y(cy), r * s * 0.5f, p)
            p.style = old
        }
        fun path(build: Path.() -> Unit) {
            val ph = Path()
            ph.build()
            p.style = Paint.Style.STROKE
            canvas.drawPath(ph, p)
        }

        when (kind) {
            SELECT -> {
                // arrow cursor
                val ph = Path()
                ph.moveTo(x(0.28f), y(0.14f)); ph.lineTo(x(0.28f), y(0.84f)); ph.lineTo(x(0.46f), y(0.66f))
                ph.lineTo(x(0.60f), y(0.90f)); ph.lineTo(x(0.72f), y(0.82f)); ph.lineTo(x(0.58f), y(0.58f))
                ph.lineTo(x(0.78f), y(0.54f)); ph.close()
                p.style = Paint.Style.STROKE
                canvas.drawPath(ph, p)
            }
            MOVE -> {
                line(0.5f, 0.10f, 0.5f, 0.90f); line(0.10f, 0.5f, 0.90f, 0.5f)
                arrow(canvas, p, x, y, 0.5f, 0.10f, 0f, -1f)   // up
                arrow(canvas, p, x, y, 0.5f, 0.90f, 0f, 1f)    // down
                arrow(canvas, p, x, y, 0.10f, 0.5f, -1f, 0f)   // left
                arrow(canvas, p, x, y, 0.90f, 0.5f, 1f, 0f)    // right
            }
            ROTATE -> {
                path {
                    val r = RectF(x(0.14f), y(0.14f), x(0.86f), y(0.86f))
                    addArc(r, 200f, 280f)
                }
                arrow(canvas, p, x, y, 0.80f, 0.30f, 0.6f, -0.8f)
            }
            SCALE -> {
                rect(0.10f, 0.10f, 0.58f, 0.58f)
                line(0.52f, 0.52f, 0.88f, 0.88f)
                fillRect(0.80f, 0.80f, 0.94f, 0.94f)
                fillRect(0.80f, 0.30f, 0.94f, 0.44f)
                fillRect(0.30f, 0.80f, 0.44f, 0.94f)
            }
            PIVOT -> {
                path {
                    val r = RectF(x(0.16f), y(0.16f), x(0.84f), y(0.84f))
                    addArc(r, 0f, 360f)
                }
                fillCircle(0.5f, 0.5f, 0.18f)
                line(0.16f, 0.5f, 0.30f, 0.5f); line(0.70f, 0.5f, 0.84f, 0.5f)
                line(0.5f, 0.16f, 0.5f, 0.30f); line(0.5f, 0.70f, 0.5f, 0.84f)
            }
            RECT -> {
                rect(0.12f, 0.22f, 0.88f, 0.78f)
                val old = p.style
                p.style = Paint.Style.STROKE
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(stroke * 1.6f, stroke * 1.4f), 0f)
                rect(0.24f, 0.34f, 0.76f, 0.66f)
                p.pathEffect = null
                p.style = old
            }
            TILE -> {
                rect(0.10f, 0.10f, 0.90f, 0.90f)
                line(0.10f, 0.43f, 0.90f, 0.43f); line(0.10f, 0.70f, 0.90f, 0.70f)
                line(0.43f, 0.10f, 0.43f, 0.90f); line(0.70f, 0.10f, 0.70f, 0.90f)
                fillRect(0.10f, 0.10f, 0.43f, 0.43f)
            }
            UI_EDIT -> {
                rect(0.10f, 0.16f, 0.90f, 0.84f)
                fillRect(0.10f, 0.16f, 0.90f, 0.30f)
                line(0.22f, 0.48f, 0.62f, 0.48f)
                line(0.22f, 0.64f, 0.50f, 0.64f)
            }
            GRID -> {
                rect(0.10f, 0.10f, 0.90f, 0.90f)
                for (i in 1..3) {
                    val f = 0.10f + i * 0.20f
                    line(f, 0.10f, f, 0.90f)
                    line(0.10f, f, 0.90f, f)
                }
            }
            SNAP -> {
                line(0.16f, 0.16f, 0.16f, 0.84f)
                line(0.16f, 0.50f, 0.62f, 0.50f)
                fillRect(0.56f, 0.42f, 0.72f, 0.58f)
                line(0.16f, 0.16f, 0.72f, 0.16f)
            }
            COLLIDER -> {
                path {
                    moveTo(x(0.50f), y(0.12f)); lineTo(x(0.86f), y(0.34f)); lineTo(x(0.74f), y(0.80f))
                    lineTo(x(0.26f), y(0.80f)); lineTo(x(0.14f), y(0.34f)); close()
                }
                fillCircle(0.50f, 0.12f, 0.10f)
            }
            CAMERA -> {
                rect(0.14f, 0.28f, 0.86f, 0.74f)
                path {
                    moveTo(x(0.62f), y(0.30f)); lineTo(x(0.74f), y(0.16f)); lineTo(x(0.86f), y(0.16f)); lineTo(x(0.86f), y(0.30f))
                }
                fillCircle(0.40f, 0.51f, 0.16f)
            }
            GUIDE -> {
                val old = p.style
                p.style = Paint.Style.STROKE
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(stroke * 1.4f, stroke * 1.6f), 0f)
                line(0.16f, 0.10f, 0.16f, 0.90f)
                p.pathEffect = null
                p.style = old
                line(0.28f, 0.50f, 0.88f, 0.50f)
            }
            PHYSICS -> {
                circle(0.34f, 0.34f, 0.44f)
                line(0.68f, 0.68f, 0.94f, 0.94f)
                fillCircle(0.30f, 0.86f, 0.16f)
            }
            PIXEL_GRID -> {
                for (i in 0..3) for (j in 0..3) {
                    if ((i + j) % 2 == 0) fillRect(i * 0.22f + 0.06f, j * 0.22f + 0.06f, i * 0.22f + 0.22f, j * 0.22f + 0.22f)
                }
            }
            PLAY -> {
                val ph = Path()
                ph.moveTo(x(0.28f), y(0.14f)); ph.lineTo(x(0.84f), y(0.50f)); ph.lineTo(x(0.28f), y(0.86f)); ph.close()
                p.style = Paint.Style.FILL
                canvas.drawPath(ph, p)
            }
            STOP -> fillRect(0.22f, 0.22f, 0.78f, 0.78f)
            PAUSE -> {
                fillRect(0.24f, 0.18f, 0.42f, 0.82f)
                fillRect(0.58f, 0.18f, 0.76f, 0.82f)
            }
            STEP -> {
                fillRect(0.22f, 0.20f, 0.42f, 0.80f)
                val ph = Path()
                ph.moveTo(x(0.48f), y(0.20f)); ph.lineTo(x(0.84f), y(0.50f)); ph.lineTo(x(0.48f), y(0.80f)); ph.close()
                p.style = Paint.Style.FILL
                canvas.drawPath(ph, p)
            }
            SAVE -> {
                path {
                    moveTo(x(0.14f), y(0.14f)); lineTo(x(0.70f), y(0.14f)); lineTo(x(0.86f), y(0.30f))
                    lineTo(x(0.86f), y(0.86f)); lineTo(x(0.14f), y(0.86f)); close()
                }
                fillRect(0.32f, 0.14f, 0.66f, 0.40f)
                rect(0.28f, 0.58f, 0.72f, 0.86f)
            }
            UNDO -> {
                path {
                    val r = RectF(x(0.14f), y(0.26f), x(0.86f), y(0.98f))
                    addArc(r, 180f, 180f)
                }
                arrow(canvas, p, x, y, 0.14f, 0.62f, -0.2f, -1f)
            }
            REDO -> {
                path {
                    val r = RectF(x(0.14f), y(0.26f), x(0.86f), y(0.98f))
                    addArc(r, 180f, 180f)
                }
                arrow(canvas, p, x, y, 0.86f, 0.62f, 0.2f, -1f)
            }
            SEARCH -> {
                circle(0.42f, 0.42f, 0.52f)
                line(0.62f, 0.62f, 0.88f, 0.88f)
            }
            PLUS -> {
                line(0.5f, 0.16f, 0.5f, 0.84f)
                line(0.16f, 0.5f, 0.84f, 0.5f)
            }
            MINUS -> line(0.16f, 0.5f, 0.84f, 0.5f)
            CLOSE -> {
                line(0.20f, 0.20f, 0.80f, 0.80f)
                line(0.80f, 0.20f, 0.20f, 0.80f)
            }
            CHECK -> {
                line(0.18f, 0.52f, 0.42f, 0.76f)
                line(0.42f, 0.76f, 0.84f, 0.24f)
            }
            TRASH -> {
                line(0.14f, 0.26f, 0.86f, 0.26f)
                path {
                    moveTo(x(0.24f), y(0.26f)); lineTo(x(0.30f), y(0.88f)); lineTo(x(0.70f), y(0.88f)); lineTo(x(0.76f), y(0.26f))
                }
                line(0.38f, 0.16f, 0.62f, 0.16f)
            }
            RENAME -> {
                fillRect(0.10f, 0.70f, 0.60f, 0.82f)     // text line
                path {
                    moveTo(x(0.52f), y(0.60f)); lineTo(x(0.82f), y(0.16f)); lineTo(x(0.94f), y(0.26f))
                    lineTo(x(0.64f), y(0.70f)); close()
                }
            }
            COPY -> {
                rect(0.12f, 0.12f, 0.62f, 0.62f)
                rect(0.38f, 0.38f, 0.88f, 0.88f)
            }
            IMPORT -> {
                line(0.5f, 0.10f, 0.5f, 0.62f)
                arrow(canvas, p, x, y, 0.5f, 0.62f, 0f, 1f)
                path {
                    moveTo(x(0.18f), y(0.66f)); lineTo(x(0.18f), y(0.88f)); lineTo(x(0.82f), y(0.88f)); lineTo(x(0.82f), y(0.66f))
                }
            }
            EXPORT, UPLOAD -> {
                line(0.5f, 0.62f, 0.5f, 0.10f)
                arrow(canvas, p, x, y, 0.5f, 0.10f, 0f, -1f)
                path {
                    moveTo(x(0.18f), y(0.62f)); lineTo(x(0.18f), y(0.88f)); lineTo(x(0.82f), y(0.88f)); lineTo(x(0.82f), y(0.62f))
                }
            }
            FOLDER -> {
                path {
                    moveTo(x(0.10f), y(0.24f)); lineTo(x(0.44f), y(0.24f)); lineTo(x(0.52f), y(0.36f))
                    lineTo(x(0.90f), y(0.36f)); lineTo(x(0.90f), y(0.82f)); lineTo(x(0.10f), y(0.82f)); close()
                }
            }
            FILE -> {
                path {
                    moveTo(x(0.24f), y(0.10f)); lineTo(x(0.62f), y(0.10f)); lineTo(x(0.78f), y(0.28f))
                    lineTo(x(0.78f), y(0.90f)); lineTo(x(0.24f), y(0.90f)); close()
                }
                line(0.62f, 0.10f, 0.62f, 0.28f); line(0.62f, 0.28f, 0.78f, 0.28f)
                line(0.36f, 0.50f, 0.66f, 0.50f); line(0.36f, 0.64f, 0.66f, 0.64f)
            }
            IMAGE, TEXTURE -> {
                rect(0.10f, 0.18f, 0.90f, 0.82f)
                fillCircle(0.32f, 0.36f, 0.12f)
                path {
                    moveTo(x(0.16f), y(0.78f)); lineTo(x(0.42f), y(0.50f)); lineTo(x(0.62f), y(0.70f))
                    lineTo(x(0.74f), y(0.58f)); lineTo(x(0.86f), y(0.78f))
                }
            }
            SOUND -> {
                val ph = Path()
                ph.moveTo(x(0.16f), y(0.38f)); ph.lineTo(x(0.34f), y(0.38f)); ph.lineTo(x(0.56f), y(0.18f))
                ph.lineTo(x(0.56f), y(0.82f)); ph.lineTo(x(0.34f), y(0.62f)); ph.lineTo(x(0.16f), y(0.62f)); ph.close()
                p.style = Paint.Style.STROKE
                canvas.drawPath(ph, p)
                path { addArc(RectF(x(0.56f), y(0.30f), x(0.86f), y(0.70f)), -60f, 120f) }
            }
            SPEAKER -> {
                val ph = Path()
                ph.moveTo(x(0.14f), y(0.36f)); ph.lineTo(x(0.34f), y(0.36f)); ph.lineTo(x(0.58f), y(0.14f))
                ph.lineTo(x(0.58f), y(0.86f)); ph.lineTo(x(0.34f), y(0.64f)); ph.lineTo(x(0.14f), y(0.64f)); ph.close()
                p.style = Paint.Style.FILL
                canvas.drawPath(ph, p)
            }
            WAVE -> {
                path {
                    moveTo(x(0.10f), y(0.50f))
                    cubicTo(x(0.22f), y(0.10f), x(0.32f), y(0.90f), x(0.44f), y(0.50f))
                    cubicTo(x(0.56f), y(0.10f), x(0.66f), y(0.90f), x(0.78f), y(0.50f))
                    lineTo(x(0.90f), y(0.50f))
                }
            }
            SCRIPT -> {
                rect(0.10f, 0.14f, 0.90f, 0.86f)
                line(0.32f, 0.36f, 0.52f, 0.50f); line(0.52f, 0.50f, 0.32f, 0.64f)
                line(0.58f, 0.66f, 0.78f, 0.66f)
            }
            SHADER -> {
                path {
                    moveTo(x(0.50f), y(0.10f)); lineTo(x(0.50f), y(0.34f))
                }
                path {
                    moveTo(x(0.26f), y(0.34f)); lineTo(x(0.74f), y(0.34f))
                }
                path {
                    moveTo(x(0.26f), y(0.34f)); lineTo(x(0.26f), y(0.62f)); lineTo(x(0.42f), y(0.62f)); lineTo(x(0.42f), y(0.80f))
                }
                path {
                    moveTo(x(0.74f), y(0.34f)); lineTo(x(0.74f), y(0.62f)); lineTo(x(0.58f), y(0.62f)); lineTo(x(0.58f), y(0.80f))
                }
                fillCircle(0.42f, 0.86f, 0.10f)
                fillCircle(0.58f, 0.86f, 0.10f)
            }
            ANIMATION, KEYFRAME -> {
                path {
                    moveTo(x(0.28f), y(0.50f)); lineTo(x(0.50f), y(0.26f)); lineTo(x(0.72f), y(0.50f)); lineTo(x(0.50f), y(0.74f)); close()
                }
                line(0.10f, 0.88f, 0.90f, 0.88f)
                fillCircle(0.28f, 0.50f, 0.07f)
                fillCircle(0.72f, 0.50f, 0.07f)
            }
            TILESET -> {
                rect(0.10f, 0.14f, 0.90f, 0.86f)
                fillRect(0.14f, 0.18f, 0.45f, 0.45f)
                fillRect(0.55f, 0.50f, 0.86f, 0.82f)
                line(0.50f, 0.14f, 0.50f, 0.86f)
                line(0.10f, 0.50f, 0.90f, 0.50f)
            }
            PARTICLES -> {
                fillCircle(0.30f, 0.30f, 0.13f)
                fillCircle(0.66f, 0.24f, 0.09f)
                fillCircle(0.52f, 0.54f, 0.15f)
                fillCircle(0.24f, 0.68f, 0.10f)
                fillCircle(0.76f, 0.72f, 0.12f)
            }
            SCENE, WORLD -> {
                rect(0.10f, 0.42f, 0.44f, 0.88f)
                rect(0.52f, 0.12f, 0.90f, 0.60f)
                line(0.27f, 0.42f, 0.71f, 0.36f)
            }
            PREFAB -> {
                path {
                    moveTo(x(0.50f), y(0.14f)); lineTo(x(0.86f), y(0.34f)); lineTo(x(0.50f), y(0.54f)); lineTo(x(0.14f), y(0.34f)); close()
                }
                path {
                    moveTo(x(0.14f), y(0.34f)); lineTo(x(0.14f), y(0.66f)); lineTo(x(0.50f), y(0.86f)); lineTo(x(0.86f), y(0.66f)); lineTo(x(0.86f), y(0.34f))
                }
                line(0.50f, 0.54f, 0.50f, 0.86f)
            }
            MATERIAL -> {
                circle(0.50f, 0.50f, 0.74f)
                fillCircle(0.50f, 0.50f, 0.36f)
            }
            STAR -> {
                path {
                    moveTo(x(0.50f), y(0.10f)); lineTo(x(0.62f), y(0.40f)); lineTo(x(0.92f), y(0.42f))
                    lineTo(x(0.68f), y(0.60f)); lineTo(x(0.76f), y(0.90f)); lineTo(x(0.50f), y(0.72f))
                    lineTo(x(0.24f), y(0.90f)); lineTo(x(0.32f), y(0.60f)); lineTo(x(0.08f), y(0.42f))
                    lineTo(x(0.38f), y(0.40f)); close()
                }
            }
            CLOCK -> {
                circle(0.50f, 0.50f, 0.80f)
                line(0.50f, 0.26f, 0.50f, 0.52f)
                line(0.50f, 0.52f, 0.70f, 0.62f)
            }
            EYE -> {
                path {
                    moveTo(x(0.08f), y(0.50f))
                    cubicTo(x(0.30f), y(0.14f), x(0.70f), y(0.14f), x(0.92f), y(0.50f))
                    cubicTo(x(0.70f), y(0.86f), x(0.30f), y(0.86f), x(0.08f), y(0.50f))
                }
                fillCircle(0.50f, 0.50f, 0.24f)
            }
            LOCK -> {
                rect(0.20f, 0.46f, 0.80f, 0.88f)
                path { addArc(RectF(x(0.30f), y(0.16f), x(0.70f), y(0.60f)), 180f, 180f) }
            }
            FOCUS -> {
                circle(0.50f, 0.50f, 0.60f)
                line(0.50f, 0.06f, 0.50f, 0.24f); line(0.50f, 0.76f, 0.50f, 0.94f)
                line(0.06f, 0.50f, 0.24f, 0.50f); line(0.76f, 0.50f, 0.94f, 0.50f)
            }
            ZOOM_IN, ZOOM_OUT -> {
                circle(0.42f, 0.42f, 0.56f)
                line(0.62f, 0.62f, 0.90f, 0.90f)
                line(0.28f, 0.42f, 0.56f, 0.42f)
                if (kind == ZOOM_IN) line(0.42f, 0.28f, 0.42f, 0.56f)
            }
            LAYERS -> {
                path {
                    moveTo(x(0.50f), y(0.10f)); lineTo(x(0.90f), y(0.34f)); lineTo(x(0.50f), y(0.58f)); lineTo(x(0.10f), y(0.34f)); close()
                }
                line(0.10f, 0.52f, 0.50f, 0.76f)
                line(0.50f, 0.76f, 0.90f, 0.52f)
                line(0.10f, 0.66f, 0.50f, 0.90f)
                line(0.50f, 0.90f, 0.90f, 0.66f)
            }
            SETTINGS -> {
                circle(0.50f, 0.50f, 0.42f)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    val cx = 0.5f + Math.cos(a).toFloat() * 0.36f
                    val cy = 0.5f + Math.sin(a).toFloat() * 0.36f
                    val ex = 0.5f + Math.cos(a).toFloat() * 0.48f
                    val ey = 0.5f + Math.sin(a).toFloat() * 0.48f
                    line(cx, cy, ex, ey)
                }
            }
            PALETTE -> {
                path {
                    val r = RectF(x(0.08f), y(0.12f), x(0.92f), y(0.92f))
                    addArc(r, 20f, 320f)
                }
                fillCircle(0.34f, 0.34f, 0.10f)
                fillCircle(0.62f, 0.30f, 0.10f)
                fillCircle(0.70f, 0.60f, 0.10f)
            }
            DEBUG -> {
                path {
                    moveTo(x(0.16f), y(0.30f)); lineTo(x(0.40f), y(0.30f)); lineTo(x(0.50f), y(0.44f))
                    lineTo(x(0.60f), y(0.30f)); lineTo(x(0.84f), y(0.30f))
                }
                line(0.50f, 0.44f, 0.50f, 0.78f)
                line(0.26f, 0.60f, 0.40f, 0.60f)
                line(0.60f, 0.60f, 0.74f, 0.60f)
                line(0.26f, 0.74f, 0.40f, 0.74f)
                line(0.60f, 0.74f, 0.74f, 0.74f)
            }
            PROFILE -> {
                fillRect(0.14f, 0.56f, 0.30f, 0.88f)
                fillRect(0.38f, 0.34f, 0.54f, 0.88f)
                fillRect(0.62f, 0.18f, 0.78f, 0.88f)
                line(0.10f, 0.90f, 0.90f, 0.90f)
            }
            CONSOLE -> {
                rect(0.08f, 0.16f, 0.92f, 0.84f)
                line(0.28f, 0.38f, 0.44f, 0.50f); line(0.44f, 0.50f, 0.28f, 0.62f)
                line(0.54f, 0.64f, 0.74f, 0.64f)
            }
            NODE -> {
                rect(0.34f, 0.34f, 0.66f, 0.66f)
                line(0.50f, 0.10f, 0.50f, 0.34f)
                line(0.10f, 0.50f, 0.34f, 0.50f)
                line(0.66f, 0.50f, 0.90f, 0.50f)
                line(0.50f, 0.66f, 0.50f, 0.90f)
            }
            SPRITE -> {
                rect(0.12f, 0.20f, 0.88f, 0.80f)
                fillCircle(0.36f, 0.40f, 0.12f)
                path {
                    moveTo(x(0.70f), y(0.44f)); lineTo(x(0.84f), y(0.44f)); lineTo(x(0.84f), y(0.66f)); lineTo(x(0.50f), y(0.66f))
                    lineTo(x(0.50f), y(0.58f)); lineTo(x(0.70f), y(0.58f)); close()
                }
            }
            TEXT -> {
                line(0.16f, 0.20f, 0.84f, 0.20f)
                line(0.50f, 0.20f, 0.50f, 0.84f)
                line(0.34f, 0.84f, 0.66f, 0.84f)
            }
            CROP -> {
                line(0.24f, 0.06f, 0.24f, 0.76f)
                line(0.24f, 0.76f, 0.94f, 0.76f)
                line(0.06f, 0.24f, 0.76f, 0.24f)
                line(0.76f, 0.24f, 0.76f, 0.94f)
            }
            GRID_SLICE -> {
                rect(0.10f, 0.20f, 0.90f, 0.80f)
                line(0.37f, 0.20f, 0.37f, 0.80f)
                line(0.63f, 0.20f, 0.63f, 0.80f)
                line(0.10f, 0.50f, 0.90f, 0.50f)
            }
            FLIP -> {
                line(0.50f, 0.06f, 0.50f, 0.94f)
                path {
                    moveTo(x(0.42f), y(0.22f)); lineTo(x(0.10f), y(0.50f)); lineTo(x(0.42f), y(0.78f)); close()
                }
                path {
                    moveTo(x(0.58f), y(0.22f)); lineTo(x(0.90f), y(0.50f)); lineTo(x(0.58f), y(0.78f)); close()
                }
            }
            CHEVRON_DOWN -> {
                line(0.22f, 0.38f, 0.50f, 0.66f)
                line(0.50f, 0.66f, 0.78f, 0.38f)
            }
            CHEVRON_RIGHT -> {
                line(0.38f, 0.22f, 0.66f, 0.50f)
                line(0.66f, 0.50f, 0.38f, 0.78f)
            }
            ARROW_UP -> {
                line(0.50f, 0.84f, 0.50f, 0.22f)
                arrow(canvas, p, x, y, 0.50f, 0.22f, 0f, -1f)
            }
            PACKAGE -> {
                path {
                    moveTo(x(0.50f), y(0.08f)); lineTo(x(0.92f), y(0.30f)); lineTo(x(0.92f), y(0.70f))
                    lineTo(x(0.50f), y(0.92f)); lineTo(x(0.08f), y(0.70f)); lineTo(x(0.08f), y(0.30f)); close()
                }
                line(0.08f, 0.30f, 0.50f, 0.52f); line(0.50f, 0.52f, 0.92f, 0.30f)
                line(0.50f, 0.52f, 0.50f, 0.92f)
            }
            PLUGIN -> {
                path {
                    moveTo(x(0.24f), y(0.48f)); lineTo(x(0.24f), y(0.82f)); lineTo(x(0.58f), y(0.82f)); lineTo(x(0.58f), y(0.48f))
                }
                line(0.34f, 0.48f, 0.34f, 0.18f)
                line(0.48f, 0.48f, 0.48f, 0.18f)
                path {
                    moveTo(x(0.58f), y(0.68f)); lineTo(x(0.82f), y(0.68f))
                }
            }
            else -> rect(0.18f, 0.18f, 0.82f, 0.82f)
        }
    }

    /**
     * Filled arrow head used by the directional icons. [dirX]/[dirY] point away from the tip, so a
     * "move up" arrow uses (0,-1).
     */
    private fun arrow(
        canvas: Canvas,
        p: Paint,
        x: (Float) -> Float,
        y: (Float) -> Float,
        tipX: Float,
        tipY: Float,
        dirX: Float,
        dirY: Float,
        scale: Float = 0.16f
    ) {
        val len = scale * (x(1f) - x(0f))
        val px = x(tipX)
        val py = y(tipY)
        val path = Path()
        path.moveTo(px, py)
        path.lineTo(px + dirX * len - dirY * len * 0.7f, py + dirY * len + dirX * len * 0.7f)
        path.lineTo(px + dirX * len + dirY * len * 0.7f, py + dirY * len - dirX * len * 0.7f)
        path.close()
        val old = p.style
        p.style = Paint.Style.FILL
        canvas.drawPath(path, p)
        p.style = old
    }
}

/** A single icon, sized in dp and tinted by the theme. */
class IconView(context: Context, val theme: Theme, var kind: Int, val sizeDp: Float = 18f) : View(context) {
    var color: Int = theme.text

    init {
        val px = theme.dp(sizeDp)
        layoutParams = ViewGroup.LayoutParams(px, px)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val px = theme.dp(sizeDp)
        setMeasuredDimension(px, px)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        Icons.draw(canvas, kind, 0f, 0f, s, color)
    }
}

/**
 * Square icon button used by toolbars. Shows only the icon, so nothing can wrap or ellipsize, and
 * carries the human readable name as its accessibility description (tooltips show it on hover).
 */
class IconButton(
    context: Context,
    val theme: Theme,
    val kind: Int,
    private val label: String,
    val onClick: () -> Unit,
    var toggled: Boolean = false,
    val sizeDp: Float = 36f
) : FrameLayout(context) {

    private val icon = IconView(context, theme, kind, 18f)

    init {
        val px = theme.dp(sizeDp)
        layoutParams = ViewGroup.LayoutParams(px, px)
        addView(icon, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        contentDescription = label
        isClickable = true
        isFocusable = true
        refresh()
        setOnClickListener { onClick() }
    }

    fun refresh() {
        background = theme.rounded(
            if (toggled) theme.selection else Color.TRANSPARENT,
            6f,
            if (toggled) theme.accent else 0
        )
        icon.color = if (toggled) theme.text else theme.textDim
        icon.invalidate()
    }

}
