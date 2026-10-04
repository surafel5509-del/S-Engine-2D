package com.sengine.engine.core

import org.json.JSONArray
import org.json.JSONObject

class Scene(var name: String) {
    val objects = mutableListOf<GameObject>()
    var gravityX = 0f
    var gravityY = -9.81f
    var nextId = 1L

    fun create(name: String, parent: GameObject? = null): GameObject {
        val go = GameObject(nextId++, uniqueName(name))
        go.parent = parent
        objects.add(go)
        return go
    }

    fun uniqueName(base: String): String {
        if (objects.none { it.name == base }) return base
        val stem = base.replace(Regex(" \\(\\d+\\)$"), "")
        var i = 1
        while (objects.any { it.name == "$stem ($i)" }) i++
        return "$stem ($i)"
    }

    fun findById(id: Long) = objects.firstOrNull { it.id == id }
    fun find(name: String) = objects.firstOrNull { it.name == name && !it.destroyed }

    fun childrenOf(go: GameObject?) = objects.filter { it.parent === go }

    /** Depth-first hierarchy listing with depth. */
    fun hierarchy(): List<Pair<GameObject, Int>> {
        val out = ArrayList<Pair<GameObject, Int>>()
        fun walk(p: GameObject?, depth: Int) {
            for (c in objects) if (c.parent === p) {
                out.add(c to depth); walk(c, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    fun remove(go: GameObject) {
        for (c in childrenOf(go)) remove(c)
        go.destroyed = true
        objects.remove(go)
    }

    fun updateTransforms() {
        for ((go, _) in hierarchy()) {
            val p = go.parent
            if (p == null) go.localMatrix(go.world)
            else go.world.setMul(p.world, go.localMatrix())
        }
    }

    /** Deep copy of an object (and its children) placed next to the original. */
    fun duplicate(src: GameObject, newParent: GameObject? = src.parent): GameObject {
        val json = SceneSerializer.objectToJson(src)
        val copy = create(src.name, newParent)
        SceneSerializer.applyObjectJson(copy, json)
        copy.parent = newParent
        for (child in childrenOf(src).toList()) {
            if (child !== copy) duplicate(child, copy)
        }
        return copy
    }

    fun moveInOrder(go: GameObject, delta: Int) {
        val siblings = objects.filter { it.parent === go.parent }
        val i = siblings.indexOf(go)
        val j = (i + delta).coerceIn(0, siblings.size - 1)
        if (i == j) return
        val other = siblings[j]
        val a = objects.indexOf(go)
        val b = objects.indexOf(other)
        objects[a] = other
        objects[b] = go
    }
}

object SceneSerializer {
    fun objectToJson(go: GameObject): JSONObject {
        val o = JSONObject()
        o.put("id", go.id)
        o.put("name", go.name)
        o.put("tag", go.tag)
        o.put("active", go.active)
        o.put("order", go.order)
        o.put("x", go.x.toDouble()); o.put("y", go.y.toDouble())
        o.put("rotation", go.rotation.toDouble())
        o.put("scaleX", go.scaleX.toDouble()); o.put("scaleY", go.scaleY.toDouble())
        go.parent?.let { o.put("parent", it.id) }
        val comps = JSONArray()
        for (c in go.components) comps.put(c.toJson())
        o.put("components", comps)
        return o
    }

    /** Applies everything except id and parent. */
    fun applyObjectJson(go: GameObject, o: JSONObject) {
        go.tag = o.optString("tag", "Untagged")
        go.active = o.optBoolean("active", true)
        go.order = o.optInt("order", 0)
        go.x = o.optDouble("x", 0.0).toFloat()
        go.y = o.optDouble("y", 0.0).toFloat()
        go.rotation = o.optDouble("rotation", 0.0).toFloat()
        go.scaleX = o.optDouble("scaleX", 1.0).toFloat()
        go.scaleY = o.optDouble("scaleY", 1.0).toFloat()
        go.components.clear()
        val comps = o.optJSONArray("components") ?: JSONArray()
        for (i in 0 until comps.length()) {
            val cj = comps.getJSONObject(i)
            val c = ComponentRegistry.create(cj.optString("type")) ?: continue
            c.fromJson(cj)
            go.add(c)
        }
    }

    fun toJson(scene: Scene): JSONObject {
        val o = JSONObject()
        o.put("name", scene.name)
        o.put("gravityX", scene.gravityX.toDouble())
        o.put("gravityY", scene.gravityY.toDouble())
        o.put("nextId", scene.nextId)
        val arr = JSONArray()
        for (go in scene.objects) arr.put(objectToJson(go))
        o.put("objects", arr)
        return o
    }

    fun fromJson(o: JSONObject): Scene {
        val s = Scene(o.optString("name", "Main"))
        s.gravityX = o.optDouble("gravityX", 0.0).toFloat()
        s.gravityY = o.optDouble("gravityY", -9.81).toFloat()
        val arr = o.optJSONArray("objects") ?: JSONArray()
        val parents = HashMap<GameObject, Long>()
        var maxId = 0L
        for (i in 0 until arr.length()) {
            val oj = arr.getJSONObject(i)
            val go = GameObject(oj.optLong("id"), oj.optString("name", "GameObject"))
            applyObjectJson(go, oj)
            if (oj.has("parent")) parents[go] = oj.getLong("parent")
            s.objects.add(go)
            maxId = maxOf(maxId, go.id)
        }
        for ((go, pid) in parents) go.parent = s.findById(pid)
        s.nextId = maxOf(o.optLong("nextId", 1L), maxId + 1)
        return s
    }
}
