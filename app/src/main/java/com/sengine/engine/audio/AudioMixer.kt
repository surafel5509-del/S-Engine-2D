package com.sengine.engine.audio

import com.sengine.engine.json.JVal
import com.sengine.engine.json.jobj
import com.sengine.engine.math.M
import kotlin.math.abs
import kotlin.math.sqrt

/** A mixer bus with volume, mute/solo and an optional parent bus. */
class AudioBus(var name: String, var parent: String? = null) {
    var volume = 1f
    var muted = false
    var solo = false

    fun toJson(): JVal.Obj = jobj(
        "name" to name, "parent" to (parent ?: ""),
        "volume" to volume, "muted" to muted, "solo" to solo
    )

    companion object {
        fun fromJson(o: JVal.Obj) = AudioBus(o.str("name"), o.str("parent").ifBlank { null }).also {
            it.volume = o.f("volume", 1f)
            it.muted = o.bool("muted")
            it.solo = o.bool("solo")
        }
    }
}

/** A scheduled volume ramp. */
class Fade(val handle: Int, val bus: String, val from: Float, val to: Float, val duration: Float, val stopAtEnd: Boolean) {
    var time = 0f
    var done = false
}

/**
 * Platform-independent audio mixer.
 *
 * The Android layer implements [AudioBackend] (SoundPool for short SFX + MediaPlayer for music);
 * this class owns buses, voices, fades, spatial attenuation and the per-frame volume computation,
 * so mixing behaviour is identical in tests and on device.
 */
class AudioMixer {
    val buses = LinkedHashMap<String, AudioBus>()
    var backend: AudioBackend? = null

    /** Listener position for 2D attenuation. */
    var listenerX = 0f
    var listenerY = 0f
    var masterVolume = 1f
    var muted = false

    class Voice(
        val handle: Int,
        val clip: String,
        val bus: String,
        var baseVolume: Float,
        var pitch: Float,
        var loop: Boolean,
        var nodeId: Long,
        var x: Float,
        var y: Float,
        var spatial: Boolean,
        var minDistance: Float,
        var maxDistance: Float,
        var attenuation: Int,
        var playing: Boolean = true,
        var fadeIn: Float = 0f,
        var age: Float = 0f
    )

    val voices = ArrayList<Voice>()

    /** Deterministic-ish musical probe tone, used by the editor's "test bus" button. */
    var totalVoices = 0
        private set

    init {
        buses["Master"] = AudioBus("Master")
        buses["Music"] = AudioBus("Music", "Master")
        buses["SFX"] = AudioBus("SFX", "Master")
        buses["UI"] = AudioBus("UI", "Master")
        buses["Ambient"] = AudioBus("Ambient", "Master")
    }

    // ------------------------------------------------------------------ buses
    fun bus(name: String): AudioBus = buses.getOrPut(name) { AudioBus(name, "Master") }

    fun addBus(name: String, parent: String = "Master"): AudioBus = buses.getOrPut(name) { AudioBus(name, parent) }

    fun removeBus(name: String): Boolean {
        if (name == "Master") return false
        buses.remove(name)
        return true
    }

    /** Effective gain of a bus: parent chain × mute × solo rules × master. */
    fun effectiveVolume(name: String): Float {
        var volume = 1f
        var current: AudioBus? = buses[name]
        var guard = 0
        while (current != null && guard++ < 16) {
            if (current.muted) return 0f
            volume *= current.volume
            current = current.parent?.let { buses[it] }
        }
        if (muted) return 0f
        val anySolo = buses.values.any { it.solo }
        if (anySolo) {
            var p: AudioBus? = buses[name]
            var soloed = false
            var g2 = 0
            while (p != null && g2++ < 16) {
                if (p.solo) soloed = true
                p = p.parent?.let { buses[it] }
            }
            if (!soloed) return 0f
        }
        return (volume * masterVolume).coerceIn(0f, 4f)
    }

    // ------------------------------------------------------------------ playback
    private var nextHandle = 1

    /** Play a clip, returning a voice handle (0 when the clip is unknown). */
    fun play(
        clip: String,
        nodeId: Long = 0L,
        bus: String = "SFX",
        volume: Float = 1f,
        pitch: Float = 1f,
        loop: Boolean = false,
        spatial: Boolean = false,
        x: Float = 0f,
        y: Float = 0f,
        minDistance: Float = 0.5f,
        maxDistance: Float = 12f,
        attenuation: Int = 1,
        fadeIn: Float = 0f
    ): Int {
        val b = backend ?: return 0
        val handle = nextHandle++
        val ok = b.play(clip, handle, volume, pitch, loop)
        if (!ok) return 0
        val voice = Voice(handle, clip, bus, volume, pitch, loop, nodeId, x, y, spatial, minDistance, maxDistance, attenuation, fadeIn = fadeIn)
        if (fadeIn > 0f) b.setVolume(handle, 0f)
        voices.add(voice)
        totalVoices++
        return handle
    }

    fun stop(handle: Int) {
        backend?.stop(handle)
        voices.removeAll { it.handle == handle }
        fades.removeAll { it.handle == handle }
    }

    fun stopVoiceOfNode(nodeId: Long) {
        val list = voices.filter { it.nodeId == nodeId }
        for (v in list) stop(v.handle)
    }

    fun pauseAll() { backend?.pauseAll() }
    fun resumeAll() { backend?.resumeAll() }

    fun stopAll() {
        backend?.stopAll()
        voices.clear()
        fades.clear()
    }

    fun stopBus(bus: String) {
        for (v in voices.filter { it.bus == bus }) stop(v.handle)
    }

