# Using the S ENGINE editor

This is the tour of the editor as it exists in the code today. Everything described here is wired to
real engine state — there are no mock panels.

## Window chrome

```
S ENGINE  Scene  Project  Editor  Debug  Search                 ▣ Save ↺ Undo ↻ Redo   ▶ Run
[ tool strip: ▣ select ✥ move ↻ rotate ⤢ scale ◉ pivot ▭ rect ▦ tile ⬚ ui │ grid snap colliders
  cameras guides tile-grid pixel-grid physics-debug ui-bounds audio-areas │ focus frame 100% 1280×720 ]
┌────────────────┬──────────────────────────────────┬─────────────────────┐
│ Scene / Files  │           2D viewport            │ Inspector / Script  │
│ Sprite / Anim  │                                  │ Input / Particles   │
│ Tiles          │                                  │ Shaders / Audio     │
│                ├──────────────────────────────────┤ Export              │
│                │ Output / Debugger / Profiler      │                     │
└────────────────┴──────────────────────────────────┴─────────────────────┘
status: scene * · 3 selected · zoom 100% · x 1.25 y -0.50 · snap 0.25
```

* **Landscape** uses three docks around the viewport with draggable splitters (the layout is saved
  with *Editor → Save workspace layout*).
* **Portrait** keeps the viewport on top and puts every panel in one tab strip underneath, so
  nothing is crushed into an unreadable column. Rotating the device rebuilds the docks.
* Every tool button has an icon, a tooltip and a real toggled state.

## Viewport

| Gesture | Result |
|---|---|
| Tap a node | Select it (children win over parents, locked/hidden nodes are skipped) |
| Tap empty space | Deselect (Shift/Ctrl keeps the selection) |
| Drag empty space | Pan; with **Move/Rotate/Scale/Pivot** the drag edits the selection |
| Two fingers | Pinch-zoom around the pinch point + pan |
| Mouse wheel | Zoom around the cursor (the point under the cursor stays put) |
| Right click / long press | Node context menu (rename, show/hide, lock, focus, delete, add node) |
| Arrow keys | Nudge by snap-step/4 (Shift: a full snap step) |

* **Zoom** is a real readout (`zoom 100%` = design pixels map 1:1). Tap the percentage to reset,
  `+`/`-` to step, `0` to reset, `F` to focus the selection.
* **Rectangle tool** drags a marquee and selects everything it touches.
* **Tile tool** paints on the active TileMap layer using the palette from the TileMap panel:
  paint, erase, fill, line, rect (filled or outline), random variants, picker and autotile.
* **UI tool** drags a control to move it and its bottom-right handle to resize it; the change is
  written into the control's anchors and offsets, i.e. it is a normal layout edit.
* Overlays: grid, snapping, colliders, cameras, guides, tile grid, pixel grid, physics debug,
  UI bounds, audio areas, plus the design-resolution frame.
* A whole gesture is **one undo step** (moves, rotations, scales, pivots, UI edits, tile strokes).

## Docks and panels

