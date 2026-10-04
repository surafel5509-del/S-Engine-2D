package com.sengine.engine.animation

import com.sengine.engine.json.JVal
import com.sengine.engine.json.jarr
import com.sengine.engine.json.jobj
import com.sengine.engine.math.Easing

enum class LoopMode(val label: String) {
    ONCE("Once"), LOOP("Loop"), PING_PONG("Ping Pong");

    companion object {
        val LABELS = values().map { it.label }
        fun of(index: Int) = values()[index.coerceIn(0, values().size - 1)]
    }
}

/** A keyframe on a scalar track. [easing] indexes [Easing.NAMES]. */
data class Keyframe(
    var time: Float,
    var value: Float,
    var easing: Int = 0
) {
    fun copy() = Keyframe(time, value, easing)
}

/**
 * A named animation made of scalar tracks plus script/audio event markers.
 *
 * Track names are conventional and understood by [AnimationPlayer] when it drives a node:
 *  * `position.x`, `position.y`
 *  * `rotation`
 *  * `scale.x`, `scale.y`
 *  * `color.r`, `color.g`, `color.b`, `alpha` (0..1)
 *  * `visible` (0/1)
 *  * `sprite.frame` (flip-book frame index)
 * Everything else is treated as a custom property that scripts can read.
 */
class Animation(var name: String = "New Animation") {
    var length: Float = 1f
    var loop: LoopMode = LoopMode.LOOP
    var speed: Float = 1f
    val tracks = LinkedHashMap<String, MutableList<Keyframe>>()
    val events = ArrayList<Event>()

    data class Event(var time: Float, var type: Int, var value: String) {
        // type: 0 = call script method, 1 = play sound, 2 = emit signal
        companion object {
            val TYPES = listOf("Call Method", "Play Sound", "Emit Signal")
        }
    }

    // ------------------------------------------------------------------ editing
    fun track(name: String): MutableList<Keyframe> = tracks.getOrPut(name) { ArrayList() }

    fun trackNames(): List<String> = tracks.keys.toList()

    fun setKey(trackName: String, time: Float, value: Float, easing: Int = 0): Keyframe {
        val list = track(trackName)
        val existing = list.firstOrNull { kotlin.math.abs(it.time - time) < 1e-4f }
        if (existing != null) {
            existing.value = value
            existing.easing = easing
            return existing
        }
        val k = Keyframe(time, value, easing)
        list.add(k)
        list.sortBy { it.time }
        return k
    }

    fun removeKey(trackName: String, index: Int): Boolean {
        val list = tracks[trackName] ?: return false
        if (index !in list.indices) return false
        list.removeAt(index)
        if (list.isEmpty()) tracks.remove(trackName)
        return true
    }

    fun moveKey(trackName: String, index: Int, time: Float): Boolean {
        val list = tracks[trackName] ?: return false
        if (index !in list.indices) return false
        list[index].time = time.coerceIn(0f, length)
        list.sortBy { it.time }
        return true
    }

    fun removeTrack(name: String) {
        tracks.remove(name)
    }

    fun addEvent(time: Float, type: Int, value: String) {
        events.add(Event(time.coerceIn(0f, length), type, value))
        events.sortBy { it.time }
    }

    fun trimToContent() {
        var max = 0f
        for (list in tracks.values) for (k in list) max = maxOf(max, k.time)
        for (e in events) max = maxOf(max, e.time)
        if (max > 0f) length = max
    }

