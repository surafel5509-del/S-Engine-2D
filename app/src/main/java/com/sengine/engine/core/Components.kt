package com.sengine.engine.core

object ComponentRegistry {
    val types: LinkedHashMap<String, () -> Component> = linkedMapOf(
        "SpriteRenderer" to { SpriteRenderer() },
        "TextRenderer" to { TextRenderer() },
        "Camera" to { Camera2D() },
        "Rigidbody2D" to { Rigidbody2D() },
        "Collider2D" to { Collider2D() },
        "Script" to { ScriptComponent() },
        "ParticleEmitter" to { ParticleEmitter() },
        "AudioSource" to { AudioSource() },
    )

    fun create(type: String): Component? = types[type]?.invoke()
}

class SpriteRenderer : Component() {
    override val type = "SpriteRenderer"
    var shape = 0 // 0 Square, 1 Circle, 2 Triangle
    var color = 0xFFFFFFFF.toInt()
    var texture = ""
    var flipX = false
    var flipY = false

    override fun props() = listOf(
        Prop.Choice("Shape", SHAPES, { shape }, { shape = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.B("Flip X", { flipX }, { flipX = it }),
        Prop.B("Flip Y", { flipY }, { flipY = it }),
    )

    companion object {
        val SHAPES = listOf("Square", "Circle", "Triangle")
    }
}

class TextRenderer : Component() {
    override val type = "TextRenderer"
    var text = "Hello S Engine"
    var size = 0.5f
    var color = 0xFFFFFFFF.toInt()
    var align = 1 // 0 left, 1 center, 2 right
    var bold = false

    override fun props() = listOf(
        Prop.S("Text", { text }, { text = it }, multiline = true),
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.01f) }, 0.05f),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Choice("Align", listOf("Left", "Center", "Right"), { align }, { align = it }),
        Prop.B("Bold", { bold }, { bold = it }),
    )
}

class Camera2D : Component() {
    override val type = "Camera"
    var size = 5f
    var background = 0xFF1B2533.toInt()
    var follow = ""
    var smoothing = 5f

    override fun props() = listOf(
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.1f) }),
        Prop.Color("Background", { background }, { background = it }),
        Prop.S("Follow Target", { follow }, { follow = it }),
        Prop.F("Follow Smoothing", { smoothing }, { smoothing = it.coerceAtLeast(0f) }),
    )
}

class Rigidbody2D : Component() {
    override val type = "Rigidbody2D"
    var bodyType = 0 // 0 Dynamic, 1 Kinematic, 2 Static
    var mass = 1f
    var gravityScale = 1f
    var drag = 0f
    var bounciness = 0f
    var friction = 0.4f
    var startVx = 0f
    var startVy = 0f

    // runtime
    var vx = 0f
    var vy = 0f
    var grounded = false

    override fun props() = listOf(
        Prop.Choice("Body Type", listOf("Dynamic", "Kinematic", "Static"), { bodyType }, { bodyType = it }),
        Prop.F("Mass", { mass }, { mass = it.coerceAtLeast(0.001f) }),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it }),
        Prop.F("Linear Drag", { drag }, { drag = it.coerceAtLeast(0f) }),
        Prop.F("Bounciness", { bounciness }, { bounciness = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Friction", { friction }, { friction = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Velocity X", { startVx }, { startVx = it }),
        Prop.F("Velocity Y", { startVy }, { startVy = it }),
    )

    override fun resetRuntime() {
        vx = startVx; vy = startVy; grounded = false
    }
}

class Collider2D : Component() {
    override val type = "Collider2D"
    var shape = 0 // 0 Box, 1 Circle
    var width = 1f
    var height = 1f
    var radius = 0.5f
    var offsetX = 0f
    var offsetY = 0f
    var isTrigger = false

    override fun props() = listOf(
        Prop.Choice("Shape", listOf("Box", "Circle"), { shape }, { shape = it }),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.01f) }),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.01f) }),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.01f) }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }),
        Prop.B("Is Trigger", { isTrigger }, { isTrigger = it }),
    )
}

class ScriptComponent : Component() {
    override val type = "Script"
    var script = ""
    var params = ""

    override fun props() = listOf(
        Prop.Asset("Script", AssetKind.SCRIPT, { script }, { script = it }),
        Prop.S("Params", { params }, { params = it }),
    )
}

class ParticleEmitter : Component() {
    override val type = "ParticleEmitter"
    var emitting = true
    var rate = 30f
    var lifetime = 1.2f
    var speed = 3f
    var direction = 90f
    var spread = 30f
    var startSize = 0.25f
    var endSize = 0.02f
    var startColor = 0xFFFFC940.toInt()
    var endColor = 0x00FF3D00
    var gravity = 0f
    var maxParticles = 300

    // runtime
    val particles = ArrayList<Particle>()
    var accumulator = 0f
    var pendingBurst = 0

    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var age: Float, var life: Float)

    override fun props() = listOf(
        Prop.B("Emitting", { emitting }, { emitting = it }),
        Prop.F("Rate", { rate }, { rate = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Lifetime", { lifetime }, { lifetime = it.coerceAtLeast(0.01f) }),
        Prop.F("Speed", { speed }, { speed = it }),
        Prop.F("Direction", { direction }, { direction = it }, 1f),
        Prop.F("Spread", { spread }, { spread = it.coerceIn(0f, 360f) }, 1f),
        Prop.F("Start Size", { startSize }, { startSize = it.coerceAtLeast(0f) }, 0.01f),
        Prop.F("End Size", { endSize }, { endSize = it.coerceAtLeast(0f) }, 0.01f),
        Prop.Color("Start Color", { startColor }, { startColor = it }),
        Prop.Color("End Color", { endColor }, { endColor = it }),
        Prop.F("Gravity", { gravity }, { gravity = it }),
        Prop.I("Max Particles", { maxParticles }, { maxParticles = it.coerceIn(1, 5000) }),
    )

    override fun resetRuntime() {
        particles.clear(); accumulator = 0f; pendingBurst = 0
    }
}

class AudioSource : Component() {
    override val type = "AudioSource"
    var clip = ""
    var playOnStart = true
    var loop = false
    var volume = 1f

    override fun props() = listOf(
        Prop.Asset("Clip", AssetKind.SOUND, { clip }, { clip = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.F("Volume", { volume }, { volume = it.coerceIn(0f, 1f) }, 0.05f),
    )
}
