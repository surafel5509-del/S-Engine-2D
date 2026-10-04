package com.sengine.engine.ui

import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Rect2

/** Visual theme of the runtime UI (and the editor's resolution preview). */
object UiTheme {
    const val STYLE_DEFAULT = "Default"
    const val STYLE_PRIMARY = "Primary"
    const val STYLE_DANGER = "Danger"
    const val STYLE_FLAT = "Flat"
    val STYLE_NAMES = listOf(STYLE_DEFAULT, STYLE_PRIMARY, STYLE_DANGER, STYLE_FLAT)

    // colours are ARGB
    var panel = 0xF21E1F22.toInt()
    var panelAccent = 0xFF2B2D31.toInt()
    var button = 0xFF3A3D44.toInt()
    var buttonHover = 0xFF474B54.toInt()
    var buttonPressed = 0xFF2E3138.toInt()
    var primary = 0xFF4C8DFF.toInt()
    var danger = 0xFFE5534B.toInt()
    var text = 0xFFE6E6E6.toInt()
    var textDim = 0xFF9AA0A6.toInt()
    var border = 0xFF4A4D55.toInt()
    var track = 0xFF23252A.toInt()
    var slot = 0xFF2A2C31.toInt()
    var radius = 4f
    var borderWidth = 1f

    fun backgroundFor(style: String): Int = when (style) {
        STYLE_PRIMARY -> primary
        STYLE_DANGER -> danger
        STYLE_FLAT -> 0x00000000
        else -> button
    }

    fun reset() {
        panel = 0xF21E1F22.toInt()
        panelAccent = 0xFF2B2D31.toInt()
        button = 0xFF3A3D44.toInt()
        buttonHover = 0xFF474B54.toInt()
        buttonPressed = 0xFF2E3138.toInt()
        primary = 0xFF4C8DFF.toInt()
        danger = 0xFFE5534B.toInt()
        text = 0xFFE6E6E6.toInt()
        textDim = 0xFF9AA0A6.toInt()
        border = 0xFF4A4D55.toInt()
        track = 0xFF23252A.toInt()
        slot = 0xFF2A2C31.toInt()
    }
}

/**
 * Layout engine for UI nodes.
 *
 * Two rules, applied in this order:
 *  1. A container control (Row/Column/Grid/Center/Scroll/Tabs) arranges its children itself.
 *  2. Every other control derives its rectangle from anchors + offsets + minimum size.
 *
 * Results are written into each [ControlComponent.rect] (UI/pixel space) so the renderer and the
 * hit tester always agree on where things are — the same numbers the editor shows in the
 * resolution preview.
 */
object UiLayout {

    class ScrollState {
        var offsetX = 0f
        var offsetY = 0f
        var contentW = 0f
        var contentH = 0f
        var grabbing = false
        var grabStartX = 0f
        var grabStartY = 0f
        var startOffsetX = 0f
        var startOffsetY = 0f
    }

    private val scrollStates = HashMap<Long, ScrollState>()

    fun scrollState(id: Long): ScrollState = scrollStates.getOrPut(id) { ScrollState() }

    fun clear() = scrollStates.clear()

    /** Lay out the whole UI tree of [scene] inside a viewport of [viewportW] × [viewportH] pixels. */
    fun layout(scene: Scene, viewportW: Float, viewportH: Float) {
        val root = Rect2(0f, 0f, viewportW, viewportH)
        for (go in scene.objects) {
            if (go.ui == null) continue
            if (go.parent != null && go.parent!!.ui != null) continue // laid out by its parent
            layoutNode(go, root, scene)
        }
    }

    private fun layoutNode(go: GameObject, parentRect: Rect2, scene: Scene) {
        val control = go.getAny<ControlComponent>() ?: return
        val u = control.ui
        val rect = if (go.parent?.ui != null) control.rect else anchoredRect(u, parentRect)
        control.rect = rect
        layoutChildren(go, rect, scene)
    }

    private fun anchoredRect(u: GameObject.UiProps, parent: Rect2): Rect2 {
        val left = parent.x + u.anchorMinX * parent.width + u.offsetLeft
        val top = parent.y + u.anchorMinY * parent.height + u.offsetTop
        val right = parent.x + u.anchorMaxX * parent.width + u.offsetRight
        val bottom = parent.y + u.anchorMaxY * parent.height + u.offsetBottom
        val w = maxOf(right - left, u.minWidth.coerceAtLeast(0f))
        val h = maxOf(bottom - top, u.minHeight.coerceAtLeast(0f))
        return Rect2(left, top, w, h)
    }

