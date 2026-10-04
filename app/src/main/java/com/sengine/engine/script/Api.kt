package com.sengine.engine.script

import com.sengine.engine.Engine
import com.sengine.engine.core.AnimationPlayer
import com.sengine.engine.audio.AudioMixer
import com.sengine.engine.audio.Db
import com.sengine.engine.core.AnimatedSprite2D
import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Label2D
import com.sengine.engine.core.ParticleEmitter2D
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Sprite2D
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.particles.ParticlePresets
import com.sengine.engine.tilemap.TileBrush
import com.sengine.engine.ui.ControlComponent
import org.mozilla.javascript.Context
import kotlin.math.sqrt

/**
 * JavaScript view of a node. Getter/setter pairs become JS properties (`self.x`, `self.vx`, …);
 * every method performs the real engine operation.
 */
class SObject(val go: GameObject, private val engine: Engine, private val sys: ScriptSystem) {

    // ---- identity ---------------------------------------------------------
    fun getId(): Double = go.id.toDouble()
    fun getName(): String = go.name
    fun setName(v: String) { go.name = v }
    fun getTag(): String = go.tag
    fun setTag(v: String) { go.tag = v }
    fun getActive(): Boolean = go.active
    fun setActive(v: Boolean) { go.active = v }
    fun getVisible(): Boolean = go.visible
    fun setVisible(v: Boolean) { go.visible = v }
    fun getLayer(): String = go.layer
    fun setLayer(v: String) { go.layer = v }
    fun getOrder(): Double = go.order.toDouble()
    fun setOrder(v: Double) { go.order = v.toInt() }
    fun getType(): String = go.type

    // ---- transform --------------------------------------------------------
    fun getX(): Double = go.x.toDouble()
    fun setX(v: Double) { go.x = v.toFloat() }
    fun getY(): Double = go.y.toDouble()
    fun setY(v: Double) { go.y = v.toFloat() }
    fun getRotation(): Double = go.rotation.toDouble()
    fun setRotation(v: Double) { go.rotation = v.toFloat() }
    fun getScaleX(): Double = go.scaleX.toDouble()
    fun setScaleX(v: Double) { go.scaleX = v.toFloat() }
    fun getScaleY(): Double = go.scaleY.toDouble()
    fun setScaleY(v: Double) { go.scaleY = v.toFloat() }
    fun getWorldX(): Double = go.world.tx.toDouble()
    fun getWorldY(): Double = go.world.ty.toDouble()
    fun getPivotX(): Double = go.pivotX.toDouble()
    fun setPivotX(v: Double) { go.pivotX = v.toFloat() }
    fun getPivotY(): Double = go.pivotY.toDouble()
    fun setPivotY(v: Double) { go.pivotY = v.toFloat() }

