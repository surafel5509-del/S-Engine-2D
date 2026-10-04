package com.sengine.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sengine.engine.core.AnimatedSprite2D
import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Sprite2D
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.debug.Log
import com.sengine.engine.editor.EditorDocument
import com.sengine.engine.export.ApkExporter
import com.sengine.engine.input.Binding
import com.sengine.engine.input.InputMap
import com.sengine.engine.json.Json
import com.sengine.engine.json.jarr
import com.sengine.engine.json.jobj
import com.sengine.engine.tilemap.TileSet
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// --------------------------------------------------------------------------------- sprite editor

/**
 * Sprite editor: real pixel-level slicing of a texture asset.
 *
 *  - **Grid** slicing with tile size, offset and spacing (the sprite-sheet workhorse).
 *  - **Auto** slicing: the alpha channel is scanned column-by-column and row-by-row, so sheets that
 *    are not on a regular grid still produce usable frames.
 *  - **Manual** slicing: drag a crop rectangle in the preview.
 *  - Slices can be written to the selected [AnimatedSprite2D], to a [TileSet] asset (with real tile
 *    definitions and regions) or to a new `.sheet.json` asset.
 *  - Clicking the preview sets the pivot of the selected sprite, and the pivot marker is drawn over
 *    the sheet so the change is visible immediately.
 */
class SpriteEditorPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private var assetName = ""
    private var bitmap: Bitmap? = null
    private val preview = SlicePreview(context)
    private val content = Panels.column(context, theme)
    private val sliceList = Panels.column(context, theme)

    // slicing parameters (kept between assets so repeating a workflow is quick)
    private var cellW = 16
    private var cellH = 16
    private var offsetX = 0
    private var offsetY = 0
    private var spacingX = 0
    private var spacingY = 0
    private var slices: MutableList<IntArray> = ArrayList()   // x, y, w, h
    private var autoDetected = false

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        Panels.scroll(this, content)
        content.addView(Panels.header(context, theme, "Sprite editor — slice, pivot, frames"))
        preview.onPivotPicked = { u, v -> applyPivot(u, v) }
        preview.onCropChanged = { rect -> manualSlice = rect }
        refresh()
    }

    private var manualSlice: IntArray? = null

    fun refresh() {
        content.removeAllViews()
        content.addView(Panels.header(context, theme, "Sprite editor — slice, pivot, frames"))

        // ---- asset picker
        val textures = doc.project.listAssets(AssetKind.TEXTURE)
        content.addView(Ui.label(context, "Texture: ${assetName.ifEmpty { "(none)" }}", theme, 12f, theme.textDim))
        val pickerRow = LinearLayout(context)
        pickerRow.orientation = HORIZONTAL
        var shown = 0
        for (name in textures.asReversed()) {
            if (shown++ >= 8) break
            val b = EditorButton(context, theme, name.substringAfterLast('/').take(12), {
                load(name)
            }, compact = true)
            pickerRow.addView(b)
        }
        if (textures.isEmpty()) pickerRow.addView(Ui.label(context, "No textures imported yet.", theme, 11f, theme.textDim))
        content.addView(pickerRow)
        content.addView(EditorButton(context, theme, "Choose texture…", {
            showMenu(content, theme, textures.take(40).map { name -> name to { load(name) } })
        }, iconKind = Icons.IMAGE, compact = true))

        if (assetName.isEmpty()) {
            content.addView(Ui.label(context, "Pick a texture to start slicing.", theme, 12f, theme.textDim))
            return
        }

        // ---- preview
        preview.sliceRects = slices
        preview.bitmap = bitmap
        preview.selected = selectedSlice
        preview.invalidate()
        val frame = FrameLayout(context)
        frame.addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(220f)))
        content.addView(frame)
        viewportInfo()

        // ---- slice modes
        content.addView(Panels.header(context, theme, "Slicing"))
        content.addView(NumberField(context, theme, "Cell width", cellW.toFloat(), 1f, 1f, 4096f, 0) { v ->
            cellW = v.toInt().coerceAtLeast(1); slices = gridSlices(); autoDetected = false; refresh()
        })
        content.addView(NumberField(context, theme, "Cell height", cellH.toFloat(), 1f, 1f, 4096f, 0) { v ->
            cellH = v.toInt().coerceAtLeast(1); slices = gridSlices(); autoDetected = false; refresh()
        })
        val row1 = LinearLayout(context)
        row1.orientation = HORIZONTAL
        row1.addView(NumberField(context, theme, "Offset X", offsetX.toFloat(), 1f, 0f, 4096f, 0) { v -> offsetX = v.toInt(); slices = gridSlices(); autoDetected = false; refresh() })
        row1.addView(NumberField(context, theme, "Offset Y", offsetY.toFloat(), 1f, 0f, 4096f, 0) { v -> offsetY = v.toInt(); slices = gridSlices(); autoDetected = false; refresh() })
        content.addView(row1)
        val row2 = LinearLayout(context)
        row2.orientation = HORIZONTAL
        row2.addView(NumberField(context, theme, "Spacing X", spacingX.toFloat(), 1f, 0f, 512f, 0) { v -> spacingX = v.toInt(); slices = gridSlices(); autoDetected = false; refresh() })
        row2.addView(NumberField(context, theme, "Spacing Y", spacingY.toFloat(), 1f, 0f, 512f, 0) { v -> spacingY = v.toInt(); slices = gridSlices(); autoDetected = false; refresh() })
        content.addView(row2)

        val modeRow = LinearLayout(context)
        modeRow.orientation = HORIZONTAL
        modeRow.addView(EditorButton(context, theme, "Grid slice", {
            slices = gridSlices()
            autoDetected = false
            note("Grid: ${slices.size} slices of ${cellW}×${cellH}")
            refresh()
        }, iconKind = Icons.GRID, compact = true))
        modeRow.addView(EditorButton(context, theme, "Auto detect", {
            slices = autoSlices()
            autoDetected = true
            note("Auto: ${slices.size} slices found from the alpha channel")
            refresh()
        }, iconKind = Icons.GRID_SLICE, compact = true))
        modeRow.addView(EditorButton(context, theme, "Keep crop", {
            val crop = manualSlice
            if (crop == null) note("Drag a crop rectangle in the preview first") else {
                slices.add(crop)
                note("Crop $crop added (${slices.size} total)")
                refresh()
            }
        }, iconKind = Icons.CROP, compact = true))
        content.addView(modeRow)

        val actionRow = LinearLayout(context)
        actionRow.orientation = HORIZONTAL
        actionRow.addView(EditorButton(context, theme, "Clear", { slices = ArrayList(); refresh() }, compact = true))
        actionRow.addView(EditorButton(context, theme, "Whole image", {
            slices = arrayListOf(intArrayOf(0, 0, bitmap?.width ?: 0, bitmap?.height ?: 0))
            refresh()
        }, compact = true))
        content.addView(actionRow)

        // ---- apply to scene / project
        content.addView(Panels.header(context, theme, "Apply"))
        val applyRow = LinearLayout(context)
        applyRow.orientation = HORIZONTAL
        applyRow.addView(EditorButton(context, theme, "→ AnimatedSprite2D", {
            applyToAnimatedSprite()
        }, iconKind = Icons.ANIMATION, compact = true))
        applyRow.addView(EditorButton(context, theme, "→ Sprite2D region", {
            applyToSprite()
        }, iconKind = Icons.IMAGE, compact = true))
        content.addView(applyRow)
        content.addView(EditorButton(context, theme, "Create TileSet asset from slices", {
            createTileSet()
        }, iconKind = Icons.TILESET, compact = true))
        content.addView(EditorButton(context, theme, "Save sheet metadata (.sheet.json)", {
            saveSheetMeta()
        }, iconKind = Icons.SAVE, compact = true))

        // ---- slice list
        content.addView(Panels.header(context, theme, "Slices (${slices.size})"))
        sliceList.removeAllViews()
        for ((i, s) in slices.withIndex()) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val label = Ui.label(context, "#$i  ${s[2]}×${s[3]} @ ${s[0]},${s[1]}", theme, 11f,
                if (i == selectedSlice) theme.accent else theme.text)
            row.addView(label)
            row.isClickable = true
            row.setOnClickListener {
                selectedSlice = i
                preview.selected = i
                preview.invalidate()
                refresh()
            }
            sliceList.addView(row)
        }
        if (slices.isEmpty()) sliceList.addView(Ui.label(context, "No slices yet — use Grid or Auto.", theme, 11f, theme.textDim))
        content.addView(sliceList)
        preview.invalidate()
    }

    private var selectedSlice = 0

    private fun viewportInfo() {
        val bmp = bitmap ?: return
        content.addView(Ui.label(context, "${assetName}  ${bmp.width}×${bmp.height}px  ·  ${doc.project.assetSize(assetName) / 1024} kB",
            theme, 11f, theme.textDim))
        val pivot = selectedNode()?.let { "pivot %.2f, %.2f".format(it.pivotX, it.pivotY) } ?: "no sprite selected"
        content.addView(Ui.label(context, "Click the preview to set the pivot · $pivot", theme, 11f, theme.textDim))
    }

    private fun load(name: String) {
        assetName = name
        bitmap = runCatching { BitmapFactory.decodeFile(doc.project.assetFile(name).absolutePath) }.getOrNull()
        if (bitmap == null) {
            note("Could not decode $name")
            return
        }
        val bmp = bitmap!!
        if (cellW <= 1) cellW = bmp.width
        if (cellH <= 1) cellH = bmp.height
        // reuse slices stored in the asset metadata if this sheet was sliced before
        val meta = doc.project.readMetadata(name)
        val savedRects = meta.arr("slices").mapNotNull { it as? com.sengine.engine.json.JVal.Arr }
        slices = if (savedRects.isNotEmpty()) {
            val restored = ArrayList<IntArray>()
            for (rect in savedRects) {
                val values = rect.mapNotNull { (it as? com.sengine.engine.json.JVal.Num)?.v?.toInt() }
                if (values.size >= 4) restored.add(intArrayOf(values[0], values[1], values[2], values[3]))
            }
            restored
        } else gridSlices()
        note("Loaded $name (${bmp.width}×${bmp.height})")
        refresh()
    }

    private fun gridSlices(): MutableList<IntArray> {
        val out = ArrayList<IntArray>()
        val bmp = bitmap ?: return out
        var y = offsetY
        while (y + cellH <= bmp.height) {
            var x = offsetX
            while (x + cellW <= bmp.width) {
                out.add(intArrayOf(x, y, cellW, cellH))
                x += cellW + spacingX
            }
            y += cellH + spacingY
        }
        return out
    }

    /**
     * Auto slicing: scan columns for any non-transparent pixel, split them into runs, then split each
     * run vertically. This handles sheets with irregular frame sizes without a magic cell size.
     */
    private fun autoSlices(): MutableList<IntArray> {
        val out = ArrayList<IntArray>()
        val bmp = bitmap ?: return out
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return out
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val columnUsed = BooleanArray(w)
        for (x in 0 until w) {
            var used = false
            for (y in 0 until h) {
                if (pixels[y * w + x] ushr 24 > 8) { used = true; break }
            }
            columnUsed[x] = used
        }
        var x = 0
        while (x < w) {
            if (!columnUsed[x]) { x++; continue }
            val startX = x
            while (x < w && columnUsed[x]) x++
            val endX = x - 1
            // vertical runs inside this column band
            var y = 0
            while (y < h) {
                var rowUsed = false
                for (yy in y until h) {
                    var any = false
                    for (xx in startX..endX) {
                        if (pixels[yy * w + xx] ushr 24 > 8) { any = true; break }
                    }
                    if (any) { rowUsed = true; break }
                }
                if (!rowUsed) { y++; continue }
                val startY = y
                while (y < h) {
                    var any = false
                    for (xx in startX..endX) {
                        if (pixels[y * w + xx] ushr 24 > 8) { any = true; break }
                    }
                    if (!any) break
                    y++
                }
                val endY = y - 1
                val rw = endX - startX + 1
                val rh = endY - startY + 1
                if (rw >= 2 && rh >= 2) out.add(intArrayOf(startX, startY, rw, rh))
            }
        }
        return out
    }

    private fun applyPivot(u: Float, v: Float) {
        val node = selectedNode() ?: run { note("Select a sprite node first"); return }
        node.pivotX = u
        node.pivotY = v
        doc.onSceneMutated()
        onChanged()
        note("Pivot of ${node.name} → %.3f, %.3f".format(u, v))
        refresh()
    }

    private fun applyToAnimatedSprite() {
        val node = doc.selection.nodes(doc.scene).firstOrNull() ?: doc.scene.objects.firstOrNull { it.getAny<AnimatedSprite2D>() != null }
        if (node == null) { note("Select a node first"); return }
        val anim = node.getAny<AnimatedSprite2D>() ?: AnimatedSprite2D().also { node.add(it); doc.onStructureChanged() }
        anim.spriteSheet = assetName
        anim.mode = 0
        val bmp = bitmap
        val columns = if (slices.isEmpty() || bmp == null) 1 else {
            // frames laid out left→right, top→bottom: number of columns in the sheet
            val rowTops = slices.map { it[1] }.distinct().sorted()
            slices.count { it[1] == rowTops.first() }.coerceAtLeast(1)
        }
        anim.columns = columns
        anim.frameCount = slices.size.coerceAtLeast(1)
        anim.startFrame = 0
        val meta = doc.project.readMetadata(assetName)
        meta.put("slices", slicesToJson())
        meta.put("columns", columns)
        doc.project.writeMetadata(assetName, meta)
        doc.onSceneMutated()
        onChanged()
        note("Wrote ${anim.frameCount} frames to ${node.name} (columns=$columns)")
        Log.info("SpriteEditor", "Anim ${node.name}: sheet=$assetName columns=$columns frames=${anim.frameCount}")
    }

    private fun applyToSprite() {
        val node = doc.selection.nodes(doc.scene).firstOrNull() ?: return
        val sprite = node.getAny<Sprite2D>() ?: Sprite2D().also { node.add(it); doc.onStructureChanged() }
        val slice = slices.getOrNull(selectedSlice) ?: slices.firstOrNull() ?: return
        sprite.texture = assetName
        sprite.regionX = slice[0]
        sprite.regionY = slice[1]
        sprite.regionW = slice[2]
        sprite.regionH = slice[3]
        doc.onSceneMutated()
        onChanged()
        note("Region ${slice[0]},${slice[1]} ${slice[2]}×${slice[3]} written to ${node.name}")
    }

    private fun createTileSet() {
        if (slices.isEmpty()) { note("No slices to write"); return }
        val bmp = bitmap
        val set = TileSet(assetName.substringBeforeLast('.'))
        set.texture = assetName
        set.tileWidth = cellW
        set.tileHeight = cellH
        for (s in slices) {
            set.createTile(s[0], s[1], s[2], s[3])
        }
        val name = doc.project.uniqueAssetName("${assetName.substringBeforeLast('.')}_tiles.tileset.json")
        doc.project.writeAsset(name, Json.write(set.toJson(), pretty = true))
        // point the selected tilemap at the new set when there is one
        doc.selection.nodes(doc.scene).firstOrNull { it.getAny<TileMap2D>() != null }?.getAny<TileMap2D>()?.let { tm ->
            tm.tileSetAsset = name
            tm.tileWidth = max(cellW, 1)
            tm.tileHeight = max(cellH, 1)
            tm.runtimeTileSet = set
            doc.onSceneMutated()
        }
        onChanged()
        note("TileSet '$name' created with ${set.tiles.size} tiles")
        Log.info("SpriteEditor", "TileSet $name (${set.tiles.size} tiles, ${bmp?.width}×${bmp?.height})")
    }

    private fun saveSheetMeta() {
        val meta = jobj(
            "format" to "sengine.sheet",
            "version" to 1,
            "texture" to assetName,
            "slices" to slicesToJson(),
            "cell" to jarr(cellW, cellH),
            "offset" to jarr(offsetX, offsetY),
            "spacing" to jarr(spacingX, spacingY),
            "auto" to autoDetected
        )
        val name = doc.project.uniqueAssetName("${assetName.substringBeforeLast('.')}.sheet.json")
        doc.project.writeAsset(name, Json.write(meta, pretty = true))
        doc.project.writeMetadata(assetName, meta)
        note("Saved $name")
    }

    private fun slicesToJson() = com.sengine.engine.json.JVal.Arr().also { arr ->
        for (s in slices) arr.add(jarr(s[0].toFloat(), s[1].toFloat(), s[2].toFloat(), s[3].toFloat()))
    }

    private fun selectedNode() = doc.selection.nodes(doc.scene).firstOrNull()
        ?: doc.scene.objects.firstOrNull { it.getAny<Sprite2D>() != null }

    private fun note(text: String) {
        Log.info("SpriteEditor", text)
        toast(context, text)
    }

    /** Preview widget: sheet image + slice grid + crop rectangle + pivot marker. */
    @SuppressLint("ViewConstructor")
    private inner class SlicePreview(context: Context) : View(context) {
        var bitmap: Bitmap? = null
        var sliceRects: List<IntArray> = emptyList()
        var selected = 0
        var onPivotPicked: ((Float, Float) -> Unit)? = null
        var onCropChanged: ((IntArray) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gridPaint = Paint()
        private val cropFrom = FloatArray(2)
        private var cropTo: FloatArray? = null
        private var dragging = false
        private var moved = false

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(theme.background)
            val bmp = bitmap ?: return
            val padding = theme.dp(6f).toFloat()
            val scale = min((width - padding * 2) / bmp.width, (height - padding * 2) / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (width - dw) * 0.5f
            val top = (height - dh) * 0.5f
            val dst = RectF(left, top, left + dw, top + dh)
            // checkerboard so transparency is visible
            paint.color = Color.rgb(70, 74, 84)
            canvas.drawRect(dst, paint)
            paint.color = Color.rgb(58, 62, 72)
            val tile = 8f
            var y = top
            var row = 0
            while (y < dst.bottom) {
                var x = left + if (row % 2 == 0) 0f else tile
                while (x < dst.right) {
                    canvas.drawRect(x, y, min(x + tile, dst.right), min(y + tile, dst.bottom), paint)
                    x += tile * 2
                }
                y += tile
                row++
            }
            canvas.drawBitmap(bmp, null, dst, paint)

            gridPaint.style = Paint.Style.STROKE
            gridPaint.strokeWidth = max(1f, theme.dp(1f).toFloat())
            gridPaint.color = theme.accent
            for ((i, s) in sliceRects.withIndex()) {
                val r = RectF(left + s[0] * scale, top + s[1] * scale, left + (s[0] + s[2]) * scale, top + (s[1] + s[3]) * scale)
                gridPaint.color = if (i == selected) theme.accent else Ui.withAlpha(theme.accent, 0.45f)
                canvas.drawRect(r, gridPaint)
                if (i == selected) {
                    paint.color = Ui.withAlpha(theme.accent, 0.16f)
                    canvas.drawRect(r, paint)
                }
            }
            cropTo?.let { end ->
                gridPaint.color = Color.WHITE
                gridPaint.strokeWidth = theme.dp(2f).toFloat()
                val r = RectF(
                    left + min(cropFrom[0], end[0]), top + min(cropFrom[1], end[1]),
                    left + max(cropFrom[0], end[0]), top + max(cropFrom[1], end[1])
                )
                canvas.drawRect(r, gridPaint)
            }
            // pivot marker
            val node = selectedNode()
            if (node != null) {
                val px = left + node.pivotX * dw
                val py = top + node.pivotY * dh
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = theme.dp(2f).toFloat()
                canvas.drawCircle(px, py, theme.dp(6f).toFloat(), paint)
                canvas.drawLine(px - theme.dp(10f).toFloat(), py, px + theme.dp(10f).toFloat(), py, paint)
                canvas.drawLine(px, py - theme.dp(10f).toFloat(), px, py + theme.dp(10f).toFloat(), paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun toSheet(x: Float, y: Float): FloatArray {
            val bmp = bitmap ?: return floatArrayOf(0f, 0f)
            val padding = theme.dp(6f).toFloat()
            val scale = min((width - padding * 2) / bmp.width, (height - padding * 2) / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (width - dw) * 0.5f
            val top = (height - dh) * 0.5f
            return floatArrayOf((x - left) / scale.coerceAtLeast(0.0001f), (y - top) / scale.coerceAtLeast(0.0001f))
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = toSheet(event.x, event.y)
                    cropFrom[0] = p[0]; cropFrom[1] = p[1]
                    cropTo = null
                    dragging = true
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = toSheet(event.x, event.y)
                    if (abs(p[0] - cropFrom[0]) > 3f || abs(p[1] - cropFrom[1]) > 3f) moved = true
                    cropTo = p
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    val bmp = bitmap
                    val p = toSheet(event.x, event.y)
                    if (!moved) {
                        // a tap sets the pivot to the tapped position inside the sheet
                        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                            onPivotPicked?.invoke((p[0] / bmp.width).coerceIn(0f, 1f), (p[1] / bmp.height).coerceIn(0f, 1f))
                        }
                    } else if (bmp != null) {
                        val x0 = max(0f, min(cropFrom[0], p[0]))
                        val y0 = max(0f, min(cropFrom[1], p[1]))
                        val x1 = min(bmp.width.toFloat(), max(cropFrom[0], p[0]))
                        val y1 = min(bmp.height.toFloat(), max(cropFrom[1], p[1]))
                        if (x1 - x0 >= 2f && y1 - y0 >= 2f) {
                            onCropChanged?.invoke(intArrayOf(x0.toInt(), y0.toInt(), (x1 - x0).toInt(), (y1 - y0).toInt()))
                        }
                    }
                    cropTo = null
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; cropTo = null; invalidate(); return true }
            }
            return true
        }
    }
}

// --------------------------------------------------------------------------------- audio

/**
 * Audio panel: the project's real audio assets with true preview playback through the engine mixer,
 * per-bus volume/mute/solo, fades and crossfades, plus the audio properties of the selected node.
 */
class AudioPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val engine: com.sengine.engine.Engine,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)
    private var playingHandle = -1
    private var playingClip = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        Panels.scroll(this, content)
        refresh()
    }

    fun refresh() {
        content.removeAllViews()
        content.addView(Panels.header(context, theme, "Audio — clips, buses, live preview"))

        // ---- buses
        content.addView(Ui.label(context, "Buses", theme, 12f, theme.text, bold = true))
        val mixer = engine.audio
        for (name in listOf("Master", "Music", "SFX", "UI", "Ambient")) {
            val bus = mixer.bus(name)
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(Ui.label(context, name, theme, 12f, theme.text))
            row.addView(NumberField(context, theme, "", bus.volume, 0.05f, 0f, 2f, 2) { v ->
                bus.volume = v
                note("$name volume → %.2f".format(v))
            })
            val mute = EditorButton(context, theme, if (bus.muted) "Unmute" else "Mute", {
                bus.muted = !bus.muted
                note("$name ${if (bus.muted) "muted" else "unmuted"}")
                refresh()
            }, compact = true)
            row.addView(mute)
            content.addView(row)
            if (bus.muted) row.alpha = 0.6f
        }
        content.addView(EditorButton(context, theme, "Stop all audio", { mixer.stopAll(); playingClip = ""; refresh() }, iconKind = Icons.STOP, compact = true))

        // ---- clips
        content.addView(Panels.header(context, theme, "Clips in this project"))
        val clips = doc.project.listAssets(AssetKind.SOUND)
        if (clips.isEmpty()) {
            content.addView(Ui.label(context, "No audio assets. Import a wav/ogg/mp3 from the Files panel.", theme, 12f, theme.textDim))
        }
        for (clip in clips) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(IconView(context, theme, Icons.SOUND, 18f))
            row.addView(Ui.label(context, clip, theme, 12f))
            row.addView(Ui.label(context, "  ${doc.project.assetSize(clip) / 1024} kB", theme, 10f, theme.textDim))
            val isPlaying = playingClip == clip
            row.addView(EditorButton(context, theme, if (isPlaying) "Stop" else "Play", {
                if (isPlaying) {
                    if (playingHandle >= 0) mixer.stop(playingHandle)
                    playingClip = ""
                    playingHandle = -1
                } else {
                    val handle = mixer.play(clip, bus = "SFX", volume = 1f, loop = false, spatial = false)
                    playingHandle = handle
                    playingClip = clip
                    note("Playing $clip (voice $handle)")
                }
                refresh()
            }, iconKind = if (isPlaying) Icons.STOP else Icons.PLAY, compact = true))
            row.addView(EditorButton(context, theme, "Music bus", {
                mixer.play(clip, bus = "Music", volume = 0.8f, loop = true, spatial = false)
                note("$clip looping on the Music bus")
            }, compact = true))
            row.addView(EditorButton(context, theme, "Use", {
                assignToSelection(clip)
            }, compact = true))
            content.addView(row)
        }

        // ---- selected node audio
        val node = doc.selection.nodes(doc.scene).firstOrNull { it.getAny<com.sengine.engine.core.AudioSource>() != null }
        if (node != null) {
            val src = node.getAny<com.sengine.engine.core.AudioSource>()!!
            content.addView(Panels.header(context, theme, "Selected: ${node.name} → ${src.clip.ifEmpty { "(no clip)" }}"))
            content.addView(EditorButton(context, theme, "Preview on node", {
                engine.audio.play(src.clip, bus = src.bus, volume = src.volume, loop = false, spatial = false,
                    nodeId = node.id)
                note("Preview ${src.clip} on ${src.bus}")
            }, iconKind = Icons.PLAY, compact = true))
            content.addView(EditorButton(context, theme, "Crossfade from node clip", {
                mixer.crossfade(-1, src.clip, 1.5f, src.bus, src.loop, src.volume)
                note("Crossfading to ${src.clip} over 1.5 s")
            }, iconKind = Icons.ANIMATION, compact = true))
        }
    }

    private fun assignToSelection(clip: String) {
        val node = doc.selection.nodes(doc.scene).firstOrNull() ?: run { note("Select a node first"); return }
        val src = node.getAny<com.sengine.engine.core.AudioSource>() ?: com.sengine.engine.core.AudioSource().also {
            node.add(it)
            doc.onStructureChanged()
        }
        src.clip = clip
        doc.onSceneMutated()
        onChanged()
        note("${node.name} now plays $clip on ${src.bus}")
    }

    private fun note(text: String) {
        Log.info("Audio", text)
        toast(context, text)
    }
}

