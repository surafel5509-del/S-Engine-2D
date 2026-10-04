# UI system

## Controls

`ControlComponent` gives a node a UI role: `PANEL`, `LABEL`, `BUTTON`, `IMAGE`, `PROGRESS`, `SLIDER`,
`CHECKBOX`, `TEXT_FIELD`, `SCROLL`, `ROW`, `COLUMN`, `GRID`, `CENTER`, `TABS`. Containers lay out
their children; leaf controls draw and interact.

## Anchors, offsets and containers

A control stores anchors (`anchorLeft/Top/Right/Bottom`) and offsets relative to them, plus minimum
size, alignment and margins. `UiLayout.layout(scene, designW, designH)` solves the whole tree for the
current design resolution and writes the resulting pixel rect back into each control — the renderer
and the hit tester read those same numbers, so what you see is what you click.

```kotlin
val panel = scene.createNode("Control", null)
panel.getAny<ControlComponent>()!!.apply {
    ui.controlType = ControlType.PANEL
    ui.anchorLeft = 0.5f; ui.anchorRight = 0.5f
    ui.offsetLeft = -160f; ui.offsetRight = 160f
    ui.offsetTop = 40f; ui.offsetBottom = 120f
}
```

## Editing on canvas

Select the **UI** tool (7) in the viewport:

* Drag the control to move it — the drag writes anchors/offsets, not just the node position.
* Drag the bottom-right handle to resize it.
* UI bounds overlay shows every control's rect; the resolution preview switches the design resolution
  (project default, HD, FHD, phone, tablet…) so the layout can be checked at real sizes.

## Runtime

`UiSystem.update(scene, input, viewportW, viewportH, dt, interactive)` performs layout, hover/press
state, sliders, text field focus and keyboard navigation. Scripts drive it through the `ui` service:

```js
ui.setText("ScoreLabel", "Score: " + store.getNumber("score", 0));
ui.show("GameOverPanel", true);
ui.setChecked("MuteCheck", true);
var w = ui.designWidth();
```

## Stretch modes

`STRETCH` (default) scales the design resolution to the viewport, `FIT` keeps the aspect ratio with a
letterbox, `OFF` uses pixels 1:1. Pick the mode in the project settings; anchors handle the rest.