    fun setPosition(x: Double, y: Double) { go.x = x.toFloat(); go.y = y.toFloat() }
    fun setWorldPosition(x: Double, y: Double) = go.setWorldPosition(x.toFloat(), y.toFloat())
    fun move(dx: Double, dy: Double) { go.x += dx.toFloat(); go.y += dy.toFloat() }
    fun moveWorld(dx: Double, dy: Double) = go.translateWorld(dx.toFloat(), dy.toFloat())
    fun rotate(deg: Double) { go.rotation += deg.toFloat() }
    fun lookAt(x: Double, y: Double) {
        val dx = x - go.world.tx
        val dy = y - go.world.ty
        go.rotation = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    // ---- physics ----------------------------------------------------------
    fun getVx(): Double = (go.getAny<Rigidbody2D>()?.vx ?: 0f).toDouble()
    fun setVx(v: Double) { go.getAny<Rigidbody2D>()?.vx = v.toFloat() }
    fun getVy(): Double = (go.getAny<Rigidbody2D>()?.vy ?: 0f).toDouble()
    fun setVy(v: Double) { go.getAny<Rigidbody2D>()?.vy = v.toFloat() }
    fun isGrounded(): Boolean = go.getAny<Rigidbody2D>()?.grounded ?: false
    fun isOnWall(): Double = (go.getAny<Rigidbody2D>()?.onWall ?: 0).toDouble()
    fun isOnCeiling(): Boolean = go.getAny<Rigidbody2D>()?.onCeiling ?: false
    fun getBodyType(): Double = (go.getAny<Rigidbody2D>()?.bodyType ?: 0).toDouble()
    fun setBodyType(t: Double) { go.getAny<Rigidbody2D>()?.bodyType = t.toInt() }

    fun addForce(fx: Double, fy: Double) {
        val rb = go.getAny<Rigidbody2D>() ?: return
        rb.vx += (fx / rb.mass).toFloat()
        rb.vy += (fy / rb.mass).toFloat()
    }

    fun setVelocity(vx: Double, vy: Double) {
        val rb = go.getAny<Rigidbody2D>() ?: return
        rb.vx = vx.toFloat(); rb.vy = vy.toFloat()
    }

    fun impulse(fx: Double, fy: Double) = addForce(fx, fy)

    fun overlaps(other: SObject?): Boolean {
        val o = other?.go ?: return false
        val a = go.computeWorld()
        val b = o.computeWorld()
        val ca = go.getAny<Collider2D>()
        val cb = o.getAny<Collider2D>()
        val aw = (ca?.bounds()?.first ?: 1f) * a.scaleX / 2
        val ah = (ca?.bounds()?.second ?: 1f) * a.scaleY / 2
        val bw = (cb?.bounds()?.first ?: 1f) * b.scaleX / 2
        val bh = (cb?.bounds()?.second ?: 1f) * b.scaleY / 2
        return kotlin.math.abs(a.tx - b.tx) < aw + bw && kotlin.math.abs(a.ty - b.ty) < ah + bh
    }

    fun distanceTo(other: SObject?): Double {
        val o = other?.go ?: return 0.0
        val a = go.computeWorld()
        val b = o.computeWorld()
        val dx = a.tx - b.tx
        val dy = a.ty - b.ty
        return sqrt((dx * dx + dy * dy).toDouble())
    }

    // ---- rendering --------------------------------------------------------
    fun getText(): String = go.getAny<Label2D>()?.text ?: go.getAny<ControlComponent>()?.ui?.text ?: ""
    fun setText(v: Any?) {
        val text = Context.toString(v)
        go.getAny<Label2D>()?.text = text
        go.getAny<ControlComponent>()?.ui?.text = text
    }

    fun getColor(): String {
        val c = go.getAny<Sprite2D>()?.color ?: go.getAny<Label2D>()?.color ?: -1
        return Prop.C.format(c)
    }

    fun setColor(hex: String) {
        val c = try { Prop.C.parse(hex) } catch (e: Exception) { return }
        go.getAny<Sprite2D>()?.color = c
        go.getAny<Label2D>()?.color = c
    }

    fun getAlpha(): Double = (go.getAny<Sprite2D>()?.opacity ?: go.getAny<Label2D>()?.opacity ?: 1f).toDouble()
    fun setAlpha(v: Double) {
        val a = v.coerceIn(0.0, 1.0).toFloat()
        go.getAny<Sprite2D>()?.opacity = a
        go.getAny<Label2D>()?.opacity = a
    }

    fun setTexture(name: String) { go.getAny<Sprite2D>()?.texture = name }
    fun setSpriteSheet(name: String) { go.getAny<AnimatedSprite2D>()?.spriteSheet = name }
    fun getFlipX(): Boolean = go.getAny<Sprite2D>()?.flipX ?: go.getAny<AnimatedSprite2D>()?.flipX ?: false
    fun setFlipX(v: Boolean) {
        go.getAny<Sprite2D>()?.flipX = v
        go.getAny<AnimatedSprite2D>()?.flipX = v
    }

    fun getSize(): Double = (go.getAny<Camera2D>()?.size ?: go.getAny<Sprite2D>()?.sizeX ?: 0f).toDouble()
    fun setSize(v: Double) {
        go.getAny<Camera2D>()?.size = v.toFloat()
        go.getAny<Sprite2D>()?.let { it.sizeX = v.toFloat(); it.sizeY = v.toFloat() }
    }

    fun setFrame(index: Double) { go.getAny<AnimatedSprite2D>()?.gotoFrame(index.toInt()) }
    fun getFrame(): Double = (go.getAny<AnimatedSprite2D>()?.currentFrame ?: 0).toDouble()
    fun playSprite(restart: Boolean = false) { go.getAny<AnimatedSprite2D>()?.play(restart) }
    fun stopSprite() { go.getAny<AnimatedSprite2D>()?.stop() }
    fun playAnimation(name: String = "") {
        val player = go.getAny<AnimationPlayer>() ?: return
        if (name.isNotBlank()) player.animationAsset = name
        player.play(true)
    }
    fun stopAnimation() { go.getAny<AnimationPlayer>()?.stop() }
    fun getAnimationTime(): Double = (go.getAny<AnimationPlayer>()?.state?.time ?: 0f).toDouble()

    // ---- particles --------------------------------------------------------
    fun burst(n: Double) { go.getAny<ParticleEmitter2D>()?.burst(n.toInt()) }
    fun burstFx(n: Double) = burst(n)
    fun setEmitting(v: Boolean) { go.getAny<ParticleEmitter2D>()?.spec?.emitting = v }
    fun setParticlePreset(name: String) {
        val pe = go.getAny<ParticleEmitter2D>() ?: return
        val index = ParticlePresets.NAMES.indexOfFirst { it.equals(name, true) }
        if (index >= 0) pe.applyPreset(index + 1)
    }
    fun particleCount(): Double = (go.getAny<ParticleEmitter2D>()?.particleCount ?: 0).toDouble()

    // ---- audio ------------------------------------------------------------
    fun playSound(clip: String = "", volume: Double = 1.0) {
        val src = go.getAny<AudioSource>()
        val name = clip.ifBlank { src?.clip ?: return }
        if (name.isBlank()) return
        engine.audio.play(
            name, nodeId = go.id, bus = src?.bus ?: "SFX", volume = (src?.volume ?: 1f) * volume.toFloat(),
            pitch = src?.pitch ?: 1f, loop = false, spatial = src?.spatial ?: false,
            x = go.world.tx, y = go.world.ty
        )
    }
    fun stopSound() = engine.audio.stopVoiceOfNode(go.id)

    // ---- tilemap ----------------------------------------------------------
    fun setTile(layer: Int, x: Double, y: Double, tile: Double) {
        go.getAny<TileMap2D>()?.data?.layers?.getOrNull(layer)?.set(x.toInt(), y.toInt(), tile.toInt())
    }
    fun getTile(layer: Int, x: Double, y: Double): Double =
        (go.getAny<TileMap2D>()?.currentTile(layer, x.toInt(), y.toInt()) ?: 0).toDouble()

    // ---- UI ---------------------------------------------------------------
    fun getValue(): Double = (go.getAny<ControlComponent>()?.ui?.value ?: 0f).toDouble()
    fun setValue(v: Double) { go.getAny<ControlComponent>()?.valueChanged(v.toFloat()) }
    fun isChecked(): Boolean = go.getAny<ControlComponent>()?.ui?.checked ?: false
    fun setChecked(v: Boolean) { go.getAny<ControlComponent>()?.ui?.checked = v }
    fun setPlaceholder(v: String) { go.getAny<ControlComponent>()?.ui?.placeholder = v }

    // ---- hierarchy & lifecycle -------------------------------------------
    fun getParent(): Any? = go.parent?.let { sys.toJs(it) }
    fun children(): Any? = sys.newArray(engine.scene.childrenOf(go).map { sys.toJs(it) })
    fun child(name: String): Any? = engine.scene.childrenOf(go).firstOrNull { it.name == name }?.let { sys.toJs(it) }
    fun destroy() { go.destroyed = true }
    fun hasComponent(type: String): Boolean = go.components.any { it.type.equals(type, true) }
    fun component(type: String): Any? = go.components.firstOrNull { it.type.equals(type, true) }
    fun setComponentEnabled(type: String, v: Boolean) {
        go.components.filter { it.type.equals(type, true) }.forEach { it.enabled = v }
    }
    fun send(fn: String, arg: Any?): Any? = sys.sendMessage(go, fn, arg)
    fun send(fn: String): Any? = sys.sendMessage(go, fn, null)
    fun signal(name: String, data: Any? = null) = go.emit(name, data)
    fun connect(signalName: String, target: Any?, method: String) {
        val t = (target as? SObject)?.go ?: return
        go.connect(signalName, t, method)
    }
    fun getMeta(key: String): String = go.meta[key] ?: ""
    fun setMeta(key: String, value: String) { go.meta[key] = value }
    fun addTag(tag: String) { go.groups.add(tag) }
    fun hasTag(tag: String): Boolean = go.groups.contains(tag)
    fun `is`(o: SObject?): Boolean = o != null && o.go === go
    fun freeze() { go.getAny<Rigidbody2D>()?.let { it.bodyType = 3 } }
    override fun toString() = "GameObject(${go.name})"
}

/** Scene-level API: finding, spawning, prefabs, gravity, cameras and scene switching. */
class SScene(private val engine: Engine, private val sys: ScriptSystem) {
    private var cachedSceneName = ""

