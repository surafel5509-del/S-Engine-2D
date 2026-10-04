package com.sengine.engine

import com.sengine.engine.core.AnimationPlayer
import com.sengine.engine.audio.AudioMixer
import com.sengine.engine.audio.NullAudioBackend
import com.sengine.engine.core.AnimatedSprite2D
import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Label2D
import com.sengine.engine.core.ParticleEmitter2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.Sprite2D
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.debug.Log
import com.sengine.engine.debug.Profiler
import com.sengine.engine.input.InputSystem
import com.sengine.engine.json.Json
import com.sengine.engine.math.M
import com.sengine.engine.physics.PhysicsWorld
import com.sengine.engine.project.Project
import com.sengine.engine.render.BlendKind
import com.sengine.engine.render.Primitive
import com.sengine.engine.render.RenderItem
import com.sengine.engine.render.RenderList
import com.sengine.engine.resources.ResourceManager
import com.sengine.engine.serialization.SceneFormat
import com.sengine.engine.ui.ControlComponent
import com.sengine.engine.ui.ControlType
import com.sengine.engine.editor.ToolState
import com.sengine.engine.ui.UiLayout
import com.sengine.engine.ui.UiSystem
import com.sengine.engine.ui.UiTheme
import com.sengine.engine.script.ScriptSystem
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Editor-time overlays drawn by the runtime render pass (grid, gizmos, collider debug, tile grid).
 * The viewport controller fills this; keeping it a plain data holder means the same code path is
 * used by the game view, the editor viewport and any future offscreen render.
 */
class EditorOverlay {
    var showGrid = true
    var gridStep = 1f
    var snapStep = 0.25f
    var showColliders = true
    var showCameras = true
    var showTileGrid = false
    var showUIBounds = false
    var showAudioAreas = false
    var selectionIds: List<Long> = emptyList()
    var primaryId = -1L
    var tool = 0                       // ToolState.Tool ordinal
    var activeAxis = 0
    var gizmoScale = 1f
    var drawPhysicsDebug = false
    var showPixelGrid = false
    var pixelGridStep = 1f / 16f
    var guides: List<FloatArray> = emptyList()   // x0,y0,x1,y1 in world space
    var selectionRect: FloatArray? = null        // marquee: x, y, w, h in world space
    var hoveredId = -1L
    var showCanvasFrame = false
    var canvasWidth = 0f
    var canvasHeight = 0f
}

/**
 * The runtime. Owns the scene, drives every system and produces the frame's [RenderList].
 *
 * Threading model: scene mutation happens under [lock]; [tick] runs on the GL thread with the lock
 * held. Editor code posts closures through [post] so it never touches the scene mid-frame.
 */
class Engine(val project: Project, initialScene: Scene) {

    enum class Mode { EDIT, PLAY, PAUSED }

    interface Listener {
        fun onModeChanged(mode: Mode) {}
        fun onSceneReplaced() {}
        fun onLog(entry: com.sengine.engine.debug.LogEntry) {}
        fun onFrameStats(fps: Float, frameMs: Float) {}
    }

    val lock = Any()

    @Volatile var scene: Scene = initialScene
        private set

    @Volatile var mode = Mode.EDIT
        private set

    val input = InputSystem()
    val physics = PhysicsWorld()
    val audio = AudioMixer()
    val scripts = ScriptSystem(this)
    val ui = UiSystem()
    val resources = ResourceManager(project)
    val profiler = Profiler()
    val renderList = RenderList()

    /** Fill in by the platform layer. */
    var audioBackendAvailable = false

    var time = 0.0
        private set
    var frame = 0L
        private set
    var fps = 0f
        private set

    val listeners = CopyOnWriteArrayList<Listener>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    private var snapshot: String? = null
    private var snapshotScene = ""
    private var pendingSceneLoad: String? = null

    /** Overlay used by the editor viewport; null in the game view unless physics debug is on. */
    var overlay: EditorOverlay? = null

    /** Set by the editor so play mode can be reported back (e.g. quit to editor). */
    var onPlayFinished: (() -> Unit)? = null

    private var lastStatsPush = 0L

    private val signalBridge = object : com.sengine.engine.core.SignalHub.Dispatcher {
        override fun dispatch(target: GameObject, method: String, data: Any?, args: String): Boolean =
            scripts.sendMessage(target, method, data) != null || scripts.hasMethod(target, method)
    }

    init {
        physics.listener = scripts
        audio.backend = NullAudioBackend()
        scripts.signalDispatcher = signalBridge
        scene.signals.dispatcher = signalBridge
        Log.addListener(object : Log.Listener {
            override fun onEntry(entry: com.sengine.engine.debug.LogEntry) {
                listeners.forEach { it.onLog(entry) }
            }
        })
    }

    // ------------------------------------------------------------------ commands
    fun post(cmd: () -> Unit) {
        commands.add(cmd)
    }

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

    /** Sets the run mode from the host activity (used by play mode). */
    fun requestMode(m: Mode) = post { setMode(m) }

    fun replaceScene(s: Scene) {
        scene = s
        s.signals.dispatcher = signalBridge
        listeners.forEach { it.onSceneReplaced() }
    }

    fun requestLoadScene(name: String) {
        pendingSceneLoad = name
    }

    private fun setMode(m: Mode) {
        mode = m
        listeners.forEach { it.onModeChanged(m) }
    }

    // ------------------------------------------------------------------ play mode
    private fun startPlay() {
        snapshot = Json.write(SceneFormat.toJson(scene), false)
        snapshotScene = scene.name
        time = 0.0
        frame = 0
        input.clear()
        ui.reset()
        Log.info("Engine", "Play: ${scene.name}")
        setMode(Mode.PLAY)
        beginScene()
    }

