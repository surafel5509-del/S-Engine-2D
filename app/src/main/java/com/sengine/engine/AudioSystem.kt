package com.sengine.engine

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import com.sengine.engine.core.AssetKind
import com.sengine.project.Project

class AudioSystem(private val project: Project) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val streams = ArrayList<Int>()
    private var tone: ToneGenerator? = null

    fun start() {
        stop()
        try { createPool() } catch (_: Throwable) { pool = null }
    }

    private fun createPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
        pool = p
        for (name in project.listAssets(AssetKind.SOUND)) {
            try {
                ids[name] = p.load(project.assetFile(name).absolutePath, 1)
            } catch (_: Exception) {
            }
        }
    }

    fun play(name: String, volume: Float = 1f, loop: Boolean = false): Int {
        val p = pool ?: return 0
        val id = ids[name] ?: return 0
        val s = p.play(id, volume, volume, 1, if (loop) -1 else 0, 1f)
        if (s != 0) streams.add(s)
        return s
    }

    fun beep(durationMs: Int = 80) {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
        } catch (_: Exception) {
        }
    }

    fun stopAll() {
        val p = pool ?: return
        try { for (s in streams) p.stop(s) } catch (_: Throwable) {}
        streams.clear()
    }

    fun stop() {
        stopAll()
        try { pool?.release() } catch (_: Throwable) {}
        pool = null
        ids.clear()
        tone?.release()
        tone = null
    }
}
