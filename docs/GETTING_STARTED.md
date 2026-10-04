# Getting started

## 1. Install and launch

Build the debug APK (`./gradlew assembleDebug`) and install `app/build/outputs/apk/debug/app-debug.apk`
on an Android 8.0+ device, or grab `SEngine.apk` from the rolling `sengine-latest` release.

The app opens with the S Engine splash, then the **start screen**:

```
S ENGINE  ·  2D GAME ENGINE
[ CREATE PROJECT ]   [ IMPORT PROJECT ]
RECENT PROJECTS · Open Scene · Examples · Documentation · Settings
```

## 2. Create a project

`CREATE PROJECT` asks for:

| Field | Notes |
|---|---|
| Project name | Used as the folder name under the app's project storage |
| Location | Where the folder is created |
| Window size | Design resolution (e.g. 1280×720, 480×270 for pixel art) |
| Orientation | Landscape, portrait or auto |
| Theme | Dark or light editor theme |
| Pixel perfect | Snap the camera to whole pixels (crisp pixel art) |
| Template | Empty 2D, Platformer, Top Down, Shooter, Puzzle, Pixel Art, UI |

Templates are real projects: they ship scenes, assets and scripts, and every one of them runs.

## 3. First scene

Opening a project lands in the editor with a scene tree on the left, the 2D viewport in the middle
and the inspector on the right.

* **Add a node** — Scene panel ▸ `＋` ▸ pick a node type (Sprite2D, AnimatedSprite2D, Label2D,
  Camera2D, TileMap, Particles2D, CollisionShape2D, Area2D, RayCast2D, AudioStream2D, Control…).
* **Assign art** — select the node, in the inspector tap the `Texture` row and pick a PNG from the
  asset browser (it refreshes thumbnails as you scroll).
* **Move/rotate/scale** — pick the tool in the toolbar (or `W`/`E`/`R`) and drag in the viewport.
  Numeric fields scrub when you drag them horizontally.
* **Save** — `Ctrl+S` (or the Scene menu). Saves are atomic; the previous revision goes to
  `.backup/`, so a bad save never costs you the scene.

## 4. Run it

Press `▶ Run` (or `F5`). `Run` switches the engine into play mode **inside the editor** so you can
watch and inspect it; `Stop` (`F6`) returns to editing exactly the scene you had.

`Debug ▸ Copy remote snapshot` copies a JSON dump (nodes, transforms, physics, profiler) for bug
reports, and the Debugger/Profiler panels show live numbers while the game runs.

## 5. First script

1. File system ▸ `assets/scripts` ▸ **New script** — a documented template is created.
2. Select a node, `＋ Add component` ▸ **Script**, assign your `.js` file.
3. Edit in the built-in script editor, then `Debug ▸ Reload scripts` to hot-reload without stopping
   the game.

```js
var speed = 6;          // exposed in the inspector as "Params: speed=6"

function start() {
  log("ready at", self.x, self.y);
}

function update(dt) {
  var mx = input.axis("move_left", "move_right");
  self.x += mx * speed * dt;
  if (mx !== 0) self.flipX = mx < 0;
}

function physics_update(dt) {
  if (input.justPressed("jump") && self.grounded) self.vy = 11;
}

function onCollision(other) {
  if (other.tag === "Pickup") { other.destroy(); audio.play("coin"); }
}
```

See [SCRIPTING.md](SCRIPTING.md) for the whole API.

## 6. Next steps

* [Editor guide](EDITOR.md) — panels, tools, shortcuts, workspaces.
* [Physics 2D](PHYSICS.md) — colliders, layers, one-way platforms, raycasts.
* [Animation](ANIMATION.md) and [Tilemaps](TILEMAP.md) — flip-books, sheets, layers, terrain.
* [Roadmap](ROADMAP.md) — what is finished and what is coming next.
