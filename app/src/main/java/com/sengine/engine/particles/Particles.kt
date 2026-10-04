package com.sengine.engine.particles

import com.sengine.engine.animation.SpriteFrames
import com.sengine.engine.json.JVal
import com.sengine.engine.json.jarr
import com.sengine.engine.json.jobj
import com.sengine.engine.math.Easing
import com.sengine.engine.math.M
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full configuration of a 2D particle emitter. Serializable, editable in the Particle Editor and
 * usable from scripts. All random ranges are seeded so a preset looks the same every run.
 */
class ParticleSpec {
    var emitting = true
    var rate = 40f
    var burst = 0
    var maxParticles = 400

    var lifetimeMin = 0.8f
    var lifetimeMax = 1.4f

    var speedMin = 2f
    var speedMax = 4f

    /** Degrees; 90 = up. */
    var direction = 90f
    var spread = 35f

    var gravityX = 0f
    var gravityY = -3f
    var radialAccel = 0f
    var tangentialAccel = 0f
    var drag = 0.6f

    var startSize = 0.3f
    var endSize = 0.0f
    var sizeEasing = 1

    var startColor = 0xFFFFC940.toInt()
    var endColor = 0x00FF3D00
    var colorEasing = 1

    var startRotation = 0f
    var rotationSpeed = 0f
    var rotateWithVelocity = false

    /** 0 = point, 1 = circle, 2 = box, 3 = edge */
    var emitShape = 0
    var emitShapeSize = 0f

    var texture = ""
    var spriteSheet = false
    var frames = SpriteFrames(1, 1, 8f)
    var blend = 0            // 0 alpha, 1 additive
    var localSpace = false
    var seed = 12345
    var sortByAge = false

    fun copy(): ParticleSpec = ParticleSpec().also { s ->
        s.emitting = emitting; s.rate = rate; s.burst = burst; s.maxParticles = maxParticles
        s.lifetimeMin = lifetimeMin; s.lifetimeMax = lifetimeMax
        s.speedMin = speedMin; s.speedMax = speedMax
        s.direction = direction; s.spread = spread
        s.gravityX = gravityX; s.gravityY = gravityY
        s.radialAccel = radialAccel; s.tangentialAccel = tangentialAccel; s.drag = drag
        s.startSize = startSize; s.endSize = endSize; s.sizeEasing = sizeEasing
        s.startColor = startColor; s.endColor = endColor; s.colorEasing = colorEasing
        s.startRotation = startRotation; s.rotationSpeed = rotationSpeed; s.rotateWithVelocity = rotateWithVelocity
        s.emitShape = emitShape; s.emitShapeSize = emitShapeSize
        s.texture = texture; s.spriteSheet = spriteSheet; s.frames = frames.copy()
        s.blend = blend; s.localSpace = localSpace; s.seed = seed; s.sortByAge = sortByAge
    }

    fun toJson(): JVal.Obj = jobj(
        "emitting" to emitting, "rate" to rate, "burst" to burst, "maxParticles" to maxParticles,
        "lifetime" to jarr(lifetimeMin, lifetimeMax),
        "speed" to jarr(speedMin, speedMax),
        "direction" to direction, "spread" to spread,
        "gravity" to jarr(gravityX, gravityY),
        "radial" to radialAccel, "tangential" to tangentialAccel, "drag" to drag,
        "startSize" to startSize, "endSize" to endSize, "sizeEasing" to sizeEasing,
        "startColor" to com.sengine.engine.core.Prop.C.format(startColor),
        "endColor" to com.sengine.engine.core.Prop.C.format(endColor),
        "colorEasing" to colorEasing,
        "rotation" to startRotation, "rotationSpeed" to rotationSpeed, "rotateWithVelocity" to rotateWithVelocity,
        "emitShape" to emitShape, "emitShapeSize" to emitShapeSize,
        "texture" to texture, "spriteSheet" to spriteSheet, "blend" to blend,
        "localSpace" to localSpace, "seed" to seed, "sortByAge" to sortByAge,
        "frames" to frames.toJson()
    )

