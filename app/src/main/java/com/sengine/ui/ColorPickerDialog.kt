package com.sengine.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.core.Component

object ColorPickerDialog {
    private val PALETTE = longArrayOf(
        0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFFEF5350, 0xFFFF7043, 0xFFFFCA28, 0xFFFFEE58,
        0xFF66BB6A, 0xFF26A69A, 0xFF42A5F5, 0xFF5C6BC0, 0xFFAB47BC, 0xFFEC407A, 0xFF8D6E63
    )

    fun show(ctx: Context, initial: Int, onPick: (Int) -> Unit) {
        var color = initial
        val box = ctx.vbox().apply { setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), 0) }
        val preview = View(ctx)
        box.addView(preview, lp(MATCH, ctx.dp(44)).margins(0, 0, 0, ctx.dp(10)))
        val hex = ctx.field("")
        val bars = ArrayList<SeekBar>()
        var updating = false

        fun refresh(fromHex: Boolean = false) {
            updating = true
            preview.background = round(color, ctx.dp(8).toFloat(), ctx.dp(1), 0xFF555555.toInt())
            val comps = intArrayOf((color shr 16) and 255, (color shr 8) and 255, color and 255, (color ushr 24) and 255)
            bars.forEachIndexed { i, b -> b.progress = comps[i] }
            if (!fromHex) hex.setText(String.format("#%08X", color))
            updating = false
        }

        val names = listOf("R", "G", "B", "A")
        for (i in 0 until 4) {
            val row = ctx.hbox()
            row.addView(ctx.label(names[i], 13f, C.DIM), lp(ctx.dp(20), WRAP))
            val sb = SeekBar(ctx).apply { max = 255 }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (updating || !fromUser) return
                    val shift = when (i) { 0 -> 16; 1 -> 8; 2 -> 0; else -> 24 }
                    color = (color and (0xFF shl shift).inv()) or (p shl shift)
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            bars.add(sb)
            row.addView(sb, lp(0, WRAP, 1f))
            box.addView(row, lp(MATCH, WRAP))
        }
        box.addView(hex, lp(MATCH, WRAP).margins(0, ctx.dp(8), 0, ctx.dp(8)))
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                try { color = Component.parseColor(s.toString()); refresh(true) } catch (_: Exception) {}
            }
        })
        val pal = ctx.hbox()
        for (c in PALETTE) {
            pal.addView(View(ctx).apply {
                background = round(c.toInt(), ctx.dp(4).toFloat(), 1, 0xFF555555.toInt())
                setOnClickListener { color = c.toInt(); refresh() }
            }, lp(0, ctx.dp(26), 1f).margins(ctx.dp(1), 0, ctx.dp(1), 0))
        }
        box.addView(pal, lp(MATCH, WRAP))
        refresh()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Color")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onPick(color) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
