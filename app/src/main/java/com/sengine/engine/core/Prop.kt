package com.sengine.engine.core

import com.sengine.engine.json.JVal
import com.sengine.engine.json.jarr
import com.sengine.engine.json.jobj
import com.sengine.engine.math.Rect2
import com.sengine.engine.math.Vec2

/**
 * Editable, serializable and inspector-friendly property descriptor.
 *
 * Every component publishes its state through [Prop]s. That single declaration powers:
 *  * the Inspector (widget choice, label, tooltip, section, numeric constraints)
 *  * serialization / migration (stable [name] keys, [encode]/[decode])
 *  * undo snapshots (before/after values)
 *  * multi-object editing (compare + write across a selection)
 */
sealed class Prop(
    val name: String,
    val label: String = name,
    val tooltip: String = "",
    val section: String = "Properties",
    val order: Int = 0
) {
    abstract fun encode(): JVal
    abstract fun decode(v: JVal)
    open val typeName: String get() = "string"

    /** Copy the value from another equivalent property (multi-object editing). */
    open fun copyFrom(other: Prop) {
        if (other::class == this::class) decode(other.encode())
    }

    class F(
        name: String,
        val get: () -> Float,
        val set: (Float) -> Unit,
        val step: Float = 0.1f,
        val min: Float = -Float.MAX_VALUE,
        val max: Float = Float.MAX_VALUE,
        val suffix: String = "",
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Num(get().toDouble())
        override fun decode(v: JVal) {
            val raw = (v as? JVal.Num)?.v?.toFloat() ?: v.let { (it as? JVal.Str)?.v?.toFloatOrNull() } ?: return
            set(raw.coerceIn(min, max))
        }

        override val typeName get() = "float"
        fun clamp(value: Float) = value.coerceIn(min, max)
    }

    class I(
        name: String,
        val get: () -> Int,
        val set: (Int) -> Unit,
        val min: Int = Int.MIN_VALUE,
        val max: Int = Int.MAX_VALUE,
        val step: Int = 1,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Num(get().toDouble())
        override fun decode(v: JVal) {
            val raw = (v as? JVal.Num)?.v?.toInt() ?: (v as? JVal.Str)?.v?.toIntOrNull() ?: return
            set(raw.coerceIn(min, max))
        }

        override val typeName get() = "int"
    }

    class B(
        name: String,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Bool(get())
        override fun decode(v: JVal) {
            when (v) {
                is JVal.Bool -> set(v.v)
                is JVal.Num -> set(v.v != 0.0)
                is JVal.Str -> set(v.v.equals("true", true) || v.v == "1")
                else -> {}
            }
        }

        override val typeName get() = "bool"
    }

    class S(
        name: String,
        val get: () -> String,
        val set: (String) -> Unit,
        val multiline: Boolean = false,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Str(get())
        override fun decode(v: JVal) {
            set(
                when (v) {
                    is JVal.Str -> v.v
                    is JVal.Num -> if (v.v == v.v.toLong().toDouble()) v.v.toLong().toString() else v.v.toString()
                    is JVal.Bool -> v.v.toString()
                    else -> return
                }
            )
        }

        override val typeName get() = if (multiline) "text" else "string"
    }

    /** Packed ARGB colour. */
    class C(
        name: String,
        val get: () -> Int,
        val set: (Int) -> Unit,
        val alphaEditable: Boolean = true,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Str(format(get()))
        override fun decode(v: JVal) {
            val s = (v as? JVal.Str)?.v ?: return
            set(parse(s))
        }

        override val typeName get() = "color"

        companion object {
            fun format(color: Int) = String.format("#%08X", color)

            fun parse(s: String): Int {
                val h = s.trim().removePrefix("#")
                return when (h.length) {
                    3 -> { // #RGB
                        val r = h[0].digitToInt(16) * 17
                        val g = h[1].digitToInt(16) * 17
                        val b = h[2].digitToInt(16) * 17
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    6 -> (0xFF shl 24) or h.toLong(16).toInt()
                    8 -> h.toLong(16).toInt()
                    else -> 0xFFFFFFFF.toInt()
                }
            }
        }
    }

    /** Named option list stored as text so files survive option reordering. */
    class E(
        name: String,
        val options: List<String>,
        val get: () -> Int,
        val set: (Int) -> Unit,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Str(options.getOrElse(get().coerceIn(0, options.size - 1)) { options.firstOrNull() ?: "" })
        override fun decode(v: JVal) {
            when (v) {
                is JVal.Str -> {
                    val i = options.indexOf(v.v)
                    if (i >= 0) set(i)
                }
                is JVal.Num -> set(v.v.toInt().coerceIn(0, options.size - 1))
                else -> {}
            }
        }

        override val typeName get() = "enum"
    }

    /** Reference to an asset file inside the project's asset folder. */
    class Asset(
        name: String,
        val kind: AssetKind,
        val get: () -> String,
        val set: (String) -> Unit,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Str(get())
        override fun decode(v: JVal) { set((v as? JVal.Str)?.v ?: return) }
        override val typeName get() = "asset:${kind.name.lowercase()}"
    }

    class V2(
        name: String,
        val get: () -> Vec2,
        val set: (Vec2) -> Unit,
        val step: Float = 0.1f,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode(): JVal {
            val v = get(); return jarr(v.x, v.y)
        }

        override fun decode(v: JVal) {
            val a = (v as? JVal.Arr) ?: return
            if (a.size < 2) return
            val x = (a[0] as? JVal.Num)?.v?.toFloat() ?: return
            val y = (a[1] as? JVal.Num)?.v?.toFloat() ?: return
            set(Vec2(x, y))
        }

        override val typeName get() = "vector2"
    }

    class R(
        name: String,
        val get: () -> Rect2,
        val set: (Rect2) -> Unit,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode(): JVal {
            val r = get(); return jarr(r.x, r.y, r.width, r.height)
        }

        override fun decode(v: JVal) {
            val a = (v as? JVal.Arr) ?: return
            if (a.size < 4) return
            set(Rect2(a[0].numF(), a[1].numF(), a[2].numF(), a[3].numF()))
        }

        override val typeName get() = "rect"
    }

    /** Reference to another node, stored by stable id. */
    class NodeRef(
        name: String,
        val get: () -> Long,
        val set: (Long) -> Unit,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Num(get().toDouble())
        override fun decode(v: JVal) { set((v as? JVal.Num)?.v?.toLong() ?: 0L) }
        override val typeName get() = "noderef"
    }

    /** Set of names, e.g. groups, layers, tags — order preserved. */
    class Flags(
        name: String,
        val options: () -> List<String>,
        val get: () -> Collection<String>,
        val set: (Set<String>) -> Unit,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Arr.strings(get().toList())
        override fun decode(v: JVal) {
            set((v as? JVal.Arr)?.mapNotNull { (it as? JVal.Str)?.v }?.toSet() ?: emptySet())
        }

        override val typeName get() = "flags"
    }

    /** Flat float list, e.g. polygon points (x0,y0,x1,y1,…). */
    class Points(
        name: String,
        val get: () -> FloatArray,
        val set: (FloatArray) -> Unit,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Arr().also { a -> get().forEach { a.add(JVal.Num(it.toDouble())) } }
        override fun decode(v: JVal) {
            val a = (v as? JVal.Arr) ?: return
            set(FloatArray(a.size) { a[it].numF() })
        }

        override val typeName get() = "points"
    }

    /** Arbitrary string-list array (e.g. script exported arrays). */
    class Strings(
        name: String,
        val get: () -> List<String>,
        val set: (List<String>) -> Unit,
        label: String = name,
        tooltip: String = "",
        section: String = "Properties",
        order: Int = 0
    ) : Prop(name, label, tooltip, section, order) {
        override fun encode() = JVal.Arr.strings(get())
        override fun decode(v: JVal) {
            set((v as? JVal.Arr)?.mapNotNull { (it as? JVal.Str)?.v } ?: emptyList())
        }

        override val typeName get() = "strings"
    }

    /** Informational, read-only row (e.g. detected type, live counter). */
    class Info(
        name: String,
        val get: () -> String,
        label: String = name,
        section: String = "Info",
        order: Int = 0
    ) : Prop(name, label, "", section, order) {
        override fun encode() = JVal.Str(get())
        override fun decode(v: JVal) {}
        override val typeName get() = "info"
    }
}

internal fun JVal.numF(): Float = (this as? JVal.Num)?.v?.toFloat() ?: (this as? JVal.Str)?.v?.toFloatOrNull() ?: 0f

/** Helper to build a JSON object of every property value (used by prefabs and undo). */
fun List<Prop>.encodeAll(): JVal.Obj {
    val o = JVal.Obj()
    for (p in this) if (p !is Prop.Info) o.fields[p.name] = p.encode()
    return o
}

/** Apply a previously encoded snapshot. Unknown/missing keys are ignored. */
fun List<Prop>.decodeAll(o: JVal.Obj) {
    for (p in this) {
        val v = o[p.name] ?: continue
        try {
            p.decode(v)
        } catch (_: Throwable) {
        }
    }
}

fun createPropObject(vararg pairs: Pair<String, Any?>): JVal.Obj = jobj(*pairs)