    fun fromJson(o: JVal.Obj) {
        emitting = o.bool("emitting", true)
        rate = o.f("rate", 40f)
        burst = o.i("burst")
        maxParticles = o.i("maxParticles", 400).coerceIn(1, 20000)
        val life = o.floats("lifetime")
        if (life.size >= 2) { lifetimeMin = life[0]; lifetimeMax = life[1] }
        val speed = o.floats("speed")
        if (speed.size >= 2) { speedMin = speed[0]; speedMax = speed[1] }
        direction = o.f("direction", 90f)
        spread = o.f("spread", 35f)
        val g = o.floats("gravity")
        if (g.size >= 2) { gravityX = g[0]; gravityY = g[1] }
        radialAccel = o.f("radial")
        tangentialAccel = o.f("tangential")
        drag = o.f("drag", 0.6f)
        startSize = o.f("startSize", 0.3f)
        endSize = o.f("endSize")
        sizeEasing = o.i("sizeEasing", 1)
        startColor = com.sengine.engine.core.Prop.C.parse(o.str("startColor", "#FFFFC940"))
        endColor = com.sengine.engine.core.Prop.C.parse(o.str("endColor", "#00FF3D00"))
        colorEasing = o.i("colorEasing", 1)
        startRotation = o.f("rotation")
        rotationSpeed = o.f("rotationSpeed")
        rotateWithVelocity = o.bool("rotateWithVelocity")
        emitShape = o.i("emitShape")
        emitShapeSize = o.f("emitShapeSize")
        texture = o.str("texture")
        spriteSheet = o.bool("spriteSheet")
        blend = o.i("blend")
        localSpace = o.bool("localSpace")
        seed = o.i("seed", 12345)
        sortByAge = o.bool("sortByAge")
        (o["frames"] as? JVal.Obj)?.let { frames.fromJson(it) }
    }

    companion object {
        const val FORMAT = "sengine.particles"
        val SHAPES = listOf("Point", "Circle", "Box", "Edge")
        val BLENDS = listOf("Alpha", "Additive")

        fun fromJson(text: String) = ParticleSpec().also { it.fromJson(com.sengine.engine.json.Json.parseObject(text)) }
    }
}

/** A live particle. Pooled — never allocated per frame. */
class Particle {
    var x = 0f; var y = 0f
    var vx = 0f; var vy = 0f
    var age = 0f; var life = 1f
    var size = 1f
    var rotation = 0f
    var rotationSpeed = 0f
    var color = 0xFFFFFFFF.toInt()
    var startSize = 1f
    var endSize = 0f
    var startColor = 0xFFFFFFFF.toInt()
    var endColor = 0
    var sizeEasing = 0
    var colorEasing = 0
    var seedX = 0f
    var seedY = 0f
}

/**
 * Deterministic, allocation-free particle simulation. Particles live in a preallocated pool and die
 * by swapping with the tail, so `update` performs no garbage generation at all.
 */
class ParticleSystem {
    private val pool = ArrayList<Particle>(512)
    private var active = 0
    private var accumulator = 0f
    private var rng = 0
    var pendingBurst = 0
    var emittedTotal = 0L

    val particles: List<Particle> get() = pool.subList(0, active)
    val count: Int get() = active

    private fun nextFloat(): Float {
        // xorshift32 — deterministic, fast, seedable
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng = x
        return (x and 0x7FFFFFFF) / 0x7FFFFFFF.toFloat()
    }

    private fun create(): Particle {
        if (pool.size <= active) pool.add(Particle())
        return pool[active++]
    }

    fun clear() {
        active = 0
        accumulator = 0f
        pendingBurst = 0
        emittedTotal = 0
    }

    fun burst(n: Int) {
        pendingBurst += n
    }

    /** Emit one particle at the emitter origin, in world units. [rotDeg] rotates the emission cone. */
    private fun spawn(spec: ParticleSpec, ox: Float, oy: Float, rotDeg: Float, scale: Float, rot: Float) {
        if (active >= spec.maxParticles) return
        val p = create()
        rng = if (rng == 0) spec.seed else rng
        var sx = ox
        var sy = oy
        when (spec.emitShape) {
            1 -> {
                val a = nextFloat() * 6.2831855f
                val r = spec.emitShapeSize * kotlin.math.sqrt(nextFloat())
                sx += cos(a) * r; sy += sin(a) * r
            }
            2 -> {
                sx += (nextFloat() - 0.5f) * spec.emitShapeSize * 2f
                sy += (nextFloat() - 0.5f) * spec.emitShapeSize * 2f
            }
            3 -> {
                sx += (nextFloat() - 0.5f) * spec.emitShapeSize * 2f
            }
        }
        p.x = sx; p.y = sy
        val angle = Math.toRadians((spec.direction + rotDeg + (nextFloat() - 0.5f) * spec.spread).toDouble())
        val speed = spec.speedMin + nextFloat() * (spec.speedMax - spec.speedMin)
        p.vx = (cos(angle) * speed).toFloat()
        p.vy = (sin(angle) * speed).toFloat()
        p.age = 0f
        p.life = (spec.lifetimeMin + nextFloat() * (spec.lifetimeMax - spec.lifetimeMin)).coerceAtLeast(0.01f)
        p.startSize = spec.startSize * scale * 0.5f
        p.endSize = spec.endSize * scale * 0.5f
        p.size = p.startSize
        p.startColor = spec.startColor
        p.endColor = spec.endColor
        p.sizeEasing = spec.sizeEasing
        p.colorEasing = spec.colorEasing
        p.rotation = spec.startRotation + if (spec.rotateWithVelocity) 0f else rot
        p.rotationSpeed = spec.rotationSpeed
        p.color = spec.startColor
        p.seedX = nextFloat()
        p.seedY = nextFloat()
        emittedTotal++
    }

