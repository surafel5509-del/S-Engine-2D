package com.sengine.engine.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.sengine.project.Project
import java.io.File

class Tex(val id: Int, val w: Int, val h: Int)

/** GL texture caches. Must only be used on the GL thread. */
class TextureCache(private val project: Project) {
    private val images = HashMap<String, Tex?>()
    private val stamps = HashMap<String, Long>()
    private val texts = LinkedHashMap<String, Tex>(64, 0.75f, true)

    fun clear() { images.clear(); stamps.clear(); texts.clear() }

    fun image(name: String): Tex? {
        if (name.isBlank()) return null
        val f: File = project.assetFile(name)
        val stamp = f.lastModified()
        if (images.containsKey(name) && stamps[name] == stamp) return images[name]
        images[name]?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
        val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
        val tex = bmp?.let { upload(it, maxOf(it.width, it.height) <= 128) }
        bmp?.recycle()
        images[name] = tex
        stamps[name] = stamp
        return tex
    }

    fun text(text: String, bold: Boolean, align: Int): Tex {
        val key = "$bold|$align|$text"
        texts[key]?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.textSize = 72f
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val lines = text.split('\n')
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent)
        val widths = lines.map { paint.measureText(it) }
        val w = (widths.maxOrNull() ?: 1f).coerceAtLeast(1f).toInt() + 8
        val h = (lineH * lines.size).toInt().coerceAtLeast(1) + 4
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for ((i, line) in lines.withIndex()) {
            val x = when (align) {
                0 -> 4f
                2 -> w - 4f - widths[i]
                else -> (w - widths[i]) / 2f
            }
            c.drawText(line, x, 2f - fm.ascent + i * lineH, paint)
        }
        val tex = upload(bmp, false)
        bmp.recycle()
        texts[key] = tex
        if (texts.size > 128) {
            val eldest = texts.entries.iterator().next()
            GLES20.glDeleteTextures(1, intArrayOf(eldest.value.id), 0)
            texts.remove(eldest.key)
        }
        return tex
    }

    private fun upload(bmp: Bitmap, nearest: Boolean): Tex {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val filter = if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return Tex(ids[0], bmp.width, bmp.height)
    }
}
