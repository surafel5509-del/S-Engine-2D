package com.sengine.engine.core

import com.sengine.engine.animation.Animation
import com.sengine.engine.animation.LoopMode
import com.sengine.engine.animation.PlaybackState
import com.sengine.engine.particles.ParticleSpec
import com.sengine.engine.particles.ParticleSystem
import com.sengine.engine.tilemap.TileMapData

/** Attaches a JavaScript behaviour to a node. */
class ScriptComponent : Component() {
    override val type = TYPE
    override val category = "Scripting"
    override val description = "JavaScript behaviour (start / update / physics_update / signals)"

    var script = ""
    var params = ""
    var enabledInEditor = true

    override fun props() = listOf(
        Prop.Asset("Script", AssetKind.SCRIPT, { script }, { script = it }),
        Prop.S("Params", { params }, { params = it }, multiline = true, tooltip = "k=v pairs exposed as top-level variables, e.g. speed=6, jump=11"),
        Prop.B("Run In Editor", { enabledInEditor }, { enabledInEditor = it }, tooltip = "Execute while the scene is being edited (hot reload preview)")
    )

    override fun resetRuntime() {}

    companion object {
        const val TYPE = "Script"

        /** Starter script offered by FileSystem ▸ New script — every lifecycle hook documented. */
        const val TEMPLATE = """// S Engine script — runs on the node this component is attached to.
var speed = 4;

function start() {
  // called once when play mode starts
}

function update(dt) {
  // called every frame; dt is in seconds
  var mov = SInput.axis("move_left", "move_right");
  if (mov != 0) {
    self.x = self.x + mov * speed * dt;
    self.flipX = mov < 0;
  }
}

function physics_update(dt) {
  // fixed 60 Hz step — use it for movement that must be frame-rate independent
}

function onCollision(other) {
  SConsole.print("hit " + other.name);
}

function onTrigger(other) {
}

function onTap() {
}

function onSignal(name, data) {
}
"""
    }
}

/**
 * Sound source. Supports buses (Master/Music/SFX/UI/Ambient), pitch, looping, fades and 2D
 * positional attenuation handled by the audio engine.
 */
class AudioSource : Component(), SignalListener {
    override val type = TYPE
    override val category = "Audio"
    override val description = "Sound emitter with bus routing and 2D attenuation"

    var clip = ""
    var bus = "SFX"
    var volume = 1f
    var pitch = 1f
    var loop = false
    var playOnStart = true
    var playOnAwake = false
    var spatial = true
    var minDistance = 0.5f
    var maxDistance = 12f
    var attenuation = 1              // 0 linear, 1 inverse, 2 constant power
    var fadeIn = 0f
    var fadeOut = 0f
    var maxConcurrent = 1

    /** Runtime handle returned by the audio backend. */
    @Volatile var playing = false
    var playCount = 0

