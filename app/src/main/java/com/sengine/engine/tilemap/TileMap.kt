package com.sengine.engine.tilemap

import com.sengine.engine.json.JVal
import com.sengine.engine.json.jarr
import com.sengine.engine.json.jobj
import com.sengine.engine.math.M

/**
 * A tile inside a [TileSet]: atlas region, collision, tags, random weights and optional
 * flip-book animation. Tiles are addressed by a positive integer id; id 0 always means "empty".
 */
class TileDef(var id: Int = 1) {
    var atlasX = 0
    var atlasY = 0
    var width = 16
    var height = 16

    /** 0 = none, 1 = box, 2 = circle, 3 = polygon (uses [points]). */
    var collision = 0
    var points = FloatArray(0)
    var tags = ""
    var weight = 1f
    var terrainGroup = ""

    /** Animated tiles: frames (x,y) offsets inside the atlas + fps. */
    val frames = ArrayList<IntArray>()
    var fps = 0f

    fun copy() = TileDef(id).also {
        it.atlasX = atlasX; it.atlasY = atlasY; it.width = width; it.height = height
        it.collision = collision; it.points = points.copyOf(); it.tags = tags; it.weight = weight
        it.terrainGroup = terrainGroup; it.fps = fps
        frames.forEach { f -> it.frames.add(f.copyOf()) }
    }

    fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("id", id)
        o.put("region", jarr(atlasX, atlasY, width, height))
        if (collision != 0) o.put("collision", collision)
        if (points.isNotEmpty()) o.put("points", JVal.Arr().also { a -> points.forEach { p -> a.add(JVal.Num(p.toDouble())) } })
        if (tags.isNotEmpty()) o.put("tags", tags)
        if (weight != 1f) o.put("weight", weight)
        if (terrainGroup.isNotEmpty()) o.put("terrain", terrainGroup)
        if (frames.isNotEmpty()) {
            o.put("frames", JVal.Arr().also { a -> frames.forEach { f -> a.add(jarr(f[0], f[1])) } })
            o.put("fps", fps)
        }
        return o
    }

    fun fromJson(o: JVal.Obj) {
        id = o.i("id", id)
        val r = o.floats("region")
        if (r.size >= 4) { atlasX = r[0].toInt(); atlasY = r[1].toInt(); width = r[2].toInt(); height = r[3].toInt() }
        collision = o.i("collision")
        points = o.floats("points").toFloatArray()
        tags = o.str("tags")
        weight = o.f("weight", 1f)
        terrainGroup = o.str("terrain")
        frames.clear()
        o.arr("frames").forEach { f ->
            val a = (f as? JVal.Arr) ?: return@forEach
            frames.add(intArrayOf((a.getOrNull(0) as? JVal.Num)?.v?.toInt() ?: 0, (a.getOrNull(1) as? JVal.Num)?.v?.toInt() ?: 0))
        }
        fps = o.f("fps")
    }
}

/**
 * TileSet: an atlas texture plus tile metadata (collision, terrain, animations).
 * Stored as `<name>.tileset.json` in the project asset folder.
 */
class TileSet(var name: String = "tileset") {
    var texture: String = ""
    var tileWidth = 16
    var tileHeight = 16
    val tiles = LinkedHashMap<Int, TileDef>()
    var nextTileId = 1

    fun tile(id: Int): TileDef? = tiles[id]

    fun createTile(atlasX: Int, atlasY: Int, w: Int = tileWidth, h: Int = tileHeight): TileDef {
        val def = TileDef(nextTileId++)
        def.atlasX = atlasX; def.atlasY = atlasY; def.width = w; def.height = h
        tiles[def.id] = def
        return def
    }

    fun ensureTile(id: Int): TileDef = tiles.getOrPut(id) { TileDef(id).also { nextTileId = maxOf(nextTileId, id + 1) } }