    fun sync() {
        if (cachedSceneName != engine.scene.name) cachedSceneName = engine.scene.name
    }

    fun getName(): String = engine.scene.name
    fun find(name: String): Any? = engine.scene.find(name)?.let { sys.toJs(it) }
    fun findPath(path: String): Any? = engine.scene.findPath(path)?.let { sys.toJs(it) }
    fun findInGroup(group: String): Any? = sys.newArray(engine.scene.inGroup(group).map { sys.toJs(it) })
    fun findAll(tag: String): Any? =
        sys.newArray(engine.scene.objects.filter { it.tag == tag && !it.destroyed && it.isActiveInHierarchy() }.map { sys.toJs(it) })
    fun count(tag: String): Double =
        engine.scene.objects.count { it.tag == tag && !it.destroyed && it.isActiveInHierarchy() }.toDouble()
    fun nodes(): Double = engine.scene.objects.size.toDouble()

    fun spawn(name: String, x: Double, y: Double): Any? {
        val template = engine.scene.find(name) ?: run {
            engine.log(1, "spawn: template '$name' not found")
            return null
        }
        val copy = engine.scene.instantiate(template, x.toFloat(), y.toFloat())
        copy.active = true
        copy.visible = true
        for (d in listOf(copy) + engine.scene.descendants(copy)) {
            d.components.forEach { it.resetRuntime() }
        }
        engine.scene.updateTransforms()
        sys.attach(copy)
        return sys.toJs(copy)
    }

