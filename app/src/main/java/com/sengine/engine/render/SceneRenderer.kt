package com.sengine.engine.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.sengine.engine.Engine
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.math.Affine
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the scene. When [editor] is non-null and the engine is in edit mode,
 * the editor camera, grid and gizmos are drawn as well.
 */
class SceneRenderer(private val engine: Engine, private val editor: EditorState?) : GLSurfaceView.Renderer {

    private val r = Renderer2D()
    private val textures = TextureCache(engine.project)
    private var lastNs = 0L
    private val tmp = Affine()
    private val tmp2 = Affine()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        r.init()
        textures.clear()
        lastNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        engine.gameView.widthPx = width; engine.gameView.heightPx = height
        editor?.view?.let { it.widthPx = width; it.heightPx = height }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        synchronized(engine.lock) {
            engine.tick(dt)
            val editing = editor != null && engine.mode == Engine.Mode.EDIT
            val view = if (editing) editor!!.view else engine.gameView
            val bg = engine.backgroundColor()
            val bgEdit = if (editing) 0xFF262B33.toInt() else bg
            GLES20.glClearColor(GL.r(bgEdit), GL.g(bgEdit), GL.b(bgEdit), 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            r.begin(view)
            if (editing && editor!!.showGrid) drawGrid(view)
            drawScene(view)
            if (editing) drawEditorOverlay(view, editor!!)
        }
    }

    private fun drawScene(view: View2D) {
        val scene = engine.scene
        val ppu = view.pixelsPerUnit
        val list = scene.objects.withIndex()
            .filter { it.value.isActiveInHierarchy() }
            .sortedWith(compareBy({ it.value.order }, { it.index }))
        for ((_, go) in list) {
            val w = go.world
            go.get<SpriteRenderer>()?.let { sr ->
                val tex = if (sr.texture.isNotBlank()) textures.image(sr.texture) else null
                r.quad(w, sr.color, if (tex != null) 0 else sr.shape, tex,
                    min(w.scaleX, w.scaleY) * ppu, sr.flipX, sr.flipY)
            }
            go.get<TextRenderer>()?.let { tr ->
                if (tr.text.isNotEmpty()) {
                    val tex = textures.text(tr.text, tr.bold, tr.align)
                    val lines = tr.text.count { it == '\n' } + 1
                    val hh = tr.size * lines
                    val ww = hh * tex.w / tex.h
                    val ox = when (tr.align) { 0 -> ww / 2; 2 -> -ww / 2; else -> 0f }
                    tmp2.a = ww; tmp2.b = 0f; tmp2.c = 0f; tmp2.d = hh; tmp2.tx = ox; tmp2.ty = 0f
                    tmp.setMul(w, tmp2)
                    r.quad(tmp, tr.color, 0, tex, 100f)
                }
            }
            go.getAny<ParticleEmitter>()?.let { pe -> drawParticles(pe, ppu) }
        }
    }

