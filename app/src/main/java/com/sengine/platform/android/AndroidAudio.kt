package com.sengine.platform.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.sengine.engine.audio.AudioBackend
import java.io.File

/**
 * Android audio backend.
 *
 * Short SFX use [SoundPool] (low latency, many simultaneous voices, per-voice rate/volume — which
 * is how pitch and bus volume are implemented). Music and long ambient loops use [MediaPlayer] so
 * tracks stream from disk instead of being held in memory.
 *
 * Assets are cached by clip name; a missing or corrupt file reports once through [onError] and
 * stays silent — audio never throws into the game loop.
 */
class AndroidAudio(
    private val context: Context,
    private val resolver: (String) -> File?
) : AudioBackend {

    private val soundIds = HashMap<String, Int>()
    private val handles = HashMap<Int, Handle>()
    private var soundPool: SoundPool? = null
    private var nextHandle = 1

    var onError: ((String, String) -> Unit)? = null

    private class Handle(val clip: String, val streamed: Boolean, val poolId: Int, val player: MediaPlayer?)

    private fun pool(): SoundPool {
        soundPool?.let { return it }
        val p = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        soundPool = p
        return p
    }

    override fun prepare(clip: String, filePath: String): Boolean {
        val file = if (filePath.isNotEmpty()) File(filePath) else resolver(clip)
        if (file == null || !file.exists()) {
            onError?.invoke(clip, "file not found")
            return false
        }
        if (isStreamed(file)) return true
        if (soundIds.containsKey(clip)) return true
        return try {
            val id = pool().load(file.absolutePath, 1)
            if (id == 0) false else { soundIds[clip] = id; true }
        } catch (e: Exception) {
            onError?.invoke(clip, e.message ?: "load failed")
            false
        }
    }

    override fun play(clip: String, handle: Int, volume: Float, pitch: Float, loop: Boolean): Boolean {
        val file = resolver(clip) ?: return false
        if (!file.exists()) { onError?.invoke(clip, "file not found"); return false }
        val id = handle.coerceAtLeast(1)
        val v = volume.coerceIn(0f, 1f)
        val rate = pitch.coerceIn(0.5f, 2.0f)
        if (isStreamed(file)) return playStreamed(id, clip, file, v, rate, loop)
        val poolId = soundIds[clip] ?: try {
            pool().load(file.absolutePath, 1).also { soundIds[clip] = it }
        } catch (e: Exception) {
            onError?.invoke(clip, e.message ?: "load failed"); return false
        }
        if (poolId == 0) return false
        return try {
            val stream = pool().play(poolId, v, v, 1, if (loop) -1 else 0, rate)
            if (stream == 0) return false
            handles[id] = Handle(clip, false, stream, null)
            true
        } catch (e: Exception) {
            onError?.invoke(clip, e.message ?: "play failed")
            false
        }
    }

    private fun playStreamed(handle: Int, clip: String, file: File, volume: Float, pitch: Float, loop: Boolean): Boolean {
        return try {
            handles[handle]?.player?.let { runCatching { it.release() } }
            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.isLooping = loop
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                player.playbackParams = player.playbackParams.setSpeed(pitch)
            }
            player.setVolume(volume, volume)
            player.prepare()
            player.start()
            handles[handle] = Handle(clip, true, 0, player)
            player.setOnCompletionListener {
                handles.remove(handle)
                runCatching { it.release() }
            }
            true
        } catch (e: Exception) {
            onError?.invoke(clip, e.message ?: "stream failed")
            false
        }
    }

    override fun setVolume(handle: Int, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        val h = handles[handle] ?: return
        if (h.streamed) runCatching { h.player?.setVolume(v, v) }
        else runCatching { soundPool?.setVolume(h.poolId, v, v) }
    }

    override fun setPitch(handle: Int, pitch: Float) {
        val rate = pitch.coerceIn(0.5f, 2.0f)
        val h = handles[handle] ?: return
        if (h.streamed) {
            val p = h.player ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                runCatching { p.playbackParams = p.playbackParams.setSpeed(rate) }
            }
        } else {
            runCatching { soundPool?.setRate(h.poolId, rate) }
        }
    }

    override fun stop(handle: Int) {
        val h = handles.remove(handle) ?: return
        if (h.streamed) {
            runCatching { h.player?.stop() }
            runCatching { h.player?.release() }
        } else {
            runCatching { soundPool?.stop(h.poolId) }
        }
    }

    override fun pauseAll() {
        for (h in handles.values) if (h.streamed) runCatching { if (h.player?.isPlaying == true) h.player?.pause() }
    }

    override fun resumeAll() {
        for (h in handles.values) if (h.streamed) runCatching { if (h.player?.isPlaying == false) h.player?.start() }
    }

    override fun stopAll() {
        for (id in handles.keys.toList()) stop(id)
    }

    override fun hasFinished(handle: Int): Boolean {
        val h = handles[handle] ?: return true
        if (!h.streamed) return false // SoundPool has no reliable "finished" query
        return h.player?.let { runCatching { !it.isPlaying }.getOrDefault(true) } ?: true
    }

    override fun release() {
        stopAll()
        soundPool?.release()
        soundPool = null
        soundIds.clear()
    }

    /** Preloads an SFX asset so the first play has no hitch. */
    fun preload(clip: String, filePath: String) { prepare(clip, filePath) }

    /** True for tracks that should stream (music/ambience) instead of being held by SoundPool. */
    private fun isStreamed(file: File): Boolean {
        val size = file.length()
        val ext = file.extension.lowercase()
        return when (ext) {
            "wav" -> size > STREAM_THRESHOLD
            "ogg", "mp3", "m4a", "flac", "aac" -> true
            else -> size > STREAM_THRESHOLD
        }
    }

    companion object {
        private const val MAX_STREAMS = 24
        private const val STREAM_THRESHOLD = 512 * 1024L
    }
}
