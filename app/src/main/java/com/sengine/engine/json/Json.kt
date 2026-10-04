package com.sengine.engine.json

/**
 * Dependency-free JSON model, parser and writer.
 *
 * S Engine deliberately owns its document format: scene, project, tileset, animation and import
 * metadata files must stay readable, diffable and — most importantly — migratable. Keeping the
 * implementation here (instead of `org.json`) also means the whole core is plain Kotlin and can be
 * unit-tested on the JVM without any Android dependency.
 */
sealed class JVal {
    object Null : JVal()

    data class Bool(val v: Boolean) : JVal()
    data class Num(val v: Double) : JVal()
    data class Str(val v: String) : JVal()

    class Arr(val items: MutableList<JVal> = ArrayList()) : JVal(), MutableList<JVal> by items {
        companion object {
            fun of(vararg values: Any?) = Arr().also { a -> values.forEach { a.add(toJson(it)) } }
            fun floats(values: List<Float>) = Arr().also { a -> values.forEach { a.add(Num(it.toDouble())) } }
            fun ints(values: List<Int>) = Arr().also { a -> values.forEach { a.add(Num(it.toDouble())) } }
            fun strings(values: List<String>) = Arr().also { a -> values.forEach { a.add(Str(it)) } }
        }
    }

    class Obj(val fields: LinkedHashMap<String, JVal> = LinkedHashMap()) : JVal() {
        operator fun get(key: String): JVal? = fields[key]
        operator fun set(key: String, value: JVal?) {
            if (value == null) fields.remove(key) else fields[key] = value
        }

        fun has(key: String) = fields.containsKey(key)
        fun isEmpty() = fields.isEmpty()

        fun put(key: String, value: JVal): Obj { fields[key] = value; return this }
        fun put(key: String, value: String): Obj { fields[key] = Str(value); return this }
        fun put(key: String, value: Boolean): Obj { fields[key] = Bool(value); return this }
        fun put(key: String, value: Int): Obj { fields[key] = Num(value.toDouble()); return this }
        fun put(key: String, value: Long): Obj { fields[key] = Num(value.toDouble()); return this }
        fun put(key: String, value: Float): Obj { fields[key] = Num(value.toDouble()); return this }
        fun put(key: String, value: Double): Obj { fields[key] = Num(value); return this }
        fun putIf(condition: Boolean, key: String, value: JVal): Obj { if (condition) fields[key] = value; return this }
        fun remove(key: String): Obj { fields.remove(key); return this }

        fun obj(key: String): Obj = get(key) as? Obj ?: Obj()
        fun arr(key: String): Arr = get(key) as? Arr ?: Arr()
        fun str(key: String, def: String = ""): String = (get(key) as? Str)?.v ?: def
        fun bool(key: String, def: Boolean = false): Boolean = (get(key) as? Bool)?.v ?: def
        fun num(key: String, def: Double = 0.0): Double = (get(key) as? Num)?.v ?: def
        fun f(key: String, def: Float = 0f): Float = num(key, def.toDouble()).toFloat()
        fun i(key: String, def: Int = 0): Int = num(key, def.toDouble()).toInt()
        fun l(key: String, def: Long = 0L): Long = num(key, def.toDouble()).toLong()
        fun strings(key: String): List<String> = arr(key).mapNotNull { (it as? Str)?.v }
        fun floats(key: String): List<Float> = arr(key).mapNotNull { (it as? Num)?.v?.toFloat() }
        fun ints(key: String): List<Int> = arr(key).mapNotNull { (it as? Num)?.v?.toInt() }
        fun objects(key: String): List<Obj> = arr(key).mapNotNull { it as? Obj }

    }

    fun deepCopy(): JVal = copyValue(this)

    companion object {
        fun of(value: Any?): JVal = toJson(value)
    }
}

/** `jobj { "name" to "Player"; "x" to 3.5f }` — concise JSON building. */
private fun copyValue(v: JVal): JVal = when (v) {
    is JVal.Obj -> JVal.Obj().also { dest -> v.fields.forEach { (k, value) -> dest.fields[k] = copyValue(value) } }
    is JVal.Arr -> JVal.Arr().also { dest -> v.items.forEach { dest.items.add(copyValue(it)) } }
    else -> v
}

fun jobj(vararg pairs: Pair<String, Any?>): JVal.Obj {
    val o = JVal.Obj()
    for ((k, v) in pairs) o.put(k, toJson(v))
    return o
}

fun jarr(vararg values: Any?): JVal.Arr {
    val a = JVal.Arr()
    values.forEach { a.add(toJson(it)) }
    return a
}