    override fun props() = listOf(
        Prop.Asset("Clip", AssetKind.SOUND, { clip }, { clip = it }),
        Prop.E("Bus", BUSES, { BUSES.indexOf(bus).coerceAtLeast(0) }, { bus = BUSES[it] }),
        Prop.F("Volume", { volume }, { volume = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.F("Pitch", { pitch }, { pitch = it.coerceIn(0.1f, 4f) }, 0.05f, 0.1f, 4f),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.F("Fade In", { fadeIn }, { fadeIn = it.coerceAtLeast(0f) }, 0.1f, 0f, 30f, section = "Fades"),
        Prop.F("Fade Out", { fadeOut }, { fadeOut = it.coerceAtLeast(0f) }, 0.1f, 0f, 30f, section = "Fades"),
        Prop.B("2D Positional", { spatial }, { spatial = it }, section = "Spatial"),
        Prop.F("Min Distance", { minDistance }, { minDistance = it.coerceAtLeast(0f) }, 0.1f, 0f, 100f, section = "Spatial"),
        Prop.F("Max Distance", { maxDistance }, { maxDistance = it.coerceAtLeast(0.01f) }, 0.5f, 0.01f, 500f, section = "Spatial"),
        Prop.E("Attenuation", ATTENUATION, { attenuation }, { attenuation = it }, section = "Spatial"),
        Prop.Info("Playing", { playing.toString() }, section = "Runtime")
    )

    override fun resetRuntime() {
        playing = false
        playCount = 0
    }

    override fun onSignal(name: String, data: Any?): Boolean {
        when (name) {
            "play" -> { play(); return true }
            "stop" -> { stop(); return true }
        }
        return false
    }

    /** Runtime hooks filled by the engine (kept as lambdas so the core stays platform-free). */
    var onPlayRequest: ((AudioSource) -> Unit)? = null
    var onStopRequest: ((AudioSource) -> Unit)? = null

    fun play() {
        onPlayRequest?.invoke(this)
        playing = true
        playCount++
    }

    fun stop() {
        onStopRequest?.invoke(this)
        playing = false
    }

    companion object {
        const val TYPE = "AudioSource"
        val BUSES = listOf("Master", "Music", "SFX", "UI", "Ambient")
        val ATTENUATION = listOf("Linear", "Inverse", "Constant Power")
    }
}

/**
 * 2D particle emitter. The simulation itself lives in [ParticleSystem] (pooled, allocation free);
 * this component wires it to a node transform and exposes every parameter to the editor.
 */
class ParticleEmitter2D : Component(), SignalListener {
    override val type = TYPE
    override val category = "Rendering"
    override val description = "Particle emitter with presets, curves and bursts"

    val spec = ParticleSpec()
    val system = ParticleSystem()

    var preset = ""
    val particles get() = system.particles
    val particleCount get() = system.count

    override fun props() = listOf(
        Prop.E("Preset", listOf("") + com.sengine.engine.particles.ParticlePresets.NAMES, { PRESET_INDEX }, { applyPreset(it) }, section = "Preset"),
        Prop.B("Emitting", { spec.emitting }, { spec.emitting = it }, section = "Emission"),
        Prop.F("Rate", { spec.rate }, { spec.rate = it.coerceAtLeast(0f) }, 1f, 0f, 2000f, section = "Emission"),
        Prop.I("Burst", { spec.burst }, { spec.burst = it.coerceAtLeast(0) }, 0, 5000, section = "Emission"),
        Prop.I("Max Particles", { spec.maxParticles }, { spec.maxParticles = it.coerceIn(1, 20000) }, 1, 20000, section = "Emission"),
        Prop.F("Lifetime Min", { spec.lifetimeMin }, { spec.lifetimeMin = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 60f, section = "Lifetime"),
        Prop.F("Lifetime Max", { spec.lifetimeMax }, { spec.lifetimeMax = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 60f, section = "Lifetime"),
        Prop.F("Speed Min", { spec.speedMin }, { spec.speedMin = it }, 0.1f, 0f, 200f, section = "Velocity"),
        Prop.F("Speed Max", { spec.speedMax }, { spec.speedMax = it }, 0.1f, 0f, 200f, section = "Velocity"),
        Prop.F("Direction", { spec.direction }, { spec.direction = it }, 1f, -360f, 360f, section = "Velocity"),
        Prop.F("Spread", { spec.spread }, { spec.spread = it.coerceIn(0f, 360f) }, 1f, 0f, 360f, section = "Velocity"),
        Prop.F("Gravity X", { spec.gravityX }, { spec.gravityX = it }, 0.1f, section = "Forces"),
        Prop.F("Gravity Y", { spec.gravityY }, { spec.gravityY = it }, 0.1f, section = "Forces"),
        Prop.F("Radial Accel", { spec.radialAccel }, { spec.radialAccel = it }, 0.1f, section = "Forces"),
        Prop.F("Tangential Accel", { spec.tangentialAccel }, { spec.tangentialAccel = it }, 0.1f, section = "Forces"),
        Prop.F("Drag", { spec.drag }, { spec.drag = it.coerceAtLeast(0f) }, 0.05f, 0f, 20f, section = "Forces"),
        Prop.F("Start Size", { spec.startSize }, { spec.startSize = it.coerceAtLeast(0f) }, 0.02f, 0f, 100f, section = "Appearance"),
        Prop.F("End Size", { spec.endSize }, { spec.endSize = it.coerceAtLeast(0f) }, 0.02f, 0f, 100f, section = "Appearance"),
        Prop.E("Size Curve", com.sengine.engine.math.Easing.NAMES, { spec.sizeEasing }, { spec.sizeEasing = it }, section = "Appearance"),
        Prop.C("Start Color", { spec.startColor }, { spec.startColor = it }, section = "Appearance"),
        Prop.C("End Color", { spec.endColor }, { spec.endColor = it }, section = "Appearance"),
        Prop.E("Color Curve", com.sengine.engine.math.Easing.NAMES, { spec.colorEasing }, { spec.colorEasing = it }, section = "Appearance"),
        Prop.Asset("Texture", AssetKind.TEXTURE, { spec.texture }, { spec.texture = it }, section = "Appearance"),
        Prop.B("Sprite Sheet", { spec.spriteSheet }, { spec.spriteSheet = it }, section = "Appearance"),
        Prop.E("Blend", ParticleSpec.BLENDS, { spec.blend }, { spec.blend = it }, section = "Appearance"),
        Prop.F("Start Rotation", { spec.startRotation }, { spec.startRotation = it }, 1f, section = "Rotation"),
        Prop.F("Rotation Speed", { spec.rotationSpeed }, { spec.rotationSpeed = it }, 1f, section = "Rotation"),
        Prop.B("Rotate With Velocity", { spec.rotateWithVelocity }, { spec.rotateWithVelocity = it }, section = "Rotation"),
        Prop.E("Emit Shape", ParticleSpec.SHAPES, { spec.emitShape }, { spec.emitShape = it }, section = "Emission Shape"),
        Prop.F("Shape Size", { spec.emitShapeSize }, { spec.emitShapeSize = it.coerceAtLeast(0f) }, 0.1f, 0f, 100f, section = "Emission Shape"),
        Prop.B("Local Space", { spec.localSpace }, { spec.localSpace = it }, section = "Advanced"),
        Prop.B("Sort By Age", { spec.sortByAge }, { spec.sortByAge = it }, section = "Advanced"),
        Prop.I("Seed", { spec.seed }, { spec.seed = it }, section = "Advanced"),
        Prop.Info("Particles", { particleCount.toString() }, section = "Runtime")
    )

    private var PRESET_INDEX: Int
        get() = (listOf("") + com.sengine.engine.particles.ParticlePresets.NAMES).indexOf(preset).coerceAtLeast(0)
        set(value) {
            val names = listOf("") + com.sengine.engine.particles.ParticlePresets.NAMES
            applyPreset(value.coerceIn(0, names.size - 1))
        }

    fun applyPreset(index: Int) {
        val names = com.sengine.engine.particles.ParticlePresets.NAMES
        if (index <= 0) {
            preset = ""
            return
        }
        preset = names[index - 1]
        com.sengine.engine.particles.ParticlePresets.apply(preset, spec)
    }

    fun burst(n: Int) {
        system.burst(n)
    }

    fun clear() = system.clear()

    override fun resetRuntime() {
        system.clear()
        if (spec.burst > 0) system.burst(spec.burst)
    }

    override fun onSignal(name: String, data: Any?): Boolean {
        when (name) {
            "burst" -> { system.burst((data as? Number)?.toInt() ?: 16); return true }
            "start" -> { spec.emitting = true; return true }
            "stop" -> { spec.emitting = false; return true }
        }
        return false
    }

    companion object {
        const val TYPE = "Particles2D"
    }
}

/**
 * Paints tile layers from a [TileMapData] + `TileSet` asset. Provides world/cell conversion, tile
 * lookup (including collision metadata for the physics world) and playback of animated tiles.
 */
class TileMap2D : Component() {
    override val type = TYPE
    override val category = "Rendering"
    override val description = "Tile layers painted from a TileSet"

    var tileSetAsset = ""
    var tileWidth = 16
    var tileHeight = 16
    var pixelsPerUnit = 16f
    var opaque = true

    val data = TileMapData()

    /** Runtime: the loaded tileset, filled by the resource manager. */
    var runtimeTileSet: com.sengine.engine.tilemap.TileSet? = null

    /** Animation clock for animated tiles. */
    var animationTime = 0f

    override fun props() = listOf(
        Prop.Asset("Tile Set", AssetKind.TILESET, { tileSetAsset }, { tileSetAsset = it }),
        Prop.I("Tile Width", { tileWidth }, { tileWidth = it.coerceAtLeast(1) }, 1, 512),
        Prop.I("Tile Height", { tileHeight }, { tileHeight = it.coerceAtLeast(1) }, 1, 512),
        Prop.F("Pixels / Unit", { pixelsPerUnit }, { pixelsPerUnit = it.coerceAtLeast(1f) }, 1f, 1f, 512f),
        Prop.S("Layers", { data.layers.joinToString(", ") { it.name } }, { }, tooltip = "Edit layers in the TileMap panel", section = "Info"),
        Prop.Info("Tiles", { data.layers.sumOf { l -> l.cells.count { it != 0 } }.toString() }, section = "Info")
    )

    fun currentTile(layerIndex: Int, cellX: Int, cellY: Int): Int {
        val layer = data.layers.getOrNull(layerIndex) ?: return 0
        return layer[cellX, cellY]
    }

    /** Paints a tile into a layer from a cell coordinate; returns true when something changed. */
    fun setTile(layerIndex: Int, cellX: Int, cellY: Int, tileId: Int): Boolean {
        val layer = data.layers.getOrNull(layerIndex) ?: return false
        if (cellX < 0 || cellY < 0 || cellX >= layer.width || cellY >= layer.height) return false
        if (layer[cellX, cellY] == tileId) return false
        layer[cellX, cellY] = tileId
        return true
    }

    /** Paint/erase at a world position (tile brush + scripts). */
    fun paintAt(worldX: Float, worldY: Float, tileId: Int, erase: Boolean = false, layerIndex: Int = 0): Boolean {
        val (cx, cy) = worldToCell(worldX, worldY)
        return setTile(layerIndex, cx, cy, if (erase) 0 else tileId)
    }

    /** Fills a rectangle of cells (fill tool / rect tool). */
    fun fillRect(layerIndex: Int, x0: Int, y0: Int, x1: Int, y1: Int, tileId: Int): Int {
        var changed = 0
        for (y in minOf(y0, y1)..maxOf(y0, y1)) {
            for (x in minOf(x0, x1)..maxOf(x0, x1)) if (setTile(layerIndex, x, y, tileId)) changed++
        }
        return changed
    }

    /** World position of a cell's top-left corner. */
    fun cellToWorld(cellX: Int, cellY: Int): Pair<Float, Float> {
        val go = if (attached) gameObject else return 0f to 0f
        val w = go.computeWorld()
        val lx = cellX * tileWidth / pixelsPerUnit
        val ly = cellY * tileHeight / pixelsPerUnit
        return w.mapX(lx, ly) to w.mapY(lx, ly)
    }

    /** Cell coordinate for a world point (used by the tile brush). */
    fun worldToCell(worldX: Float, worldY: Float): Pair<Int, Int> {
        val go = if (attached) gameObject else return 0 to 0
        val inv = go.computeWorld().inverted() ?: return 0 to 0
        val lx = inv.mapX(worldX, worldY) * pixelsPerUnit
        val ly = inv.mapY(worldX, worldY) * pixelsPerUnit
        return kotlin.math.floor(lx / tileWidth).toInt() to kotlin.math.floor(ly / tileHeight).toInt()
    }

    /** Resolve the collision shape of a painted cell (0 when the tile has no collision). */
    fun tileCollision(cellX: Int, cellY: Int): Int {
        val set = runtimeTileSet ?: return 0
        for (layer in data.layers) {
            if (!layer.collision) continue
            val id = layer[cellX, cellY]
            if (id == 0) continue
            val def = set.tile(id) ?: continue
            if (def.collision != 0) return def.collision
        }
        return 0
    }

    /** Bounding box of all painted cells in local units — used to size the physics area. */
    fun paintedBounds(): FloatArray? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (layer in data.layers) {
            for (y in 0 until layer.height) for (x in 0 until layer.width) {
                if (layer[x, y] == 0) continue
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        if (minX > maxX) return null
        return floatArrayOf(
            minX * tileWidth / pixelsPerUnit,
            minY * tileHeight / pixelsPerUnit,
            (maxX - minX + 1) * tileWidth / pixelsPerUnit,
            (maxY - minY + 1) * tileHeight / pixelsPerUnit
        )
    }

    override fun resetRuntime() {
        animationTime = 0f
    }

    companion object {
        const val TYPE = "TileMap"
    }
}

/**
 * Plays [Animation] assets and applies their tracks to the node (transform, sprite colour, alpha,
 * visibility, sprite frame) plus events (script calls, sounds, signals).
 */
class AnimationPlayer : Component(), SignalListener {
    override val type = TYPE
    override val category = "Animation"
    override val description = "Plays animation assets on this node"

    var animationAsset = ""
    var autoplay = true
    var speed = 1f
    var loop = 1                     // LoopMode ordinal
    var playing = false

    /** Runtime. */
    var runtime: Animation? = null
    val state = PlaybackState()
    private val firedEvents = HashSet<Int>()

    override fun props() = listOf(
        Prop.Asset("Animation", AssetKind.ANIMATION, { animationAsset }, { animationAsset = it }),
        Prop.B("Autoplay", { autoplay }, { autoplay = it }),
        Prop.F("Speed", { speed }, { speed = it }, 0.1f, 0.05f, 10f),
        Prop.E("Loop", LoopMode.LABELS, { loop }, { loop = it }),
        Prop.Info("Time", { "%.2f".format(state.time) }, section = "Runtime"),
        Prop.Info("Playing", { playing.toString() }, section = "Runtime")
    )

    fun loopMode(): LoopMode = LoopMode.of(loop)

    fun play(restart: Boolean = false) {
        if (restart) state.time = 0f
        state.playing = true
        playing = true
        firedEvents.clear()
    }

    fun stop() {
        state.playing = false
        playing = false
    }

    /** Apply one frame of animation to the owning node. [anim] is resolved by the runtime. */
    fun apply(anim: Animation, dt: Float, scriptBridge: ((String, Float) -> Unit)? = null) {
        runtime = anim
        val length = anim.length.coerceAtLeast(0.001f)
        if (playing) {
            val previous = state.time
            state.advance(dt, length, loopMode(), speed * anim.speed)
            // fire events whose marker was crossed
            for ((i, e) in anim.events.withIndex()) {
                if (e.time >= previous && e.time <= state.time + 1e-4f && !firedEvents.contains(i)) {
                    firedEvents.add(i)
                    when (e.type) {
                        0 -> scriptBridge?.invoke(e.value, 0f)
                        1 -> gameObject.emit("play_sound", e.value)
                        2 -> gameObject.emit(e.value)
                    }
                }
            }
            if (state.time < previous) firedEvents.clear()
        }
        val t = state.time
        val go = gameObject
        anim.sample("position.x", t)?.let { go.x = it }
        anim.sample("position.y", t)?.let { go.y = it }
        anim.sample("rotation", t)?.let { go.rotation = it }
        anim.sample("scale.x", t)?.let { go.scaleX = it }
        anim.sample("scale.y", t)?.let { go.scaleY = it }
        anim.sample("visible", t)?.let { go.visible = it > 0.5f }
        val alpha = anim.sample("alpha", t)
        go.getAny<Sprite2D>()?.let { s ->
            anim.sample("color.r", t)?.let { r -> s.color = withChannel(s.color, 16, r) }
            anim.sample("color.g", t)?.let { g -> s.color = withChannel(s.color, 8, g) }
            anim.sample("color.b", t)?.let { b -> s.color = withChannel(s.color, 0, b) }
            if (alpha != null) s.opacity = alpha
        }
        go.getAny<Label2D>()?.let { l ->
            anim.sample("color.r", t)?.let { r -> l.color = withChannel(l.color, 16, r) }
            anim.sample("color.g", t)?.let { g -> l.color = withChannel(l.color, 8, g) }
            anim.sample("color.b", t)?.let { b -> l.color = withChannel(l.color, 0, b) }
            if (alpha != null) l.opacity = alpha
        }
        anim.sample("sprite.frame", t)?.let { f ->
            go.getAny<AnimatedSprite2D>()?.currentFrame = f.toInt().coerceAtLeast(0)
        }
    }

    private fun withChannel(color: Int, shift: Int, value: Float): Int {
        val v = (value.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
        return (color and (0xFF shl shift).inv()) or (v shl shift)
    }

    override fun resetRuntime() {
        state.time = 0f
        state.playing = false
        state.finished = false
        firedEvents.clear()
        playing = autoplay
    }

    override fun onSignal(name: String, data: Any?): Boolean {
        when (name) {
            "play" -> { play(true); return true }
            "stop" -> { stop(); return true }
            "pause" -> { state.playing = false; playing = false; return true }
        }
        return false
    }

    companion object {
        const val TYPE = "AnimationPlayer"
    }
}

/** One-shot or repeating timer that emits a signal / calls a script method. */
class TimerComponent : Component(), SignalListener {
    override val type = TYPE
    override val category = "Gameplay"
    override val description = "Timer emitting the timeout signal"

    var waitTime = 1f
    var oneShot = true
    var autostart = true
    var signalName = Signals.TIMER_TIMEOUT

    var running = false
    var timeLeft = 0f
    var ticks = 0

    override fun props() = listOf(
        Prop.F("Wait Time", { waitTime }, { waitTime = it.coerceAtLeast(0.001f) }, 0.1f, 0.001f, 3600f),
        Prop.B("One Shot", { oneShot }, { oneShot = it }),
        Prop.B("Autostart", { autostart }, { autostart = it }),
        Prop.S("Signal", { signalName }, { signalName = it }),
        Prop.Info("Left", { "%.2f".format(timeLeft) }, section = "Runtime"),
        Prop.Info("Ticks", { ticks.toString() }, section = "Runtime")
    )

    fun restart() {
        timeLeft = waitTime
        running = true
    }

    fun stop() {
        running = false
    }

    fun tick(dt: Float) {
        if (!running) return
        timeLeft -= dt
        if (timeLeft > 0f) return
        ticks++
        gameObject.emit(signalName)
        gameObject.emit(Signals.TIMER_TIMEOUT)
        if (oneShot) running = false else timeLeft += waitTime
    }

    override fun resetRuntime() {
        ticks = 0
        timeLeft = waitTime
        running = autostart
    }

    override fun onSignal(name: String, data: Any?): Boolean {
        when (name) {
            "start" -> { restart(); return true }
            "stop" -> { stop(); return true }
        }
        return false
    }

    companion object {
        const val TYPE = "Timer"
    }
}

/** Emits a configured signal when the node starts / becomes visible / is triggered. */
class SignalEmitter : Component(), SignalListener {
    override val type = TYPE
    override val category = "Logic"
    override val description = "Emits signals on start, enable or trigger"

    var onStartSignal = ""
    var onTriggerSignal = ""
    var onInteractSignal = ""
    var payload = ""

    override fun props() = listOf(
        Prop.S("On Start", { onStartSignal }, { onStartSignal = it }, tooltip = "Signal emitted when play mode starts"),
        Prop.S("On Trigger", { onTriggerSignal }, { onTriggerSignal = it }),
        Prop.S("On Interact", { onInteractSignal }, { onInteractSignal = it }),
        Prop.S("Payload", { payload }, { payload = it })
    )

    override fun onStart() {
        if (onStartSignal.isNotBlank()) gameObject.emit(onStartSignal, payload.ifBlank { null })
    }

    override fun onSignal(name: String, data: Any?): Boolean {
        when (name) {
            "trigger" -> if (onTriggerSignal.isNotBlank()) { gameObject.emit(onTriggerSignal, data ?: payload); return true }
            "interact" -> if (onInteractSignal.isNotBlank()) { gameObject.emit(onInteractSignal, data ?: payload); return true }
        }
        return false
    }

    companion object {
        const val TYPE = "SignalEmitter"
    }
}
