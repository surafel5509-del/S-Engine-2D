package com.sengine.ui

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.Engine
import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Component
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.ScriptComponent

interface EditorHost {
    val engine: Engine
    val history: History
    val project: com.sengine.project.Project
    fun selectedId(): Long
    fun onStructureChanged()
    fun select(id: Long)
    fun openScript(name: String)
    fun newScriptDialog(onCreated: (String) -> Unit)
}

/** Builds the property editor for the selected GameObject (or scene settings). */
class InspectorPanel(private val act: EditorActivity, private val host: EditorHost, private val container: LinearLayout) {

    private val refreshers = ArrayList<() -> Unit>()
    private var current: GameObject? = null
    private var updating = false
    private val engine get() = host.engine

    fun rebuild() {
        container.removeAllViews()
        refreshers.clear()
        val go = synchronized(engine.lock) { engine.scene.findById(host.selectedId()) }
        current = go
        if (go == null) buildSceneSettings() else buildObject(go)
    }

    fun refreshValues() {
        val go = current
        if (go != null && go.destroyed) { rebuild(); return }
        updating = true
        refreshers.forEach { it() }
        updating = false
    }

    private fun <T> locked(block: () -> T): T = synchronized(engine.lock) { block() }

    private fun changed(structural: Boolean = false) {
        if (structural) host.onStructureChanged()
    }

    // ------------------------------------------------------------------ scene
    private fun buildSceneSettings() {
        val scene = engine.scene
        container.addView(sectionHeader("Scene: ${scene.name}", null))
        val body = sectionBody()
        body.addView(act.label("Nothing selected. Tap an object in the viewport or hierarchy.", 12f, C.DIM),
            lp(MATCH, WRAP).margins(0, 0, 0, act.dp(8)))
        addProp(body, Prop.F("Gravity X", { engine.scene.gravityX }, { engine.scene.gravityX = it }))
        addProp(body, Prop.F("Gravity Y", { engine.scene.gravityY }, { engine.scene.gravityY = it }))
        body.addView(act.label("Project", 12f, C.DIM, true), lp(MATCH, WRAP).margins(0, act.dp(10), 0, act.dp(2)))
        addProp(body, Prop.Choice("Orientation", listOf("Landscape", "Portrait"), { host.project.orientation },
            { host.project.orientation = it; host.project.saveMeta() }), undo = false)
        val scenes = host.project.listScenes().ifEmpty { listOf(scene.name) }
        addProp(body, Prop.Choice("Start Scene", scenes, { scenes.indexOf(host.project.startScene).coerceAtLeast(0) },
            { host.project.startScene = scenes[it]; host.project.saveMeta() }), undo = false)
        val stats = act.label("", 12f, C.DIM)
        body.addView(stats, lp(MATCH, WRAP).margins(0, act.dp(10), 0, 0))
        refreshers.add {
            stats.text = "Objects: ${engine.scene.objects.size}   •   Mode: ${engine.mode.name.lowercase()}"
        }
        container.addView(body)
    }

    // ------------------------------------------------------------------ object
    private fun buildObject(go: GameObject) {
        val head = act.vbox().apply { setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(6)); setBackgroundColor(C.HEADER) }
        val row = act.hbox()
        val active = CheckBox(act).apply { isChecked = go.active }
        active.setOnCheckedChangeListener { _, b ->
            if (updating) return@setOnCheckedChangeListener
            record(); locked { go.active = b }; changed(true)
        }
        refreshers.add { if (active.isChecked != go.active) active.isChecked = go.active }
        row.addView(active)
        val name = act.field(go.name)
        bindText(name, { go.name }, { go.name = it.ifBlank { "GameObject" }; host.onStructureChanged() })
        row.addView(name, lp(0, WRAP, 1f))
        head.addView(row)

