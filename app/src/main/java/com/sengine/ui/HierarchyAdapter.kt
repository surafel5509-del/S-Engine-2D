package com.sengine.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer

class HierarchyAdapter(
    private val act: EditorActivity,
    private val onClick: (GameObject) -> Unit,
    private val onLongClick: (GameObject, View) -> Unit,
    private val onToggleActive: (GameObject) -> Unit,
) : RecyclerView.Adapter<HierarchyAdapter.VH>() {

    class Row(val go: GameObject, val depth: Int, val hasChildren: Boolean, val name: String, val active: Boolean, val icon: String)

    private var rows: List<Row> = emptyList()
    val collapsed = HashSet<Long>()
    var selectedId = -1L

    class VH(val root: LinearLayout, val arrow: TextView, val icon: TextView, val name: TextView, val eye: TextView) : RecyclerView.ViewHolder(root)

    fun submit(items: List<Pair<GameObject, Int>>, all: List<GameObject>) {
        val out = ArrayList<Row>()
        var hideDepth = Int.MAX_VALUE
        for ((go, depth) in items) {
            if (depth > hideDepth) continue
            hideDepth = Int.MAX_VALUE
            val hasChildren = all.any { it.parent === go }
            out.add(Row(go, depth, hasChildren, go.name, go.active, iconFor(go)))
            if (hasChildren && go.id in collapsed) hideDepth = depth
        }
        rows = out
        notifyDataSetChanged()
    }

    private fun iconFor(go: GameObject): String = when {
        go.getAny<Camera2D>() != null -> "🎥"
        go.getAny<TextRenderer>() != null -> "T"
        go.getAny<ParticleEmitter>() != null -> "✦"
        go.getAny<SpriteRenderer>() != null -> when (go.getAny<SpriteRenderer>()!!.shape) { 1 -> "●"; 2 -> "▲"; else -> "■" }
        go.getAny<ScriptComponent>() != null -> "{}"
        else -> "○"
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = act.hbox().apply {
            setPadding(act.dp(4), act.dp(7), act.dp(4), act.dp(7))
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        }
        val arrow = act.label("", 12f, C.DIM).apply { gravity = Gravity.CENTER }
        val icon = act.label("", 12f, C.ACCENT).apply { gravity = Gravity.CENTER }
        val name = act.label("", 13f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        val eye = act.label("", 12f, C.DIM).apply { gravity = Gravity.CENTER }
        root.addView(arrow, lp(act.dp(18), WRAP))
        root.addView(icon, lp(act.dp(22), WRAP))
        root.addView(name, lp(0, WRAP, 1f))
        root.addView(eye, lp(act.dp(26), WRAP))
        return VH(root, arrow, icon, name, eye)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        h.root.setPadding(act.dp(4 + r.depth * 14), act.dp(7), act.dp(4), act.dp(7))
        h.arrow.text = if (r.hasChildren) (if (r.go.id in collapsed) "▸" else "▾") else ""
        h.icon.text = r.icon
        h.name.text = r.name
        h.name.setTextColor(if (r.active) C.TEXT else C.DIM)
        h.eye.text = if (r.active) "👁" else "–"
        h.root.setBackgroundColor(if (r.go.id == selectedId) C.SEL else 0)
        h.root.setOnClickListener { onClick(r.go) }
        h.root.setOnLongClickListener { onLongClick(r.go, it); true }
        h.arrow.setOnClickListener {
            if (!r.hasChildren) { onClick(r.go); return@setOnClickListener }
            if (!collapsed.add(r.go.id)) collapsed.remove(r.go.id)
            act.refreshHierarchy()
        }
        h.eye.setOnClickListener { onToggleActive(r.go) }
    }
}
