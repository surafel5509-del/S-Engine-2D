# S ENGINE — architecture

S ENGINE is a 2D game engine plus a visual editor that runs on Android (Kotlin, GLES2). It is
2D-only by design: there is no 3D renderer, no meshes, no 3D camera. Everything is built from
modules with one job each, so the same systems power the editor and the running game.

## Layers

```
app/src/main/java/com/sengine
├── engine/          pure Kotlin runtime — no Android imports (unit-testable on the JVM)
│   ├── core/        Prop, Component, GameObject, Scene, signals, builtin components
│   ├── json/        own JSON reader/writer (JVal) used by every file format
│   ├── math/        Vec2, Rect2, Affine (2D matrix), Easing, helpers (M)
│   ├── render/      render list, render stats, camera view, platform render interface
│   ├── physics/     fixed-step 2D world: boxes/circles/polygons, layers, raycasts, areas
│   ├── animation/   Animation/Keyframe/LoopMode/SpriteFrames, AnimationPlayer component
│   ├── tilemap/     TileSet, TileLayer (RLE), TileMapData, TileBrush, terrain
│   ├── particles/   ParticleSpec, ParticleSystem, ParticlePresets
│   ├── audio/       AudioMixer with buses, fades, spatial attenuation
│   ├── input/       InputMap/Binding/InputDevices/InputSystem
│   ├── ui/          ControlComponent, UiProps, UiLayout, UiSystem (runtime UI)
│   ├── resources/   ResourceManager (cached assets), Material, shader templates
│   ├── serialization/SceneFormat (versioned + migrations)
│   ├── script/      Rhino JS host: ScriptSystem, Api (SObject/SScene/SInput/…)
│   ├── project/     Project, ProjectSettings, ProjectManager, Templates
│   ├── editor/      EditorDocument, undo commands, EditorState, command palette
│   ├── debug/       Log, Profiler, RemoteInspector, DebuggerModel
│   └── plugins/     plugin API + registry
├── platform/        the only place that talks to the device
│   ├── gl/          GLES2 renderer (GLRenderer2D, GL2 helpers)
│   └── android/     textures, text rasterizer, audio backend
└── ui/              editor shell (Activities, panels, theme, widgets, input)
```

`engine/` never imports `android.*`. That is what makes the headless test suite possible: the same
scene, physics, animation, tilemap and serialization code runs in JUnit on a plain JVM.

## Data flow

```
             ┌───────────── editor (ui/) ─────────────┐
             │ panels edit  ── EditorDocument ── undo   │
             │                 │                        │
             │            Scene (core/)                 │
             └─────────────────┬───────────────────────┘
                               ▼
   Engine.tick(dt):  transforms → camera → input → scripts → physics → animation
                     → particles → UI layout → audio mix
                               ▼
        buildRenderList(view, overlay)  →  RenderList (+ RenderStats)
                               ▼
        GLRenderer2D.frame(list, view)  →  GLES2 (batched)  →  screen
```

* **Authoring** mutates `Scene` through `EditorDocument` commands, so every change is undoable and
  can be saved (atomically, with rotating backups).
* **Playing** runs the same `Scene` object; `Engine.play()` snapshots nothing — it just switches
  mode, resets runtime state and starts ticking. Stopping restores the authored state.
* **Rendering** is data-driven: the engine fills a `RenderList`, sorts it for batching, and the
  platform renderer uploads one interleaved vertex buffer per frame. The editor view and the game
  view are the same renderer with different cameras and overlays.

## Key conventions

* `Prop` drives everything: inspector widgets, scene/prefab serialization, copy/paste, undo and
  script access. Adding a property to a component's `props()` adds it to all of them at once.
* Component types are registered in `ComponentRegistry`; the node creation menu, the "Add
  component" menu and the loader all read that registry — including plugin components.
* File formats are versioned and human readable: `project.json`, `*.scene.json` (`sengine.scene`
  v2), `*.tileset.json`, `*.animation.json`, `*.material.json`, `*.particle.json`, prefabs.
  `SceneFormat.MIGRATIONS` upgrades older documents on load (flat transforms, renamed properties,
  old component names) and unknown components are preserved instead of dropped.
* Units: 1 world unit = `pixelsPerUnit` pixels of the source texture (default 100, 16 for pixel
  art). UI uses design pixels with anchors so it scales to any screen.
* The editor never blocks the render thread: engine work is posted into the engine's command queue
  and executed inside `tick`.

## Platform boundary

`platform/gl` and `platform/android` implement three small interfaces:

| interface | implementations |
|---|---|
| `TextureSource` | `AndroidTextures` (BitmapFactory + GL upload, nearest filtering for pixel art) |
| `TextRasterizer` | `AndroidTextRenderer` (canvas glyph atlas, 9 floats per glyph) |
| `AudioBackend` | `AndroidAudio` (SoundPool), `NullAudioBackend` (headless/tests) |

This is why the JVM tests can run the whole engine: the null/headless implementations are used when
no Android is present.

## Where to look first

| I want to… | read |
|---|---|
| add a component | `core/BuiltinComponents.kt`, `core/Prop.kt` |
| change how scenes are saved | `serialization/SceneFormat.kt` |
| touch physics | `physics/PhysicsWorld.kt` (+ `PolygonUtil.kt`) |
| add an editor panel | `ui/EditorPanels.kt`, `ui/EditorActivity.kt` |
| extend the JS API | `script/Api.kt`, `script/ScriptSystem.kt` |
| understand the renderer | `render/RenderList.kt`, `platform/gl/GLRenderer2D.kt` |