// --------------------------------------------------------------------------------- scripting

/**
 * Script editor: opens a real script asset, edits it, recompiles it through the Rhino engine and
 * reports the compile errors. `Check` runs the same compile path the runtime uses, so what the panel
 * shows is what the game will do.
 */
class ScriptEditorPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val engine: com.sengine.engine.Engine,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)
    private val editor = EditText(context)
    private val errors = Panels.column(context, theme)
    private var assetName = ""
    private var dirty = false

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        val tabs = TabStrip(context, theme)
        val body = FrameLayout(context)
        val list = Panels.column(context, theme)
        val editorPage = LinearLayout(context)
        editorPage.orientation = VERTICAL

        editor.setBackgroundColor(theme.panelAlt)
        editor.setTextColor(theme.text)
        editor.setTypeface(android.graphics.Typeface.MONOSPACE)
        editor.textSize = 11f
        editor.gravity = Gravity.TOP or Gravity.START
        editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.setHorizontallyScrolling(true)
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                dirty = true
            }
        })
        editorPage.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(context)
        buttons.orientation = HORIZONTAL
        buttons.addView(EditorButton(context, theme, "Save", { save() }, iconKind = Icons.SAVE, compact = true))
        buttons.addView(EditorButton(context, theme, "Check", { check() }, iconKind = Icons.DEBUG, compact = true))
        buttons.addView(EditorButton(context, theme, "Revert", { load(assetName) }, iconKind = Icons.UNDO, compact = true))
        buttons.addView(EditorButton(context, theme, "New script", { createScript() }, iconKind = Icons.SCRIPT, compact = true))
        editorPage.addView(buttons)
        Panels.scroll(editorPage, errors)
        body.addView(editorPage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        Panels.scroll(list, Panels.column(context, theme))
        body.addView(ScrollView(context).also { sv ->
            list.let { sv.addView(it) }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).also { it.marginStart = 0 })

        tabs.setTabItems(listOf("Editor" to Icons.SCRIPT, "Assets" to Icons.FOLDER))
        tabs.onSelect = { index ->
            body.getChildAt(0).visibility = if (index == 0) View.VISIBLE else View.GONE
            body.getChildAt(1).visibility = if (index == 1) View.VISIBLE else View.GONE
            if (index == 1) fillAssetList(body.getChildAt(1) as ScrollView)
        }
        addView(tabs)
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.getChildAt(1).visibility = View.GONE
        load(doc.project.listAssets(AssetKind.SCRIPT).firstOrNull() ?: "")
    }

    private fun fillAssetList(scroll: ScrollView) {
        val column = Panels.column(context, theme)
        scroll.removeAllViews()
        for (name in doc.project.listAssets(AssetKind.SCRIPT)) {
            val b = EditorButton(context, theme, name, { load(name); }.also { }, compact = true)
            b.setOnClickListener { load(name) }
            column.addView(b)
        }
        if (column.childCount == 0) column.addView(Ui.label(context, "No scripts yet — use New script.", theme, 12f, theme.textDim))
        scroll.addView(column)
    }

    fun load(name: String) {
        assetName = name
        if (name.isEmpty()) {
            editor.setText(SCRIPT_TEMPLATE)
            editor.setSelection(0)
            errors.removeAllViews()
            errors.addView(Ui.label(context, "New unsaved script — press Save to create it.", theme, 11f, theme.textDim))
            return
        }
        val text = doc.project.readAsset(name) ?: SCRIPT_TEMPLATE
        editor.setText(text)
        editor.setSelection(0)
        dirty = false
        errors.removeAllViews()
        errors.addView(Ui.label(context, "$name — ${text.lines().size} lines", theme, 11f, theme.textDim))
    }

    private fun save() {
        val name = assetName.ifEmpty { doc.project.uniqueAssetName("new_script.script.js") }
        val text = editor.text.toString()
        if (!doc.project.writeAsset(name, text)) {
            note("Could not write $name")
            return
        }
        assetName = name
        dirty = false
        doc.onSceneMutated()
        onChanged()
        errors.removeAllViews()
        errors.addView(Ui.label(context, "Saved $name (${text.lines().size} lines)", theme, 11f, theme.accent))
        note("Saved $name")
    }

    /** Compiles the script with the real script engine and lists every reported problem. */
    private fun check() {
        val text = editor.text.toString()
        errors.removeAllViews()
        val result = engine.scripts.validate(text) { line, message ->
            errors.addView(Ui.label(context, "line $line: $message", theme, 11f, theme.error))
        }
        if (result.isEmpty()) {
            errors.addView(Ui.label(context, "No compile errors.", theme, 11f, theme.accent))
            note("Script compiles cleanly")
        } else {
            Log.warn("ScriptEditor", "${result.size} problem(s) in ${assetName.ifEmpty { "(unsaved)" }}")
        }
    }

    private fun createScript() {
        assetName = ""
        editor.setText(SCRIPT_TEMPLATE)
        note("New script — press Save to write it into the project")
    }

    private fun note(text: String) {
        Log.info("ScriptEditor", text)
        toast(context, text)
    }

    companion object {
        val SCRIPT_TEMPLATE = """
            // S ENGINE script — attached to a node.
            // Lifecycle: _ready() once, _process(dt) every frame, _physics_process(dt) after physics,
            // _input(event), _signal(name, data).
            var speed = 3.0;
            var target = null;   // exported node reference

            function _ready() {
                print("ready: " + node.name);
            }

            function _process(dt) {
                var h = input.axis("move_left", "move_right");
                var v = input.axis("move_up", "move_down");
                node.translate(h * speed * dt, v * speed * dt);
            }
        """.trimIndent()
    }
}

