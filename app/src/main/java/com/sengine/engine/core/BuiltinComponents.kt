package com.sengine.engine.core

import com.sengine.engine.json.JVal
import com.sengine.engine.ui.ControlComponent

/**
 * Component from a newer engine version, or from a plugin that is not installed.
 *
 * Its JSON payload is preserved verbatim so saving a scene never silently drops data.
 */
class UnknownComponent(override val type: String, private var raw: JVal.Obj = JVal.Obj()) : Component() {
    override val category = "Unknown"
    override val description = "Preserved component (unknown type `$type`)"

    override fun props(): List<Prop> = listOf(
        Prop.Info("Payload", { com.sengine.engine.json.Json.write(raw, false).take(200) })
    )

    override fun toJson(): JVal.Obj {
        val o = raw.deepCopy() as JVal.Obj
        o.put("type", type)
        o.put("enabled", enabled)
        return o
    }

    override fun copy(): Component = UnknownComponent(type, raw.deepCopy() as JVal.Obj)

    fun payload(): JVal.Obj = raw
}

/** Registers every built-in component. Called once when [ComponentRegistry] is first touched. */
object BuiltinComponents {
    fun registerAll() {
        // ---- rendering -------------------------------------------------------
        ComponentRegistry.register(Sprite2D.TYPE, "Rendering", "Draws a texture region or a 2D shape") { Sprite2D() }
        ComponentRegistry.register(AnimatedSprite2D.TYPE, "Rendering", "Flip-book sprite animation") { AnimatedSprite2D() }
        ComponentRegistry.register(Label2D.TYPE, "Rendering", "World-space text") { Label2D() }
        ComponentRegistry.register(Camera2D.TYPE, "Rendering", "2D camera with follow, limits and pixel-perfect mode") { Camera2D() }
        ComponentRegistry.register(TileMap2D.TYPE, "Rendering", "Tile layers painted from a TileSet") { TileMap2D() }
        ComponentRegistry.register(ParticleEmitter2D.TYPE, "Rendering", "Particle emitter with presets") { ParticleEmitter2D() }

        // ---- physics ---------------------------------------------------------
        ComponentRegistry.register(Rigidbody2D.TYPE, "Physics", "Physics body") { Rigidbody2D() }
        ComponentRegistry.register(Collider2D.TYPE, "Physics", "Box / circle / polygon collider") { Collider2D() }
        ComponentRegistry.register(Area2D.TYPE, "Physics", "Trigger volume with enter/exit signals") { Area2D() }
        ComponentRegistry.register(RayCast2D.TYPE, "Physics", "Ray query with hit reporting") { RayCast2D() }

        // ---- animation -------------------------------------------------------
        ComponentRegistry.register(AnimationPlayer.TYPE, "Animation", "Plays animation assets on this node") { AnimationPlayer() }

        // ---- audio -----------------------------------------------------------
        ComponentRegistry.register(AudioSource.TYPE, "Audio", "Sound emitter") { AudioSource() }

        // ---- gameplay / logic ------------------------------------------------
        ComponentRegistry.register(TimerComponent.TYPE, "Gameplay", "Timer emitting the timeout signal") { TimerComponent() }
        ComponentRegistry.register(SignalEmitter.TYPE, "Logic", "Emits signals on start or trigger") { SignalEmitter() }

        // ---- scripting -------------------------------------------------------
        ComponentRegistry.register(ScriptComponent.TYPE, "Scripting", "JavaScript behaviour") { ScriptComponent() }

        // ---- UI --------------------------------------------------------------
        ComponentRegistry.register(ControlComponent.TYPE, "UI", "UI control: label, button, panel, slider, …") { ControlComponent() }
    }
}