    fun fade(handle: Int, to: Float, duration: Float, stopAtEnd: Boolean = false) {
        val v = voices.firstOrNull { it.handle == handle } ?: return
        val from = currentGain(v)
        fades.removeAll { it.handle == handle }
        fades.add(Fade(handle, v.bus, from, to, duration.coerceAtLeast(0.01f), stopAtEnd))
    }

    /** Crossfade one clip out while another fades in (music transitions). */
    fun crossfade(fromHandle: Int, toClip: String, duration: Float, bus: String = "Music", loop: Boolean = true, volume: Float = 1f): Int {
        val newHandle = play(toClip, bus = bus, volume = volume, loop = loop, fadeIn = duration)
        if (fromHandle != 0) fade(fromHandle, 0f, duration, stopAtEnd = true)
        return newHandle
    }

    private val fades = ArrayList<Fade>()

    // ------------------------------------------------------------------ per-frame
    fun update(dt: Float) {
        val b = backend ?: return
        // fades
        for (i in fades.indices.reversed()) {
            val f = fades[i]
            f.time += dt
            val t = (f.time / f.duration).coerceIn(0f, 1f)
            val value = f.from + (f.to - f.from) * t
            val v = voices.firstOrNull { it.handle == f.handle }
            if (v != null) v.baseVolume = value
            if (t >= 1f) {
                if (f.stopAtEnd) stop(f.handle) else fades.removeAt(i)
            }
        }
        // voices: compute gain, prune finished ones
        for (i in voices.indices.reversed()) {
            val v = voices[i]
            v.age += dt
            var gain = v.baseVolume * effectiveVolume(v.bus)
            if (v.fadeIn > 0f) {
                val k = (v.age / v.fadeIn).coerceIn(0f, 1f)
                gain *= k
            }
            if (v.spatial) gain *= attenuationFactor(v)
            b.setVolume(v.handle, gain.coerceIn(0f, 1f))
            if (!v.loop && b.hasFinished(v.handle)) {
                voices.removeAt(i)
                v.playing = false
            }
        }
    }

    private fun currentGain(v: Voice): Float {
        var gain = v.baseVolume
        if (v.spatial) gain *= attenuationFactor(v)
        return gain
    }

    private fun attenuationFactor(v: Voice): Float {
        val dx = v.x - listenerX
        val dy = v.y - listenerY
        val d = sqrt(dx * dx + dy * dy)
        if (d <= v.minDistance) return 1f
        if (d >= v.maxDistance) return 0f
        val t = (d - v.minDistance) / (v.maxDistance - v.minDistance).coerceAtLeast(0.0001f)
        return when (v.attenuation) {
            0 -> 1f - t                                     // linear
            2 -> sqrt(1f - t)                               // constant power-ish
            else -> 1f / (1f + t * 6f)                       // inverse distance
        }
    }

    /** Update the position of a spatial voice (called by the engine for moving nodes). */
    fun updateVoicePosition(nodeId: Long, x: Float, y: Float) {
        for (v in voices) if (v.nodeId == nodeId) { v.x = x; v.y = y }
    }

    // ------------------------------------------------------------------ persistence
    fun toJson(): JVal.Obj = jobj(
        "master" to masterVolume,
        "muted" to muted,
        "buses" to JVal.Arr().also { a -> buses.values.forEach { a.add(it.toJson()) } }
    )

    fun fromJson(o: JVal.Obj) {
        masterVolume = o.f("master", 1f)
        muted = o.bool("muted")
        val saved = o.objects("buses")
        if (saved.isEmpty()) return
        buses.clear()
        for (b in saved) buses[b.str("name")] = AudioBus.fromJson(b)
        if (!buses.containsKey("Master")) buses["Master"] = AudioBus("Master")
    }
}

/**
 * Platform audio implementation. Implementations must be cheap: [setVolume] is called for every
 * active voice each frame.
 */
interface AudioBackend {
    fun prepare(clip: String, filePath: String): Boolean
    fun play(clip: String, handle: Int, volume: Float, pitch: Float, loop: Boolean): Boolean
    fun setVolume(handle: Int, volume: Float)
    fun setPitch(handle: Int, pitch: Float)
    fun stop(handle: Int)
    fun stopAll()
    fun pauseAll()
    fun resumeAll()
    fun hasFinished(handle: Int): Boolean
    fun release()
}

/** Silent backend used by headless tests and when audio hardware is unavailable. */
class NullAudioBackend : AudioBackend {
    private val finished = HashSet<Int>()
    override fun prepare(clip: String, filePath: String) = true
    override fun play(clip: String, handle: Int, volume: Float, pitch: Float, loop: Boolean) = true
    override fun setVolume(handle: Int, volume: Float) {}
    override fun setPitch(handle: Int, pitch: Float) {}
    override fun stop(handle: Int) { finished.add(handle) }
    override fun stopAll() {}
    override fun pauseAll() {}
    override fun resumeAll() {}
    override fun hasFinished(handle: Int) = false
    override fun release() {}
}

/** Decibel conversion helpers used by the mixer UI. */
object Db {
    fun toGain(db: Float): Float = Math.pow(10.0, (db / 20f).toDouble()).toFloat()
    fun fromGain(gain: Float): Float = if (gain <= 0f) -80f else (20f * kotlin.math.log10(gain.toDouble())).toFloat()
    fun format(gain: Float): String = if (gain <= 0.0001f) "-∞ dB" else "%.1f dB".format(fromGain(gain))
    fun clampDb(db: Float) = M.clamp(db, -80f, 6f)
    fun approx(a: Float, b: Float) = abs(a - b) < 1e-4f
}
