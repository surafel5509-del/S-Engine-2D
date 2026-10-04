# S ENGINE — 2D game engine & visual editor

S ENGINE is a complete 2D game engine with a built-in visual editor, written in Kotlin and running
natively on Android (GLES2). It is 2D-only by design: sprite rendering, 2D physics, tilemaps,
particle effects, a UI system, JavaScript gameplay scripting and a docked editor you can use on a
phone, tablet or Chromebook — with mouse, touch or keyboard.

```
S ENGINE | Scene  Project  Editor  Debug  Search            ▶ Run   ▣ Grid   ✥ Snap
┌────────────┬─────────────────────────────────────┬──────────────────┐
│ Scene Tree │            2D Viewport              │    Inspector     │
│ FileSystem │   pan · zoom · grid · guides        │  properties      │
│ Animation  ├─────────────────────────────────────┤  signals         │
│ TileMap    │   Output · Debugger · Profiler      │  particles       │
└────────────┴─────────────────────────────────────┴──────────────────┘
```

## Features

**Engine**
* Scene graph with hierarchy, world transforms, pivots, z-order, groups/tags, metadata and signals
* Data-driven components (`Prop` system): adding one property adds inspector UI, serialization,
  prefab support, undo and copy/paste at the same time
* Versioned, human-readable file formats with migrations — old projects load, unknown data survives
* Batched GLES2 sprite renderer: sprite sheets, nine-slice, tint/alpha, blend modes, layers,
  pixel-perfect camera, grid snapping, clipping, render stats
* 2D physics: dynamic/kinematic/static/character bodies, boxes/circles/polygons (rotated),
  layers & masks, triggers/areas, raycasts, overlap queries, continuous collisions, sleeping,
  debug draw
* Animation timelines with easing and events, sprite flip-books, tilesets with layers/terrain,
  particle emitters with 8 presets, audio mixer with buses/fades/2D attenuation
* Rhino JavaScript scripting with per-node scopes, lifecycle hooks, exported properties and a rich
  API (`self`, `input`, `scene`, `audio`, `ui`, `physics`, `resources`, `store`, `time`, `console`)

**Editor**
* Splash → project manager (create/import/duplicate/rename/delete, recent list, thumbnails) → editor
* Icon toolbars with real toggle states and tooltips; tabbed docks with draggable splitters and saved
  layouts — Scene, Files, Sprite, Anim, Tiles, Inspector, Script, Input Map, Particles, Shaders,
  Audio, Export, Output/Debugger/Profiler; three-dock landscape layout and a portrait tab stack
* Viewport: zoom-to-cursor with a live percentage, pan, pinch, marquee select, move/rotate/scale/pivot
  gizmos, tile painting (paint/erase/fill/line/rect/picker/random/autotile), UI move+resize handles,
  node context menus, arrow-key nudging, overlays for grid, snapping, colliders, cameras, guides,
  tile grid, pixel grid, physics debug, UI bounds, audio areas — every gesture is one undo step
* **Sprite editor**: grid slicing (cell/offset/spacing), auto slicing from the alpha channel, manual
  crop, click-to-set pivot, apply frames to `AnimatedSprite2D`, regions to `Sprite2D`, build `TileSet`
  assets, save sheet metadata
* **Animation timeline**: track lanes, draggable keys, scrubbing that previews the node live, easing
  per key, event markers, flip-book preview
* **Asset browser**: folders (create/rename/delete/move), search, kind filters, sort, favourites,
  recents, thumbnails, create scene/script/shader/material/tileset/animation/particle/text, import,
  rename/duplicate/reimport/delete
* **Script editor** with real compile checking (line-numbered errors from the runtime compiler),
  **Input Map editor** with press-to-rebind capture and live values, **Audio panel** with bus mixing
  and previews, **Particle editor** with save/load presets, **Shader panel** with 8 templates
* **Export**: writes a complete Android Gradle project with the game data and a signing keystore;
  patches a bundled player APK when present (with in-app JAR signing); hands the project to Gradle
  when the device has one; otherwise reports exactly which toolchain step is missing
