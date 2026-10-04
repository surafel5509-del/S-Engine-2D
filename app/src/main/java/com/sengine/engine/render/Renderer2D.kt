package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.math.Affine
import java.nio.FloatBuffer

/** Low level immediate-mode drawing of shaped / textured quads and coloured lines. */
class Renderer2D {
    private var spriteProg = 0
    private var lineProg = 0
    private lateinit var quad: FloatBuffer
    private var lineBuf: FloatBuffer = GL.floatBuffer(6 * 2048)
    private var lineData = FloatArray(6 * 2048)
    private var lineCount = 0

    private var sPos = 0; private var sMVP = 0; private var sColor = 0; private var sTex = 0
    private var sUseTex = 0; private var sShape = 0; private var sAA = 0; private var sUV = 0
    private var lPos = 0; private var lColor = 0; private var lMVP = 0

    val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = Affine()

    fun init() {
        spriteProg = GL.compile(SPRITE_VS, SPRITE_FS)
        lineProg = GL.compile(LINE_VS, LINE_FS)
        sPos = GLES20.glGetAttribLocation(spriteProg, "aPos")
        sMVP = GLES20.glGetUniformLocation(spriteProg, "uMVP")
        sColor = GLES20.glGetUniformLocation(spriteProg, "uColor")
        sTex = GLES20.glGetUniformLocation(spriteProg, "uTex")
        sUseTex = GLES20.glGetUniformLocation(spriteProg, "uUseTex")
        sShape = GLES20.glGetUniformLocation(spriteProg, "uShape")
        sAA = GLES20.glGetUniformLocation(spriteProg, "uAA")
        sUV = GLES20.glGetUniformLocation(spriteProg, "uUV")
        lPos = GLES20.glGetAttribLocation(lineProg, "aPos")
        lColor = GLES20.glGetAttribLocation(lineProg, "aColor")
        lMVP = GLES20.glGetUniformLocation(lineProg, "uMVP")
        quad = GL.floatBuffer(8)
        quad.put(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)).position(0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun begin(view: View2D) {
        view.matrix(viewProj)
    }

    /**
     * Draw a unit quad transformed by [m].
     * shape: 0 rect, 1 circle, 2 triangle, 3 ring
     */
    fun quad(m: Affine, color: Int, shape: Int, tex: Tex?, aaPixels: Float, flipX: Boolean = false, flipY: Boolean = false) {
        GLES20.glUseProgram(spriteProg)
        m.toMat4(model)
        android.opengl.Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES20.glUniformMatrix4fv(sMVP, 1, false, mvp, 0)
        GLES20.glUniform4f(sColor, GL.r(color), GL.g(color), GL.b(color), GL.a(color))
        GLES20.glUniform1f(sShape, shape.toFloat())
        GLES20.glUniform1f(sAA, aaPixels.coerceAtLeast(1f))
        val u0 = if (flipX) 1f else 0f
        val u1 = if (flipX) 0f else 1f
        val v0 = if (flipY) 0f else 1f
        val v1 = if (flipY) 1f else 0f
        GLES20.glUniform4f(sUV, u0, v0, u1, v1)
        if (tex != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id)
            GLES20.glUniform1i(sTex, 0)
            GLES20.glUniform1f(sUseTex, 1f)
        } else GLES20.glUniform1f(sUseTex, 0f)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(sPos)
        GLES20.glVertexAttribPointer(sPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(sPos)
    }

    /** Axis-aligned helper. */
    fun rect(cx: Float, cy: Float, w: Float, h: Float, color: Int, shape: Int, ppu: Float) {
        tmp.a = w; tmp.b = 0f; tmp.c = 0f; tmp.d = h; tmp.tx = cx; tmp.ty = cy
        quad(tmp, color, shape, null, minOf(w, h) * ppu)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        if ((lineCount + 2) * 6 > lineData.size) {
            lineData = lineData.copyOf(lineData.size * 2)
            lineBuf = GL.floatBuffer(lineData.size)
        }
        val r = GL.r(color); val g = GL.g(color); val b = GL.b(color); val a = GL.a(color)
        var i = lineCount * 6
        lineData[i++] = x1; lineData[i++] = y1; lineData[i++] = r; lineData[i++] = g; lineData[i++] = b; lineData[i++] = a
        lineData[i++] = x2; lineData[i++] = y2; lineData[i++] = r; lineData[i++] = g; lineData[i++] = b; lineData[i] = a
        lineCount += 2
    }

    fun circleLines(cx: Float, cy: Float, r: Float, color: Int, seg: Int = 40) {
        var px = cx + r
        var py = cy
        for (i in 1..seg) {
            val a = i * Math.PI * 2 / seg
            val nx = cx + (Math.cos(a) * r).toFloat()
            val ny = cy + (Math.sin(a) * r).toFloat()
            line(px, py, nx, ny, color)
            px = nx; py = ny
        }
    }

    fun obb(m: Affine, hw: Float, hh: Float, color: Int) {
        val xs = floatArrayOf(-hw, hw, hw, -hw)
        val ys = floatArrayOf(-hh, -hh, hh, hh)
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            line(m.mapX(xs[i], ys[i]), m.mapY(xs[i], ys[i]), m.mapX(xs[j], ys[j]), m.mapY(xs[j], ys[j]), color)
        }
    }