    fun spawn(name: String): Any? {
        val t = engine.scene.find(name) ?: return null
        return spawn(name, t.world.tx.toDouble(), t.world.ty.toDouble())
    }

    fun instantiate(prefab: SObject?): Any? {
        val src = prefab?.go ?: return null
        val copy = engine.scene.instantiate(src)
        copy.active = true
        engine.scene.updateTransforms()
        sys.attach(copy)
        return sys.toJs(copy)
    }

    fun load(sceneName: String) = engine.requestLoadScene(sceneName)
    fun reload() = engine.requestLoadScene(engine.scene.name)
    fun getCamera(): Any? = engine.mainCamera()?.let { sys.toJs(it) }
    fun getGravityX(): Double = engine.scene.settings.gravityX.toDouble()
    fun setGravityX(v: Double) { engine.scene.settings.gravityX = v.toFloat() }
    fun getGravityY(): Double = engine.scene.settings.gravityY.toDouble()
    fun setGravityY(v: Double) { engine.scene.settings.gravityY = v.toFloat() }
    fun getTimeScale(): Double = 1.0
    fun setBackground(hex: String) { engine.scene.settings.background = Prop.C.parse(hex) }
    fun getNodeCount(): Double = engine.scene.objects.size.toDouble()
    fun setPixelPerfect(v: Boolean) {
        engine.mainCamera()?.get<Camera2D>()?.pixelPerfect = v
    }
}

/** Input API: actions first, plus the raw device state and virtual controls. */
class SInput(private val engine: Engine) {
    @JvmField var axisX = 0.0
    @JvmField var axisY = 0.0
    @JvmField var a = false
    @JvmField var b = false
    @JvmField var aDown = false
    @JvmField var bDown = false
    @JvmField var touching = false
    @JvmField var tapped = false
    @JvmField var touchX = 0.0
    @JvmField var touchY = 0.0
    @JvmField var mouseX = 0.0
    @JvmField var mouseY = 0.0
    @JvmField var scroll = 0.0