    private fun beginScene() {
        ui.focusedScene = scene
        ui.designWidth = project.settings.uiDesignWidth.toFloat()
        ui.designHeight = project.settings.uiDesignHeight.toFloat()
        for (go in scene.objects) {
            for (c in go.components) c.resetRuntime()
        }
        physics.reset()
        physics.gravityScale = 1f
        scriptStartPending = true
        scene.updateTransforms()
        snapCameraToTarget()
        updateCameraView()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            go.get<AudioSource>()?.let { src ->
                if (src.playOnStart || src.playOnAwake) src.play()
            }
        }
        scripts.begin()
    }

    private var scriptStartPending = false

    private fun endScene() {
        for (go in scene.objects) for (c in go.components) c.onStop()
        scripts.end()
        audio.stopAll()
    }

    private fun stopPlay() {
        endScene()
        val snap = snapshot
        if (snap != null) {
            val restored = SceneFormat.fromJson(Json.parseObject(snap))
            restored.name = snapshotScene
            restored.signals.dispatcher = signalBridge
            scene = restored
        }
        snapshot = null
        input.clear()
        ui.reset()
        setMode(Mode.EDIT)
        listeners.forEach { it.onSceneReplaced() }
        Log.info("Engine", "Stopped")
    }

    // ------------------------------------------------------------------ loop
    /** Called on the GL thread with [lock] held. */
    fun tick(dt: Float) {
        while (true) {
            val c = commands.poll() ?: break
            try {
                c()
            } catch (e: Throwable) {
                Log.error("Engine", "Command failed: ${e.message}")
            }
        }
        profiler.beginFrame()
        profiler.totalNodes = scene.objects.size
        when (mode) {
            Mode.PLAY -> {
                runFrame(dt)
            }
            Mode.EDIT -> {
                profiler.beginPhase()
                scene.updateTransforms()
                profiler.endPhase(profiler.updateMs)
                updateEditorSimulation(dt)
            }
            Mode.PAUSED -> {
                scene.updateTransforms()
            }
        }
        profiler.endFrame(dt)
        fps = profiler.smoothFps
        val now = System.currentTimeMillis()
        if (now - lastStatsPush > 250) {
            lastStatsPush = now
            listeners.forEach { it.onFrameStats(fps, profiler.frameMs.last()) }
        }
    }

    /** In edit mode scripts may run for live preview, particles keep animating, tiles animate. */
    private fun updateEditorSimulation(dt: Float) {
        profiler.beginPhase()
        // The UI is laid out in the editor too, so controls are visible (and editable) before play.
        ui.beginFrame(ui.designWidth, ui.designHeight)
        UiLayout.layout(scene, ui.designWidth, ui.designHeight)
        profiler.uiControls = countControls()
        updateParticles(dt, emit = true)
        updateTileAnimations(dt)
        updateSpriteAnimations(dt)
        updateAnimationPlayers(dt)
        profiler.endPhase(profiler.updateMs)
    }

    private fun runFrame(dt0: Float) {
        val dt = dt0.coerceAtMost(0.1f)
        time += dt
        frame++
        scene.updateTransforms()

        profiler.beginPhase()
        input.beginFrame()
        updateCameraView()
        profiler.endPhase(profiler.updateMs)

        profiler.beginPhase()
        scripts.beginFrame()
        scriptStartPending = false
        scripts.update(dt)
        scripts.endFrame()
        profiler.endPhase(profiler.scriptMs)

        profiler.beginPhase()
        physics.step(scene, dt)
        profiler.endPhase(profiler.physicsMs)
        profiler.bodies = physics.bodyCount
        profiler.contacts = physics.contactCount
        profiler.tileColliders = physics.tileColliderCount

        profiler.beginPhase()
        cleanupDestroyed()
        scene.updateTransforms()
        updateCameraFollow(dt)
        updateCameraView()
        updateSpriteAnimations(dt)
        updateAnimationPlayers(dt)
        updateParticles(dt, emit = true)
        updateTileAnimations(dt)
        updateAudio(dt)
        profiler.endPhase(profiler.updateMs)

        profiler.beginPhase()
        ui.update(scene, input, scene.objects.let { viewportWidth() }, viewportHeight(), dt, interactive = true)
        handleUiKeyboard()
        profiler.endPhase(profiler.uiMs)

        profiler.scripts = scripts.instanceCount
        profiler.activeNodes = scene.objects.count { it.isActiveInHierarchy() }
        profiler.visibleNodes = scene.objects.count { it.isVisibleInHierarchy() }
        profiler.particles = countParticles()
        profiler.audioVoices = audio.voices.size
        profiler.uiControls = countControls()

        input.endFrame()

        val load = pendingSceneLoad
        if (load != null) {
            pendingSceneLoad = null
            val snap = snapshot
            val fromSnapshot = snap != null && load == snapshotScene
            if (fromSnapshot || project.sceneExists(load)) {
                endScene()
                val next = if (fromSnapshot) SceneFormat.fromJson(Json.parseObject(snap!!)) else project.loadScene(load)
                next.name = load
                next.signals.dispatcher = signalBridge
                if (fromSnapshot) snapshot = Json.write(SceneFormat.toJson(next), false)
                scene = next
                listeners.forEach { it.onSceneReplaced() }
                beginScene()
                Log.info("Engine", "Loaded scene $load")
            } else {
                Log.error("Engine", "Scene not found: $load")
            }
        }
    }

    private fun handleUiKeyboard() {
        val devices = input.devices
        if (ui.focusedFieldId <= 0) return
        if (devices.wasKeyPressed(com.sengine.engine.input.InputMap.KEY_ESCAPE) ||
            devices.wasKeyPressed(com.sengine.engine.input.InputMap.KEY_BACK)
        ) {
            ui.clearFocus(scene)
            ui.onTextDismissed?.invoke()
        }
    }

    private fun countParticles(): Int {
        var n = 0
        for (go in scene.objects) n += go.getAny<ParticleEmitter2D>()?.particleCount ?: 0
        return n
    }

    private fun countControls(): Int {
        var n = 0
        for (go in scene.objects) if (go.ui != null) n++
        return n
    }

    private fun cleanupDestroyed() {
        if (scene.objects.none { it.destroyed }) return
        val dead = scene.objects.filter { it.destroyed || isUnderDestroyed(it) }
        for (d in dead) {
            d.destroyed = true
            for (c in d.components.toList()) runCatching { c.onStop() }
            scripts.onDestroyed(d)
        }
        scene.objects.removeAll(dead.toSet())
        listeners.forEach { it.onSceneReplaced() }
    }

    private fun isUnderDestroyed(go: GameObject): Boolean {
        var p = go.parent
        while (p != null) {
            if (p.destroyed) return true
            p = p.parent
        }
        return false
    }

    // ------------------------------------------------------------------ camera
    val gameView = com.sengine.engine.render.Camera2DView()

    fun mainCamera(): GameObject? {
        var best: GameObject? = null
        var bestPriority = Int.MIN_VALUE
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val cam = go.get<Camera2D>() ?: continue
            if (!cam.current) continue
            if (cam.priority > bestPriority) {
                bestPriority = cam.priority
                best = go
            }
        }
        return best ?: scene.objects.firstOrNull { it.isActiveInHierarchy() && it.get<Camera2D>() != null }
    }

    private fun snapCameraToTarget() {
        val camGo = mainCamera() ?: return
        val cam = camGo.get<Camera2D>() ?: return
        if (cam.followMode == 2 || cam.follow.isBlank()) return
        val target = scene.find(cam.follow) ?: return
        camGo.setWorldPosition(target.world.tx, target.world.ty)
    }

    private fun updateCameraFollow(dt: Float) {
        val camGo = mainCamera() ?: return
        val cam = camGo.get<Camera2D>() ?: return
        if (cam.followMode == 2 || cam.follow.isBlank()) return
        val target = scene.find(cam.follow) ?: return
        val world = camGo.computeWorld()
        val targetX = target.world.tx + cam.offsetX
        val targetY = target.world.ty + cam.offsetY
        val k = if (cam.followMode == 1) 1f else if (cam.smoothing <= 0f) 1f else (1f - exp(-cam.smoothing * dt))
        var nx = M.smoothDamp(world.tx, targetX, cam.smoothing, dt)
        var ny = M.smoothDamp(world.ty, targetY, cam.smoothing, dt)
        if (cam.followMode == 1) {
            nx = targetX; ny = targetY
        }
        // dead zone: only move when the target leaves the box
        val dx = target.world.tx - world.tx
        val dy = target.world.ty - world.ty
        if (kotlin.math.abs(dx) < cam.deadZone.x * cam.size && kotlin.math.abs(dy) < cam.deadZone.y * cam.size) {
            nx = world.tx
            ny = world.ty
        }
        if (cam.limitEnabled) {
            val halfW = cam.halfWidth(gameView.aspect.coerceAtLeast(0.1f))
            val halfH = cam.halfHeight()
            nx = nx.coerceIn(cam.limitX + halfW, (cam.limitX + cam.limitW - halfW).coerceAtLeast(cam.limitX + halfW))
            ny = ny.coerceIn(cam.limitY + halfH, (cam.limitY + cam.limitH - halfH).coerceAtLeast(cam.limitY + halfH))
        }
        camGo.setWorldPosition(nx, ny)
        // audio listener follows the camera
        audio.listenerX = nx
        audio.listenerY = ny
    }

    fun updateCameraView() {
        val camGo = mainCamera()
        if (camGo == null) {
            gameView.cx = 0f; gameView.cy = 0f; gameView.size = 5f
            return
        }
        val cam = camGo.get<Camera2D>() ?: return
        val world = camGo.computeWorld()
        gameView.cx = world.tx
        gameView.cy = world.ty
        gameView.size = cam.size / cam.zoom.coerceAtLeast(0.001f)
        gameView.pixelSnap = cam.pixelPerfect || cam.snapToPixels
        gameView.backgroundColor = cam.background
        gameView.layerMask = if (cam.layers.isBlank()) emptySet() else cam.layers.split(',').map { it.trim() }.toSet()
    }

    fun backgroundColor(): Int = mainCamera()?.get<Camera2D>()?.background ?: scene.settings.background

    fun viewportWidth(): Float = gameView.widthPx.toFloat()
    fun viewportHeight(): Float = gameView.heightPx.toFloat()

    // ------------------------------------------------------------------ systems
    private fun updateSpriteAnimations(dt: Float) {
        for (go in scene.objects) {
            val a = go.get<AnimatedSprite2D>() ?: continue
            a.advance(dt)
        }
    }

    private fun updateAnimationPlayers(dt: Float) {
        for (go in scene.objects) {
            val player = go.get<AnimationPlayer>() ?: continue
            val anim = resources.animation(player.animationAsset) ?: continue
            if (player.autoplay && !player.playing && player.state.time == 0f) player.play()
            player.apply(anim, dt) { method, _ -> scripts.sendMessage(go, method, null) }
        }
    }

    private fun updateTileAnimations(dt: Float) {
        for (go in scene.objects) {
            val tm = go.get<TileMap2D>() ?: continue
            tm.animationTime += dt
            if (tm.runtimeTileSet == null && tm.tileSetAsset.isNotBlank()) {
                tm.runtimeTileSet = resources.tileset(tm.tileSetAsset)
            }
        }
    }

    private fun updateParticles(dt: Float, emit: Boolean) {
        for (go in scene.objects) {
            val pe = go.getAny<ParticleEmitter2D>() ?: continue
            val active = go.isActiveInHierarchy() && pe.enabled
            val world = go.world
            val scale = maxOf(kotlin.math.abs(world.scaleX), kotlin.math.abs(world.scaleY))
            val spec = pe.spec
            if (spec.localSpace) {
                pe.system.update(spec, dt, 0f, 0f, 0f, scale, active && spec.emitting && emit)
            } else {
                pe.system.update(spec, dt, world.tx, world.ty, world.rotationDeg, scale, active && spec.emitting && emit)
            }
        }
    }

    private fun updateAudio(dt: Float) {
        for (go in scene.objects) {
            val src = go.getAny<AudioSource>() ?: continue
            val world = go.world
            audio.updateVoicePosition(go.id, world.tx, world.ty)
        }
        audio.update(dt)
    }

    fun playAudio(source: AudioSource, worldX: Float, worldY: Float) {
        if (!audioBackendAvailable) return
        audio.play(
            source.clip, nodeId = source.gameObject.id, bus = source.bus, volume = source.volume,
            pitch = source.pitch, loop = source.loop, spatial = source.spatial && mode == Mode.PLAY,
            x = worldX, y = worldY, minDistance = source.minDistance, maxDistance = source.maxDistance,
            attenuation = source.attenuation, fadeIn = source.fadeIn
        )
    }

    fun log(level: Int, message: String) {
        when (level) {
            0 -> Log.debug("Engine", message)
            1 -> Log.warn("Engine", message)
            else -> Log.error("Engine", message)
        }
    }

    // ------------------------------------------------------------------ rendering
    /**
     * Build the frame's display list. World space items come from the scene; screen space items
     * (UI controls, editor overlays) are appended after. Nothing is uploaded here — the platform
     * renderer only needs the list.
     */
    fun buildRenderList(view: com.sengine.engine.render.Camera2DView, editorOverlay: EditorOverlay?, editingView: Boolean) {
        profiler.beginPhase()
        renderList.begin(view.backgroundColor)
        renderList.camera.copyFrom(view)
        buildScene(view)
        if (editingView && editorOverlay != null) buildOverlay(view, editorOverlay)
        buildUi(view)
        renderList.sortForBatching()
        renderList.populate(renderList.stats)
        profiler.applyRenderStats(renderList.stats)
        profiler.textureCount = renderList.stats.batches
        profiler.endPhase(profiler.renderMs)
    }

    private fun buildScene(view: com.sengine.engine.render.Camera2DView) {
        val visibleRect = view.snappedBounds().grow(1f)
        val layerMask = view.layerMask
        for (go in scene.objects) {
            if (!go.isVisibleInHierarchy()) continue
            val world = go.world
            if (layerMask.isNotEmpty() && go.layer !in layerMask) {
                var any = false
                for (c in go.components) if (c is Sprite2D || c is AnimatedSprite2D || c is Label2D || c is TileMap2D) { any = true; break }
                if (any) continue
            }
            go.getAny<Sprite2D>()?.let { s ->
                if (s.enabled) {
                    val (fw, fh) = s.size()
                    val w = fw * kotlin.math.abs(world.scaleX)
                    val h = fh * kotlin.math.abs(world.scaleY)
                    val cx = world.tx + (0.5f - go.pivotX) * w
                    val cy = world.ty + (0.5f - go.pivotY) * h
                    if (!cull(cx, cy, w, h, visibleRect)) {
                        renderList.stats.itemsCulled++
                        return@let
                    }
                    val item = renderList.item()
                    item.primitive = if (s.texture.isNotBlank()) Primitive.SPRITE else Primitive.RECT
                    item.x = world.tx
                    item.y = world.ty
                    item.width = fw
                    item.height = fh
                    item.rotation = world.rotationDeg + s.angleOffset
                    item.scaleX = world.scaleX
                    item.scaleY = world.scaleY
                    item.pivotX = go.pivotX
                    item.pivotY = go.pivotY
                    item.color = applyOpacity(s.color, s.opacity)
                    item.blend = s.blend
                    item.texture = s.texture
                    item.material = s.material
                    item.layer = go.layer
                    item.order = go.order
                    item.nodeId = go.id
                    item.textureWidth = s.textureWidth
                    item.textureHeight = s.textureHeight
                    item.u0 = shapeCode(s, s.texture.isNotBlank())
                    item.u1 = if (s.flipX) 1f else 0f
                    item.u2 = if (s.flipY) 1f else 0f
                    item.u3 = if (s.pixelSnap) 1f else 0f
                    s.region()?.let { r -> item.region = com.sengine.engine.math.Rect2(r[0], r[1], r[2], r[3]) }
                }
            }
            go.getAny<AnimatedSprite2D>()?.let { a ->
                if (a.enabled) {
                    val sheet = resources.textureSize(a.spriteSheet)
                    val cols = a.columns.coerceAtLeast(1)
                    val fw = if (sheet != null) sheet.width / cols else 16
                    val fh = if (sheet != null) sheet.height / ((a.frameCount + cols - 1) / cols) else 16
                    val frame = a.currentFrame
                    val rx = (frame % cols) * fw
                    val ry = (frame / cols) * fh
                    val w = if (a.sizeX > 0f) a.sizeX else fw / a.pixelsPerUnit
                    val h = if (a.sizeY > 0f) a.sizeY else fh / a.pixelsPerUnit
                    val item = renderList.item()
                    item.primitive = Primitive.SPRITE
                    item.x = world.tx
                    item.y = world.ty
                    item.width = w
                    item.height = h
                    item.rotation = world.rotationDeg
                    item.scaleX = world.scaleX
                    item.scaleY = world.scaleY
                    item.pivotX = go.pivotX
                    item.pivotY = go.pivotY
                    item.color = applyOpacity(a.color, a.opacity)
                    item.blend = a.blend
                    item.texture = a.spriteSheet
                    item.layer = go.layer
                    item.order = go.order
                    item.nodeId = go.id
                    item.region = com.sengine.engine.math.Rect2(rx.toFloat(), ry.toFloat(), fw.toFloat(), fh.toFloat())
                    item.textureWidth = sheet?.width ?: fw
                    item.textureHeight = sheet?.height ?: fh
                    item.u1 = if (a.flipX) 1f else 0f
                    item.u2 = if (a.flipY) 1f else 0f
                }
            }
            go.getAny<Label2D>()?.let { l ->
                if (l.enabled && l.text.isNotEmpty()) {
                    val item = renderList.item()
                    item.primitive = Primitive.TEXT
                    item.x = world.tx
                    item.y = world.ty
                    item.text = l.text
                    item.fontSize = l.size * view.pixelsPerUnit / 1f
                    item.color = applyOpacity(l.color, l.opacity)
                    item.bold = l.bold
                    item.align = l.align
                    item.font = l.font
                    item.rotation = 0f
                    item.pivotX = go.pivotX
                    item.pivotY = go.pivotY
                    item.layer = go.layer
                    item.order = go.order
                    item.nodeId = go.id
                    item.outlineSize = l.outlineSize
                    item.outlineColor = l.outlineColor
                    item.shadow = l.shadow
                    item.shadowColor = l.shadowColor
                    item.shadowOffsetX = l.shadowOffset.x
                    item.shadowOffsetY = l.shadowOffset.y
                    // store the world-space size and line spacing in the free uniform slots
                    item.u0 = l.size
                    item.u1 = l.lineSpacing
                }
            }
            go.getAny<TileMap2D>()?.let { tm ->
                buildTileMap(tm, go, view, visibleRect)
            }
            go.getAny<ParticleEmitter2D>()?.let { pe ->
                buildParticles(pe, go, view)
            }
        }
    }

    private fun shapeCode(s: Sprite2D, hasTexture: Boolean): Float {
        if (hasTexture) return 0f
        // shapes beyond the primitive enum are encoded in u0 for the platform renderer
        return 1f + s.shape
    }

    private fun applyOpacity(color: Int, opacity: Float): Int {
        if (opacity >= 1f) return color
        val a = (((color ushr 24) and 0xFF) * opacity).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun cull(cx: Float, cy: Float, w: Float, h: Float, rect: com.sengine.engine.math.Rect2): Boolean {
        val hw = w * 0.5f + 0.5f
        val hh = h * 0.5f + 0.5f
        return cx + hw >= rect.x && cx - hw <= rect.right && cy + hh >= rect.y && cy - hh <= rect.bottom
    }

    private fun buildTileMap(tm: TileMap2D, go: GameObject, view: com.sengine.engine.render.Camera2DView, visibleRect: com.sengine.engine.math.Rect2) {
        val set = tm.runtimeTileSet ?: return
        val unit = 1f / tm.pixelsPerUnit
        val world = go.world
        for (layer in tm.data.layers) {
            if (!layer.visible) continue
            for (y in 0 until layer.height) {
                for (x in 0 until layer.width) {
                    val id = layer[x, y]
                    if (id == 0) continue
                    val def = set.tile(id) ?: continue
                    var tx = def.atlasX
                    var ty = def.atlasY
                    if (def.frames.isNotEmpty() && def.fps > 0f) {
                        val frameIndex = ((tm.animationTime * def.fps).toInt()) % (def.frames.size + 1)
                        if (frameIndex > 0) {
                            val f = def.frames[frameIndex - 1]
                            tx = f[0]; ty = f[1]
                        }
                    }
                    val lx = x * tm.tileWidth * unit
                    val ly = y * tm.tileHeight * unit
                    val wx = world.mapX(lx, ly)
                    val wy = world.mapY(lx, ly)
                    val w = tm.tileWidth * unit
                    val h = tm.tileHeight * unit
                    val cx = wx + w * 0.5f
                    val cy = wy + h * 0.5f
                    if (!cull(cx, cy, w, h, visibleRect)) continue
                    val item = renderList.item()
                    item.primitive = Primitive.SPRITE
                    item.x = wx
                    item.y = wy
                    item.width = w
                    item.height = h
                    item.pivotX = 0f
                    item.pivotY = 0f
                    item.rotation = world.rotationDeg
                    item.scaleX = world.scaleX
                    item.scaleY = world.scaleY
                    item.color = applyOpacity(0xFFFFFFFF.toInt(), layer.opacity)
                    item.texture = set.texture
                    item.layer = go.layer
                    item.order = go.order
                    item.nodeId = go.id
                    item.region = com.sengine.engine.math.Rect2(tx.toFloat(), ty.toFloat(), def.width.toFloat(), def.height.toFloat())
                }
            }
        }
    }

    private fun buildParticles(pe: ParticleEmitter2D, go: GameObject, view: com.sengine.engine.render.Camera2DView) {
        val spec = pe.spec
        val world = go.world
        for (p in pe.system.particles) {
            if (p.size <= 0f) continue
            val item = renderList.item()
            item.primitive = if (spec.texture.isNotBlank()) Primitive.SPRITE else Primitive.CIRCLE
            if (spec.localSpace) {
                item.x = world.mapX(p.x, p.y)
                item.y = world.mapY(p.x, p.y)
            } else {
                item.x = p.x
                item.y = p.y
            }
            item.width = p.size
            item.height = p.size
            item.rotation = if (spec.rotateWithVelocity) Math.toDegrees(kotlin.math.atan2(p.vy.toDouble(), p.vx.toDouble())).toFloat() else p.rotation
            item.color = p.color
            item.blend = spec.blend
            item.texture = spec.texture
            item.layer = go.layer
            item.order = go.order
            item.nodeId = go.id
            item.u0 = 1f
        }
    }

    /** Screen-space UI items (design space → viewport pixels). */
    private fun buildUi(view: com.sengine.engine.render.Camera2DView) {
        val sx = view.widthPx / ui.designWidth.coerceAtLeast(1f)
        val sy = view.heightPx / ui.designHeight.coerceAtLeast(1f)
        val interactive = mode != Mode.EDIT
        for (go in scene.objects) {
            val control = go.getAny<ControlComponent>() ?: continue
            if (!go.visible || !go.isActiveInHierarchy()) continue
            val u = control.ui
            if (mode == Mode.PLAY && !u.visibleInPlay) continue
            val r = control.rect
            if (r.width <= 0f || r.height <= 0f) continue
            val x = r.x * sx
            val y = r.y * sy
            val w = r.width * sx
            val h = r.height * sy

            fun item(primitive: Int, color: Int, tx: Float = x, ty: Float = y, tw: Float = w, th: Float = h): RenderItem {
                val it = renderList.item()
                it.primitive = primitive
                it.space = 1
                it.x = tx; it.y = ty
                it.width = tw; it.height = th
                it.pivotX = 0f; it.pivotY = 0f
                it.color = color
                it.layer = "UI"
                it.order = go.order
                it.subOrder = (go.depth() * 100)
                it.nodeId = go.id
                it.clip = if (go.parent?.ui != null) {
                    val pr = go.parent!!.getAny<ControlComponent>()?.rect
                    if (pr != null) com.sengine.engine.math.Rect2(pr.x * sx, pr.y * sy, pr.width * sx, pr.height * sy) else com.sengine.engine.math.Rect2.EMPTY
                } else com.sengine.engine.math.Rect2.EMPTY
                return it
            }

            when (u.controlType) {
                ControlType.PANEL -> item(Primitive.RECT, UiTheme.panel)
                ControlType.LABEL -> {
                    val label = item(Primitive.TEXT, UiTheme.text, x, y, w, h)
                    label.text = u.text
                    label.fontSize = u.fontSize * sy
                    label.align = u.textAlign
                    label.u0 = u.textAlign * 1f
                }
                ControlType.BUTTON -> {
                    val color = when {
                        control.pressed -> UiTheme.buttonPressed
                        control.hovered -> UiTheme.buttonHover
                        else -> UiTheme.backgroundFor(u.style)
                    }
                    item(Primitive.RECT, color)
                    val label = item(Primitive.TEXT, UiTheme.text)
                    label.text = u.text
                    label.fontSize = u.fontSize * sy
                    label.align = 1
                }
                ControlType.IMAGE -> item(Primitive.RECT, 0x00000000)
                ControlType.PROGRESS -> {
                    item(Primitive.RECT, UiTheme.track)
                    val filled = item(Primitive.RECT, UiTheme.primary, x, y, w * u.value.coerceIn(0f, 1f), h)
                    filled.subOrder += 1
                }
                ControlType.SLIDER -> {
                    item(Primitive.RECT, UiTheme.track)
                    val knobW = 18f * sx
                    val knobX = x + (w - knobW) * u.value.coerceIn(0f, 1f)
                    val filled = item(Primitive.RECT, UiTheme.primary, x, y, knobX - x + knobW * 0.5f, h)
                    filled.subOrder += 1
                    val knob = item(Primitive.RECT, if (control.hovered || control.pressed) UiTheme.buttonHover else UiTheme.button, knobX, y - 4f * sy, knobW, h + 8f * sy)
                    knob.subOrder += 2
                }
                ControlType.CHECKBOX -> {
                    item(Primitive.RECT, UiTheme.slot)
                    if (u.checked) {
                        val check = item(Primitive.RECT, UiTheme.primary, x + 4f * sx, y + 4f * sy, 14f * sx, h - 8f * sy)
                        check.subOrder += 1
                    }
                    val label = item(Primitive.TEXT, UiTheme.text, x + 26f * sx, y, w - 26f * sx, h)
                    label.text = u.text
                    label.fontSize = u.fontSize * sy
                    label.align = 0
                    label.u0 = 0f
                }
                ControlType.TEXT_FIELD -> {
                    item(Primitive.RECT, UiTheme.slot)
                    val label = item(Primitive.TEXT, if (u.text.isEmpty()) UiTheme.textDim else UiTheme.text, x + 6f * sx, y, w - 12f * sx, h)
                    label.text = if (u.text.isEmpty()) u.placeholder else u.text + if (control.focused) "|" else ""
                    label.fontSize = u.fontSize * sy
                    label.align = 0
                    label.u0 = 0f
                }
                ControlType.SCROLL -> item(Primitive.RECT, UiTheme.panelAccent)
                ControlType.TABS -> {
                    item(Primitive.RECT, UiTheme.panelAccent)
                    val names = UiLayout.tabNames(u)
                    var tabX = x
                    for ((i, name) in names.withIndex()) {
                        val tabW = (24f + name.length * u.fontSize * 0.62f) * sx
                        val active = i == u.activeTab.coerceIn(0, names.size - 1)
                        val tab = item(Primitive.RECT, if (active) UiTheme.panel else UiTheme.slot, tabX, y, tabW, 30f * sy)
                        tab.subOrder += 1
                        val label = item(Primitive.TEXT, if (active) UiTheme.text else UiTheme.textDim, tabX, y + 6f * sy, tabW, 20f * sy)
                        label.text = name
                        label.fontSize = u.fontSize * sy
                        label.align = 1
                        label.subOrder += 2
                        tabX += tabW
                    }
                }
                ControlType.MENU -> {
                    item(Primitive.RECT, UiTheme.backgroundFor(u.style))
                    val label = item(Primitive.TEXT, UiTheme.text)
                    label.text = u.text
                    label.fontSize = u.fontSize * sy
                    label.align = 0
                    label.u0 = 0f
                }
                ControlType.ROW, ControlType.COLUMN, ControlType.GRID, ControlType.CENTER -> {
                    item(Primitive.RECT, 0x00000000)
                }
                else -> item(Primitive.RECT, UiTheme.panel)
            }
            profiler.uiControls = countControls()
        }
        // focused field border
        val focused = scene.findById(ui.focusedFieldId)
        if (focused != null) {
            val r = focused.getAny<ControlComponent>()?.rect ?: com.sengine.engine.math.Rect2.EMPTY
            val it = renderList.item()
            it.primitive = Primitive.RING
            it.space = 1
            it.x = r.x * sx; it.y = r.y * sy
            it.width = r.width * sx; it.height = r.height * sy
            it.pivotX = 0f; it.pivotY = 0f
            it.color = UiTheme.primary
            it.layer = "UI"
            it.order = 100000
            it.subOrder = -100
        }
    }

    // ------------------------------------------------------------------ editor overlay
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, layer: String = "Editor") {
        val it = renderList.item()
        it.primitive = Primitive.LINE
        it.x = x0; it.y = y0
        it.width = x1 - x0; it.height = y1 - y0
        it.pivotX = 0f; it.pivotY = 0f
        it.color = color
        it.layer = layer
        it.order = 10000
    }

    private fun outline(x: Float, y: Float, w: Float, h: Float, color: Int, rot: Float = 0f, layer: String = "Editor") {
        if (rot == 0f) {
            line(x, y, x + w, y, color, layer)
            line(x + w, y, x + w, y + h, color, layer)
            line(x + w, y + h, x, y + h, color, layer)
            line(x, y + h, x, y, color, layer)
        } else {
            val r = Math.toRadians(rot.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            val cx = x + w * 0.5f; val cy = y + h * 0.5f
            val pts = floatArrayOf(x - cx, y - cy, x + w - cx, y - cy, x + w - cx, y + h - cy, x - cx, y + h - cy)
            val rotated = FloatArray(8)
            for (i in 0 until 4) {
                rotated[i * 2] = pts[i * 2] * c - pts[i * 2 + 1] * s + cx
                rotated[i * 2 + 1] = pts[i * 2] * s + pts[i * 2 + 1] * c + cy
            }
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                line(rotated[i * 2], rotated[i * 2 + 1], rotated[j * 2], rotated[j * 2 + 1], color, layer)
            }
        }
    }

    private fun buildOverlay(view: com.sengine.engine.render.Camera2DView, ed: EditorOverlay) {
        val bounds = view.snappedBounds()
        if (ed.showGrid) {
            val step = ed.gridStep.coerceAtLeast(0.01f)
            val minor = 0x22FFFFFF
            val major = 0x44FFFFFF
            var x = kotlin.math.floor(bounds.x / step) * step
            var guard = 0
            while (x <= bounds.right && guard++ < 600) {
                val idx = Math.round(x / step)
                val color = if (kotlin.math.abs(x) < step * 0.01f) 0xAA7FA7FF.toInt() else if (idx % 5 == 0) major else minor
                line(x, bounds.y, x, bounds.bottom, color)
                x += step
            }
            var y = kotlin.math.floor(bounds.y / step) * step
            guard = 0
            while (y <= bounds.bottom && guard++ < 600) {
                val idx = Math.round(y / step)
                val color = if (kotlin.math.abs(y) < step * 0.01f) 0xAAFF7F7F.toInt() else if (idx % 5 == 0) major else minor
                line(bounds.x, y, bounds.right, y, color)
                y += step
            }
        }
        if (ed.showPixelGrid) {
            val step = ed.pixelGridStep
            var x = kotlin.math.floor(bounds.x / step) * step
            var guard = 0
            while (x <= bounds.right && guard++ < 2000) {
                line(x, bounds.y, x, bounds.bottom, 0x18FFFFFF)
                x += step
            }
            var y = kotlin.math.floor(bounds.y / step) * step
            guard = 0
            while (y <= bounds.bottom && guard++ < 2000) {
                line(bounds.x, y, bounds.right, y, 0x18FFFFFF)
                y += step
            }
        }

        for ((go, _) in scene.hierarchy()) {
            if (!go.isActiveInHierarchy()) continue
            val selected = go.id == ed.primaryId || go.id in ed.selectionIds
            go.get<Camera2D>()?.let { cam ->
                if (ed.showCameras) {
                    val hw = cam.halfWidth(view.aspect) + view.cx - go.world.tx
                    outline(go.world.tx - hw, go.world.ty - cam.halfHeight(), hw * 2, cam.halfHeight() * 2, 0xCCFFFFFF.toInt())
                    line(go.world.tx - 0.2f, go.world.ty, go.world.tx + 0.2f, go.world.ty, 0xCCFFFFFF.toInt())
                    line(go.world.tx, go.world.ty - 0.2f, go.world.tx, go.world.ty + 0.2f, 0xCCFFFFFF.toInt())
                }
            }
            go.get<com.sengine.engine.core.Collider2D>()?.let { col ->
                if (ed.showColliders || selected) {
                    val color = if (col.isTrigger) 0xCC4FC3F7.toInt() else col.debugColor
                    val world = go.world
                    val cx = world.mapX(col.offsetX, col.offsetY)
                    val cy = world.mapY(col.offsetX, col.offsetY)
                    when (col.shape) {
                        com.sengine.engine.core.ColliderShape.CIRCLE -> {
                            val r = col.radius * kotlin.math.abs(world.scaleX)
                            var prevX = cx + r; var prevY = cy
                            for (i in 1..24) {
                                val a = i * Math.PI * 2 / 24
                                val nx = cx + (cos(a) * r).toFloat()
                                val ny = cy + (sin(a) * r).toFloat()
                                line(prevX, prevY, nx, ny, color)
                                prevX = nx; prevY = ny
                            }
                        }
                        com.sengine.engine.core.ColliderShape.POLYGON -> {
                            val pts = com.sengine.engine.physics.PolygonUtil.worldPoints(go, col)
                            var i = 0
                            while (i + 3 < pts.size) {
                                line(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], color)
                                i += 2
                            }
                            line(pts[pts.size - 2], pts[pts.size - 1], pts[0], pts[1], color)
                        }
                        else -> {
                            val w = col.width * kotlin.math.abs(world.scaleX)
                            val h = col.height * kotlin.math.abs(world.scaleY)
                            outline(cx - w / 2, cy - h / 2, w, h, color, world.rotationDeg)
                        }
                    }
                }
            }
            go.get<com.sengine.engine.core.Area2D>()?.let { area ->
                if (ed.showColliders || ed.showAudioAreas) {
                    val world = go.world
                    val cx = world.mapX(area.offsetX, area.offsetY)
                    val cy = world.mapY(area.offsetX, area.offsetY)
                    val color = 0xCC7FDBFF.toInt()
                    if (area.shape == com.sengine.engine.core.ColliderShape.CIRCLE) {
                        var prevX = cx + area.radius; var prevY = cy
                        for (i in 1..20) {
                            val a = i * Math.PI * 2 / 20
                            val nx = cx + (cos(a) * area.radius).toFloat()
                            val ny = cy + (sin(a) * area.radius).toFloat()
                            line(prevX, prevY, nx, ny, color)
                            prevX = nx; prevY = ny
                        }
                    } else {
                        outline(cx - area.width / 2, cy - area.height / 2, area.width, area.height, color)
                    }
                }
            }
            go.get<AudioSource>()?.let { src ->
                if (ed.showAudioAreas && src.spatial) {
                    val world = go.world
                    var prevX = world.tx + src.maxDistance; var prevY = world.ty
                    for (i in 1..28) {
                        val a = i * Math.PI * 2 / 28
                        val nx = world.tx + (cos(a) * src.maxDistance).toFloat()
                        val ny = world.ty + (sin(a) * src.maxDistance).toFloat()
                        line(prevX, prevY, nx, ny, 0x667FDBFF)
                        prevX = nx; prevY = ny
                    }
                }
            }
            go.get<TileMap2D>()?.let { tm ->
                if (ed.showTileGrid) {
                    val unit = 1f / tm.pixelsPerUnit
                    val w = tm.tileWidth * unit
                    val h = tm.tileHeight * unit
                    val bounds2 = tm.paintedBounds()
                    if (bounds2 != null) {
                        val world = go.world
                        var x = bounds2[0]
                        while (x <= bounds2[0] + bounds2[2] + 0.001f) {
                            line(world.mapX(x, bounds2[1]), world.mapY(x, bounds2[1]), world.mapX(x, bounds2[1] + bounds2[3]), world.mapY(x, bounds2[1] + bounds2[3]), 0x22FFFFFF)
                            x += w
                        }
                        var y = bounds2[1]
                        while (y <= bounds2[1] + bounds2[3] + 0.001f) {
                            line(world.mapX(bounds2[0], y), world.mapY(bounds2[0], y), world.mapX(bounds2[0] + bounds2[2], y), world.mapY(bounds2[0] + bounds2[2], y), 0x22FFFFFF)
                            y += h
                        }
                    }
                }
            }
            if (ed.showUIBounds && go.ui != null) {
                val control = go.getAny<ControlComponent>()
                val r = control?.rect
                if (r != null && view.widthPx > 0) {
                    val sx = view.widthPx / ui.designWidth.coerceAtLeast(1f)
                    val sy = view.heightPx / ui.designHeight.coerceAtLeast(1f)
                    val wx = view.cx - view.halfWidth + r.x / sx
                    val wy = view.cy + view.halfHeight - r.y / sy
                    outline(wx, wy - r.height / sy, r.width / sx, r.height / sy, 0x88FFB74D.toInt())
                }
            }
        }

        // composition guides: thirds + center cross
        if (ed.guides.isEmpty()) {
            val w = bounds.width
            val h = bounds.height
            val c = 0x18FFFFFF
            line(bounds.x + w / 3f, bounds.y, bounds.x + w / 3f, bounds.bottom, c)
            line(bounds.x + w * 2f / 3f, bounds.y, bounds.x + w * 2f / 3f, bounds.bottom, c)
            line(bounds.x, bounds.y + h / 3f, bounds.right, bounds.y + h / 3f, c)
            line(bounds.x, bounds.y + h * 2f / 3f, bounds.right, bounds.y + h * 2f / 3f, c)
        } else {
            for (g in ed.guides) line(g[0], g[1], g[2], g[3], 0x88FF7FD0.toInt(), "Editor")
        }

        // selection + gizmos
        val selectedNodes = ed.selectionIds.mapNotNull { scene.findById(it) }
        val primary = scene.findById(ed.primaryId)
        for (go in selectedNodes) {
            val world = go.world
            val bounds2 = scene.nodeBounds(go) ?: com.sengine.engine.math.Rect2(world.tx - 0.5f, world.ty - 0.5f, 1f, 1f)
            val color = if (go === primary) 0xFFFF9F1C.toInt() else 0x88FF9F1C.toInt()
            outline(world.mapX(go.world.tx - world.tx, 0f) - 0f + bounds2.x, bounds2.y, bounds2.width, bounds2.height, color)
            // pivot marker
            val px = world.tx
            val py = world.ty
            line(px - 0.12f, py, px + 0.12f, py, 0xFFFFD24D.toInt())
            line(px, py - 0.12f, px, py + 0.12f, 0xFFFFD24D.toInt())
        }

        if (primary != null) {
            val world = primary.world
            val len = 1.2f * ed.gizmoScale
            val active = ed.activeAxis
            when (ToolState.of(ed.tool)) {
                ToolState.MOVE -> {
                    line(world.tx, world.ty, world.tx + len, world.ty, if (active == 1) 0xFFFFFF66.toInt() else 0xFFFF4D4D.toInt())
                    line(world.tx, world.ty, world.tx, world.ty + len, if (active == 2) 0xFFFFFF66.toInt() else 0xFF5CE65C.toInt())
                }
                ToolState.ROTATE -> {
                    var prevX = world.tx + len; var prevY = world.ty
                    val color = if (active != 0) 0xFFFFFF66.toInt() else 0xFF4DA6FF.toInt()
                    for (i in 1..40) {
                        val a = i * Math.PI * 2 / 40
                        val nx = world.tx + (cos(a) * len).toFloat()
                        val ny = world.ty + (sin(a) * len).toFloat()
                        line(prevX, prevY, nx, ny, color)
                        prevX = nx; prevY = ny
                    }
                }
                ToolState.SCALE -> {
                    line(world.tx, world.ty, world.tx + len, world.ty, if (active == 1) 0xFFFFFF66.toInt() else 0xFFFF4D4D.toInt())
                    line(world.tx, world.ty, world.tx, world.ty + len, if (active == 2) 0xFFFFFF66.toInt() else 0xFF5CE65C.toInt())
                    outline(world.tx + len - 0.08f, world.ty - 0.08f, 0.16f, 0.16f, 0xFFFF4D4D.toInt())
                    outline(world.tx - 0.08f, world.ty + len - 0.08f, 0.16f, 0.16f, 0xFF5CE65C.toInt())
                }
                else -> {}
            }
        }

        // rectangular selection marquee
        val marquee = ed.selectionRect
        if (marquee != null && marquee[2] > 0.001f && marquee[3] > 0.001f) {
            val c = 0xFFFFFFFF.toInt()
            outline(marquee[0], marquee[1], marquee[2], marquee[3], c)
            outline(marquee[0], marquee[1], marquee[2], marquee[3], 0x554DA6FF.toInt())
        }

        // hover highlight: shows what a click would select
        if (ed.hoveredId > 0L) {
            scene.findById(ed.hoveredId)?.let { hovered ->
                if (hovered.id !in ed.selectionIds && hovered.id != ed.primaryId) {
                    scene.nodeBounds(hovered)?.let { b -> outline(b.x, b.y, b.width, b.height, 0x99FFFFFF.toInt()) }
                }
            }
        }

        if (ed.showCanvasFrame && ed.canvasWidth > 0f && ed.canvasHeight > 0f) {
            // the design resolution of the game, so a scene can be composed for the real screen
            val left = view.cx - ed.canvasWidth * 0.5f
            val bottom = view.cy - ed.canvasHeight * 0.5f
            outline(left, bottom, ed.canvasWidth, ed.canvasHeight, 0x66FFFFFF.toInt())
        }

        if (ed.drawPhysicsDebug) {
            val contacts = physics.debugContacts
            for (c in contacts) {
                line(c[0] - 0.08f, c[1], c[0] + 0.08f, c[1], 0xFFFF00FF.toInt())
                line(c[0], c[1] - 0.08f, c[0], c[1] + 0.08f, 0xFFFF00FF.toInt())
                line(c[0], c[1], c[0] + c[2] * 0.3f, c[1] + c[3] * 0.3f, 0xFF00FF00.toInt())
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (mode != Mode.EDIT) endScene()
            audio.stopAll()
        }
    }
}
