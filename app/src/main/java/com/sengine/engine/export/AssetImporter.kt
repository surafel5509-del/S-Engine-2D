package com.sengine.engine.export

import java.io.File
import java.security.MessageDigest

/**
 * Asset import helpers shared by the browser and the export pipeline: real hashing, real image header
 * parsing (no full decode needed for metadata) and the starter templates for every asset type the
 * editor can create.
 */
object AssetImporter {

    /** SHA-256 of a file, streamed so large assets do not need to fit in memory. */
    fun hashFile(file: File): String {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    /**
     * Image dimensions straight from the file header (PNG, JPEG, GIF, WebP, BMP) — the importer can
     * record size and aspect without decoding megabytes of pixels.
     */
    fun imageInfo(file: File): IntArray? {
        return runCatching {
            val bytes = ByteArray(64)
            val read = file.inputStream().use { it.read(bytes) }
            if (read < 12) return null
            when {
                (bytes[0].toInt() and 0xFF) == 0x89 && (bytes[1].toInt() and 0xFF) == 0x50 -> {   // PNG
                    intArrayOf(
                        (bytes[16].toInt() and 0xFF shl 24) or (bytes[17].toInt() and 0xFF shl 16) or
                            (bytes[18].toInt() and 0xFF shl 8) or (bytes[19].toInt() and 0xFF),
                        (bytes[20].toInt() and 0xFF shl 24) or (bytes[21].toInt() and 0xFF shl 16) or
                            (bytes[22].toInt() and 0xFF shl 8) or (bytes[23].toInt() and 0xFF)
                    )
                }
                bytes[0].toInt() == 0xFF && bytes[1].toInt() == 0xD8 -> {   // JPEG
                    jpegSize(file)
                }
                bytes[0].toInt() == 'G'.code -> {                            // GIF
                    intArrayOf(
                        (bytes[6].toInt() and 0xFF) or (bytes[7].toInt() and 0xFF shl 8),
                        (bytes[8].toInt() and 0xFF) or (bytes[9].toInt() and 0xFF shl 8)
                    )
                }
                bytes[0].toInt() == 'B'.code && bytes[1].toInt() == 'M'.code -> { // BMP
                    intArrayOf(
                        (bytes[18].toInt() and 0xFF) or (bytes[19].toInt() and 0xFF shl 8),
                        (bytes[22].toInt() and 0xFF) or (bytes[23].toInt() and 0xFF shl 8)
                    )
                }
                bytes[0].toInt() == 'R'.code && bytes[1].toInt() == 'I'.code -> { // WebP
                    intArrayOf(
                        (bytes[26].toInt() and 0xFF) or (bytes[27].toInt() and 0xFF shl 8) or
                            (bytes[28].toInt() and 0xFF shl 16),
                        (bytes[29].toInt() and 0xFF) or (bytes[30].toInt() and 0xFF shl 8) or
                            (bytes[31].toInt() and 0xFF shl 16)
                    )
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun jpegSize(file: File): IntArray? = runCatching {
        file.inputStream().use { input ->
            val data = input.readBytes()
            var i = 2
            while (i + 9 < data.size) {
                if (data[i].toInt() and 0xFF != 0xFF) { i++; continue }
                val marker = data[i + 1].toInt() and 0xFF
                val length = (data[i + 2].toInt() and 0xFF shl 8) or (data[i + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val height = (data[i + 5].toInt() and 0xFF shl 8) or (data[i + 6].toInt() and 0xFF)
                    val width = (data[i + 7].toInt() and 0xFF shl 8) or (data[i + 8].toInt() and 0xFF)
                    return@use intArrayOf(width, height)
                }
                i += 2 + length
            }
            null
        }
    }.getOrNull()

    fun scriptTemplate(): String = """
        // S ENGINE script — attach it to a node and it runs in play mode.
        var speed = 3.0;

        function _ready() {
            print("ready: " + node.name);
        }

        function _process(dt) {
            // input.axis(a, b) reads the Input Map, so keys are remappable in the UI
            var h = input.axis("move_left", "move_right");
            var v = input.axis("move_up", "move_down");
            node.translate(h * speed * dt, v * speed * dt);
        }
    """.trimIndent() + "\n"

    fun shaderTemplate(): String = """
        // S ENGINE 2D shader — GLES2 fragment shader.
        // Uniforms provided by the renderer: uTexture, uColor, uTime, uResolution.
        precision mediump float;
        varying vec2 vUv;
        varying vec4 vColor;
        uniform sampler2D uTexture;
        uniform float uTime;

        void main() {
            vec2 uv = vUv;
            uv.x += sin(uv.y * 12.0 + uTime * 2.0) * 0.02;   // gentle wave
            gl_FragColor = texture2D(uTexture, uv) * vColor;
        }
    """.trimIndent() + "\n"

    fun materialTemplate(): String = """
        {
          "format": "sengine.material",
          "version": 1,
          "name": "new_material",
          "shader": "",
          "blend": "alpha",
          "params": { "tint": [1, 1, 1, 1] }
        }
    """.trimIndent() + "\n"

    fun tilesetTemplate(): String = """
        {
          "format": "sengine.tileset",
          "version": 1,
          "name": "new_tileset",
          "texture": "",
          "tileSize": [16, 16],
          "nextTileId": 1,
          "tiles": []
        }
    """.trimIndent() + "\n"

    fun animationTemplate(): String = """
        {
          "format": "sengine.anim",
          "version": 1,
          "name": "new_animation",
          "length": 1.0,
          "loop": true,
          "tracks": []
        }
    """.trimIndent() + "\n"

    fun particleTemplate(): String = """
        {
          "format": "sengine.particle",
          "version": 1,
          "name": "new_particles",
          "emissionRate": 24,
          "life": [0.6, 1.2],
          "speed": [1.0, 2.5],
          "size": [0.25, 0.1],
          "colors": ["#ffffffff", "#ffffff00"],
          "gravity": 0.0
        }
    """.trimIndent() + "\n"
}
