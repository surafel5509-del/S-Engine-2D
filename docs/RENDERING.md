# Rendering

GLES2, forward 2D sprite batching, one draw call per texture + blend state change.

## Pipeline

1. `Engine.buildRenderList(view, overlay, editingView)` walks visible nodes, culls against the camera
   bounds, and fills a pooled `RenderList` with sprites, texts, shapes, lines and particle quads.
2. `GLRenderer2D.frame(list, view)` sorts for batching (layer → z-order → texture), uploads the batch
   buffers and draws, applying scissor clipping, blend mode and material shaders per run.
3. Stats (draw calls, batches, sprites, texts, shapes, lines, vertices, culled/clipped items) are fed
   to the profiler and shown in the Profiler panel.

## Features

| Area | Support |
|---|---|
| Sprites | Tint, opacity, flip, nine-slice, region (atlas) slicing, pixel snap, nearest/linear filtering |
| Animation | Sheet flip-books and keyframe-driven properties (`AnimationPlayer`) |
| Layers | Named render layers, per-camera layer masks, z-order and per-node z-sorting |
| Camera | Follow target, smoothing, limits, zoom, pixel-perfect rounding (`snappedBounds()`), background colour |
| UI | Screen-space controls drawn after the world, stretched or contained per `stretchMode` |
| Text | CPU-rasterized glyph atlas with a font cache; sizes in world or screen units |
| Particles | CPU-updated quads with colour/size over lifetime, additive or alpha blending |
| Shaders | Per-material fragment shaders with uniform values; compile errors go to the Output log and the Shader panel |
| Clipping | Axis-aligned scissor rects (containers, UI clipping) |
| Pixel art | Nearest filtering, integer camera scaling, optional pixel grid overlay, `Pixel Perfect` project setting |

## Pixel-art workflow

1. Import the texture, then slice it in the **Sprite** panel (grid or auto-detect).
2. Set `Pixels / Unit` on the sprite (e.g. 16) so a 16×16 tile is one world unit.
3. Enable *Pixel Perfect* in the project settings — the camera snaps the visible rect to whole pixels.
4. Turn on the pixel grid overlay while placing tiles.

## Materials and shaders

A `.material.json` asset holds the fragment source and uniform values. Templates: outline, glow,
dissolve, grayscale, pixelate, wave, distortion, flash. The renderer asks the project for a material
by name (`materialSource`), compiles it once, caches the program and reports errors through
`onShaderError`.