    /** Slice the whole atlas in a regular grid — the fast path for uniform sprite sheets. */
    fun sliceGrid(imageWidth: Int, imageHeight: Int, tileW: Int = tileWidth, tileH: Int = tileHeight): Int {
        tileWidth = tileW; tileHeight = tileH
        var created = 0
        var y = 0
        while (y + tileH <= imageHeight) {
            var x = 0
            while (x + tileW <= imageWidth) {
                if (tiles.values.none { it.atlasX == x && it.atlasY == y }) {
                    createTile(x, y, tileW, tileH); created++
                }
                x += tileW
            }
            y += tileH
        }
        return created
    }

    fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("format", FORMAT)
        o.put("version", 1)
        o.put("name", name)
        o.put("texture", texture)
        o.put("tileSize", jarr(tileWidth, tileHeight))
        o.put("nextTileId", nextTileId)
        val ts = JVal.Arr()
        tiles.values.sortedBy { it.id }.forEach { ts.add(it.toJson()) }
        o.put("tiles", ts)
        return o
    }

    fun fromJson(o: JVal.Obj) {
        name = o.str("name", name)
        texture = o.str("texture")
        val size = o.floats("tileSize")
        if (size.size >= 2) { tileWidth = size[0].toInt(); tileHeight = size[1].toInt() }
        tiles.clear()
        o.objects("tiles").forEach { td ->
            val t = TileDef()
            t.fromJson(td)
            tiles[t.id] = t
        }
        nextTileId = o.i("nextTileId", (tiles.keys.maxOrNull() ?: 0) + 1)
    }

    fun copy(): TileSet {
        val ts = TileSet(name)
        ts.texture = texture; ts.tileWidth = tileWidth; ts.tileHeight = tileHeight; ts.nextTileId = nextTileId
        tiles.forEach { (id, t) -> ts.tiles[id] = t.copy() }
        return ts
    }

    companion object {
        const val FORMAT = "sengine.tileset"
        fun fromJson(text: String) = TileSet().also { it.fromJson(com.sengine.engine.json.Json.parseObject(text)) }
    }
}

/** One painted grid layer. Cells are tile ids; 0 = empty. */
class TileLayer(var name: String = "Layer", var width: Int = 32, var height: Int = 18) {
    var visible = true
    var locked = false
    var opacity = 1f
    var offsetX = 0f
    var offsetY = 0f
    var parallax = 1f
    var collision = true
    var cells = IntArray(width * height)

    fun index(x: Int, y: Int) = y * width + x
    fun inBounds(x: Int, y: Int) = x >= 0 && y >= 0 && x < width && y < height

    operator fun get(x: Int, y: Int): Int = if (inBounds(x, y)) cells[index(x, y)] else 0

    operator fun set(x: Int, y: Int, value: Int) {
        if (inBounds(x, y)) cells[index(x, y)] = value
    }

    fun resize(newWidth: Int, newHeight: Int) {
        val next = IntArray(newWidth * newHeight)
        for (y in 0 until minOf(height, newHeight)) {
            for (x in 0 until minOf(width, newWidth)) next[y * newWidth + x] = cells[y * width + x]
        }
        width = newWidth; height = newHeight; cells = next
    }

    fun fill(value: Int) = java.util.Arrays.fill(cells, value)

    fun copy(): TileLayer {
        val l = TileLayer(name, width, height)
        l.visible = visible; l.locked = locked; l.opacity = opacity
        l.offsetX = offsetX; l.offsetY = offsetY; l.parallax = parallax; l.collision = collision
        l.cells = cells.copyOf()
        return l
    }

    /** Run-length encoded persistence keeps large maps small and diffable. */
    fun toJson(): JVal.Obj {
        val o = JVal.Obj()
        o.put("name", name)
        o.put("size", jarr(width, height))
        if (!visible) o.put("visible", false)
        if (locked) o.put("locked", true)
        if (opacity != 1f) o.put("opacity", opacity)
        if (offsetX != 0f || offsetY != 0f) o.put("offset", jarr(offsetX, offsetY))
        if (parallax != 1f) o.put("parallax", parallax)
        if (!collision) o.put("collision", false)
        o.put("data", encodeRle())
        return o
    }

