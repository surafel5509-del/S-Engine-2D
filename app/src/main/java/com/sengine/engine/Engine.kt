package com.sengine.engine

import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.physics.PhysicsWorld
import com.sengine.engine.render.View2D
import com.sengine.engine.script.ScriptSystem
import com.sengine.project.Project
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The runtime core. All scene mutation happens while holding [lock];
 * simulation runs on the GL thread via [tick].
 */
class Engine(val project: Project, initialScene: Scene) {

    enum class Mode { EDIT, PLAY, PAUSED }

    interface Listener {
        fun onLog(level: Int, message: String) {}
        fun onModeChanged(mode: Mode) {}
        fun onSceneReplaced() {}
    }

    val lock = Any()
    @Volatile var scene: Scene = initialScene
        private set
    @Volatile var mode = Mode.EDIT
        private set

    val input = Input()
    val physics = PhysicsWorld()
    val scripts = ScriptSystem(this)
    val audio = AudioSystem(project)
    val gameView = View2D()

    var time = 0.0; private set
    var frame = 0L; private set
    @Volatile var fps = 0f; private set
    private var fpsAcc = 0f
    private var fpsFrames = 0

    val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private var snapshot: String? = null
    private var snapshotScene = ""
    private var pendingSceneLoad: String? = null

    val logs = ArrayDeque<String>()

    init {
        physics.listener = scripts
    }

    // ---------------------------------------------------------------- commands
    fun post(cmd: () -> Unit) { commands.add(cmd) }

    fun play() = post {
        when (mode) {
            Mode.EDIT -> startPlay()
            Mode.PAUSED -> setMode(Mode.PLAY)
            else -> {}
        }
    }

    fun pause() = post { if (mode == Mode.PLAY) setMode(Mode.PAUSED) }
    fun stop() = post { if (mode != Mode.EDIT) stopPlay() }
    fun stepFrame() = post { if (mode == Mode.PAUSED) runFrame(1f / 60f) }

    /** Replace the edited scene (editor only, call while holding lock). */
    fun replaceScene(s: Scene) {
        scene = s
        listeners.forEach { it.onSceneReplaced() }
    }

    fun requestLoadScene(name: String) { pendingSceneLoad = name }

    fun log(level: Int, msg: String) {
        synchronized(logs) {
            logs.addLast(msg)
            while (logs.size > 300) logs.removeFirst()
        }
        listeners.forEach { it.onLog(level, msg) }
    }

    private fun setMode(m: Mode) {
        mode = m
        listeners.forEach { it.onModeChanged(m) }
    }

    // ---------------------------------------------------------------- play mode
    private fun startPlay() {
        snapshot = SceneSerializer.toJson(scene).toString()
        snapshotScene = scene.name
        time = 0.0; frame = 0
        input.clear()
        setMode(Mode.PLAY)
        beginScene()
        log(0, "▶ Play: ${scene.name}")
    }

