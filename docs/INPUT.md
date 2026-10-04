# Input

## The input map

Actions are named, remappable and stored in `project.json` (`Input Map` panel in the editor,
`input_map.json` when exporting). Defaults:

| Action | Default bindings |
|---|---|
| `move_left` / `move_right` / `move_up` / `move_down` | A/D, arrow keys, left stick X/Y, D-Pad |
| `jump` | Space, W/Up, gamepad A, touch button A |
| `attack` | J, mouse left, gamepad X |
| `interact` | E, Enter, touch button B |
| `pause` | Escape, Back, gamepad Start |
| `aim_x` / `aim_y` (axis) | right stick X/Y |

A binding has a *kind* — `key`, `axis`, `mouse`, `touch` — plus a code, an optional `positive` sign,
`scale` and `deadZone` (for sticks).

## Reading input

```js
input.pressed("jump")            // held this frame
input.justPressed("attack")      // went down this frame
input.value("move_left")         // 0..1 (analogue aware)
input.axis("move_left", "move_right")   // -1..1
input.vector("move_left", "move_right", "move_down", "move_up")
input.stickX(); input.stickY()
input.keyDown(66)                // raw Android key code
```

Kotlin callers use `InputSystem`: `isPressed`, `isJustPressed`, `isJustReleased`, `value`, `axis`,
`vector`, `moveX`, `moveY`, `devices` (keyboard state, pointer positions, gamepad axes).

## Rebinding

Open the **Input Map** panel, press *Rebind* on a binding and then press the key, click the mouse, or
move the stick — the capture writes the real code into the project settings and the running engine
reads the same map, so the change applies instantly (also in play mode).

## Touch controls

`TouchControls` (added by `GameActivity` in play mode) draws a virtual stick and action buttons. Its
buttons map to the `touch` binding kind, so a touch button can drive any action without extra code.