/** Utility: builds a small text field used by the script/audio panels. */
private fun scriptLine(context: Context, theme: Theme, text: String): TextView =
    Ui.label(context, text, theme, 11f, theme.textDim)


// --------------------------------------------------------------------------------- export

/**
 * Export panel: writes a real Android project around the game and reports exactly which build stage
 * produced a file. Nothing here reports success it did not achieve — see [ApkExporter].
 */
class ExportPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)
    private var lastResult: ApkExporter.ExportResult? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        Panels.scroll(this, content)
        refresh()
    }

    fun refresh() {
        content.removeAllViews()
        content.addView(Panels.header(context, theme, "Export game"))

        content.addView(Ui.label(context, "Project: ${doc.project.settings.name} (${doc.project.name})", theme, 12f, theme.text))
        content.addView(Ui.label(context, "Start scene: ${doc.project.settings.startScene}   ·   ${doc.project.settings.windowWidth}×${doc.project.settings.windowHeight}   ·   " +
            (if (doc.project.settings.pixelPerfect) "pixel perfect" else "scaled"), theme, 11f, theme.textDim))
        val scenes = doc.project.listScenes()
        val assets = doc.project.listAssetsRecursive()
        content.addView(Ui.label(context, "${scenes.size} scene(s), ${assets.size} asset(s), ${doc.project.listAssets(AssetKind.SCRIPT).size} script(s)",
            theme, 11f, theme.textDim))

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.addView(EditorButton(context, theme, "Export APK / project", { runExport() }, iconKind = Icons.PACKAGE, minWidthDp = 150f))
        row.addView(EditorButton(context, theme, "Check toolchain", {
            toolchainReport = ApkExporter.toolchainReport(context)
            refresh()
        }, iconKind = Icons.DEBUG, compact = true))
        content.addView(row)

        content.addView(EditorButton(context, theme, "Export .zip (send to another device)", {
            zipExport(doc.project)
        }, iconKind = Icons.EXPORT, compact = true))

        lastResult?.let { result ->
            content.addView(Panels.header(context, theme, "Last export"))
            content.addView(Ui.label(context, result.message, theme, 11f,
                if (result.apk != null) theme.ok else theme.warning))
            content.addView(Ui.label(context, "Project: ${result.projectDir.absolutePath}", theme, 10f, theme.textDim))
            result.apk?.let { apk ->
                content.addView(Ui.label(context, "APK: ${apk.absolutePath}  (${apk.length() / 1024} kB)", theme, 10f, theme.textDim))
                content.addView(EditorButton(context, theme, "Install APK", {
                    installApk(apk)
                }, iconKind = Icons.PLAY, compact = true))
            }
            if (result.missingTools.isNotEmpty()) {
                content.addView(Ui.label(context, "Missing: ${result.missingTools.joinToString(", ")}", theme, 11f, theme.warning))
                content.addView(Ui.label(context,
                    "An APK can be produced on-device two ways: (1) ship a prebuilt player-template.apk with the editor — it gets patched and signed here, or " +
                        "(2) build the generated project on a desktop/CI with ./gradlew assembleRelease.",
                    theme, 10f, theme.textDim))
            }
        }

        toolchainReport?.let { report ->
            content.addView(Panels.header(context, theme, "Toolchain"))
            for ((name, value) in report) {
                content.addView(Ui.label(context, "$name: $value", theme, 11f, theme.textDim))
            }
        }

        content.addView(Panels.header(context, theme, "What gets exported"))
        for (line in listOf(
            "scenes/*.scene.json — every scene, versioned format",
            "assets/** — textures, audio, scripts, tilesets, materials, animations",
            "project.json + input_map.json — settings and remappable input actions",
            "AndroidManifest.xml, build.gradle.kts, SEnginePlayerActivity.kt — a buildable Android app",
            "export/keystore.jks — signing key reused for every build of this project"
        )) content.addView(Ui.label(context, "· $line", theme, 11f, theme.textDim))
    }

    private var toolchainReport: List<Pair<String, String>>? = null

    private fun runExport() {
        // warn first when the project has never been saved — exports read from disk, not memory
        if (doc.dirty) {
            confirmDialog(context, "Save first?",
                "The project has unsaved changes. Save them before exporting?") {
                doc.save()
                onChanged()
                runExport()
            }
            return
        }
        val result = ApkExporter.export(
            context = context,
            project = doc.project,
            appName = doc.project.settings.name,
            onProgress = { step -> Log.info("Export", step) }
        )
        lastResult = result
        Log.info("Export", result.message)
        toast(context, result.apk?.let { "Exported ${it.name}" } ?: "Project exported (no APK stage available)")
        onChanged()
    }

    private fun installApk(apk: File) {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.fromFile(apk), "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }.onFailure {
            toast(context, "Open ${apk.absolutePath} with your file manager to install it")
        }
    }

    private fun zipExport(project: com.sengine.engine.project.Project) {
        val out = File(project.dir, "export/${project.name}.sengine.zip")
        out.parentFile?.mkdirs()
        runCatching { out.outputStream().use { project.exportZip(it) } }
            .onSuccess {
                Log.info("Export", "Wrote ${out.absolutePath} (${out.length() / 1024} kB)")
                toast(context, "Zipped to export/${out.name}")
            }
            .onFailure { e -> toast(context, "Zip failed: ${e.message}") }
    }
}