    fun sync() {
        val i = engine.input
        axisX = i.moveX.toDouble()
        axisY = i.moveY.toDouble()
        a = i.isPressed("jump")
        b = i.isPressed("attack")
        aDown = i.isJustPressed("jump")
        bDown = i.isJustPressed("attack")
        val d = i.devices
        touching = d.touchActive
        tapped = d.tapPending
        touchX = engine.gameView.screenToWorldX(d.touchX).toDouble()
        touchY = engine.gameView.screenToWorldY(d.touchY).toDouble()
        mouseX = engine.gameView.screenToWorldX(d.mouseX).toDouble()
        mouseY = engine.gameView.screenToWorldY(d.mouseY).toDouble()
        scroll = d.scrollDelta.toDouble()
    }

    fun pressed(action: String): Boolean = engine.input.isPressed(action)
    fun justPressed(action: String): Boolean = engine.input.isJustPressed(action)
    fun released(action: String): Boolean = engine.input.isJustReleased(action)
    fun value(action: String): Double = engine.input.value(action).toDouble()
    fun axis(negative: String, positive: String): Double = engine.input.axis(negative, positive).toDouble()
    fun stickX(): Double = engine.input.moveX.toDouble()
    fun stickY(): Double = engine.input.moveY.toDouble()
    fun vector(negativeX: String, positiveX: String, negativeY: String, positiveY: String): Any? {
        val v = engine.input.vector(negativeX, positiveX, negativeY, positiveY)
        return com.sengine.engine.json.jarr(v.x, v.y)
    }
    fun keyDown(keyCode: Double): Boolean = engine.input.devices.isKeyDown(keyCode.toInt())
    fun actionList(): Any? = sys?.newArray(engine.input.map.actions.keys.toList())
    private val sys: ScriptSystem? get() = null
}

class STime(private val engine: Engine) {
    fun getTime(): Double = engine.time
    fun getFrame(): Double = engine.frame.toDouble()
    fun getFps(): Double = engine.fps.toDouble()
    fun getDelta(): Double = 1.0 / (engine.fps.coerceAtLeast(1f))
}

/** Audio API: playback plus full bus control (the same mixer the Audio panel edits). */
class SAudio(private val engine: Engine) {
    fun play(clip: String) = engine.audio.play(clip, bus = "SFX")
    fun play(clip: String, volume: Double) = engine.audio.play(clip, bus = "SFX", volume = volume.toFloat())
    fun music(clip: String) = engine.audio.play(clip, bus = "Music", loop = true)
    fun music(clip: String, volume: Double) = engine.audio.play(clip, bus = "Music", loop = true, volume = volume.toFloat())
    fun ui(clip: String) = engine.audio.play(clip, bus = "UI")
    fun ambient(clip: String) = engine.audio.play(clip, bus = "Ambient", loop = true)
    fun beep() = engine.audio.play("__beep", bus = "UI")
    fun stopAll() = engine.audio.stopAll()
    fun stopBus(bus: String) = engine.audio.stopBus(bus)
    fun fadeIn(clip: String, seconds: Double) = engine.audio.play(clip, bus = "Music", loop = true, fadeIn = seconds.toFloat())
    fun crossfade(fromHandle: Double, toClip: String, seconds: Double) =
        engine.audio.crossfade(fromHandle.toInt(), toClip, seconds.toFloat())

