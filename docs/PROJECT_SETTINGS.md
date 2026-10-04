# Project settings

`project.json` holds everything the runtime and the editor need to open a game.

```json
{
  "format": "sengine.project", "version": 2, "name": "My Game", "startScene": "Main",
  "orientation": 0,                       // 0 landscape, 1 portrait, 2 auto
  "window": [1280, 720],                  // design resolution
  "theme": "Dark",                        // editor theme (Dark/Light/High contrast)
  "pixelPerfect": false,
  "gravity": [0, -9.8],
  "layers": ["Default", "Foreground", "UI"],
  "input": { "actions": [ { "name": "jump", "bindings": [ { "kind": "key", "code": 32 } ] } ] },
  "audio": { "buses": [ { "name": "Master" }, { "name": "Music", "parent": "Master" } ] },
  "uiDesign": [1280, 720], "uiStretch": 0,
  "locale": "en", "showGrid": true, "gridStep": 0.25, "snapStep": 0.25, "snapEnabled": true,
  "custom": { "store.highScore": "1200" }
}
```

| Field | Meaning |
|---|---|
| `startScene` | Scene loaded by the player and by Run in the editor |
| `orientation` | Locks the exported player's orientation; the editor follows the device |
| `window` | Design resolution: the reference frame for physics units, camera zoom and the canvas overlay |
| `gravity` | World gravity in units/s² (physics reads this unless a body overrides its scale) |
| `pixelPerfect` | Snaps the camera's visible rect to whole pixels (pixel art) |
| `input` | The input map (see [INPUT.md](INPUT.md)) |
| `audio` | Bus tree (see [AUDIO.md](AUDIO.md)) |
| `uiDesign` / `uiStretch` | UI layout reference resolution and stretch mode |
| `locale` | Editor/runtime language (`en`, `am`, `ar`) |
| `custom` | Free-form storage, also written by `store.*` script calls |

Settings are edited from *Project → Project Settings…* (name, start scene, resolution, orientation,
theme, pixel perfect, gravity, UI design, locale) and from *Editor → Editor settings* (UI scale,
theme, high contrast, autosave).

Exports read these values: the generated Android manifest uses `orientation`, the export metadata
carries `window` and `pixelPerfect`, and the player boots `startScene`.