    private fun encodeRle(): String {
        val sb = StringBuilder()
        var current = cells[0]
        var run = 1
        for (i in 1 until cells.size) {
            if (cells[i] == current) {
                run++
            } else {
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(current).append('*').append(run)
                current = cells[i]; run = 1
            }
        }
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(current).append('*').append(run)
        return sb.toString()
    }

    private fun decodeRle(text: String) {
        if (text.isBlank()) return
        var i = 0
        for (chunk in text.split(',')) {
            val parts = chunk.split('*')
            if (parts.size < 2) continue
            val value = parts[0].trim().toIntOrNull() ?: 0
            val count = parts[1].trim().toIntOrNull() ?: 0
            var n = 0
            while (n < count && i < cells.size) { cells[i++] = value; n++ }
        }
    }

    fun fromJson(o: JVal.Obj) {
        name = o.str("name", name)
        val size = o.floats("size")
        if (size.size >= 2) resize(size[0].toInt(), size[1].toInt())
        visible = o.bool("visible", true)
        locked = o.bool("locked", false)
        opacity = o.f("opacity", 1f)
        val off = o.floats("offset")
        if (off.size >= 2) { offsetX = off[0]; offsetY = off[1] }
        parallax = o.f("parallax", 1f)
        collision = o.bool("collision", true)
        cells = IntArray(width * height)
        decodeRle(o.str("data"))
    }
}

/** Serializable tile map document (layers + reference to the TileSet asset). */
class TileMapData(var tileSetPath: String = "", var tileWidth: Int = 16, var tileHeight: Int = 16) {
    val layers = ArrayList<TileLayer>()

    fun layer(name: String): TileLayer? = layers.firstOrNull { it.name == name }

    fun addLayer(name: String = "Layer ${layers.size + 1}", width: Int = 32, height: Int = 18): TileLayer {
        val l = TileLayer(name, width, height)
        layers.add(l)
        return l
    }

    fun removeLayer(name: String) = layers.removeAll { it.name == name }

    fun copy(): TileMapData {
        val d = TileMapData(tileSetPath, tileWidth, tileHeight)
        layers.forEach { d.layers.add(it.copy()) }
        return d
    }

    fun toJson(): JVal.Obj = jobj(
        "tileSet" to tileSetPath,
        "tileSize" to jarr(tileWidth, tileHeight),
        "layers" to JVal.Arr().also { a -> layers.forEach { a.add(it.toJson()) } }
    )

    fun fromJson(o: JVal.Obj) {
        tileSetPath = o.str("tileSet")
        val size = o.floats("tileSize")
        if (size.size >= 2) { tileWidth = size[0].toInt(); tileHeight = size[1].toInt() }
        layers.clear()
        o.objects("layers").forEach { lo -> layers.add(TileLayer().also { it.fromJson(lo) }) }
        if (layers.isEmpty()) addLayer()
    }

    /** Convert a world point into tile coordinates for [layer]. */
    fun worldToCell(layer: TileLayer, worldX: Float, worldY: Float): Pair<Int, Int> {
        val lx = worldX - layer.offsetX
        val ly = worldY - layer.offsetY
        return Pair(M.floorTo(lx / tileWidth, 1f).toInt(), M.floorTo(ly / tileHeight, 1f).toInt())
    }

    fun cellToWorld(layer: TileLayer, x: Int, y: Int): Pair<Float, Float> =
        Pair(x * tileWidth + layer.offsetX, y * tileHeight + layer.offsetY)
}

/** Painting operations shared by the editor tools and the scripting API. */
object TileBrush {
    enum class Mode { PAINT, ERASE, RECT, LINE, PICK, FILL }