        val row2 = act.hbox()
        row2.addView(act.label("Tag", 12f, C.DIM), lp(act.dp(30), WRAP))
        val tag = act.field(go.tag)
        bindText(tag, { go.tag }, { go.tag = it })
        row2.addView(tag, lp(0, WRAP, 1f))
        row2.addView(act.label("  Order", 12f, C.DIM))
        val order = act.field(go.order.toString(), numeric = true)
        bindText(order, { go.order.toString() }, { s -> s.toFloatOrNull()?.let { go.order = it.toInt() } })
        row2.addView(order, lp(act.dp(56), WRAP))
        head.addView(row2, lp(MATCH, WRAP).margins(0, act.dp(4), 0, 0))

        val row3 = act.hbox()
        row3.addView(act.label("Parent", 12f, C.DIM), lp(act.dp(46), WRAP))
        val parentBtn = act.button(go.parent?.name ?: "(none)") { choose -> chooseParent(go, choose as TextView) }
        parentBtn.textSize = 12f
        row3.addView(parentBtn, lp(0, WRAP, 1f))
        head.addView(row3, lp(MATCH, WRAP).margins(0, act.dp(4), 0, 0))
        container.addView(head, lp(MATCH, WRAP))

        // transform
        container.addView(sectionHeader("Transform", null))
        val tb = sectionBody()
        vec2(tb, "Position", Prop.F("X", { go.x }, { go.x = it }), Prop.F("Y", { go.y }, { go.y = it }))
        addProp(tb, Prop.F("Rotation", { go.rotation }, { go.rotation = it }, 1f))
        vec2(tb, "Scale", Prop.F("X", { go.scaleX }, { go.scaleX = it }, 0.05f), Prop.F("Y", { go.scaleY }, { go.scaleY = it }, 0.05f))
        container.addView(tb)

        for (c in go.components.toList()) buildComponent(go, c)