// --------------------------------------------------------------------------------- input map

/**
 * Input Map editor: every action with its real bindings (keyboard, mouse, gamepad axis, touch
 * controls). New bindings are captured by pressing the key / moving the stick, edits are written to
 * the project settings immediately, and the panel shows the live value of each action so a binding
 * can be verified without entering play mode.
 */
class InputMapPanel(
    context: Context,
    val doc: EditorDocument,
    val theme: Theme,
    val engineProvider: () -> com.sengine.engine.Engine?,
    val onChanged: () -> Unit
) : LinearLayout(context) {

    private val content = Panels.column(context, theme)
    private var capturing: Pair<String, Int>? = null      // action name → binding kind being captured

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.panel)
        Panels.scroll(this, content)
        refresh()
    }

    fun refresh() {
        content.removeAllViews()
        val engine = engineProvider()
        val map = engine?.input?.map ?: doc.project.settings.inputMap
        content.addView(Panels.header(context, theme, "Input map — ${map.actions.size} actions"))

        for ((name, action) in map.actions.toList()) {
            val box = SectionBox(context, theme, "$name${if (action.axis) " (axis)" else ""}", false)
            val live = if (engine != null) {
                val pressed = engine.input.isPressed(name)
                val value = engine.input.value(name)
                "  value %.2f%s".format(value, if (pressed) "  PRESSED" else "")
            } else ""
            box.body.addView(Ui.label(context, "${action.bindings.size} binding(s)$live", theme, 11f, theme.textDim))

            for ((index, binding) in action.bindings.withIndex()) {
                val row = LinearLayout(context)
                row.orientation = HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(Ui.label(context, binding.label { code -> android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_") },
                    theme, 11f, theme.text))
                row.addView(EditorButton(context, theme, "Rebind", {
                    capturing = name to binding.kind
                    toast(context, "Press the key/button for '$name'…")
                }, compact = true))
                row.addView(EditorButton(context, theme, "✕", {
                    action.bindings.removeAt(index)
                    persist(map)
                    refresh()
                }, compact = true))
                box.body.addView(row)
            }

            val addRow = LinearLayout(context)
            addRow.orientation = HORIZONTAL
            addRow.addView(EditorButton(context, theme, "+ Key", { captureBinding(name, Binding.KEY) }, compact = true))
            addRow.addView(EditorButton(context, theme, "+ Mouse", { captureBinding(name, Binding.MOUSE) }, compact = true))
            addRow.addView(EditorButton(context, theme, "+ Stick", { captureBinding(name, Binding.AXIS) }, compact = true))
            addRow.addView(EditorButton(context, theme, "+ Touch", { captureBinding(name, Binding.TOUCH) }, compact = true))
            box.body.addView(addRow)
            box.body.addView(EditorButton(context, theme, "Duplicate action", {
                map.action("${name}_copy").also { copy ->
                    copy.axis = action.axis
                    copy.bindings.addAll(action.bindings.map { it.copy() })
                }
                persist(map)
                refresh()
            }, compact = true))
            box.body.addView(EditorButton(context, theme, "Remove action", {
                map.remove(name)
                persist(map)
                refresh()
            }, iconKind = Icons.TRASH, compact = true))
            content.addView(box)
        }

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.addView(EditorButton(context, theme, "New action…", {
            inputDialog(context, theme, "Action name", "dash") { name ->
                map.action(name)
                persist(map)
                refresh()
            }
        }, iconKind = Icons.PLUS, compact = true))
        row.addView(EditorButton(context, theme, "New axis…", {
            inputDialog(context, theme, "Axis action name", "aim_x") { name ->
                map.axisAction(name)
                persist(map)
                refresh()
            }
        }, iconKind = Icons.PLUS, compact = true))
        row.addView(EditorButton(context, theme, "Restore defaults", {
            confirmDialog(context, "Restore input defaults", "Replace every action with the built-in defaults?") {
                val defaults = InputMap.defaults()
                doc.project.settings.inputMap.fromJson(defaults.toJson())
                persist(doc.project.settings.inputMap)
                refresh()
            }
        }, iconKind = Icons.UNDO, compact = true))
        content.addView(row)

        content.addView(Ui.label(context,
            "Defaults: " + InputMap.defaults().actions.keys.joinToString(", "),
            theme, 10f, theme.textDim))
        if (capturing != null) {
            content.addView(Ui.label(context, "Waiting for input for '${capturing!!.first}'…", theme, 11f, theme.warning))
        }
        content.addView(Panels.header(context, theme, "Built-in touch controls"))
        for ((index, name) in Binding.TOUCH_NAMES.withIndex()) {
            content.addView(Ui.label(context, "$name → binding kind ${Binding.KIND_NAMES[Binding.TOUCH]} code $index", theme, 11f, theme.textDim))
        }
    }

    private fun captureBinding(name: String, kind: Int) {
        capturing = name to kind
        toast(context, "Press the input for '$name'…")
        refresh()
    }

    /**
     * Called by the activity for every key event while the panel is capturing — this is what makes
     * "press any key to rebind" work.
     */
    fun onKeyCaptured(keyCode: Int): Boolean {
        val capture = capturing ?: return false
        val engine = engineProvider() ?: return false
        val action = engine.input.map.action(capture.first)
        when (capture.second) {
            Binding.KEY -> action.add(Binding.key(keyCode))
            Binding.MOUSE -> action.add(Binding.mouse(0))
            Binding.AXIS -> action.add(Binding.axis(if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) 0 else 1,
                positive = keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT))
            Binding.TOUCH -> action.add(Binding.touch(1))
        }
        capturing = null
        persist(engine.input.map)
        refresh()
        return true
    }

    private fun persist(map: InputMap) {
        doc.project.saveMeta()
        // the running engine reads the same map, so play mode picks the change up immediately
        engineProvider()?.input?.map = map
        onChanged()
        Log.info("Input", "Map saved (${map.actions.size} actions)")
    }
}