| Panel | What it does |
|---|---|
| **Scene** | Hierarchy with search, multi-select, visibility/lock toggles, context menu (rename, duplicate, delete, reparent, add component, save prefab) |
| **Files** | Asset browser: breadcrumbs, folders (create/rename/delete/move), search, kind filters, sort by name/date/kind/size, favourites and recents (stored in asset metadata), thumbnails, create scene/script/shader/material/tileset/animation/particle/text, import via the system file picker, rename/duplicate/delete/reimport, drag-to-assign |
| **Inspector** | Data-driven properties for the selection: floats with scrubbing, ints, bools, enums, colours, vectors, rects, assets, node references, polygons; collapsible sections, property search, reset, copy/paste, multi-object edit |
| **Sprite** | Slicing editor (grid with offset/spacing, **auto detect** from the alpha channel, manual crop), slice list, pivot picker on the sheet, apply to `AnimatedSprite2D` / `Sprite2D` region, create a real `TileSet` asset, save `.sheet.json` |
| **Anim** | Flip-book timeline for `AnimatedSprite2D` (fps, columns, frames, loop mode, play/pause) plus the keyframe editor for `.anim.json` assets: track lanes, draggable keys, playhead scrubbing that previews the node live, easing per key, event markers |
| **Tiles** | TileMap layers (add/rename/reorder/visible/lock/opacity/parallax/collision/resize/delete), brush modes and size, random + autotile toggles, and a real palette drawn from the tileset texture |
| **Script** | Script asset list plus an editor with line/char feedback, **Check** (compiles through the same Rhino path the runtime uses and lists errors with line numbers), Save, Revert, New |
| **Input Map** | Every action with its bindings (keyboard, mouse, gamepad axis, touch), rebind capture ("press the key…"), add/remove actions and bindings, restore defaults, live pressed/value readout |
| **Particles** | 8 presets, emission/motion/colour/shape parameters, live particle count, burst, restart, save/load `.particle.json` presets |
| **Shaders** | Material picker, 8 templates (outline, glow, dissolve, grayscale, pixelate, wave, distortion, flash), uniform editing and live compile errors routed to the Output log |
| **Audio** | Real clips from the project, preview playback through the mixer, buses (Master/Music/SFX/UI/Ambient) with volume/mute, looping on the Music bus, crossfade, assign clip to the selected node |
| **Export** | See below |
| **Output / Debugger / Profiler** | Log with levels and filters; debugger (scene tree, variables, input state, physics); profiler with real measurements (FPS, frame/update/physics/render/script/audio ms, draw calls, batches, sprites, texts, culled items, nodes, particles, memory) |

## Command palette and shortcuts

`Ctrl+Shift+P` opens the palette: create node, open scene, save, run, stop, focus selection, toggle
grid, toggle snap, reset zoom, open every panel, export, undo/redo, duplicate, delete, save layout.

| Keys | Action |
|---|---|
| `Ctrl+S` | Save scene (atomic write, keeps a backup) |
| `Ctrl+Z` / `Ctrl+Shift+Z` / `Ctrl+Y` | Undo / redo |
| `Ctrl+D` | Duplicate selection |
| `Ctrl+G` | Toggle grid |
| `F5` / `F6` | Play / stop |
| `Q W E R T Y` | Select, move, rotate, scale, pivot (tools declare their own shortcut) |
| `5` `6` `7` | Tile, rectangle, UI tool |
| `F` | Focus selection, `0` reset zoom, `+`/`-` zoom |
| `Del` | Delete selection |
| `Esc` | Stop play mode / cancel the current tool |

## Export

*Export → Export APK / project* runs [ApkExporter](../app/src/main/java/com/sengine/engine/export/ApkExporter.kt)
and reports which stage produced a file:

1. **Android project** (always): `export/<Game>/` with `settings.gradle.kts`, `app/build.gradle.kts`,
   `AndroidManifest.xml` (orientation from the project settings), `SEnginePlayerActivity.kt`, the
   game data in `app/src/main/assets/game` (scenes, assets, `project.json`, `input_map.json`) and a
   reusable signing keystore at `export/keystore.jks`.
2. **Patched template APK**: if a prebuilt player APK is bundled with the editor
   (`assets/player-template.apk`), the game data is injected into it with `java.util.zip` and the
   result is signed with the project keystore (JAR/v1 signing, implemented in the exporter with the
   JCA). This needs no toolchain at all.
3. **Gradle build**: when a `gradle` and a JDK are present on the device (Termux, a Chromebook, a
   desktop), the generated project is built and the real APK is copied next to it.

A stock phone has no `aapt2`/`d8`/`apksigner`, so the light of the *Check toolchain* button shows
exactly what is missing; the panel never pretends an APK exists when it does not. `Export .zip`
produces the portable project bundle.
