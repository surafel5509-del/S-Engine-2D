package com.sengine.engine.core

import com.sengine.engine.math.Affine

class GameObject(var id: Long, var name: String) {
    var tag: String = "Untagged"
    var active: Boolean = true
    /** Sorting order: higher is drawn on top. */
    var order: Int = 0

    var x = 0f
    var y = 0f
    var rotation = 0f
    var scaleX = 1f
    var scaleY = 1f

    var parent: GameObject? = null
    val components = mutableListOf<Component>()

    @Volatile var destroyed = false

    /** Cached world transform, refreshed by [Scene.updateTransforms]. */
    val world = Affine()

    fun localMatrix(out: Affine = Affine()): Affine = out.setTRS(x, y, rotation, scaleX, scaleY)

    fun computeWorld(): Affine {
        val local = localMatrix()
        val p = parent ?: return local
        return Affine().setMul(p.computeWorld(), local)
    }

    fun setWorldPosition(wx: Float, wy: Float) {
        val p = parent
        if (p == null) {
            x = wx; y = wy
        } else {
            val inv = p.computeWorld().inverted() ?: return
            x = inv.mapX(wx, wy); y = inv.mapY(wx, wy)
        }
    }

    fun isActiveInHierarchy(): Boolean = active && !destroyed && (parent?.isActiveInHierarchy() ?: true)

    fun isAncestorOf(other: GameObject): Boolean {
        var p = other.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    fun <T : Component> add(c: T): T {
        c.gameObject = this
        components.add(c)
        return c
    }

    inline fun <reified T : Component> get(): T? = components.firstOrNull { it is T && it.enabled } as T?
    inline fun <reified T : Component> getAny(): T? = components.firstOrNull { it is T } as T?
}
