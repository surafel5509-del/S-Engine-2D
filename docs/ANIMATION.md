# Animation

Two complementary systems, both editable in the **Anim** panel and both usable from scripts.

## 1. Sprite flip-books (`AnimatedSprite2D`)

* `spriteSheet` + `columns` + `frameCount` describe the frames inside one texture.
* `fps`, `speed`, `loop` (once / loop / ping-pong), `autoplay`, `startFrame`.
* Tinting, flipping, opacity and region sizing work exactly like `Sprite2D`.
* The Sprite panel can slice the sheet for you (grid or auto-detect) and writes the frame count and
  column count straight into the component.

## 2. Keyframe animations (`.anim.json` assets + `AnimationPlayer`)

A clip has a length, a loop mode, a speed and two kinds of content:

* **Tracks** — scalar curves named after conventional channels: `position.x`, `position.y`,
  `rotation`, `scale.x`, `scale.y`, `alpha`, `color.r/g/b`, `visible`, `sprite.frame`, or any custom
  name a script reads.
* **Events** — markers that call a script method, play a sound, or emit a signal when the playhead
  crosses them.

Each keyframe stores time, value and an easing index; 27 easing curves are available (sine, quad,
cubic, quart, expo, back, bounce, elastic, smoothstep, step).

```js
var walk = resources.animation("walk.anim.json");
// AnimationPlayer drives the node automatically; scripts can also read the state:
// player.playing, player.state.time, player.play(true), player.stop()
```

## Timeline editor

The Anim panel shows one lane per track with draggable keys and a playhead:

* Tap a lane to select the track, tap the ruler to scrub — the node previews that exact frame live
  (animation players run in edit mode too).
* Drag a key to retime it (snapped to 0.01 s), drag it onto another key time to overwrite.
* *Add key* inserts a key at the playhead; each key row has an easing cycler and a delete button.
* *Add event* inserts a call-method / play-sound / emit-signal marker at the playhead.
* Length, speed and loop mode are editable, and every change is written to the asset immediately.

## Editor-vs-runtime

`Engine.updateEditorSimulation` advances particles, tile animations, sprite animations and animation
players while editing, which is what makes live preview accurate without pressing Play.
