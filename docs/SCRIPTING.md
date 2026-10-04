# Scripting

Gameplay scripts are plain JavaScript (Rhino, ES6 syntax accepted) stored as assets and attached to a
node with a **ScriptComponent**. Each script gets a per-node scope: `node` (the owning node), the
service objects below, and the exported `k = v` properties for the editor.

## Lifecycle

```js
var speed = 3.0;          // exported: shows up in the Inspector and can be tuned per node

function _ready()            { }   // node is active for the first time
function _process(dt)        { }   // every frame (alias: _update / update)
function _physics_process(dt){ }   // after each physics step
function _input(event)       { }   // key/pointer events
function _collision(other)   { }   // first contact with another body
function _trigger(other)     { }   // entering an Area2D
function _trigger_exit(other){ }
function _tap()              { }   // touch on this node's bounds
function _signal(name, data) { }   // a node signal fired
function _destroy()          { }
```

Errors are reported with file + line to the Output log and disable only that instance; the rest of the
game keeps running.

## Services

| Object | Members |
|---|---|
| `node` | `id`, `name`, `tag`, `type`, `active`, `visible`, `layer`, `order`, `x`, `y`, `rotation`, `scaleX`, `scaleY`, `pivotX`, `pivotY`, `worldX`, `worldY`, `setPosition`, `setWorldPosition`, `move`, `moveWorld`, `rotate`, `lookAt`, `setTexture`, `setSpriteSheet`, `setText`, `getText`, `setColor`, `setAlpha`, `flipX`, `vx/vy`, `isGrounded`, `isOnWall`, `isOnCeiling`, `setVelocity`, `addForce`, `overlaps`, `distanceTo` |
| `input` | `pressed/justPressed/released(action)`, `value(action)`, `axis(neg, pos)`, `vector(nx, px, ny, py)`, `stickX()`, `stickY()`, `keyDown(code)`, `actionList()` |
| `scene` | `find(name)`, `findPath("Player/Weapon")`, `findInGroup(g)`, `findAll(tag)`, `count(tag)`, `nodes()`, `spawn(name, x, y)`, `instantiate(prefab)`, `load(sceneName)`, `reload()` |
| `physics` | `raycast(x, y, dx, dy, length, mask)`, `overlapPoint/Circle/Rect`, `explode(x, y, r, strength)`, `bodyCount()`, `contacts()`, `setGravity`, `setTimeScale` |
| `audio` | `play(clip[, volume])`, `ui(clip)`, `music(clip[, volume])`, `ambient(clip)`, `fadeIn(clip, s)`, `crossfade(handle, clip, s)`, `stopBus(bus)`, `setVolume(bus, v)`, `mute(bus, b)`, `master(v)`, `addBus(name)` |
| `ui` | `find(name)`, `setText(name, v)`, `setValue/getValue(name)`, `setChecked/isChecked(name)`, `show(name, bool)`, `focus(name)`, `designWidth()`, `designHeight()` |
| `resources` | `animation(name)`, `animationNames()`, `textureNames()`, `soundNames()`, `exists(name)`, `size(name)`, `listAssets(kind)` |
| `store` | `set(key, value)`, `get`, `getString`, `getNumber`, `getBool`, `remove`, `clear` — saved into the project settings |
| `time` | `getTime()`, `getFrame()`, `getFps()`, `getDelta()` |
| `console` | `log(msg)`, `warn(msg)`, `error(msg)`, `clear()` |
| globals | `print(msg)` |

## Signals

Any node can `emit("hit", 3)`; a listener script receives it in `_signal(name, data)`, and the editor
can wire node-to-node connections from the Inspector (*Signals* section) which are stored in the
scene file.

## Example: platformer controller

```js
var speed = 6.0;
var jump = 11.0;

function _process(dt) {
    var h = input.axis("move_left", "move_right");
    node.setVx(h * speed);
    if (input.justPressed("jump") && node.isGrounded()) node.setVy(jump);
}

function _physics_process(dt) {
    if (node.isOnWall() != 0) node.vx = 0;
}

function _collision(other) {
    if (other.tag == "Coin") { audio.play("coin.wav"); other.active = false; store.set("score", store.getNumber("score", 0) + 1); }
}
```

## Letting scripts see editor changes

* The script editor's **Check** button compiles the buffer through the same Rhino path the runtime
  uses, so a green check means the script parses; errors show the line number.
* *Debug → Reload scripts* clears the compile cache and re-attaches instances without restarting the
  editor session.
