# Tilemaps

## TileSet assets

`assets/maps/*.tileset.json`:

```json
{ "format": "sengine.tileset", "version": 1, "name": "dungeon", "texture": "dungeon.png",
  "tileSize": [16, 16], "nextTileId": 4,
  "tiles": [ { "id": 1, "atlas": [0, 0], "size": [16, 16], "collision": 1, "terrain": "wall",
               "tags": "mask:5", "weight": 1, "animation": { "frames": [1, 2], "fps": 6 } } ] }
```

Per-tile data includes the atlas region, collision shape id, terrain group + mask tag, random weight,
animation frames, pivot and free-form metadata.

Create tilesets from the **Sprite** panel (slice a sheet → *Create TileSet asset from slices*) or from
the Files panel (*＋ → TileSet*).

## Maps and layers

`TileMap2D` holds ordered layers; each layer has a name, size, visibility, lock, opacity, scroll
offset, parallax, per-layer collision flag and a run-length encoded cell array (so a large map stays
small and diffable).

The **Tiles** panel manages layers (add, rename, reorder, hide, lock, opacity, parallax, collision,
resize, fill, clear, delete) and the brush.

## Brushes

| Mode | Behaviour |
|---|---|
| Paint | Square brush of `brushSize` cells, optional random tile variants |
| Erase | Same brush, writes 0 |
| Fill | Bounded scanline flood fill |
| Line | Bresenham line previewed live while dragging |
| Rect | Filled or outline rectangle previewed live |
| Picker | Copies the clicked tile into the brush |

With **Autotile** enabled, painted cells resolve their terrain variants using a 4-bit neighbour mask
(`mask:0` … `mask:15`, or `mask:*` for a fallback tile).

## Painting in the viewport

Choose the **Tile** tool (5), pick a tile from the palette and paint in the viewport. The brush follows
the active layer, snapping is respected, and a whole stroke (including fill and rect operations)
becomes one undo step.

## Collision

Layers flagged `collision` contribute static geometry to the physics world: each painted cell with a
tile definition that has a collision shape becomes a body, so a platformer can stand on the painted
floor without extra colliders.