        container.addView(act.button("+ Add Component", C.ACCENT, 0xFFFFFFFF.toInt()) { addComponentDialog(go) },
            lp(MATCH, WRAP).margins(act.dp(10), act.dp(12), act.dp(10), act.dp(24)))
    }

    private fun buildComponent(go: GameObject, c: Component) {
        val header = sectionHeader(prettyType(c.type), c)
        val en = CheckBox(act).apply { isChecked = c.enabled }
        en.setOnCheckedChangeListener { _, b -> if (!updating) { record(); locked { c.enabled = b } } }
        header.addView(en, 0)
        val menu = act.button("⋮", C.HEADER) { v ->
            val pm = PopupMenu(act, v)
            pm.menu.add("Move Up"); pm.menu.add("Move Down"); pm.menu.add("Reset"); pm.menu.add("Remove")
            pm.setOnMenuItemClickListener {
                record()
                locked {
                    val i = go.components.indexOf(c)
                    when (it.title) {
                        "Move Up" -> if (i > 0) { go.components.removeAt(i); go.components.add(i - 1, c) }
                        "Move Down" -> if (i < go.components.size - 1) { go.components.removeAt(i); go.components.add(i + 1, c) }
                        "Reset" -> {
                            val fresh = ComponentRegistry.create(c.type)!!
                            fresh.gameObject = go
                            go.components[i] = fresh
                        }
                        "Remove" -> go.components.remove(c)
                    }
                    Unit
                }
                rebuild(); changed(true)
                true
            }
            pm.show()
        }
        header.addView(menu)
        container.addView(header)
        val body = sectionBody()
        for (p in c.props()) addProp(body, p)
        if (c is ScriptComponent) {
            val row = act.hbox()
            row.addView(act.button("Edit Script") { if (c.script.isNotBlank()) host.openScript(c.script) }, lp(0, WRAP, 1f).margins(0, act.dp(6), act.dp(4), 0))
            row.addView(act.button("New Script") {
                host.newScriptDialog { n -> record(); locked { c.script = n }; rebuild(); host.openScript(n) }
            }, lp(0, WRAP, 1f).margins(act.dp(4), act.dp(6), 0, 0))
            body.addView(row, lp(MATCH, WRAP))
        }
        container.addView(body)
    }

    private fun prettyType(t: String) = t.replace(Regex("([a-z])([A-Z])"), "$1 $2")

    // ------------------------------------------------------------------ widgets
    private fun sectionHeader(title: String, @Suppress("UNUSED_PARAMETER") c: Component?): LinearLayout {
        val h = act.hbox().apply {
            setBackgroundColor(C.PANEL2)
            setPadding(act.dp(8), act.dp(4), act.dp(4), act.dp(4))
        }
        h.addView(act.label(title, 13f, C.TEXT, true), lp(0, WRAP, 1f))
        h.layoutParams = lp(MATCH, WRAP).margins(0, act.dp(6), 0, 0)
        return h
    }

    private fun sectionBody(): LinearLayout = act.vbox().apply { setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6)) }

    private fun record() = host.history.record(host.selectedId())

    private fun bindText(et: EditText, get: () -> String, set: (String) -> Unit) {
        et.setOnFocusChangeListener { _, has -> if (has) record() }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                locked { set(s.toString()) }
            }
        })
        et.setOnEditorActionListener { v, _, _ -> v.clearFocus(); act.hideKeyboard(v); false }
        refreshers.add {
            if (!et.hasFocus()) {
                val v = locked { get() }
                if (et.text.toString() != v) et.setText(v)
            }
        }
    }

    private fun propLabel(text: String, width: Int = act.dp(92)): TextView =
        act.label(text, 12f, C.DIM).apply { layoutParams = lp(width, WRAP) }

    @SuppressLint("ClickableViewAccessibility")
    private fun scrubbable(label: TextView, p: Prop.F) {
        var lastX = 0f
        label.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; record(); label.setTextColor(C.ACCENT) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX
                    lastX = e.rawX
                    locked { p.set(p.get() + dx / act.dp(4) * p.step) }
                    refreshValues()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> label.setTextColor(C.DIM)
            }
            true
        }
    }

    private fun floatField(p: Prop.F): EditText {
        val et = act.field(fmt(p.get()), numeric = true)
        bindText(et, { fmt(p.get()) }, { s -> s.toFloatOrNull()?.let { p.set(it) } })
        return et
    }

    private fun vec2(parent: LinearLayout, title: String, px: Prop.F, py: Prop.F) {
        val row = act.hbox()
        row.addView(propLabel(title, act.dp(64)))
        val lx = act.label("X", 12f, 0xFFFF6B6B.toInt()).apply { setPadding(act.dp(4), 0, act.dp(4), 0) }
        scrubbable(lx, px)
        row.addView(lx)
        row.addView(floatField(px), lp(0, WRAP, 1f))
        val ly = act.label("Y", 12f, 0xFF6BDB6B.toInt()).apply { setPadding(act.dp(8), 0, act.dp(4), 0) }
        scrubbable(ly, py)
        row.addView(ly)
        row.addView(floatField(py), lp(0, WRAP, 1f))
        parent.addView(row, lp(MATCH, WRAP).margins(0, act.dp(2), 0, act.dp(2)))
    }

    private fun addProp(parent: LinearLayout, p: Prop, undo: Boolean = true) {
        val row = act.hbox()
        val label = propLabel(p.name)
        row.addView(label)
        when (p) {
            is Prop.F -> {
                scrubbable(label, p)
                row.addView(floatField(p), lp(0, WRAP, 1f))
            }
            is Prop.I -> {
                val et = act.field(p.get().toString(), numeric = true)
                bindText(et, { p.get().toString() }, { s -> s.toFloatOrNull()?.let { p.set(it.toInt()) } })
                row.addView(et, lp(0, WRAP, 1f))
            }
            is Prop.B -> {
                val cb = CheckBox(act).apply { isChecked = p.get() }
                cb.setOnCheckedChangeListener { _, b -> if (!updating) { if (undo) record(); locked { p.set(b) } } }
                refreshers.add { val v = locked { p.get() }; if (cb.isChecked != v) cb.isChecked = v }
                row.addView(cb)
            }
            is Prop.S -> {
                val et = act.field(p.get(), multiline = p.multiline)
                if (p.multiline) et.maxLines = 4
                bindText(et, p.get, p.set)
                row.addView(et, lp(0, WRAP, 1f))
            }
            is Prop.Color -> {
                val sw = View(act)
                fun paint() { sw.background = round(p.get(), act.dp(4).toFloat(), act.dp(1), 0xFF555555.toInt()) }
                paint()
                sw.setOnClickListener {
                    ColorPickerDialog.show(act, p.get()) { c -> if (undo) record(); locked { p.set(c) }; paint() }
                }
                refreshers.add { paint() }
                row.addView(sw, lp(0, act.dp(26), 1f))
            }
            is Prop.Choice -> {
                val b = act.button(p.options.getOrElse(p.get()) { "?" }) {}
                b.textSize = 12f
                b.setOnClickListener { v ->
                    val pm = PopupMenu(act, v)
                    p.options.forEachIndexed { i, o -> pm.menu.add(0, i, i, o) }
                    pm.setOnMenuItemClickListener { item ->
                        if (undo) record()
                        locked { p.set(item.itemId) }
                        b.text = p.options[item.itemId]
                        true
                    }
                    pm.show()
                }
                refreshers.add { val t = p.options.getOrElse(locked { p.get() }) { "?" }; if (b.text != t) b.text = t }
                row.addView(b, lp(0, WRAP, 1f))
            }
            is Prop.Asset -> {
                val b = act.button(p.get().ifBlank { "(none)" }) {}
                b.textSize = 12f
                b.setOnClickListener { chooseAsset(p) { b.text = p.get().ifBlank { "(none)" } } }
                refreshers.add { val t = locked { p.get() }.ifBlank { "(none)" }; if (b.text != t) b.text = t }
                row.addView(b, lp(0, WRAP, 1f))
            }
        }
        parent.addView(row, lp(MATCH, WRAP).margins(0, act.dp(2), 0, act.dp(2)))
    }

    // ------------------------------------------------------------------ dialogs
    private fun chooseAsset(p: Prop.Asset, done: () -> Unit) {
        val items = listOf("(none)") + host.project.listAssets(p.kind)
        val extra = if (p.kind == AssetKind.SCRIPT) listOf("+ New Script…") else emptyList()
        val all = items + extra
        MaterialAlertDialogBuilder(act)
            .setTitle("Select ${p.name}")
            .setItems(all.toTypedArray()) { _, i ->
                when {
                    i == 0 -> { record(); locked { p.set("") }; done() }
                    i < items.size -> { record(); locked { p.set(items[i]) }; done() }
                    else -> host.newScriptDialog { n -> record(); locked { p.set(n) }; done() }
                }
            }
            .show()
    }

    private fun chooseParent(go: GameObject, btn: TextView) {
        val candidates = locked { engine.scene.objects.filter { it !== go && !go.isAncestorOf(it) } }
        val names = listOf("(none)") + candidates.map { it.name }
        MaterialAlertDialogBuilder(act)
            .setTitle("Parent of ${go.name}")
            .setItems(names.toTypedArray()) { _, i ->
                record()
                locked { reparentKeepWorld(go, if (i == 0) null else candidates[i - 1]) }
                btn.text = go.parent?.name ?: "(none)"
                changed(true)
                refreshValues()
            }
            .show()
    }

    fun addComponentDialog(go: GameObject) {
        val types = ComponentRegistry.types.keys.filter { t -> t == "Script" || go.components.none { it.type == t } }
        MaterialAlertDialogBuilder(act)
            .setTitle("Add Component")
            .setItems(types.map { prettyType(it) }.toTypedArray()) { _, i ->
                record()
                locked { ComponentRegistry.create(types[i])?.let { go.add(it) } }
                rebuild(); changed(true)
            }
            .show()
    }

    companion object {
        /** Changes parent while keeping the world position/rotation/scale approximately intact. */
        fun reparentKeepWorld(go: GameObject, newParent: GameObject?) {
            val w = go.computeWorld()
            go.parent = newParent
            if (newParent == null) {
                go.x = w.tx; go.y = w.ty; go.rotation = w.rotationDeg
                go.scaleX = w.scaleX; go.scaleY = w.scaleY
            } else {
                val inv = newParent.computeWorld().inverted() ?: return
                val local = com.sengine.engine.math.Affine().setMul(inv, w)
                go.x = local.tx; go.y = local.ty; go.rotation = local.rotationDeg
                go.scaleX = local.scaleX; go.scaleY = local.scaleY
            }
        }
    }
}