    // ------------------------------------------------------------------ sampling
    fun sample(trackName: String, time: Float): Float? {
        val list = tracks[trackName] ?: return null
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0].value
        if (time <= list.first().time) return list.first().value
        if (time >= list.last().time) return list.last().value
        for (i in 0 until list.size - 1) {
            val a = list[i]
            val b = list[i + 1]
            if (time >= a.time && time <= b.time) {
                val span = (b.time - a.time).coerceAtLeast(1e-5f)
                val t = Easing.apply(a.easing, (time - a.time) / span)
                return a.value + (b.value - a.value) * t
            }
        }
        return list.last().value
    }

    fun hasTrack(name: String) = tracks.containsKey(name) && tracks[name]!!.isNotEmpty()

    fun copy(): Animation {
        val a = Animation(name)
        a.length = length; a.loop = loop; a.speed = speed
        tracks.forEach { (k, v) -> a.tracks[k] = v.map { it.copy() }.toMutableList() }
        a.events.addAll(events.map { it.copy() })
        return a
    }

    // ------------------------------------------------------------------ persistence
    fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("format", FORMAT)
        o.put("version", 1)
        o.put("name", name)
        o.put("length", length)
        o.put("loop", loop.name)
        o.put("speed", speed)
        val ts = JVal.Obj()
        tracks.forEach { (k, keys) ->
            val arr = JVal.Arr()
            for (key in keys) arr.add(jarr(key.time, key.value, key.easing))
            ts.put(k, arr)
        }
        o.put("tracks", ts)
        if (events.isNotEmpty()) {
            val es = JVal.Arr()
            for (e in events) es.add(jobj("time" to e.time, "type" to e.type, "value" to e.value))
            o.put("events", es)
        }
        return o
    }

    fun fromJson(o: JVal.Obj) {
        name = o.str("name", name)
        length = o.f("length", 1f).coerceAtLeast(0.01f)
        loop = runCatching { LoopMode.valueOf(o.str("loop", "LOOP")) }.getOrDefault(LoopMode.LOOP)
        speed = o.f("speed", 1f)
        tracks.clear()
        (o["tracks"] as? JVal.Obj)?.fields?.forEach { (k, v) ->
            val list = ArrayList<Keyframe>()
            (v as? JVal.Arr)?.forEach { kv ->
                val arr = kv as? JVal.Arr ?: return@forEach
                val t = (arr.getOrNull(0) as? JVal.Num)?.v?.toFloat() ?: return@forEach
                val value = (arr.getOrNull(1) as? JVal.Num)?.v?.toFloat() ?: 0f
                val easing = (arr.getOrNull(2) as? JVal.Num)?.v?.toInt() ?: 0
                list.add(Keyframe(t, value, easing))
            }
            list.sortBy { it.time }
            if (list.isNotEmpty()) tracks[k] = list
        }
        events.clear()
        o.arr("events").forEach { e ->
            val eo = e as? JVal.Obj ?: return@forEach
            events.add(Event(eo.f("time"), eo.i("type"), eo.str("value")))
        }
        events.sortBy { it.time }
    }

    companion object {
        const val FORMAT = "sengine.animation"

        fun fromJson(text: String) = Animation().also { it.fromJson(com.sengine.engine.json.Json.parseObject(text)) }
    }
}

/**
 * Flip-book sprite animation: a frame range inside a sprite sheet, played at [fps].
 * Used by [com.sengine.engine.core.AnimatedSprite2D] and by particle textures.
 */
class SpriteFrames(
    var columns: Int = 1,
    var count: Int = 1,
    var fps: Float = 8f,
    var startIndex: Int = 0,
    var loop: LoopMode = LoopMode.LOOP
) {
    fun frameAt(time: Float): Int {
        if (count <= 1 || fps <= 0f) return startIndex
        val raw = (time * fps).toInt()
        val index = when (loop) {
            LoopMode.ONCE -> raw.coerceIn(0, count - 1)
            LoopMode.LOOP -> ((raw % count) + count) % count
            LoopMode.PING_PONG -> {
                val period = (count - 1) * 2
                if (period <= 0) 0 else {
                    val m = ((raw % period) + period) % period
                    if (m < count) m else period - m
                }
            }
        }
        return startIndex + index
    }

    fun duration(): Float = if (fps <= 0f) 0f else count / fps

    fun copy() = SpriteFrames(columns, count, fps, startIndex, loop)

    fun toJson(): JVal.Obj = jobj(
        "columns" to columns, "count" to count, "fps" to fps,
        "start" to startIndex, "loop" to loop.name
    )

    fun fromJson(o: JVal.Obj) {
        columns = o.i("columns", 1).coerceAtLeast(1)
        count = o.i("count", 1).coerceAtLeast(1)
        fps = o.f("fps", 8f)
        startIndex = o.i("start")
        loop = runCatching { LoopMode.valueOf(o.str("loop", "LOOP")) }.getOrDefault(LoopMode.LOOP)
    }

    companion object {
        fun fromJson(o: JVal.Obj) = SpriteFrames().also { it.fromJson(o) }
    }
}

/** Runtime state of a playing animation (kept out of the serialized data). */
class PlaybackState {
    var time = 0f
    var playing = false
    var finished = false
    var currentAnimation = ""
    private var direction = 1f

    fun play(animation: String) {
        if (currentAnimation != animation) {
            currentAnimation = animation
            time = 0f
        }
        playing = true
        finished = false
        direction = 1f
    }

    fun stop() {
        playing = false
    }

    fun advance(dt: Float, length: Float, loop: LoopMode, speed: Float): Boolean {
        if (!playing) return false
        val step = dt * speed * direction
        time += step
        var wrapped = false
        when (loop) {
            LoopMode.ONCE -> if (time >= length) {
                time = length
                playing = false
                finished = true
            }
            LoopMode.LOOP -> if (time >= length) {
                time -= length * kotlin.math.floor(time / length).coerceAtLeast(1f)
                wrapped = true
            }
            LoopMode.PING_PONG -> {
                if (time > length) {
                    time = length - (time - length)
                    direction = -1f
                    wrapped = true
                } else if (time < 0f) {
                    time = -time
                    direction = 1f
                    wrapped = true
                }
            }
        }
        return wrapped
    }
}