    fun setVolume(bus: String, volume: Double) { engine.audio.bus(bus).volume = volume.toFloat().coerceIn(0f, 2f) }
    fun getVolume(bus: String): Double = engine.audio.bus(bus).volume.toDouble()
    fun mute(bus: String, muted: Boolean) { engine.audio.bus(bus).muted = muted }
    fun master(volume: Double) { engine.audio.masterVolume = volume.toFloat().coerceIn(0f, 2f) }
    fun addBus(name: String) { engine.audio.addBus(name) }
    fun toDecibels(gain: Double): Double = Db.fromGain(gain.toFloat()).toDouble()
    fun fromDecibels(db: Double): Double = Db.toGain(db.toFloat()).toDouble()
}

/** Runtime UI API: drive controls from gameplay code. */
class SUi(private val engine: Engine, private val sys: ScriptSystem) {
    fun find(name: String): Any? {
        val go = engine.scene.find(name) ?: return null
        return if (go.ui != null) sys.toJs(go) else null
    }
    fun setText(name: String, value: Any?) {
        val go = engine.scene.find(name) ?: return
        val text = Context.toString(value)
        go.getAny<Label2D>()?.text = text
        go.getAny<ControlComponent>()?.ui?.text = text
    }
    fun setValue(name: String, value: Double) {
        engine.scene.find(name)?.getAny<ControlComponent>()?.valueChanged(value.toFloat())
    }
    fun getValue(name: String): Double = (engine.scene.find(name)?.getAny<ControlComponent>()?.ui?.value ?: 0f).toDouble()
    fun setChecked(name: String, value: Boolean) {
        engine.scene.find(name)?.getAny<ControlComponent>()?.ui?.checked = value
    }
    fun isChecked(name: String): Boolean = engine.scene.find(name)?.getAny<ControlComponent>()?.ui?.checked ?: false
    fun show(name: String, visible: Boolean) {
        engine.scene.find(name)?.visible = visible
    }
    fun focus(name: String) {
        val go = engine.scene.find(name) ?: return
        if (go.ui == null) return
        engine.ui.clearFocus(engine.scene)
        engine.ui.focusedFieldId = go.id
    }
    fun designWidth(): Double = engine.ui.designWidth.toDouble()
    fun designHeight(): Double = engine.ui.designHeight.toDouble()
}

/** Physics API: queries that gameplay code needs (raycasts, overlaps, impulses). */
class SPhysics(private val engine: Engine, private val sys: ScriptSystem) {
    fun raycast(x: Double, y: Double, dx: Double, dy: Double, length: Double, mask: Double = 65535.0): Any? {
        val hit = engine.physics.raycast(engine.scene, x.toFloat(), y.toFloat(), dx.toFloat(), dy.toFloat(), length.toFloat(), mask.toInt())
            ?: return null
        val node = hit.node
        val result = com.sengine.engine.json.jobj(
            "hit" to true,
            "node" to (node?.let { sys.toJs(it) } ?: org.mozilla.javascript.Undefined.instance),
            "x" to hit.x, "y" to hit.y,
            "nx" to hit.nx, "ny" to hit.ny,
            "distance" to hit.distance
        )
        engine.physics.recycle(hit)
        return result
    }