    private fun layoutChildren(go: GameObject, rect: Rect2, scene: Scene) {
        val control = go.getAny<ControlComponent>() ?: return
        val u = control.ui
        val children = scene.childrenOf(go).filter { it.ui != null && it.getAny<ControlComponent>() != null }
        if (children.isEmpty()) return
        if (!ControlType.isContainer(u.controlType)) {
            for (c in children) {
                val cc = c.getAny<ControlComponent>() ?: continue
                cc.rect = anchoredRect(cc.ui, rect)
                layoutChildren(c, cc.rect, scene)
            }
            return
        }

        val pad = u.padding
        val area = Rect2(rect.x + pad, rect.y + pad, maxOf(0f, rect.width - pad * 2), maxOf(0f, rect.height - pad * 2))
        when (u.controlType) {
            ControlType.ROW -> {
                var x = area.x
                var maxH = 0f
                for (c in children) {
                    val cc = c.getAny<ControlComponent>()!!
                    val w = cc.ui.minWidth.takeIf { it > 0f } ?: 80f
                    val h = cc.ui.minHeight.takeIf { it > 0f } ?: area.height
                    cc.rect = Rect2(x, area.y, w, h)
                    x += w + u.spacing
                    maxH = maxOf(maxH, h)
                }
                for (c in children) layoutChildren(c, c.getAny<ControlComponent>()!!.rect, scene)
            }
            ControlType.COLUMN -> {
                var y = area.y
                for (c in children) {
                    val cc = c.getAny<ControlComponent>()!!
                    val h = cc.ui.minHeight.takeIf { it > 0f } ?: 32f
                    cc.rect = Rect2(area.x, y, area.width, h)
                    y += h + u.spacing
                }
                for (c in children) layoutChildren(c, c.getAny<ControlComponent>()!!.rect, scene)
            }
            ControlType.GRID -> {
                val columns = 2
                val cellW = (area.width - u.spacing * (columns - 1)) / columns
                var x = area.x
                var y = area.y
                var col = 0
                var rowH = 0f
                for (c in children) {
                    val cc = c.getAny<ControlComponent>()!!
                    val h = cc.ui.minHeight.takeIf { it > 0f } ?: 32f
                    cc.rect = Rect2(x, y, cellW, h)
                    rowH = maxOf(rowH, h)
                    col++
                    if (col >= columns) {
                        col = 0
                        x = area.x
                        y += rowH + u.spacing
                        rowH = 0f
                    } else x += cellW + u.spacing
                }
                for (c in children) layoutChildren(c, c.getAny<ControlComponent>()!!.rect, scene)
            }
            ControlType.CENTER -> {
                for (c in children) {
                    val cc = c.getAny<ControlComponent>()!!
                    val w = cc.ui.minWidth.takeIf { it > 0f } ?: minOf(area.width, 160f)
                    val h = cc.ui.minHeight.takeIf { it > 0f } ?: minOf(area.height, 40f)
                    cc.rect = Rect2(area.centerX - w / 2f, area.centerY - h / 2f, w, h)
                }
                for (c in children) layoutChildren(c, c.getAny<ControlComponent>()!!.rect, scene)
            }
            ControlType.SCROLL -> {
                val state = scrollState(go.id)
                var y = area.y - state.offsetY
                var widest = area.width
                for (c in children) {
                    val cc = c.getAny<ControlComponent>()!!
                    val w = cc.ui.minWidth.takeIf { it > 0f } ?: area.width
                    val h = cc.ui.minHeight.takeIf { it > 0f } ?: 32f
                    cc.rect = Rect2(area.x - state.offsetX, y, w, h)
                    y += h + u.spacing
                    widest = maxOf(widest, w)
                }
                state.contentW = widest
                state.contentH = maxOf(area.height, y + state.offsetY - area.y + state.offsetY)
                for (c in children) layoutChildren(c, c.getAny<ControlComponent>()!!.rect, scene)
            }
            ControlType.TABS -> {
                val names = tabNames(u)
                val barHeight = 30f
                var x = area.x
                for ((i, name) in names.withIndex()) {
                    val w = 24f + name.length * u.fontSize * 0.62f
                    if (i == u.activeTab.coerceIn(0, names.size - 1)) {
                        // active tab content fills the rest of the container
                    }
                    x += w
                }
                val content = Rect2(area.x, area.y + barHeight, area.width, maxOf(0f, area.height - barHeight))
                val active = children.getOrNull(u.activeTab.coerceIn(0, maxOf(0, children.size - 1)))
                for (c in children) {
                    val cc = c.getAny<ControlComponent>()!!
                    cc.rect = if (c === active) content else Rect2(content.x, content.y, content.width, content.height)
                    c.visible = c === active
                    layoutChildren(c, cc.rect, scene)
                }
            }
        }
    }

    fun tabNames(u: GameObject.UiProps): List<String> =
        if (u.tabs.isBlank()) listOf("Tab 1", "Tab 2") else u.tabs.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Top-most interactive control under a UI-space point, or null. */
    fun hitTest(scene: Scene, x: Float, y: Float): GameObject? {
        var best: GameObject? = null
        var bestDepth = -1
        for (go in scene.objects) {
            if (go.ui == null || !go.visible || !go.isActiveInHierarchy()) continue
            val control = go.getAny<ControlComponent>() ?: continue
            if (control.ui.controlType == ControlType.SCROLL) continue
            if (!ControlType.isInteractive(control.ui.controlType)) continue
            if (!control.rect.contains(x, y)) continue
            val depth = go.depth()
            if (depth >= bestDepth) {
                bestDepth = depth
                best = go
            }
        }
        if (best != null) return best
        // fall back to containers (for scroll dragging) top-most first
        for (go in scene.objects.asReversed()) {
            if (go.ui == null || !go.visible || !go.isActiveInHierarchy()) continue
            val control = go.getAny<ControlComponent>() ?: continue
            if (control.ui.controlType == ControlType.SCROLL && control.rect.contains(x, y)) return go
        }
        return null
    }

    fun rectOf(go: GameObject): Rect2 = go.getAny<ControlComponent>()?.rect ?: Rect2.EMPTY
}
