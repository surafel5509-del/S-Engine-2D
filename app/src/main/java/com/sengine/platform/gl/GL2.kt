package com.sengine.platform.gl

import android.opengl.GLES20

/**
 * Minimal GLES2 helpers: shader compilation with readable errors, program cache and a growable
 * vertex batch buffer. Everything the 2D renderer needs, nothing else.
 */
object GL2 {

    const val FLOAT_SIZE = 4

    fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        if (id == 0) return 0
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            lastError = log
            return 0
        }
        return id
    }

    fun link(vertexSource: String, fragmentSource: String, attributes: List<String>, uniforms: List<String>): Program? {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vs == 0) return null
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fs == 0) { GLES20.glDeleteShader(vs); return null }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (ok[0] == 0) {
            lastError = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            return null
        }
        val p = Program(id)
        for (a in attributes) p.attributes[a] = GLES20.glGetAttribLocation(id, a)
        for (u in uniforms) p.uniforms[u] = GLES20.glGetUniformLocation(id, u)
        return p
    }

    /** Last shader/program error, surfaced by the shader editor and the debugger. */
    @Volatile var lastError: String = ""

    class Program(val id: Int) {
        val attributes = HashMap<String, Int>()
        val uniforms = HashMap<String, Int>()
        fun attr(name: String) = attributes[name] ?: -1
        fun uni(name: String) = uniforms[name] ?: -1
        fun use() = GLES20.glUseProgram(id)
        fun delete() = GLES20.glDeleteProgram(id)
    }

    /** A reusable interleaved float vertex buffer for triangle lists. */
    class VertexBuffer(val floatsPerVertex: Int, initialVertices: Int = 1024) {
        private var data = java.nio.ByteBuffer.allocateDirect(initialVertices * floatsPerVertex * FLOAT_SIZE)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        var vertexCount = 0
            private set

        fun clear() {
            data.clear()
            vertexCount = 0
        }

        fun put(vararg values: Float) {
            ensure(values.size)
            data.put(values)
            vertexCount += values.size / floatsPerVertex
        }

        fun put(values: FloatArray, count: Int) {
            ensure(count)
            data.put(values, 0, count)
            vertexCount += count / floatsPerVertex
        }

        private fun ensure(floats: Int) {
            if (data.remaining() >= floats) return
            var capacity = data.capacity() * 2
            while (capacity < data.position() + floats) capacity *= 2
            val bigger = java.nio.ByteBuffer.allocateDirect(capacity * FLOAT_SIZE)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            data.flip()
            bigger.put(data)
            data = bigger
        }

        /** Uploads the batch and draws it. [bytesPerVertex], [stride] and offsets are byte units. */
        fun draw(mode: Int = GLES20.GL_TRIANGLES) {
            if (vertexCount == 0) return
            data.flip()
            GLES20.glDrawArrays(mode, 0, vertexCount)
            data.clear()
            vertexCount = 0
        }

        fun buffer() = data
    }

    fun checkError(tag: String): Int {
        val e = GLES20.glGetError()
        if (e != 0) android.util.Log.e("SENGINE-GL", "$tag glError=0x${Integer.toHexString(e)}")
        return e
    }
}