@Suppress("UNCHECKED_CAST")
fun toJson(value: Any?): JVal = when (value) {
    null -> JVal.Null
    is JVal -> value
    is Boolean -> JVal.Bool(value)
    is Int -> JVal.Num(value.toDouble())
    is Long -> JVal.Num(value.toDouble())
    is Float -> JVal.Num(value.toDouble())
    is Double -> JVal.Num(value)
    is String -> JVal.Str(value)
    is Enum<*> -> JVal.Str(value.name)
    is Map<*, *> -> JVal.Obj().also { o -> value.forEach { (k, v) -> o.fields[k.toString()] = toJson(v) } }
    is Iterable<*> -> JVal.Arr().also { a -> value.forEach { a.add(toJson(it)) } }
    is FloatArray -> JVal.Arr().also { a -> value.forEach { a.add(JVal.Num(it.toDouble())) } }
    is IntArray -> JVal.Arr().also { a -> value.forEach { a.add(JVal.Num(it.toDouble())) } }
    is Array<*> -> JVal.Arr().also { a -> value.forEach { a.add(toJson(it)) } }
    else -> JVal.Str(value.toString())
}

object Json {
    // ---------------------------------------------------------------- writing
    fun write(value: JVal, pretty: Boolean = true): String {
        val sb = StringBuilder(256)
        writeValue(sb, value, if (pretty) 0 else -1, StringBuilder())
        return sb.toString()
    }

    fun write(value: JVal): String = write(value, true)

    private fun indent(sb: StringBuilder, depth: Int, pretty: Boolean) {
        if (!pretty) return
        sb.append('\n')
        for (i in 0 until depth) sb.append("  ")
    }

    private fun writeValue(sb: StringBuilder, v: JVal, depth: Int, pad: StringBuilder) {
        val pretty = depth >= 0
        when (v) {
            is JVal.Null -> sb.append("null")
            is JVal.Bool -> sb.append(if (v.v) "true" else "false")
            is JVal.Num -> sb.append(numToString(v.v))
            is JVal.Str -> escape(sb, v.v)
            is JVal.Arr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                for ((i, item) in v.items.withIndex()) {
                    if (i > 0) sb.append(',')
                    indent(sb, depth + 1, pretty)
                    writeValue(sb, item, depth + 1, pad)
                }
                indent(sb, depth, pretty)
                sb.append(']')
            }
            is JVal.Obj -> {
                if (v.fields.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, depth + 1, pretty)
                    escape(sb, k)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    writeValue(sb, item, depth + 1, pad)
                }
                indent(sb, depth, pretty)
                sb.append('}')
            }
        }
    }

    private fun numToString(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "0"
        val l = d.toLong()
        return if (l.toDouble() == d && kotlin.math.abs(d) < 1e15) l.toString()
        else {
            // trim float noise so files stay diff-friendly
            val s = String.format(java.util.Locale.US, "%.5f", d).trimEnd('0').trimEnd('.')
            if (s.isEmpty() || s == "-") "0" else s
        }
    }

    private fun escape(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    // ---------------------------------------------------------------- parsing
    fun parse(text: String): JVal {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        return v
    }

    /** Parse and coerce to an object (empty object when the document is not an object). */
    fun parseObject(text: String): JVal.Obj = parse(text) as? JVal.Obj ?: JVal.Obj()

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): JVal {
            if (i >= s.length) return JVal.Null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JVal.Str(string())
                't' -> { expect("true"); JVal.Bool(true) }
                'f' -> { expect("false"); JVal.Bool(false) }
                'n' -> { expect("null"); JVal.Null }
                else -> number()
            }
        }

        fun expect(word: String) {
            if (i + word.length <= s.length && s.regionMatches(i, word, 0, word.length)) i += word.length
            else throw IllegalArgumentException("Invalid JSON at $i: expected $word")
        }

        fun obj(): JVal.Obj {
            val o = JVal.Obj()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return o }
            while (i < s.length) {
                skipWs()
                if (i < s.length && s[i] == '}') { i++; break }
                val key = if (s[i] == '"') string() else unquotedKey()
                skipWs()
                if (i < s.length && s[i] == ':') i++
                skipWs()
                o.fields[key] = value()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                if (i >= s.length) break
                throw IllegalArgumentException("Invalid JSON object at $i")
            }
            return o
        }

        fun arr(): JVal.Arr {
            val a = JVal.Arr()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (i < s.length) {
                skipWs()
                if (i < s.length && s[i] == ']') { i++; break }
                a.items.add(value())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                if (i >= s.length) break
                throw IllegalArgumentException("Invalid JSON array at $i")
            }
            return a
        }

        fun unquotedKey(): String {
            val start = i
            while (i < s.length && s[i] != ':' && s[i] != ' ' && s[i] != '\n') i++
            return s.substring(start, i).trim()
        }

        fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(i, minOf(i + 4, s.length))
                                i += hex.length
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        fun number(): JVal.Num {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            val text = s.substring(start, i)
            return JVal.Num(text.toDoubleOrNull() ?: 0.0)
        }
    }
}