* Command palette (`Ctrl+Shift+P`), keyboard shortcuts, undo/redo for every edit, autosave,
  crash recovery and atomic saves with rotating backups
* Templates: Empty 2D, Platformer, Top Down, Shooter, Puzzle, Pixel Art, UI
* Debugger with real measurements, remote inspector snapshots, error/warning/output views
* Localization (English, Amharic, Arabic), UI scaling, high contrast, tooltips
* Plugin API: custom components, panels, importers, commands, inspector widgets, settings sections

**Quality**
* 28 headless tests covering transforms, hierarchy, serialization/migration, resources, input,
  physics (including CCD), animation, tilemaps (brushes, terrain masks, tile-stroke undo), particles,
  UI layout, projects, folder operations, asset metadata, undo/redo, prefabs, the remote inspector
  and Android project export
* CI builds the APK and runs the test suite on every push

## Requirements

* Android 8.0+ (API 26), GLES2 — phones, tablets, TV, Chromebooks
* To build: JDK 17 and the Android SDK (compileSdk 34)

## Build

```bash
# debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew --no-daemon assembleDebug

# unit tests (headless engine)
./gradlew --no-daemon testDebugUnitTest

# everything CI does, in one go
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

On a workstation with a Kotlin 1.9.23 compiler and an `android.jar`, `tools/localtest.sh` runs the
same compilation and the test suite without Gradle (see the script header for the expected layout).
CI publishes a rolling `sengine-latest` pre-release with `SEngine.apk` on every push.

## Project layout

A project is a folder — easy to diff, back up and keep in Git:

```
MyGame/
├── project.json            settings: window, orientation, gravity, layers, input map, audio buses
├── scenes/
│   └── Main.scene.json     nodes, transforms, components, signals   (sengine.scene v2)
├── assets/
│   ├── art/…               PNG/JPG/WebP textures and sheets
│   ├── maps/*.tileset.json tile sets (slicing, collision, terrain)
│   ├── anim/*.animation.json
│   ├── fx/*.particle.json
│   ├── fx/*.material.json  fragment shaders and uniforms
│   ├── sfx/…, music/…      audio
│   ├── scripts/*.js        gameplay scripts
│   ├── prefabs/*.json      reusable node trees
│   └── .meta/              import metadata, thumbnails
└── .backup/                rotating scene backups; .recovery/ holds autosaves
```

## Documentation

| Document | Contents |
|---|---|
| [Getting started](docs/GETTING_STARTED.md) | First project, first scene, first script |
| [Editor guide](docs/EDITOR.md) | Panels, tools, shortcuts, workflows |
| [Architecture](docs/ARCHITECTURE.md) | Modules, data flow, conventions, where to change what |
| [Scripting](docs/SCRIPTING.md) | JavaScript API reference and examples |
| [Scenes & serialization](docs/SCENES.md) | Node model, file format, migrations, prefabs |
| [Rendering](docs/RENDERING.md) | Sprite pipeline, materials, pixel art, stats |
| [Physics 2D](docs/PHYSICS.md) | Bodies, shapes, layers, queries, tuning |
| [Animation](docs/ANIMATION.md) | Timelines, keyframes, sprite animation, events |
| [Tilemaps](docs/TILEMAP.md) | Tile sets, layers, brushes, collision, terrain |
| [UI system](docs/UI.md) | Control nodes, anchors, containers, runtime input |
| [Audio](docs/AUDIO.md) | Buses, music, SFX, spatial sound, fades |
| [Input](docs/INPUT.md) | Actions, bindings, rebinding, touch and gamepads |
| [Project settings](docs/PROJECT_SETTINGS.md) | Everything in `project.json` |
| [Plugins](docs/PLUGINS.md) | Extending the engine and the editor |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common problems and what to do |
| [Roadmap & status](docs/ROADMAP.md) | What works, weak areas, phase plan |

## License

MIT — see the repository for details. S ENGINE and its logo are original to this project.