    fun flushLines(width: Float = 1f) {
        if (lineCount == 0) return
        GLES20.glUseProgram(lineProg)
        GLES20.glUniformMatrix4fv(lMVP, 1, false, viewProj, 0)
        lineBuf.position(0)
        lineBuf.put(lineData, 0, lineCount * 6)
        lineBuf.position(0)
        GLES20.glEnableVertexAttribArray(lPos)
        GLES20.glEnableVertexAttribArray(lColor)
        GLES20.glVertexAttribPointer(lPos, 2, GLES20.GL_FLOAT, false, 24, lineBuf)
        lineBuf.position(2)
        GLES20.glVertexAttribPointer(lColor, 4, GLES20.GL_FLOAT, false, 24, lineBuf)
        GLES20.glLineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineCount)
        GLES20.glDisableVertexAttribArray(lPos)
        GLES20.glDisableVertexAttribArray(lColor)
        lineCount = 0
    }

    companion object {
        const val SPRITE_VS = """
uniform mat4 uMVP;
uniform vec4 uUV;
attribute vec2 aPos;
varying vec2 vP;
varying vec2 vUV;
void main() {
  vP = aPos;
  vec2 t = aPos + 0.5;
  vUV = vec2(mix(uUV.x, uUV.z, t.x), mix(uUV.y, uUV.w, t.y));
  gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}
"""
        const val SPRITE_FS = """
precision mediump float;
varying vec2 vP;
varying vec2 vUV;
uniform vec4 uColor;
uniform sampler2D uTex;
uniform float uUseTex;
uniform float uShape;
uniform float uAA;
void main() {
  float a = 1.0;
  if (uShape > 0.5 && uShape < 1.5) {
    a = clamp((0.5 - length(vP)) * uAA, 0.0, 1.0);
  } else if (uShape > 1.5 && uShape < 2.5) {
    float w = (0.5 - vP.y) * 0.5;
    float e = min(w - abs(vP.x), vP.y + 0.5);
    a = clamp(e * uAA, 0.0, 1.0);
  } else if (uShape > 2.5) {
    float d = length(vP);
    a = clamp((0.5 - d) * uAA, 0.0, 1.0) * clamp((d - 0.40) * uAA, 0.0, 1.0);
  }
  vec4 c = uColor;
  if (uUseTex > 0.5) c *= texture2D(uTex, vUV);
  gl_FragColor = vec4(c.rgb, c.a * a);
}
"""
        const val LINE_VS = """
uniform mat4 uMVP;
attribute vec2 aPos;
attribute vec4 aColor;
varying vec4 vColor;
void main() { vColor = aColor; gl_Position = uMVP * vec4(aPos, 0.0, 1.0); }
"""
        const val LINE_FS = """
precision mediump float;
varying vec4 vColor;
void main() { gl_FragColor = vColor; }
"""
    }
}
