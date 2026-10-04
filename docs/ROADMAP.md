# S ENGINE — status & roadmap

This is the honest state of the engine after the core rewrite: what works today, what is weak,
and the order in which the remaining work should happen. Every claim below is backed by code in
this repository and by the headless test suite (`app/src/test/java/com/sengine/EngineTest.kt`).

## How to verify a change

```bash
# fast inner loop (needs a local Kotlin 1.9.23 + JDK 17, see tools/localtest.sh header)
tools/localtest.sh --fast     # engine + platform + UI type-check, then the JUnit suite
tools/localtest.sh            # same, with the full source set

# authoritative build (same as CI)
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

CI (`.github/workflows/android.yml`) runs both on every push, uploads the debug APK and publishes
a rolling `sengine-latest` pre-release.

## Working today

| Area | State |
|---|---|
| Scene graph | Nodes, hierarchy, world transforms (cached `Affine`), pivot, z-order, groups/tags, metadata, signals, duplication, reparenting |
| Serialization | `sengine.scene` v2 with a migration chain (flat transforms, renamed props, old component names), unknown components preserved, atomic saves + rotating backups |
| Components | Data-driven `Prop` system (float/int/bool/string/text/colour/enum/asset/vector/rect/node ref/polygon/info) feeding inspector, serialization, prefabs, undo; registry-driven node creation |
| Rendering | GLES2 batched sprite/text/shape renderer, layers, camera with follow/limits/pixel-perfect, nine-slice, clipping (scissor), tint/alpha/blend, pixel snapping, render stats |
| Physics 2D | Fixed step (60 Hz, sub-stepping, sleeping), boxes/circles/polygons with rotated world points, layers/masks, triggers/areas, raycasts, overlap queries, continuous collisions (CCD) for fast bodies, debug draw |
| Animation | Timeline + keyframes + easing (27 curves) + events, sprite flip-books (once/loop/ping-pong), runtime player |
| Tilemaps | TileSet with slicing/animation/collision/terrain metadata, multi-layer maps with RLE cells, paint/erase/fill/rect/line/pick/random brushes, tile collision feeding the physics world |
| Particles | Spec-driven emitters, 8 presets (fire, smoke, dust, rain, snow, sparks, magic, explosion), bursts, lifetime curves |
| Audio | Mixer with buses (Master/Music/SFX/UI/Ambient), volume/pitch/loop, fades and crossfades, 2D attenuation, SoundPool backend + headless null backend |
| Input | Input map with remappable bindings (keyboard/mouse/gamepad/touch), rebind capture, action/axis API, virtual stick |
| UI system | Control nodes (label, button, panel, image, progress, slider, checkbox, text field, scroll, row/column/grid/centre/tabs), anchors + offsets + containers, runtime layout and interaction |
| Scripting | Rhino host with per-node scopes, lifecycle hooks, exported `k=v` properties, JS API (SObject/SScene/SInput/STime/SAudio/SUi/SPhysics/SResources/SStore/SConsole), error reporting that disables only the failing instance |
| Editor shell | Icon toolbars with tooltips and toggled overlay states, tabbed docks (Scene, Files, Sprite, Anim, Tiles, Inspector, Script, Input Map, Particles, Shaders, Audio, Export, Output/Debugger/Profiler) with draggable splitters and saved layouts, landscape three-dock layout / portrait tab stack, live status bar (cursor position, zoom %, selection, snap), command palette, autosave + crash recovery |
| Projects | Create/import/open/duplicate/rename/delete, recent list, thumbnails, 7 templates (Empty 2D, Platformer, Top Down, Shooter, Puzzle, Pixel Art, UI), ZIP import/export with zip-slip protection |
| Export | In-editor export: writes a complete Android Gradle project (manifest, build files, player Activity, keystore) with the game data, patches a bundled player APK when present, signs it with an in-app JCA/JAR signer, or hands the project to a real Gradle install; otherwise it reports exactly which toolchain step is missing. Also ZIP export |, profiler with real timings (frame/update/physics/render/script/audio), render stats, remote inspector snapshots (JSON + tree text), debugger model (variables, input, physics, scene stats, problems) |
| Plugins | Plugin API (custom components, panels, importers, commands, inspector widgets, settings sections) with manifest template and per-project loading |
| Localization | English, Amharic, Arabic built in, language switch in the project manager |
| Sprite editor | Texture picker with thumbnails, grid slicing (cell size/offset/spacing), **auto slicing from the alpha channel**, manual crop, click-to-set pivot with a live pivot marker, slice list, writes frames to `AnimatedSprite2D`, regions to `Sprite2D`, real `TileSet` assets and `.sheet.json` metadata |: transforms, hierarchy, serialization + migration, resources, input, physics (rest, sleep, layers, triggers, queries, CCD), animation, tilemaps, particles, UI layout, projects, atomic saves, undo/redo, prefabs, remote inspector |

## Weak areas (audited, in priority order)

1. **Physics broad phase is O(n²).** Every pair is tested each step. Fine for the hundreds of
   bodies typical of a 2D level; a bullet hell or a big platformer will hurt. → uniform grid or
   sweep-and-prune keyed on the existing cached AABBs.
2. **No joints or constraints.** Springs, pins, ropes, hinges and motors are missing; gameplay code
   has to fake them with forces.
3. **Single-point contacts.** The solver resolves one point per pair, so stacked boxes can jitter
   and slowly drift. → two-point manifolds for box-on-box.
4. **Renderer gaps.** No render targets/offscreen passes, clipping is axis-aligned scissor only,
   text goes through a CPU canvas atlas, particles are CPU quads (no GPU instancing), and there is
   no sprite-shape mask or per-object shader cache warm-up.
5. **Audio limitations.** SoundPool caps stream count and cannot stream long music tracks; no
   filters/reverb; pitch shifting is platform dependent.
6. **Scripting performance.** Rhino runs interpreted (no persisted bytecode cache) and each node
   gets its own scope. Long scenes with hundreds of scripts pay for it.
7. **Sprite editor is thin on advanced tools.** Grid/auto/manual slicing, pivot picking and frame
   writing exist; there is still no per-pixel painting, onion skinning or per-slice 9-patch editing.
8. **Localization coverage.** Only ~20 keys are translated; most editor strings are still English
   literals, and Arabic has no RTL mirroring of the docks.
9. **Accessibility is partial.** UI scale, high contrast and tooltips exist; there is no full
   keyboard-only path through the editor, no focus ring audit and no font-size scaling of panel
   text beyond the global scale.
10. **On-device APK building is limited by the platform.** A stock Android device has no
    `aapt2`/`d8`/`apksigner`, so the in-editor export writes a complete, buildable Android project
    and can only produce a signed APK on-device when a prebuilt player template APK is bundled with
    the editor (`assets/player-template.apk`) or when Gradle/JDK are present. Everything else
    (project layout, data packaging, keystore, v1 signing, template patching) is implemented and
    tested; shipping the template APK is a build-pipeline task for CI.
11. **Test coverage** stops at the engine. No instrumented/UI tests, no golden-image tests for the
    renderer, no soak test for long sessions, no fuzz test for malformed scene files.
12. **Tooling.** No lint/static analysis in CI, no APK smoke test on an emulator, no performance
    regression gate.

## Phase plan

| Phase | Scope | Status |
|---|---|---|
| **P1** Architecture cleanup, editor foundation, splash, project manager, scene tree, inspector, viewport, asset browser | modules split, data-driven core, editor shell, icon toolbars, tabbed docks, viewport tools with gizmos and undo, sprite editor, browser with folders/favourites/thumbnails, command palette, templates | **done** |
| **P2** Serialization, resources, undo/redo, input, physics, audio | versioned format + migrations, cached resource manager, command-based undo, input map, fixed-step physics with layers/triggers/raycasts/CCD, mixer with buses/fades | **done** (joints, spatial hash open) |
| **P3** Animation, tilemap, particles, UI editor, shaders | keyframe timeline with draggable keys/events/easing + sprite flip-books, tilemap layers/brushes/terrain autotile + visual palette, particle presets as real assets, UI controls laid out in the editor and edited on-canvas (move/resize handles), 8 shader templates with live compile feedback | **done** (polygon terrain polish open) |
| **P4** Debugger, profiler, remote inspector, command palette, advanced tools | log levels, real profiler timings and meters, render stats, JSON snapshots + scene-tree dump, debugger tabs, input map editor with live values, palette, script editor with real compile checking | **mostly done** (breakpoint stepping, memory timeline open) |
| **P5** Plugins, templates, export, docs, performance | plugin API + registry, 7 templates, ZIP export, Android project export + signing + template patching, this documentation set | **partial** — shipping the player template APK and the performance passes remain |

## Next milestones (concrete)

1. **Player template APK** (P5): have CI build the minimal player APK and drop it into
   `app/src/main/assets/player-template.apk`, which turns the existing on-device patch+sign path
   into a one-tap "Export APK".
2. **Physics hardening** (P2): uniform-grid broad phase, two-point box manifolds, joints
   (pin/spring/rope), and a stress test with 2 000 bodies to prove the budget.
3. **Renderer passes** (P2/P3): render targets, rotated/rounded clip rects, instanced particles,
   text layout cache trimming, and a golden-image test harness.
4. **Export polish** (P5): per-game launcher icons, version/versionCode editing, run the generated
   project through CI, and add APK signature verification (v2/v3 signing block) on top of the v1
   signer.
5. **Localization + accessibility** (P4/P5): extract every visible editor string through `Loc`,
   add RTL mirroring, keyboard-only navigation and a contrast audit.
6. **Performance pass** (P5): remove remaining per-frame allocations (render items are pooled and
   physics manifolds recycled, but text and particle paths still allocate), add budget warnings to
   the profiler, and record regression numbers in CI.
7. **Tooling** (P5): detekt/lint in CI, an emulator smoke test that boots the app and opens a
   template, and a soak test that runs a scene for thousands of frames.

## Non-goals

3D anything: no 3D renderer, viewport, models, meshes, glTF/GLB, 3D physics, 3D cameras or 3D
scene format. S ENGINE is a 2D engine, and this stays true on every branch.
