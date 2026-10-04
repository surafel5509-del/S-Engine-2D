package com.sengine.platform.gl

import android.opengl.GLES20
import com.sengine.engine.core.RenderShape
import com.sengine.engine.math.Rect2
import com.sengine.engine.render.BlendKind
import com.sengine.engine.render.Camera2DView
import com.sengine.engine.render.Primitive
import com.sengine.engine.render.RenderItem
import com.sengine.engine.render.RenderList
import com.sengine.engine.render.TextRasterizer
import com.sengine.engine.render.TextureSource

/**
 * Batched GLES2 renderer for [RenderList]es.
 *
 * Everything is drawn as triangles with one of two programs (solid / textured). Items are sorted by
 * [RenderList] into a batch friendly order and merged here until the batch key changes (program,
 * texture, blend mode, clip rectangle). Rotation, pivoting, flipping, pixel snapping and nine-slice
 * expansion happen on the CPU — in pixels — so the shaders stay trivial and fast.
 *
 * Clipping uses the scissor test, so nested UI clips cost one state change per clip rectangle.
 */
class GLRenderer2D(
    private val textures: TextureSource,
    private val textRasterizer: TextRasterizer
) {

    /** Returns the [com.sengine.engine.resources.Material] for a name (shader + uniform values). */
    var materialSource: ((String) -> com.sengine.engine.resources.Material?)? = null

    /** Called when a custom material shader fails to compile (shader editor + debugger show it). */
    var onShaderError: ((String, String) -> Unit)? = null

    /** Uniforms the engine animates every frame (`uTime`, …). */
    var globalUniforms: Map<String, Float> = emptyMap()

    var widthPx = 1
        private set
    var heightPx = 1
        private set

    var lineWidth = 2f
    var ringWidth = 2f

    private val batch = GL2.VertexBuffer(FLOATS_PER_VERTEX)
    private val materials = HashMap<String, MaterialProgram>()
    private var solid: GL2.Program? = null
    private var textured: GL2.Program? = null
    private var active: GL2.Program? = null
    private var activeTexture = -1
    private var activeBlend = -1
    private var activeClip = Rect2.EMPTY
    private var camera = Camera2DView()
    private var currentStats: com.sengine.engine.render.RenderStats? = null

    fun surfaceChanged(width: Int, height: Int) {
        widthPx = width.coerceAtLeast(1)
        heightPx = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, widthPx, heightPx)
    }

    fun dispose() {
        solid?.delete(); textured?.delete()
        for (m in materials.values) m.program.delete()
        materials.clear()
        solid = null; textured = null
    }

    /** Draws one frame. [list] must already be sorted (see `RenderList.sort`). */
    fun frame(list: RenderList, view: Camera2DView) {
        ensurePrograms()
        camera.copyFrom(view)
        val stats = list.stats
        stats.reset()
        currentStats = stats
        textures.markFrame()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glClearColor(
            ((view.backgroundColor shr 16) and 0xFF) / 255f,
            ((view.backgroundColor shr 8) and 0xFF) / 255f,
            (view.backgroundColor and 0xFF) / 255f,
            ((view.backgroundColor ushr 24) and 0xFF) / 255f
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        batch.clear()
        active = null
        activeTexture = -1
        activeBlend = -1
        activeClip = Rect2.EMPTY
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(0, 0, widthPx, heightPx)

        val items = list.items
        for (i in items.indices) {
            val item = items[i]
            stats.itemsSubmitted++
            draw(item)
        }
        flush()
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    // ------------------------------------------------------------------ item drawing

    private fun draw(item: RenderItem) {
        val s = scaleFor(item)
        when (item.primitive) {
            Primitive.TEXT -> drawText(item, s)
            Primitive.LINE -> drawLine(item, s)
            Primitive.CIRCLE -> { beginSolid(item); emitEllipse(place(item, s), item.color) }
            Primitive.RING -> { beginSolid(item); emitRing(place(item, s), item.color, ringWidth) }
            Primitive.TRIANGLE -> { beginSolid(item); emitTriangle(place(item, s), item.color) }
            Primitive.NINE_SLICE -> drawNineSlice(item, s)
            else -> drawQuadItem(item, s)
        }
    }

    private fun scaleFor(item: RenderItem) = if (item.space == 1) 1f else camera.pixelsPerUnit

    /** Origin + size of an item in screen pixels, honouring space, pivot, scale and rotation. */
    private fun place(item: RenderItem, s: Float): Placement {
        val p = Placement()
        if (item.space == 1) {
            p.x = item.x
            p.y = item.y
            p.rotation = item.rotation
        } else {
            p.x = (item.x - camera.cx) * s + widthPx * 0.5f
            p.y = heightPx * 0.5f - (item.y - camera.cy) * s
            p.rotation = -item.rotation
        }
        p.w = item.width * Math.abs(item.scaleX) * s
        p.h = item.height * Math.abs(item.scaleY) * s
        p.pivotX = item.pivotX
        p.pivotY = item.pivotY
        p.snap = item.u3 > 0.5f && item.space == 0
        if (p.snap) {
            p.x = Math.round(p.x).toFloat()
            p.y = Math.round(p.y).toFloat()
        }
        return p
    }

    private class Placement {
        var x = 0f; var y = 0f; var w = 0f; var h = 0f
        var pivotX = 0f; var pivotY = 0f
        var rotation = 0f
        var snap = false
        val cos get() = kotlin.math.cos(Math.toRadians(rotation.toDouble())).toFloat()
        val sin get() = kotlin.math.sin(Math.toRadians(rotation.toDouble())).toFloat()

        /** Local pixel point (relative to the item origin) → screen pixels. */
        fun mapX(lx: Float, ly: Float): Float {
            val dx = lx - pivotX * w
            val dy = ly - pivotY * h
            val out = if (rotation == 0f) x + dx else x + dx * cos - dy * sin
            return if (snap) Math.round(out).toFloat() else out
        }

        fun mapY(lx: Float, ly: Float): Float {
            val dx = lx - pivotX * w
            val dy = ly - pivotY * h
            val out = if (rotation == 0f) y + dy else y + dx * sin + dy * cos
            return if (snap) Math.round(out).toFloat() else out
        }
    }

    private fun drawQuadItem(item: RenderItem, s: Float) {
        val p = place(item, s)
        val textured = item.primitive == Primitive.SPRITE
        val tex = if (textured) textures.texture(item.texture) else 0
        if (tex != 0) {
            var u0 = 0f; var v0 = 0f; var u1 = 1f; var v1 = 1f
            val region = item.region
            val tw = item.textureWidth
            val th = item.textureHeight
            if (tw > 0 && th > 0) {
                val rw = if (region.width > 0f) region.width else tw.toFloat()
                val rh = if (region.height > 0f) region.height else th.toFloat()
                val rx = if (region.width > 0f) region.x else 0f
                val ry = if (region.width > 0f) region.y else 0f
                u0 = rx / tw; v0 = ry / th
                u1 = (rx + rw) / tw; v1 = (ry + rh) / th
            }
            if (item.u1 > 0.5f) { val t = u0; u0 = u1; u1 = t }
            if (item.u2 > 0.5f) { val t = v0; v0 = v1; v1 = t }
            begin(tex, item)
            emitQuad(p, 0f, 0f, p.w, p.h, u0, v0, u1, v1, item.color)
            return
        }
        // solid shapes are encoded as 1 + RenderShape in u0
        val shape = (item.u0 - 1f).toInt()
        beginSolid(item)
        when (shape) {
            RenderShape.CIRCLE -> emitEllipse(p, item.color)
            RenderShape.TRIANGLE -> emitTriangle(p, item.color)
            RenderShape.RING -> emitRing(p, item.color, ringWidth)
            else -> emitQuad(p, 0f, 0f, p.w, p.h, 0.5f, 0.5f, 0.5f, 0.5f, item.color)
        }
    }

    private fun drawText(item: RenderItem, s: Float) {
        if (item.text.isEmpty()) return
        val p = place(item, s)
        val run = textRasterizer.run(item.font, item.fontSize, item.bold, item.text) ?: return
        val tail = when (item.align.coerceIn(0, 2)) {
            0 -> 0f
            2 -> p.w - run.width
            else -> (p.w - run.width) * 0.5f
        }
        val baseY = p.y + (p.h - run.height) * 0.5f
        val program = if (item.material.isNotEmpty()) materialProgram(item.material) else null
        begin(run.texture, item, program)
        var i = 0
        val q = run.quads
        while (i < q.size) {
            val gx = q[i]; val gy = q[i + 1]; val gw = q[i + 2]; val gh = q[i + 3]
            val gu0 = q[i + 4]; val gv0 = q[i + 5]; val gu1 = q[i + 6]; val gv1 = q[i + 7]
            emitScreenQuad(p, p.x + tail + gx, baseY + gy, p.x + tail + gx + gw, baseY + gy + gh, gu0, gv0, gu1, gv1, item.color)
            i += 8
        }
    }

    private fun drawLine(item: RenderItem, s: Float) {
        val ax: Float; val ay: Float; val bx: Float; val by: Float
        if (item.space == 1) {
            ax = item.x; ay = item.y
            bx = item.x + item.width; by = item.y + item.height
        } else {
            ax = (item.x - camera.cx) * s + widthPx * 0.5f
            ay = heightPx * 0.5f - (item.y - camera.cy) * s
            bx = (item.x + item.width - camera.cx) * s + widthPx * 0.5f
            by = heightPx * 0.5f - (item.y + item.height - camera.cy) * s
        }
        val dx = bx - ax; val dy = by - ay
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len <= 0.0001f) return
        val nx = -dy / len * lineWidth * 0.5f
        val ny = dx / len * lineWidth * 0.5f
        beginSolid(item)
        val c = item.color
        putVertex(ax + nx, ay + ny, c)
        putVertex(ax - nx, ay - ny, c)
        putVertex(bx + nx, by + ny, c)
        putVertex(bx + nx, by + ny, c)
        putVertex(ax - nx, ay - ny, c)
        putVertex(bx - nx, by - ny, c)
    }

    private fun drawNineSlice(item: RenderItem, s: Float) {
        val tex = textures.texture(item.texture)
        if (tex == 0 || item.textureWidth <= 0 || item.textureHeight <= 0) {
            drawQuadItem(item, s)
            return
        }
        val p = place(item, s)
        val tw = item.textureWidth.toFloat()
        val th = item.textureHeight.toFloat()
        val reg = item.region
        val rx = if (reg.width > 0f) reg.x else 0f
        val ry = if (reg.width > 0f) reg.y else 0f
        val rw = if (reg.width > 0f) reg.width else tw
        val rh = if (reg.width > 0f) reg.height else th
        val l = item.u0.coerceIn(0f, rw * 0.5f) * s
        val t = item.u1.coerceIn(0f, rh * 0.5f) * s
        val r = item.u2.coerceIn(0f, rw * 0.5f) * s
        val b = item.u3.coerceIn(0f, rh * 0.5f) * s
        fun ux(px: Float) = (rx + px) / tw
        fun vy(px: Float) = (ry + px) / th
        val xs = floatArrayOf(0f, l, p.w - r, p.w)
        val ys = floatArrayOf(0f, t, p.h - b, p.h)
        val sourceXs = floatArrayOf(0f, l / s, rw - r / s, rw)
        val sourceYs = floatArrayOf(0f, t / s, rh - b / s, rh)
        begin(tex, item)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val w = xs[col + 1] - xs[col]
                val h = ys[row + 1] - ys[row]
                if (w <= 0f || h <= 0f) continue
                emitQuad(
                    p, xs[col], ys[row], w, h,
                    ux(sourceXs[col]), vy(sourceYs[row]), ux(sourceXs[col + 1]), vy(sourceYs[row + 1]),
                    item.color
                )
            }
        }
    }

    // ------------------------------------------------------------------ primitives

    private fun emitQuad(p: Placement, lx: Float, ly: Float, lw: Float, lh: Float, u0: Float, v0: Float, u1: Float, v1: Float, color: Int) {
        val ax = p.mapX(lx, ly); val ay = p.mapY(lx, ly)
        val bx = p.mapX(lx + lw, ly); val by = p.mapY(lx + lw, ly)
        val cx = p.mapX(lx + lw, ly + lh); val cy = p.mapY(lx + lw, ly + lh)
        val dx = p.mapX(lx, ly + lh); val dy = p.mapY(lx, ly + lh)
        putVertex(ax, ay, u0, v0, color)
        putVertex(bx, by, u1, v0, color)
        putVertex(cx, cy, u1, v1, color)
        putVertex(cx, cy, u1, v1, color)
        putVertex(dx, dy, u0, v1, color)
        putVertex(ax, ay, u0, v0, color)
    }

    /** Quad already expressed in screen pixels, rotated around the placement origin (glyphs). */
    private fun emitScreenQuad(p: Placement, x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float, color: Int) {
        if (p.rotation == 0f) {
            putVertex(x0, y0, u0, v0, color)
            putVertex(x1, y0, u1, v0, color)
            putVertex(x1, y1, u1, v1, color)
            putVertex(x1, y1, u1, v1, color)
            putVertex(x0, y1, u0, v1, color)
            putVertex(x0, y0, u0, v0, color)
        } else {
            val c = p.cos; val sn = p.sin
            val ax = p.x + (x0 - p.x) * c - (y0 - p.y) * sn
            val ay = p.y + (x0 - p.x) * sn + (y0 - p.y) * c
            val bx = p.x + (x1 - p.x) * c - (y0 - p.y) * sn
            val by = p.y + (x1 - p.x) * sn + (y0 - p.y) * c
            val cx = p.x + (x1 - p.x) * c - (y1 - p.y) * sn
            val cy = p.y + (x1 - p.x) * sn + (y1 - p.y) * c
            val dx = p.x + (x0 - p.x) * c - (y1 - p.y) * sn
            val dy = p.y + (x0 - p.x) * sn + (y1 - p.y) * c
            putVertex(ax, ay, u0, v0, color)
            putVertex(bx, by, u1, v0, color)
            putVertex(cx, cy, u1, v1, color)
            putVertex(cx, cy, u1, v1, color)
            putVertex(dx, dy, u0, v1, color)
            putVertex(ax, ay, u0, v0, color)
        }
    }


    private fun emitEllipse(p: Placement, color: Int) {
        val cx = p.mapX(p.pivotX * p.w, p.pivotY * p.h)
        val cy = p.mapY(p.pivotX * p.w, p.pivotY * p.h)
        val rx = p.w * 0.5f
        val ry = p.h * 0.5f
        var prevX = cx + rx; var prevY = cy
        for (i in 1..CIRCLE_SEGMENTS) {
            val a = i * Math.PI * 2 / CIRCLE_SEGMENTS
            val nx = cx + (kotlin.math.cos(a) * rx).toFloat()
            val ny = cy + (kotlin.math.sin(a) * ry).toFloat()
            putVertex(cx, cy, color)
            putVertex(prevX, prevY, color)
            putVertex(nx, ny, color)
            prevX = nx; prevY = ny
        }
    }

    private fun emitRing(p: Placement, color: Int, thickness: Float) {
        val x0 = p.x - p.pivotX * p.w
        val y0 = p.y - p.pivotY * p.h
        val x1 = x0 + p.w
        val y1 = y0 + p.h
        val t = thickness.coerceAtLeast(1f)
        quadPx(x0, y0, x1, y0 + t, color)
        quadPx(x0, y1 - t, x1, y1, color)
        quadPx(x0, y0 + t, x0 + t, y1 - t, color)
        quadPx(x1 - t, y0 + t, x1, y1 - t, color)
    }

    private fun emitTriangle(p: Placement, color: Int) {
        val ax = p.mapX(p.w * 0.5f, 0f); val ay = p.mapY(p.w * 0.5f, 0f)
        val bx = p.mapX(p.w, p.h); val by = p.mapY(p.w, p.h)
        val cx = p.mapX(0f, p.h); val cy = p.mapY(0f, p.h)
        putVertex(ax, ay, color)
        putVertex(bx, by, color)
        putVertex(cx, cy, color)
    }

    private fun quadPx(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        putVertex(x0, y0, color)
        putVertex(x1, y0, color)
        putVertex(x1, y1, color)
        putVertex(x1, y1, color)
        putVertex(x0, y1, color)
        putVertex(x0, y0, color)
    }

    // ------------------------------------------------------------------ batching

    private fun begin(texture: Int, item: RenderItem, program: GL2.Program? = null) {
        val target = program ?: textured ?: return
        if (active === target && activeTexture == texture && activeBlend == item.blend && sameClip(item.clip)) return
        flush()
        active = target
        activeTexture = texture
        activeBlend = item.blend
        activeClip = item.clip
        target.use()
        applyClip(item.clip)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(target.uni("uTex"), 0)
        uniformViewport(target)
        GLES20.glUniform1f(target.uni("uUseTex"), 1f)
        GLES20.glUniform4f(target.uni("uColor"), 1f, 1f, 1f, 1f)
        applyBlend(item.blend)
        if (target !== textured) bindMaterialUniforms(target, item)
        applyGlobalUniforms(target)
    }

    private fun beginSolid(item: RenderItem) {
        val target = solid ?: return
        if (active === target && activeBlend == item.blend && sameClip(item.clip)) return
        flush()
        active = target
        activeTexture = 0
        activeBlend = item.blend
        activeClip = item.clip
        target.use()
        applyClip(item.clip)
        uniformViewport(target)
        GLES20.glUniform1f(target.uni("uUseTex"), 0f)
        GLES20.glUniform4f(target.uni("uColor"), 1f, 1f, 1f, 1f)
        applyBlend(item.blend)
        applyGlobalUniforms(target)
    }

    private fun uniformViewport(program: GL2.Program) {
        GLES20.glUniform2f(program.uni("uViewport"), widthPx.toFloat(), heightPx.toFloat())
        GLES20.glUniform1f(program.uni("uTime"), globalUniforms["uTime"] ?: 0f)
    }

    /** Applies a clip rectangle (pixels, y down) with the scissor test. */
    private fun applyClip(clip: Rect2) {
        if (clip.width <= 0f || clip.height <= 0f) {
            GLES20.glScissor(0, 0, widthPx, heightPx)
            return
        }
        val x = clip.x.toInt().coerceIn(0, widthPx)
        val y = (heightPx - (clip.y + clip.height)).toInt().coerceIn(0, heightPx)
        val w = clip.width.toInt().coerceAtLeast(0)
        val h = clip.height.toInt().coerceAtLeast(0)
        GLES20.glScissor(x, y, w.coerceAtMost(widthPx - x), h.coerceAtMost(heightPx - y))
        currentStats?.let { it.clippedDraws++ }
    }


    private fun sameClip(clip: Rect2) =
        clip.x == activeClip.x && clip.y == activeClip.y && clip.width == activeClip.width && clip.height == activeClip.height

    private fun applyBlend(blend: Int) {
        when (blend) {
            BlendKind.ADDITIVE -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            BlendKind.MULTIPLY -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            BlendKind.PREMULTIPLIED -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            else -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    private fun putVertex(x: Float, y: Float, u: Float, v: Float, color: Int) {
        val a = ((color ushr 24) and 0xFF) / 255f
        if (a <= 0f) return
        batch.put(
            x, y, u, v,
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            a,
            0f
        )
    }

    private fun putVertex(x: Float, y: Float, color: Int) {
        val a = ((color ushr 24) and 0xFF) / 255f
        if (a <= 0f) return
        batch.put(
            x, y, 0.5f, 0.5f,
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            a,
            0f
        )
    }

    private fun flush() {
        val program = active ?: return
        if (batch.vertexCount == 0) return
        val aPos = program.attr("aPos")
        val aUV = program.attr("aUV")
        val aColor = program.attr("aColor")
        val stride = FLOATS_PER_VERTEX * GL2.FLOAT_SIZE
        val buf = batch.buffer()
        if (aPos >= 0) {
            GLES20.glEnableVertexAttribArray(aPos)
            buf.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, buf)
        }
        if (aUV >= 0) {
            GLES20.glEnableVertexAttribArray(aUV)
            buf.position(2)
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, stride, buf)
        }
        if (aColor >= 0) {
            GLES20.glEnableVertexAttribArray(aColor)
            buf.position(4)
            GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, buf)
        }
        currentStats?.let { it.drawCalls++; it.vertices += batch.vertexCount }
        batch.draw(GLES20.GL_TRIANGLES)
    }

    // ------------------------------------------------------------------ materials

    private fun materialProgram(name: String): GL2.Program? {
        val material = materialSource?.invoke(name) ?: return null
        val revision = material.fragment.hashCode()
        val cached = materials[name]
        if (cached != null && cached.revision == revision) return cached.program
        cached?.program?.delete()
        materials.remove(name)
        val names = uniformNames(material.fragment)
        val program = GL2.link(VERTEX_SHADER, material.fragment, ATTRIBUTES, (FIXED_UNIFORMS + names).distinct())
        if (program == null) {
            onShaderError?.invoke(name, GL2.lastError)
            return null
        }
        materials[name] = MaterialProgram(program, revision)
        return program
    }

    private fun bindMaterialUniforms(program: GL2.Program, item: RenderItem) {
        GLES20.glUniform4f(
            program.uni("uColor"),
            ((item.color shr 16) and 0xFF) / 255f,
            ((item.color shr 8) and 0xFF) / 255f,
            (item.color and 0xFF) / 255f,
            ((item.color ushr 24) and 0xFF) / 255f
        )
        if (item.textureWidth > 0 && item.textureHeight > 0) {
            GLES20.glUniform2f(
                program.uni("uTexel"),
                1f / item.textureWidth, 1f / item.textureHeight
            )
        }
        val material = materialSource?.invoke(item.material) ?: return
        for ((uniformName, value) in material.uniforms) {
            val loc = program.uni(uniformName)
            if (loc >= 0 && uniformName != "uTexel" && uniformName != "uColor") GLES20.glUniform1f(loc, value)
        }
    }

    private fun applyGlobalUniforms(program: GL2.Program) {
        for ((name, value) in globalUniforms) {
            if (name == "uTime") continue
            val loc = program.uni(name)
            if (loc >= 0) GLES20.glUniform1f(loc, value)
        }
    }

    private class MaterialProgram(val program: GL2.Program, val revision: Int)

    private fun ensurePrograms() {
        if (solid == null) solid = GL2.link(VERTEX_SHADER, SOLID_FRAGMENT, ATTRIBUTES, FIXED_UNIFORMS)
        if (textured == null) textured = GL2.link(VERTEX_SHADER, TEXTURED_FRAGMENT, ATTRIBUTES, FIXED_UNIFORMS)
    }

    companion object {
        private const val CIRCLE_SEGMENTS = 28
        private const val FLOATS_PER_VERTEX = 9

        private val ATTRIBUTES = listOf("aPos", "aUV", "aColor")
        private val FIXED_UNIFORMS = listOf(
            "uViewport", "uUseTex", "uColor", "uTex", "uTexel", "uTime"
        )

        /** Uniform names declared by a fragment shader — the shader editor and material cache use it. */
        fun uniformNames(source: String): List<String> {
            val out = ArrayList<String>(4)
            for (m in Regex("uniform\\s+\\w+\\s+(\\w+)").findAll(source)) {
                val name = m.groupValues[1]
                if (name !in out) out.add(name)
            }
            return out
        }

        private const val VERTEX_SHADER = """
attribute vec2 aPos;
attribute vec2 aUV;
attribute vec4 aColor;
uniform vec2 uViewport;
uniform vec4 uColor;
varying vec2 vUV;
varying vec4 vColor;
void main() {
  vUV = aUV;
  vColor = aColor * uColor;
  vec2 ndc = vec2(aPos.x / uViewport.x * 2.0 - 1.0, 1.0 - aPos.y / uViewport.y * 2.0);
  gl_Position = vec4(ndc, 0.0, 1.0);
}
"""

        private const val TEXTURED_FRAGMENT = """
precision mediump float;
uniform sampler2D uTex;
varying vec2 vUV;
varying vec4 vColor;
void main() {
  gl_FragColor = texture2D(uTex, vUV) * vColor;
}
"""

        private const val SOLID_FRAGMENT = """
precision mediump float;
varying vec4 vColor;
void main() {
  gl_FragColor = vColor;
}
"""
    }
}