    /**
     * Advance the simulation.
     * @param ox/oy emitter world position, [rotDeg] emitter world rotation, [scale] emitter scale.
     */
    fun update(spec: ParticleSpec, dt: Float, ox: Float, oy: Float, rotDeg: Float, scale: Float, emitting: Boolean) {
        if (spec.seed != 0 && rng == 0) rng = spec.seed
        if (emitting && spec.rate > 0f) {
            accumulator += spec.rate * dt
            var toEmit = accumulator.toInt()
            accumulator -= toEmit
            if (pendingBurst > 0) {
                toEmit += pendingBurst
                pendingBurst = 0
            }
            var guard = 0
            while (toEmit-- > 0 && guard++ < spec.maxParticles) spawn(spec, ox, oy, rotDeg, scale, rotDeg)
        } else {
            pendingBurst = 0
        }

        val drag = spec.drag
        var i = 0
        while (i < active) {
            val p = pool[i]
            p.age += dt
            if (p.age >= p.life) {
                active--
                val last = pool[active]
                pool[active] = p
                pool[i] = last
                continue
            }
            val t = p.age / p.life
            if (drag > 0f) {
                val k = (1f - drag * dt).coerceAtLeast(0f)
                p.vx *= k; p.vy *= k
            }
            if (spec.radialAccel != 0f || spec.tangentialAccel != 0f) {
                var rx = p.x - ox
                var ry = p.y - oy
                val len = kotlin.math.sqrt(rx * rx + ry * ry)
                if (len > 1e-4f) { rx /= len; ry /= len } else { rx = 0f; ry = 0f }
                p.vx += (rx * spec.radialAccel - ry * spec.tangentialAccel) * dt
                p.vy += (ry * spec.radialAccel + rx * spec.tangentialAccel) * dt
            }
            p.vx += spec.gravityX * dt
            p.vy += spec.gravityY * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rotation += p.rotationSpeed * dt
            val se = Easing.apply(p.sizeEasing, t)
            p.size = p.startSize + (p.endSize - p.startSize) * se
            p.color = lerpColor(p.startColor, p.endColor, Easing.apply(p.colorEasing, t))
            i++
        }
        if (spec.sortByAge) sortActive()
    }

    private fun sortActive() {
        val list = pool.subList(0, active)
        list.sortBy { it.age }
    }

