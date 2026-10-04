package com.sengine.engine.core

enum class AssetKind(val extensions: List<String>) {
    TEXTURE(listOf("png", "jpg", "jpeg", "webp", "bmp")),
    SCRIPT(listOf("js")),
    SOUND(listOf("wav", "ogg", "mp3", "m4a"));

    companion object {
        fun of(fileName: String): AssetKind? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return values().firstOrNull { ext in it.extensions }
        }
    }
}

/** Editable / serializable property descriptor used by the Inspector and by the serializer. */
sealed class Prop(val name: String) {
    class F(name: String, val get: () -> Float, val set: (Float) -> Unit, val step: Float = 0.1f) : Prop(name)
    class I(name: String, val get: () -> Int, val set: (Int) -> Unit) : Prop(name)
    class B(name: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Prop(name)
    class S(name: String, val get: () -> String, val set: (String) -> Unit, val multiline: Boolean = false) : Prop(name)
    class Color(name: String, val get: () -> Int, val set: (Int) -> Unit) : Prop(name)
    class Choice(name: String, val options: List<String>, val get: () -> Int, val set: (Int) -> Unit) : Prop(name)
    class Asset(name: String, val kind: AssetKind, val get: () -> String, val set: (String) -> Unit) : Prop(name)
}