    private fun drawParticles(pe: ParticleEmitter, ppu: Float) {
        for (p in pe.particles) {
            val t = (p.age / p.life).coerceIn(0f, 1f)
            val size = pe.startSize + (pe.endSize - pe.startSize) * t
            if (size <= 0f) continue
            r.rect(p.x, p.y, size, size, lerpColor(pe.startColor, pe.endColor, t), 1, ppu)
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun drawGrid(view: View2D) {
        val ppu = view.pixelsPerUnit
        var step = 1f
        while (step * ppu < 24f) step *= 5f
        while (step * ppu > 200f) step /= 5f
        val left = view.cx - view.halfW
        val right = view.cx + view.halfW
        val bottom = view.cy - view.size
        val top = view.cy + view.size
        val minor = 0x22FFFFFF
        val major = 0x44FFFFFF
        var x = floor(left / step) * step
        var i = 0
        while (x <= right && i++ < 400) {
            val idx = Math.round(x / step)
            r.line(x, bottom, x, top, if (abs(x) < step * 0.01f) 0xAA7FA7FF.toInt() else if (idx % 5 == 0) major else minor)
            x += step
        }
        var y = floor(bottom / step) * step
        i = 0
        while (y <= top && i++ < 400) {
            val idx = Math.round(y / step)
            r.line(left, y, right, y, if (abs(y) < step * 0.01f) 0xAAFF7F7F.toInt() else if (idx % 5 == 0) major else minor)
            y += step
        }
        r.flushLines(1f)
    }

    private fun drawEditorOverlay(view: View2D, ed: EditorState) {
        val scene = engine.scene
        val ppu = view.pixelsPerUnit
        val aspect = view.aspect
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val w = go.world
            go.get<Camera2D>()?.let { cam ->
                val cw = cam.size * aspect
                tmp.a = 1f; tmp.b = 0f; tmp.c = 0f; tmp.d = 1f; tmp.tx = w.tx; tmp.ty = w.ty
                r.obb(tmp, cw, cam.size, 0xCCFFFFFF.toInt())
                val s = 12f / ppu
                r.line(w.tx - s, w.ty, w.tx + s, w.ty, 0xCCFFFFFF.toInt())
                r.line(w.tx, w.ty - s, w.tx, w.ty + s, 0xCCFFFFFF.toInt())
            }
            if (ed.showColliders || go.id == ed.selectedId) go.get<Collider2D>()?.let { c -> drawCollider(go, c) }
            if (go.get<SpriteRenderer>() == null && go.get<TextRenderer>() == null && go.get<Camera2D>() == null) {
                val s = 8f / ppu
                r.line(w.tx - s, w.ty, w.tx, w.ty + s, 0x99FFFFFF.toInt())
                r.line(w.tx, w.ty + s, w.tx + s, w.ty, 0x99FFFFFF.toInt())
                r.line(w.tx + s, w.ty, w.tx, w.ty - s, 0x99FFFFFF.toInt())
                r.line(w.tx, w.ty - s, w.tx - s, w.ty, 0x99FFFFFF.toInt())
            }
        }
        val sel = scene.findById(ed.selectedId)
        if (sel != null && sel.isActiveInHierarchy()) {
            r.obb(sel.world, 0.5f, 0.5f, 0xFFFF9F1C.toInt())
            r.flushLines(2f)
            drawGizmo(sel, ed, ppu)
        } else r.flushLines(2f)
    }

    private fun drawCollider(go: GameObject, c: Collider2D) {
        val w = go.world
        val cx = w.mapX(c.offsetX, c.offsetY)
        val cy = w.mapY(c.offsetX, c.offsetY)
        val color = if (c.isTrigger) 0xCC4FC3F7.toInt() else 0xCC66FF66.toInt()
        if (c.shape == 1) r.circleLines(cx, cy, c.radius * max(w.scaleX, w.scaleY), color)
        else {
            tmp.a = 1f; tmp.b = 0f; tmp.c = 0f; tmp.d = 1f; tmp.tx = cx; tmp.ty = cy
            r.obb(tmp, c.width * w.scaleX / 2, c.height * w.scaleY / 2, color)
        }
    }

    private fun drawGizmo(go: GameObject, ed: EditorState, ppu: Float) {
        val w = go.world
        val x = w.tx
        val y = w.ty
        val len = ed.gizmoLength()
        val hs = 14f / ppu * (ed.view.heightPx / 1080f).coerceAtLeast(0.6f)
        val red = if (ed.activeAxis == 1) 0xFFFFFF66.toInt() else 0xFFFF4D4D.toInt()
        val green = if (ed.activeAxis == 2) 0xFFFFFF66.toInt() else 0xFF5CE65C.toInt()
        val free = if (ed.activeAxis == 3) 0xFFFFFF66.toInt() else 0xCCFFD24D.toInt()
        when (ed.tool) {
            Tool.MOVE -> {
                r.line(x, y, x + len, y, red); r.line(x, y, x, y + len, green)
                r.flushLines(5f)
                val rot = Affine().setTRS(x + len + hs * 0.6f, y, -90f, hs * 1.8f, hs * 1.8f)
                r.quad(rot, red, 2, null, hs * 1.8f * ppu)
                val rot2 = Affine().setTRS(x, y + len + hs * 0.6f, 0f, hs * 1.8f, hs * 1.8f)
                r.quad(rot2, green, 2, null, hs * 1.8f * ppu)
                r.rect(x + len * 0.18f, y + len * 0.18f, hs * 1.6f, hs * 1.6f, free, 0, ppu)
            }
            Tool.ROTATE -> {
                r.circleLines(x, y, len, if (ed.activeAxis != 0) 0xFFFFFF66.toInt() else 0xFF4DA6FF.toInt(), 64)
                val a = Math.toRadians(go.rotation.toDouble())
                r.line(x, y, x + (Math.cos(a) * len).toFloat(), y + (Math.sin(a) * len).toFloat(), 0xFF4DA6FF.toInt())
                r.flushLines(4f)
                r.rect(x, y, hs, hs, 0xFF4DA6FF.toInt(), 1, ppu)
            }
            Tool.SCALE -> {
                r.line(x, y, x + len, y, red); r.line(x, y, x, y + len, green)
                r.flushLines(5f)
                r.rect(x + len, y, hs * 1.6f, hs * 1.6f, red, 0, ppu)
                r.rect(x, y + len, hs * 1.6f, hs * 1.6f, green, 0, ppu)
                r.rect(x, y, hs * 1.8f, hs * 1.8f, free, 0, ppu)
            }
            Tool.HAND -> {}
        }
    }
}
