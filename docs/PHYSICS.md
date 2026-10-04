# Physics 2D

The world steps at a fixed 60 Hz with sub-stepping, sleeping and continuous collision detection for
fast bodies. Everything is 2D: boxes, circles and convex polygons.

## Bodies

| Component | Behaviour |
|---|---|
| `Rigidbody2D` | Dynamic body: mass, gravity scale, friction, restitution, linear/angular drag, `continuous` CCD flag, speed caps, sleep, `grounded`/`onWall`/`onCeiling` flags |
| `CharacterBody2D` | Kinematic mover with `moveAndSlide`, step handling and platform logic |
| `StaticBody2D` | Immovable collision geometry (also used for tile collision) |
| `Area2D` | Trigger volume: detects enter/exit for bodies and other areas |

Shapes come from `Collider2D` (`BOX`, `CIRCLE`, `POLYGON` with a serialized point list) plus optional
material overrides.

## Layers, masks and queries

```js
var hit = physics.raycast(node.x, node.y, 1, 0, 6, 0x0001);
if (hit) console.log(hit.node.name + " at " + hit.x + "," + hit.y);
var near = physics.overlapCircle(node.x, node.y, 2.5);
physics.explode(node.x, node.y, 3, 40);      // radial impulses
```

Kotlin: `PhysicsWorld.raycast(scene, x, y, dx, dy, length, mask)`, `overlapPoint/Circle/Rect`,
`bodyCount`, `contactCount`, `tileColliderCount`.

## Collision callbacks

Scripts receive `_collision(other)`, `_trigger(other)`, `_trigger_exit(other)`; Kotlin systems can
register a `PhysicsWorld.Listener` (`onCollisionEnter/Exit`, `onTriggerEnter/Exit`).

## Continuous collisions

A body with `continuous = true` is swept: the world integrates it in up to `MAX_CCD_STEPS` (16)
sub-steps and stops the motion at the first contact, so a 60 units/s bullet cannot tunnel through a
thin wall. The behaviour is covered by `continuousCollisionStopsFastBodies`.

## Tuning tips

* Keep dynamic bodies between 0.1 and 10 world units; smaller bodies tunnel more easily — enable CCD.
* Use static bodies for level geometry baked from a TileMap (`collision = true` layers), and areas for
  pickups, checkpoints and triggers.
* `Snap settings` in the editor are independent of physics; snapping is an authoring aid.
