package com.sengine.platform.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.sengine.engine.render.TextureSource
import com.sengine.engine.resources.TextureInfo
import java.io.File
import java.util.LinkedHashMap

/**
 * Android texture layer: decodes image assets with [BitmapFactory], uploads them to GL and keeps an
 * LRU cache. One decode per (path, modification time) — the asset browser, the viewport and the
 * running game all share the same cache, so a sheet is never decoded twice.
 *
 * ASCII text is drawn through glyph atlases (see [AndroidTextRenderer]); arbitrary scripts
 * (Amharic, Arabic, …) fall back to cached per-string textures managed here as well.
 */
class AndroidTextures(private val resolver: (String) -> File?) : TextureSource {

    class Entry(val handle: Int, val width: Int, val height: Int, var stamp: Long, var used: Long)

    private val cache = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val pending = ArrayList<String>()
    private var whiteHandle = 0
    private var frame = 0L
    private var maxEntries = 96
    var onDecodeError: ((String, String) -> Unit)? = null

    /** Textures that must survive eviction (glyph atlases, UI sheets referenced by name). */
    private val pinned = HashSet<Int>()

    override fun texture(name: String): Int {
        if (name.isEmpty()) return 0
        val file = resolve(name) ?: return 0
        if (!file.exists()) return 0
        val stamp = file.lastModified()
        val existing = cache[name]
        if (existing != null && existing.stamp == stamp) {
            existing.used = frame
            return existing.handle
        }
        if (existing != null) {
            deleteTexture(existing.handle)
            cache.remove(name)
        }
        val decoded = decode(file, name) ?: return 0
        if (cache.size >= maxEntries) evictOne()
        cache[name] = Entry(decoded.first, decoded.second.width, decoded.second.height, stamp, frame)
        return decoded.first
    }

    /** Pixel size of an asset (used by the engine to size sprites and validate imports). */
    fun info(name: String): TextureInfo? {
        val file = resolve(name) ?: return null
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0) null else TextureInfo(opts.outWidth, opts.outHeight)
        } catch (e: Exception) {
            onDecodeError?.invoke(name, e.message ?: "decode failed")
            null
        }
    }

    override fun white(): Int {
        if (whiteHandle == 0) {
            val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bmp.setPixel(0, 0, 0xFFFFFFFF.toInt())
            whiteHandle = upload(bmp, false)
            bmp.recycle()
        }
        return whiteHandle
    }

    override fun markFrame() {
        frame++
        // evict entries not touched for two frames when the cache is under pressure
        if (cache.size > maxEntries) evictOne()
    }

    private fun evictOne() {
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.used < frame - 1 && !pinned.contains(e.value.handle)) {
                deleteTexture(e.value.handle)
                it.remove()
                return
            }
        }
        val first = cache.entries.firstOrNull() ?: return
        deleteTexture(first.value.handle)
        cache.remove(first.key)
    }

    /** Uploads a bitmap (glyph atlases, string textures, nine-slice sources). */
    fun upload(bitmap: Bitmap, pin: Boolean, nearest: Boolean = true): Int {
        val handle = uploadInternal(bitmap, nearest)
        if (handle != 0 && pin) pinned.add(handle)
        return handle
    }

    fun release(handle: Int) {
        deleteTexture(handle)
        pinned.remove(handle)
    }

    fun releaseAll() {
        for (e in cache.values) deleteTexture(e.handle)
        cache.clear()
        deleteTexture(whiteHandle)
        whiteHandle = 0
        pinned.clear()
        glyphAtlases.clear()
    }

    private fun decode(file: File, name: String): Pair<Int, TextureInfo>? {
        return try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            val info = TextureInfo(bmp.width, bmp.height)
            val handle = uploadInternal(bmp, nearest = true)
            bmp.recycle()
            if (handle == 0) null else handle to info
        } catch (e: Exception) {
            onDecodeError?.invoke(name, e.message ?: "decode failed")
            null
        } catch (o: OutOfMemoryError) {
            onDecodeError?.invoke(name, "out of memory")
            null
        }
    }

    private fun uploadInternal(bitmap: Bitmap, nearest: Boolean): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun deleteTexture(handle: Int) {
        if (handle == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(handle), 0)
    }

    private fun resolve(path: String): File? = resolver(path)

    // ------------------------------------------------------------------ fonts

    private val typefaces = HashMap<String, Typeface>()
    val glyphAtlases = HashMap<String, Int>()

    /** Typeface for a font asset path; falls back to the default sans typeface. */
    fun typeface(font: String): Typeface {
        if (font.isEmpty()) return Typeface.DEFAULT
        typefaces[font]?.let { return it }
        val file = resolve(font)
        val tf = try {
            if (file != null && file.exists()) Typeface.createFromFile(file) else Typeface.DEFAULT
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        typefaces[font] = tf
        return tf
    }

    fun paint(font: String, sizePx: Float, bold: Boolean): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typeface(font)
        paint.textSize = sizePx.coerceAtLeast(1f)
        paint.isFakeBoldText = bold
        paint.color = 0xFFFFFFFF.toInt()
        paint.letterSpacing = 0f
        return paint
    }

    fun measureBounds(paint: Paint, text: String, out: Rect) {
        paint.getTextBounds(text, 0, text.length, out)
    }

    public fun newBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    fun newCanvas(bitmap: Bitmap) = Canvas(bitmap)
}