    fun overlapPoint(x: Double, y: Double): Any? =
        engine.physics.overlapPoint(engine.scene, x.toFloat(), y.toFloat())?.let { sys.toJs(it) }

    fun overlapCircle(x: Double, y: Double, r: Double): Any? =
        sys.newArray(engine.physics.overlapCircle(engine.scene, x.toFloat(), y.toFloat(), r.toFloat()).map { sys.toJs(it) })

    fun overlapRect(x: Double, y: Double, w: Double, h: Double): Any? =
        sys.newArray(engine.physics.overlapRect(engine.scene, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()).map { sys.toJs(it) })

    fun explode(x: Double, y: Double, radius: Double, strength: Double) =
        engine.physics.applyRadialImpulse(engine.scene, x.toFloat(), y.toFloat(), radius.toFloat(), strength.toFloat())

    fun bodyCount(): Double = engine.physics.bodyCount.toDouble()
    fun contacts(): Double = engine.physics.contactCount.toDouble()
    fun setGravity(x: Double, y: Double) {
        engine.scene.settings.gravityX = x.toFloat()
        engine.scene.settings.gravityY = y.toFloat()
    }
    fun setTimeScale(scale: Double) { engine.physics.gravityScale = scale.toFloat() }
}

/** Resource API: create and load project resources at runtime. */
class SResources(private val engine: Engine, private val sys: ScriptSystem) {
    fun animation(name: String): Any? {
        val a = engine.resources.animation(name) ?: return null
        return com.sengine.engine.json.jarr(name, a.length, a.trackNames())
    }
    fun animationNames(): Any? = sys.newArray(engine.project.listAssets(com.sengine.engine.core.AssetKind.ANIMATION))
    fun textureNames(): Any? = sys.newArray(engine.project.listAssets(com.sengine.engine.core.AssetKind.TEXTURE))
    fun soundNames(): Any? = sys.newArray(engine.project.listAssets(com.sengine.engine.core.AssetKind.SOUND))
    fun exists(name: String): Boolean = engine.project.assetExists(name)
    fun size(name: String): Double = engine.project.assetSize(name).toDouble()
    fun listAssets(kind: String): Any? {
        val k = runCatching { com.sengine.engine.core.AssetKind.valueOf(kind.uppercase()) }.getOrNull()
        return sys.newArray(engine.project.listAssets(k))
    }
}

/** Small persistent key/value store backed by the project settings (save games, options). */
class SStore(private val engine: Engine) {
    fun set(key: String, value: Any?) {
        engine.project.settings.custom["store.$key"] = Context.toString(value)
        engine.project.saveMeta()
    }
    fun get(key: String, fallback: Any? = null): Any? {
        val v = engine.project.settings.custom["store.$key"] ?: return fallback
        return v.toDoubleOrNull() ?: v
    }
    fun getString(key: String, fallback: String = ""): String = engine.project.settings.custom["store.$key"] ?: fallback
    fun getNumber(key: String, fallback: Double = 0.0): Double =
        engine.project.settings.custom["store.$key"]?.toDoubleOrNull() ?: fallback
    fun getBool(key: String, fallback: Boolean = false): Boolean =
        engine.project.settings.custom["store.$key"]?.toBooleanStrictOrNull() ?: fallback
    fun remove(key: String) {
        engine.project.settings.custom.remove("store.$key")
        engine.project.saveMeta()
    }
    fun clear() {
        engine.project.settings.custom.keys.filter { it.startsWith("store.") }.forEach { engine.project.settings.custom.remove(it) }
        engine.project.saveMeta()
    }
}

class SConsole(private val engine: Engine) {
    fun log(o: Any?) = engine.log(0, Context.toString(o))
    fun warn(o: Any?) = engine.log(1, Context.toString(o))
    fun error(o: Any?) = engine.log(2, Context.toString(o))
    fun clear() = com.sengine.engine.debug.Log.clear()
}