    private fun beginScene() {
        for (go in scene.objects) for (c in go.components) c.resetRuntime()
        physics.reset()
        audio.start()
        scene.updateTransforms()
        snapCameraToTarget()
        updateGameView()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            go.get<AudioSource>()?.let { if (it.playOnStart) audio.play(it.clip, it.volume, it.loop) }
        }
        scripts.begin()
    }

    private fun endScene() {
        scripts.end()
        audio.stop()
    }

    private fun stopPlay() {
        endScene()
        val snap = snapshot
        if (snap != null) scene = SceneSerializer.fromJson(JSONObject(snap))
        snapshot = null
        input.clear()
        setMode(Mode.EDIT)
        listeners.forEach { it.onSceneReplaced() }
        log(0, "■ Stopped")
    }

    // ---------------------------------------------------------------- loop
    /** Called on the GL thread with [lock] held. */
    fun tick(dt: Float) {
        while (true) {
            val c = commands.poll() ?: break
            try { c() } catch (e: Exception) { log(2, "Engine error: ${e.message}") }
        }
        fpsAcc += dt; fpsFrames++
        if (fpsAcc >= 0.5f) { fps = fpsFrames / fpsAcc; fpsAcc = 0f; fpsFrames = 0 }

        when (mode) {
            Mode.PLAY -> runFrame(dt)
            Mode.EDIT -> { scene.updateTransforms(); updateParticles(dt) }
            Mode.PAUSED -> scene.updateTransforms()
        }
    }

    private fun runFrame(dt0: Float) {
        val dt = dt0.coerceAtMost(0.1f)
        time += dt; frame++
        scene.updateTransforms()
        updateGameView()
        input.beginFrame(gameView)
        scripts.update(dt)
        physics.step(scene, dt)
        cleanupDestroyed()
        scene.updateTransforms()
        updateCameraFollow(dt)
        updateGameView()
        updateParticles(dt)

        val load = pendingSceneLoad
        if (load != null) {
            pendingSceneLoad = null
            val snap = snapshot
            val fromSnapshot = snap != null && load == snapshotScene
            if (fromSnapshot || project.sceneExists(load)) {
                endScene()
                scene = if (fromSnapshot) SceneSerializer.fromJson(JSONObject(snap!!)) else project.loadScene(load)
                listeners.forEach { it.onSceneReplaced() }
                beginScene()
                log(0, "Loaded scene $load")
            } else log(2, "Scene not found: $load")
        }
    }

    private fun cleanupDestroyed() {
        if (scene.objects.none { it.destroyed }) return
        val dead = scene.objects.filter { it.destroyed || isUnderDestroyed(it) }
        for (d in dead) { d.destroyed = true; scripts.onDestroyed(d) }
        scene.objects.removeAll(dead.toSet())
        listeners.forEach { it.onSceneReplaced() }
    }

    private fun isUnderDestroyed(go: GameObject): Boolean {
        var p = go.parent
        while (p != null) { if (p.destroyed) return true; p = p.parent }
        return false
    }

    fun mainCamera(): GameObject? = scene.objects.firstOrNull { it.isActiveInHierarchy() && it.get<Camera2D>() != null }

    private fun snapCameraToTarget() {
        val camGo = mainCamera() ?: return
        val cam = camGo.get<Camera2D>()!!
        if (cam.follow.isBlank()) return
        val t = scene.find(cam.follow) ?: return
        camGo.setWorldPosition(t.world.tx, t.world.ty)
    }

    private fun updateCameraFollow(dt: Float) {
        val camGo = mainCamera() ?: return
        val cam = camGo.get<Camera2D>()!!
        if (cam.follow.isBlank()) return
        val t = scene.find(cam.follow) ?: return
        val w = camGo.computeWorld()
        val k = if (cam.smoothing <= 0f) 1f else (1f - exp(-cam.smoothing * dt))
        camGo.setWorldPosition(w.tx + (t.world.tx - w.tx) * k, w.ty + (t.world.ty - w.ty) * k)
    }

    fun updateGameView() {
        val camGo = mainCamera()
        if (camGo == null) {
            gameView.cx = 0f; gameView.cy = 0f; gameView.size = 5f
            return
        }
        val w = camGo.computeWorld()
        gameView.cx = w.tx; gameView.cy = w.ty
        gameView.size = camGo.get<Camera2D>()!!.size
    }

    fun backgroundColor(): Int = mainCamera()?.get<Camera2D>()?.background ?: 0xFF1B2533.toInt()

    // ---------------------------------------------------------------- particles
    private fun updateParticles(dt: Float) {
        for (go in scene.objects) {
            val pe = go.getAny<ParticleEmitter>() ?: continue
            val alive = go.isActiveInHierarchy() && pe.enabled
            val w = go.world
            if (alive && pe.emitting) pe.accumulator += pe.rate * dt
            var toEmit = pe.accumulator.toInt() + pe.pendingBurst
            pe.accumulator -= pe.accumulator.toInt()
            pe.pendingBurst = 0
            if (!alive) toEmit = 0
            while (toEmit-- > 0 && pe.particles.size < pe.maxParticles) {
                val ang = Math.toRadians((pe.direction + w.rotationDeg + (Random.nextFloat() - 0.5f) * pe.spread).toDouble())
                val sp = pe.speed * (0.6f + Random.nextFloat() * 0.8f)
                pe.particles.add(
                    ParticleEmitter.Particle(
                        w.tx, w.ty, (cos(ang) * sp).toFloat(), (sin(ang) * sp).toFloat(),
                        0f, pe.lifetime * (0.7f + Random.nextFloat() * 0.6f)
                    )
                )
            }
            val it = pe.particles.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.age += dt
                if (p.age >= p.life) { it.remove(); continue }
                p.vy += pe.gravity * dt
                p.x += p.vx * dt; p.y += p.vy * dt
            }
        }
    }

    fun findComponentOwner(c: Component): GameObject? = scene.objects.firstOrNull { c in it.components }

    fun release() {
        synchronized(lock) {
            if (mode != Mode.EDIT) endScene()
            audio.stop()
        }
    }
}