    companion object {
        fun lerpColor(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val ca = (a shr shift) and 0xFF
                val cb = (b shr shift) and 0xFF
                return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Ready-made looks for the Particle Editor: every preset writes a complete [ParticleSpec]. */
object ParticlePresets {
    val NAMES = listOf("Fire", "Smoke", "Dust", "Rain", "Snow", "Sparks", "Magic", "Explosion")

    fun apply(preset: String, spec: ParticleSpec) {
        when (preset.lowercase()) {
            "fire" -> {
                spec.rate = 60f; spec.lifetimeMin = 0.5f; spec.lifetimeMax = 0.9f
                spec.speedMin = 1.2f; spec.speedMax = 2.4f
                spec.direction = 90f; spec.spread = 22f
                spec.gravityX = 0f; spec.gravityY = 2.4f
                spec.drag = 1.6f
                spec.startSize = 0.42f; spec.endSize = 0.02f; spec.sizeEasing = 1
                spec.startColor = 0xFFFFE066.toInt(); spec.endColor = 0x66FF4400
                spec.colorEasing = 1
                spec.blend = 1; spec.maxParticles = 220
                spec.emitShape = 1; spec.emitShapeSize = 0.12f
                spec.texture = ""
            }
            "smoke" -> {
                spec.rate = 18f; spec.lifetimeMin = 1.6f; spec.lifetimeMax = 2.6f
                spec.speedMin = 0.4f; spec.speedMax = 1.1f
                spec.direction = 90f; spec.spread = 40f
                spec.gravityY = 0.6f; spec.drag = 0.9f
                spec.startSize = 0.6f; spec.endSize = 2.4f; spec.sizeEasing = 1
                spec.startColor = 0x66808080; spec.endColor = 0x0A404040
                spec.blend = 0; spec.maxParticles = 120
                spec.rotationSpeed = 20f; spec.emitShape = 1; spec.emitShapeSize = 0.2f
            }
            "dust" -> {
                spec.rate = 10f; spec.lifetimeMin = 0.7f; spec.lifetimeMax = 1.4f
                spec.speedMin = 0.5f; spec.speedMax = 1.6f
                spec.direction = 180f; spec.spread = 60f
                spec.gravityY = -1.2f; spec.drag = 2.2f
                spec.startSize = 0.22f; spec.endSize = 0.05f
                spec.startColor = 0x66C2B280; spec.endColor = 0x00C2B280
                spec.blend = 0; spec.maxParticles = 90
            }
            "rain" -> {
                spec.rate = 160f; spec.lifetimeMin = 0.8f; spec.lifetimeMax = 1.2f
                spec.speedMin = 9f; spec.speedMax = 13f
                spec.direction = 265f; spec.spread = 4f
                spec.gravityY = -14f; spec.drag = 0.1f
                spec.startSize = 0.08f; spec.endSize = 0.05f
                spec.startColor = 0x888FD8FF.toInt(); spec.endColor = 0x228FD8FF
                spec.blend = 0; spec.maxParticles = 700
                spec.emitShape = 3; spec.emitShapeSize = 9f
                spec.sortByAge = true
            }
            "snow" -> {
                spec.rate = 45f; spec.lifetimeMin = 3f; spec.lifetimeMax = 6f
                spec.speedMin = 0.4f; spec.speedMax = 1f
                spec.direction = 270f; spec.spread = 30f
                spec.gravityY = -0.6f; spec.drag = 0.4f
                spec.tangentialAccel = 0.8f
                spec.startSize = 0.12f; spec.endSize = 0.1f
                spec.startColor = 0xFFFFFFFF.toInt(); spec.endColor = 0x88FFFFFF.toInt()
                spec.maxParticles = 400
                spec.emitShape = 3; spec.emitShapeSize = 9f
            }
            "sparks" -> {
                spec.rate = 26f; spec.lifetimeMin = 0.25f; spec.lifetimeMax = 0.6f
                spec.speedMin = 4f; spec.speedMax = 8f
                spec.direction = 90f; spec.spread = 360f
                spec.gravityY = -9f; spec.drag = 0.8f
                spec.startSize = 0.12f; spec.endSize = 0f
                spec.startColor = 0xFFFFF6A0.toInt(); spec.endColor = 0x00FF8A00
                spec.blend = 1; spec.maxParticles = 260
                spec.emitShape = 1; spec.emitShapeSize = 0.1f
            }
            "magic" -> {
                spec.rate = 40f; spec.lifetimeMin = 0.9f; spec.lifetimeMax = 1.8f
                spec.speedMin = 0.6f; spec.speedMax = 1.8f
                spec.direction = 90f; spec.spread = 120f
                spec.gravityY = 0.4f; spec.drag = 1.4f
                spec.radialAccel = 1.6f; spec.tangentialAccel = 2.2f
                spec.startSize = 0.2f; spec.endSize = 0.02f
                spec.startColor = 0xFFB388FF.toInt(); spec.endColor = 0x0034B6FF
                spec.blend = 1; spec.maxParticles = 300
                spec.emitShape = 1; spec.emitShapeSize = 0.3f
                spec.rotationSpeed = 180f
            }
            "explosion" -> {
                spec.rate = 0f; spec.burst = 120
                spec.lifetimeMin = 0.4f; spec.lifetimeMax = 1.1f
                spec.speedMin = 3f; spec.speedMax = 9f
                spec.direction = 90f; spec.spread = 360f
                spec.gravityY = -2.5f; spec.drag = 2.6f
                spec.startSize = 0.5f; spec.endSize = 0f
                spec.startColor = 0xFFFFF1A8.toInt(); spec.endColor = 0x00FF3B00
                spec.blend = 1; spec.maxParticles = 400
                spec.emitShape = 1; spec.emitShapeSize = 0.15f
            }
            else -> M.clamp(0f, 0f, 0f)
        }
    }
}