    fun paint(layer: TileLayer, data: TileMapData, x: Int, y: Int, tileId: Int, size: Int = 1) {
        val half = (size - 1) / 2
        for (dy in 0 until size) for (dx in 0 until size) {
            layer[x + dx - half, y + dy - half] = tileId
        }
    }

    fun rect(layer: TileLayer, x0: Int, y0: Int, x1: Int, y1: Int, tileId: Int, filled: Boolean = true) {
        val minX = minOf(x0, x1); val maxX = maxOf(x0, x1)
        val minY = minOf(y0, y1); val maxY = maxOf(y0, y1)
        for (y in minY..maxY) for (x in minX..maxX) {
            if (filled || x == minX || x == maxX || y == minY || y == maxY) layer[x, y] = tileId
        }
    }

    fun line(layer: TileLayer, x0: Int, y0: Int, x1: Int, y1: Int, tileId: Int) {
        var x = x0; var y = y0
        val dx = kotlin.math.abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            layer[x, y] = tileId
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** Flood fill with a bounded scanline algorithm — no recursion, no stack overflow on big maps. */
    fun fill(layer: TileLayer, x: Int, y: Int, tileId: Int) {
        val target = layer[x, y]
        if (target == tileId) return
        val stack = ArrayDeque<IntArray>()
        stack.add(intArrayOf(x, y))
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            var cx = p[0]
            val cy = p[1]
            if (!layer.inBounds(cx, cy) || layer[cx, cy] != target) continue
            while (cx > 0 && layer[cx - 1, cy] == target) cx--
            var spanUp = false
            var spanDown = false
            while (cx < layer.width && layer[cx, cy] == target) {
                layer[cx, cy] = tileId
                if (cy > 0) {
                    val up = layer[cx, cy - 1] == target
                    if (up && !spanUp) { stack.add(intArrayOf(cx, cy - 1)); spanUp = true } else if (!up) spanUp = false
                }
                if (cy < layer.height - 1) {
                    val down = layer[cx, cy + 1] == target
                    if (down && !spanDown) { stack.add(intArrayOf(cx, cy + 1)); spanDown = true } else if (!down) spanDown = false
                }
                cx++
            }
        }
    }

    fun randomTile(set: TileSet, cellX: Int, cellY: Int): Int {
        val candidates = set.tiles.values.filter { it.weight > 0f }
        if (candidates.isEmpty()) return 0
        // deterministic per-cell hash keeps the map stable between runs
        var hash = cellX * 73856093 xor cellY * 19349663
        hash = hash xor (hash ushr 13)
        val total = candidates.sumOf { it.weight.toDouble() }
        var pick = (kotlin.math.abs(hash) % 10000) / 10000.0 * total
        for (c in candidates) {
            pick -= c.weight
            if (pick <= 0.0) return c.id
        }
        return candidates.last().id
    }
}

/**
 * Minimal terrain/auto-tiling: tiles sharing a `terrainGroup` are combined using a 4-bit mask of
 * their neighbours, so a single painted tile can resolve to corner/edge/side variants.
 */
object Terrain {
    fun maskOf(layer: TileLayer, set: TileSet, x: Int, y: Int, group: String): Int {
        fun matches(dx: Int, dy: Int): Boolean {
            val id = layer[x + dx, y + dy]
            val def = set.tile(id) ?: return false
            return def.terrainGroup == group
        }
        var mask = 0
        if (matches(0, -1)) mask = mask or 1   // up
        if (matches(1, 0)) mask = mask or 2    // right
        if (matches(0, 1)) mask = mask or 4    // down
        if (matches(-1, 0)) mask = mask or 8   // left
        return mask
    }

    fun resolve(set: TileSet, group: String, mask: Int): Int? {
        val candidates = set.tiles.values.filter { it.terrainGroup == group }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.tags.contains("mask:$mask") }?.id
            ?: candidates.firstOrNull { it.tags.contains("mask:*") }?.id
            ?: candidates.first().id
    }
}
