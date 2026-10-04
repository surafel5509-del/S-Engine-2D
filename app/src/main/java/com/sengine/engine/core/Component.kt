package com.sengine.engine.core

import org.json.JSONObject

abstract class Component {
    lateinit var gameObject: GameObject
    abstract val type: String
    var enabled = true

    /** Properties shown in the inspector and saved to disk. */
    abstract fun props(): List<Prop>

    /** Reset transient runtime state (called when play mode starts). */
    open fun resetRuntime() {}

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        o.put("enabled", enabled)
        for (p in props()) {
            when (p) {
                is Prop.F -> o.put(p.name, p.get().toDouble())
                is Prop.I -> o.put(p.name, p.get())
                is Prop.B -> o.put(p.name, p.get())
                is Prop.S -> o.put(p.name, p.get())
                is Prop.Color -> o.put(p.name, String.format("#%08X", p.get()))
                is Prop.Choice -> o.put(p.name, p.options.getOrElse(p.get()) { p.options[0] })
                is Prop.Asset -> o.put(p.name, p.get())
            }
        }
        return o
    }

    fun fromJson(o: JSONObject) {
        enabled = o.optBoolean("enabled", true)
        for (p in props()) {
            if (!o.has(p.name)) continue
            try {
                when (p) {
                    is Prop.F -> p.set(o.getDouble(p.name).toFloat())
                    is Prop.I -> p.set(o.getInt(p.name))
                    is Prop.B -> p.set(o.getBoolean(p.name))
                    is Prop.S -> p.set(o.getString(p.name))
                    is Prop.Color -> p.set(parseColor(o.getString(p.name)))
                    is Prop.Choice -> {
                        val idx = p.options.indexOf(o.getString(p.name))
                        if (idx >= 0) p.set(idx)
                    }
                    is Prop.Asset -> p.set(o.getString(p.name))
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun parseColor(s: String): Int {
            var h = s.trim().removePrefix("#")
            if (h.length == 6) h = "FF$h"
            return h.toLong(16).toInt()
        }
    }
}
