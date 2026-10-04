package com.sengine.platform.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.GLUtils
import com.sengine.engine.render.TextRasterizer
import com.sengine.engine.render.TextRun
import java.util.LinkedHashMap

/**
 * Text rasterizer.
 *
 * Latin text (ASCII 32…126) is baked into a per-(font, size, bold) glyph atlas the first time it is
 * used, then every string is laid out from those cached glyphs with zero allocations — the common
 * case for game UI and editor panels. Any other script (Amharic, Arabic, CJK, …) is rasterized as a
 * cached per-string texture so localization never needs a second code path.
 *
 * Layout is cached by (font, rounded size, bold, text); the LRU keeps the hottest [maxRuns] strings.
 */
class AndroidTextRenderer(private val textures: AndroidTextures) : TextRasterizer {

    private class Atlas(
        val handle: Int,
        val glyphs: HashMap<Char, FloatArray>, // x, y, w, h, u0, v0, u1, v1 (y = top of glyph)
        val ascent: Float,
        val descent: Float,
        val lineHeight: Float,
        val sizePx: Float
    )

    private val atlases = HashMap<String, Atlas>()
    private val runs = object : LinkedHashMap<String, TextRun>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextRun>?): Boolean = size > maxRuns
    }
    private val stringTextures = object : LinkedHashMap<String, StringTexture>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StringTexture>?): Boolean = size > maxStringTextures
    }

    var maxRuns = 512
    var maxStringTextures = 48

    private class StringTexture(val handle: Int, val width: Float, val height: Float, val baseline: Float)

    private val bounds = Rect()

    override fun run(font: String, sizePx: Float, bold: Boolean, text: String): TextRun? {
        if (text.isEmpty()) return null
        val size = sizePx.coerceIn(4f, 512f)
        val key = cacheKey(font, size, bold, text)
        runs[key]?.let { return it }
        val run = if (isAscii(text)) asciiRun(font, size, bold, text) else unicodeRun(font, size, bold, text)
        if (run != null) runs[key] = run
        return run
    }

    override fun invalidate(font: String) {
        if (font.isEmpty()) {
            for (a in atlases.values) textures.release(a.handle)
            atlases.clear()
            runs.clear()
            for (t in stringTextures.values) textures.release(t.handle)
            stringTextures.clear()
        } else {
            val keys = atlases.keys.filter { it.startsWith("$font|") }
            for (k in keys) {
                atlases.remove(k)?.let { textures.release(it.handle) }
            }
            runs.keys.filter { it.startsWith("$font|") }.forEach { runs.remove(it) }
        }
    }

    // ------------------------------------------------------------------ ASCII glyph atlas

    private fun asciiRun(font: String, sizePx: Float, bold: Boolean, text: String): TextRun? {
        val atlasKey = "$font|${sizePx.toInt()}|$bold"
        val atlas = atlases[atlasKey] ?: buildAtlas(atlasKey, font, sizePx, bold) ?: return null
        val spaceAdvance = atlas.glyphs[' ']?.get(0) ?: sizePx * 0.5f
        var width = 0f
        for (ch in text) width += atlas.glyphs[ch]?.get(0) ?: spaceAdvance
        val quads = FloatArray(text.length * 8)
        var penX = 0f
        var i = 0
        for (ch in text) {
            if (ch == '\n') {
                // the renderer draws single-line labels; treat line breaks as spaces
                penX += spaceAdvance
                continue
            }
            val g = atlas.glyphs[ch]
            if (g != null) {
                quads[i] = penX + g[1]
                quads[i + 1] = g[2]
                quads[i + 2] = g[3]
                quads[i + 3] = g[4]
                quads[i + 4] = g[5]
                quads[i + 5] = g[6]
                quads[i + 6] = g[7]
                quads[i + 7] = g[8]
                i += 8
            }
            penX += g?.get(0) ?: spaceAdvance
        }
        val quadsTrimmed = if (i == quads.size) quads else quads.copyOf(i)
        return TextRun(atlas.handle, width, atlas.lineHeight, -atlas.ascent, quadsTrimmed)
    }

    private fun buildAtlas(key: String, font: String, sizePx: Float, bold: Boolean): Atlas? {
        val paint = textures.paint(font, sizePx, bold)
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val cell = (sizePx * 1.35f).toInt().coerceAtLeast(8)
        val perRow = 12
        val glyphCount = 95
        val cols = perRow
        val rows = (glyphCount + cols - 1) / cols
        var atlasW = 1
        while (atlasW < cols * cell) atlasW *= 2
        var atlasH = 1
        while (atlasH < rows * cell) atlasH *= 2
        // keep the atlas inside a sane size for very large fonts
        if (atlasW > 2048 || atlasH > 2048) return null
        val bitmap = textures.newBitmap(atlasW, atlasH)
        val canvas: Canvas = textures.newCanvas(bitmap)
        val glyphs = HashMap<Char, FloatArray>(glyphCount)
        for (index in 0 until glyphCount) {
            val ch = (32 + index).toChar()
            val col = index % cols
            val row = index / cols
            val penX = col * cell + 1f
            val penY = row * cell - metrics.ascent + 2f
            canvas.drawText(ch.toString(), penX, penY, paint)
            textures.measureBounds(paint, ch.toString(), bounds)
            val advance = paint.measureText(ch.toString())
            val gw = (bounds.width() + 2).coerceAtLeast(1)
            val gh = (bounds.height() + 2).coerceAtLeast(1)
            val atlasX = penX + bounds.left - 1
            val atlasY = penY + bounds.top - 1
            val u0 = atlasX / atlasW
            val v0 = atlasY / atlasH
            // layout: advance, x, y (relative to the run's top-left), w, h, u0, v0, u1, v1
            glyphs[ch] = floatArrayOf(
                advance,
                bounds.left - 1f,
                (bounds.top - metrics.ascent) - 1f,
                gw.toFloat(),
                gh.toFloat(),
                u0, v0, u0 + gw.toFloat() / atlasW, v0 + gh.toFloat() / atlasH
            )
        }
        val handle = uploadBitmap(bitmap)
        bitmap.recycle()
        if (handle == 0) return null
        val atlas = Atlas(handle, glyphs, metrics.ascent, metrics.descent, lineHeight, sizePx)
        atlases[key] = atlas
        return atlas
    }

    // ------------------------------------------------------------------ non-Latin strings

    private fun unicodeRun(font: String, sizePx: Float, bold: Boolean, text: String): TextRun? {
        val key = "$font|${sizePx.toInt()}|$bold|$text"
        stringTextures[key]?.let { t ->
            return TextRun(t.handle, t.width, t.height, t.baseline, floatArrayOf(0f, 0f, t.width, t.height, 0f, 0f, 1f, 1f))
        }
        val paint = textures.paint(font, sizePx, bold)
        val metrics = paint.fontMetrics
        val width = paint.measureText(text)
        val lineHeight = metrics.descent - metrics.ascent
        if (width < 1f) return null
        val bmpW = Math.ceil(width.toDouble()).toInt() + 4
        val bmpH = Math.ceil(lineHeight.toDouble()).toInt() + 4
        if (bmpW <= 0 || bmpH <= 0 || bmpW > 4096 || bmpH > 2048) return null
        val bitmap = textures.newBitmap(bmpW, bmpH)
        val canvas = textures.newCanvas(bitmap)
        canvas.drawText(text, 2f, 2f - metrics.ascent, paint)
        val handle = uploadBitmap(bitmap)
        bitmap.recycle()
        if (handle == 0) return null
        val entry = StringTexture(handle, width, lineHeight, -metrics.ascent)
        stringTextures[key] = entry
        return TextRun(handle, width, lineHeight, -metrics.ascent, floatArrayOf(0f, 0f, width, lineHeight, 0f, 0f, 1f, 1f))
    }

    private fun uploadBitmap(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun cacheKey(font: String, sizePx: Float, bold: Boolean, text: String) =
        "$font|${sizePx.toInt()}|$bold|$text"

    private fun isAscii(text: String): Boolean {
        for (ch in text) if (ch.code < 32 || ch.code > 126) return false
        return true
    }
}
