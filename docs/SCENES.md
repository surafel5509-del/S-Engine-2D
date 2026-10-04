# Scenes, nodes and serialization

## Node model

A `GameObject` owns a name, type, tag, layer, z-order, a local transform (position, rotation, scale),
a pivot and a list of components. Parents compose into a cached world `Affine`, so moving a parent
moves its children without touching their local values.

```kotlin
val player = scene.createNode("Sprite2D", parent)
player.setPosition(2f, 1f)
player.pivotX = 0.5f; player.pivotY = 0f
player.getAny<Sprite2D>()?.texture = "hero.png"
```

Editor node types come from the component registry (`Scene → Add node…`): Sprite2D, AnimatedSprite2D,
Label2D, TileMap2D, ParticleEmitter2D, Camera2D, Control, Light2D, AudioStream2D, Rigidbody2D,
CharacterBody2D, StaticBody2D, Area2D, Marker, Group, Timer, AnimationPlayer, Script, and so on —
each one ships with a working component and inspector properties.

## File format

Scenes are JSON with a format tag and a version:

```json
{ "format": "sengine.scene", "version": 2, "name": "Main", "nextId": 12,
  "settings": { "backgroundColor": "#1B2533", "gravity": [0, -9.8] },
  "nodes": [ { "id": 3, "name": "Player", "type": "Sprite2D", "x": 0, "y": 0,
               "components": [ { "type": "Sprite2D", "texture": "hero.png" } ] } ] }
```

* `SceneFormat.read` runs the migration chain (flat transforms → nested, renamed properties, old
  component names) so old projects keep opening.
* Components with an unknown `type` are preserved verbatim as `UnknownComponent`, so a scene never
  loses data just because a plugin is missing.
* Writes are atomic: the previous file is kept in `.backup/`, the new one is written to a temp file
  and renamed, so a crash mid-save can never corrupt a scene. `EditorDocument.autosave()` writes to
  `.recovery/` every 30 s; the editor offers to restore it when a session ends uncleanly.

## Prefabs

*Save as prefab* on any node writes `assets/prefabs/<name>.json`; instantiating it re-creates the whole
subtree with fresh ids (mapping preserved parent/child relationships and signals inside the prefab).

## Working with scenes in the editor

* **Scene menu**: new, open, save, save as, duplicate, delete, set as start scene.
* **Scene tree**: search, multi-select, visibility/lock, rename (F2 / context menu), duplicate
  (Ctrl+D), delete, reparent by drag or context menu, add component, save prefab.
* Every edit goes through the undo stack, including structural ones.
