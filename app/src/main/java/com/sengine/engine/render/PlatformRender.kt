package com.sengine.engine.render

import com.sengine.engine.resources.TextureInfo

/**
 * Platform bridge for textures. The engine core never touches OpenGL or `android.graphics`; the
 * Android/desktop layer implements this interface and hands out GL texture handles.
 */
interface TextureSource {
    /** GL handle for an image asset (project-relative path); 0 when it cannot be loaded. */
    fun texture(name: String): Int

    /** 1×1 white texture used by untextured primitives. */
    fun white(): Int

    /** Called once per frame with the textures used, so implementations can evict unused ones. */
    fun markFrame() {}
}

/**
 * A laid-out string: glyph quads in pixel units relative to the run origin (top-left).
 * Layout is cached by the platform (font + size + bold + text) so text drawing stays allocation free.
 */
class TextRun(
    val texture: Int,
    val width: Float,
    val height: Float,
    val baseline: Float,
    /** x, y, w, h, u0, v0, u1, v1 — 8 floats per glyph. */
    val quads: FloatArray
) {
    val count get() = quads.size / 8
}

/** Rasterizes and caches text runs (glyph atlas + per-string fallback for non-Latin scripts). */
interface TextRasterizer {
    fun run(font: String, sizePx: Float, bold: Boolean, text: String): TextRun?

    /** Forces a re-rasterization (used when a custom font file changes on disk). */
    fun invalidate(font: String = "") {}
}

/** No-op implementations, used by headless tests and before the platform layer is attached. */
object NullTextureSource : TextureSource {
    override fun texture(name: String): Int = 0
    override fun white(): Int = 0
}

object NullTextRasterizer : TextRasterizer {
    override fun run(font: String, sizePx: Float, bold: Boolean, text: String): TextRun? = null
}
