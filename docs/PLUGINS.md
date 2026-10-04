# Plugins

Plugins extend the engine and the editor without touching the core. They live in
`assets/plugins/<name>/plugin.json` (template written by `PluginManager.createTemplate`) and are
loaded per project.

```json
{
  "name": "Inventory",
  "version": "1.0",
  "enabled": true,
  "components": [ { "type": "InventoryComponent", "category": "Gameplay", "description": "Item slots" } ],
  "panels": [ { "title": "Inventory", "icon": 57 } ],
  "commands": [ "Give item…" ],
  "importers": [ ".csv" ],
  "settings": [ "Inventory size" ]
}
```

## Extension points

| Point | What you register |
|---|---|
| Custom components | A `Component` subclass registered with `ComponentRegistry.register(type, category, description, factory)`; it automatically gets inspector properties (through `props()`), serialization, prefab support and undo |
| Custom node types | `ComponentRegistry` entries appear in *Scene → Add node…* and in the scene tree's create menu |
| Editor panels | `EditorPanel` implementations attach to a dock; the plugin manager hands them the document and the theme |
| Importers | Associate a file extension with an import action (metadata, thumbnails, slicing) |
| Commands | Named actions merged into the command palette (Ctrl+Shift+P) |
| Inspector widgets | Provide a custom editor for a `Prop` type |
| Project settings sections | Extra settings groups rendered in the project settings dialog |
| Resources | Register loaders for custom asset kinds so the resource cache manages them |

## Lifecycle

```kotlin
val plugins = PluginManager(project)
plugins.loadAll()                 // reads assets/plugins/*/plugin.json, applies the registry
plugins.createTemplate("Inventory")
plugins.setEnabled("Inventory", false)
```

Disabled plugins are skipped, unknown components in scenes are preserved as `UnknownComponent`, and
plugin code runs inside the same JVM as the editor — the API is deliberately small so a plugin cannot
break the core invariants.
