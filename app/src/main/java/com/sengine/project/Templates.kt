package com.sengine.project

import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer

object Templates {
    class Template(val name: String, val description: String, val build: (Project) -> Unit)

    val all: List<Template> by lazy { listOf(empty, platformer, shooter, physics) }

    const val NEW_SCRIPT = """// S Engine behaviour script (JavaScript)
// Globals: self/transform, input, time, scene, audio, log(), after(), every()

function start() {
    log("Hello from " + self.name);
}

function update(dt) {
    // transform.rotation += 90 * dt;
}

// function onCollision(other) {}
// function onTrigger(other) {}
// function onTap() {}
"""

    // ---------------------------------------------------------------- helpers
    private fun obj(s: Scene, name: String, x: Float, y: Float, sx: Float = 1f, sy: Float = 1f, parent: GameObject? = null): GameObject {
        val go = s.create(name, parent)
        go.x = x; go.y = y; go.scaleX = sx; go.scaleY = sy
        return go
    }

    private fun GameObject.sprite(color: Long, shape: Int = 0): GameObject {
        add(SpriteRenderer().also { it.color = color.toInt(); it.shape = shape }); return this
    }

    private fun GameObject.box(trigger: Boolean = false): GameObject {
        add(Collider2D().also { it.isTrigger = trigger }); return this
    }

    private fun GameObject.circleCol(trigger: Boolean = false): GameObject {
        add(Collider2D().also { it.shape = 1; it.isTrigger = trigger }); return this
    }

    private fun GameObject.body(type: Int = 0, friction: Float = 0.4f, bounce: Float = 0f, gravity: Float = 1f): GameObject {
        add(Rigidbody2D().also { it.bodyType = type; it.friction = friction; it.bounciness = bounce; it.gravityScale = gravity }); return this
    }

    private fun GameObject.script(name: String, params: String = ""): GameObject {
        add(ScriptComponent().also { it.script = name; it.params = params }); return this
    }

    private fun GameObject.text(t: String, size: Float, color: Long = 0xFFFFFFFF): GameObject {
        add(TextRenderer().also { it.text = t; it.size = size; it.color = color.toInt(); it.bold = true }); return this
    }

    private fun camera(s: Scene, size: Float, bg: Long, follow: String = ""): GameObject {
        val c = obj(s, "Main Camera", 0f, 0f)
        c.add(Camera2D().also { it.size = size; it.background = bg.toInt(); it.follow = follow })
        return c
    }

    // ---------------------------------------------------------------- empty
    private val empty = Template("Empty 2D", "A camera and a square. Start from scratch.") { p ->
        val s = Scene("Main")
        camera(s, 5f, 0xFF1B2533)
        obj(s, "Square", 0f, 0f).sprite(0xFF4FC3F7)
        p.writeAsset("NewBehaviour.js", NEW_SCRIPT)
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- platformer
    private val platformer = Template("Platformer Demo", "Run, jump and collect coins. Joystick + A button.") { p ->
        p.writeAsset("Player.js", """// Player controller
// Move: joystick / A-D keys.  Jump: button A / Space.
// Params (set in inspector): speed, jump
var coins = 0;

function start() {
    log("Collect all the coins!");
}

function update(dt) {
    self.vx = input.axisX * speed;
    if (input.aDown && self.grounded) {
        self.vy = jump;
        audio.beep();
    }
    if (input.axisX < -0.1) self.flipX = true;
    else if (input.axisX > 0.1) self.flipX = false;

    // fell off the world
    if (self.y < -12) scene.reload();
}

function onTrigger(other) {
    if (other.tag == "Coin") {
        coins++;
        var fx = scene.spawn("CoinFX", other.worldX, other.worldY);
        if (fx) {
            fx.burst(24);
            after(1.5, function () { fx.destroy(); });
        }
        other.destroy();
        var label = scene.find("ScoreText");
        if (scene.count("Coin") == 0) label.text = "You win!";
        else label.text = "Coins: " + coins;
    }
}
""")
        p.writeAsset("Coin.js", """// Makes a coin bob and spin
var baseY;
function start() { baseY = transform.y; }
function update(dt) {
    transform.y = baseY + Math.sin(time.time * 3 + transform.x) * 0.15;
    transform.scaleX = 0.1 + 0.4 * Math.abs(Math.cos(time.time * 2.5 + transform.x));
}
""")
        p.writeAsset("MovingPlatform.js", """// Moves back and forth. Params: range, speed
var startX;
function start() { startX = transform.x; }
function update(dt) {
    self.vx = Math.cos(time.time * speed) * range * speed;
}
""")
        val s = Scene("Main")
        val cam = camera(s, 6f, 0xFF6EC6FF, follow = "Player")
        obj(s, "ScoreText", 0f, 5f, parent = cam).text("Coins: 0", 0.7f).also { it.order = 100 }
        obj(s, "Ground", 0f, -3f, 30f, 1f).sprite(0xFF4E7D3A).box()
        obj(s, "Platform A", 4f, 0f, 4f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Platform B", -5f, 1.2f, 3f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Platform C", 10f, 2.5f, 3f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Moving Platform", 16f, 1f, 3f, 0.5f).sprite(0xFFB0BEC5).box().body(type = 1)
            .script("MovingPlatform.js", "range=2, speed=1")
        obj(s, "Wall L", -15.5f, 0f, 1f, 8f).sprite(0xFF4E7D3A).box()
        val player = obj(s, "Player", 0f, -1.5f, 0.8f, 0.8f).sprite(0xFFFF7043).box()
            .body(friction = 0f).script("Player.js", "speed=6, jump=11")
        player.tag = "Player"; player.order = 10
        val coinPos = listOf(4f to 1.2f, -5f to 2.4f, 10f to 3.7f, 7f to -1.8f, -9f to -1.8f, 16f to 2.3f, 20f to -1.8f)
        for ((x, y) in coinPos) {
            val c = obj(s, "Coin", x, y, 0.5f, 0.5f).sprite(0xFFFFD54F, 1).circleCol(true).script("Coin.js")
            c.tag = "Coin"; c.order = 5
        }
        val fx = obj(s, "CoinFX", 0f, 0f)
        fx.active = false
        fx.add(ParticleEmitter().also {
            it.emitting = false; it.rate = 0f; it.spread = 360f; it.speed = 4f; it.gravity = -6f
            it.lifetime = 0.7f; it.startColor = 0xFFFFF176.toInt(); it.endColor = 0x00FFA000
        })
        fx.order = 20
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- shooter
    private val shooter = Template("Space Shooter", "Top-down shooter with spawning, triggers and score.") { p ->
        p.writeAsset("Ship.js", """// Player ship. Params: speed
var cooldown = 0;
function update(dt) {
    self.vx = input.axisX * speed;
    self.vy = input.axisY * speed;
    transform.x = clamp(transform.x, -12, 12);
    transform.y = clamp(transform.y, -7, 7);
    cooldown -= dt;
    if ((input.a || input.touching) && cooldown <= 0) {
        cooldown = 0.18;
        scene.spawn("Bullet", self.worldX, self.worldY + 0.6);
    }
}
function onTrigger(other) {
    if (other.tag == "Enemy") {
        var fx = scene.spawn("Explosion", self.worldX, self.worldY);
        if (fx) fx.burst(60);
        self.active = false;
        scene.find("Game").send("gameOver");
    }
}
""")
        p.writeAsset("Bullet.js", """function update(dt) {
    if (transform.y > 10) self.destroy();
}
""")
        p.writeAsset("Enemy.js", """function start() {
    self.vy = -random(2, 4.5);
}
function update(dt) {
    transform.rotation += 90 * dt;
    if (transform.y < -10) self.destroy();
}
function onTrigger(other) {
    if (other.tag == "Bullet") {
        var fx = scene.spawn("Explosion", self.worldX, self.worldY);
        if (fx) { fx.burst(30); after(1, function () { fx.destroy(); }); }
        other.destroy();
        self.destroy();
        audio.beep();
        scene.find("Game").send("addScore", 10);
    }
}
""")
        p.writeAsset("Star.js", """function start() { self.vy = 0; speedY = random(0.5, 3); }
var speedY = 1;
function update(dt) {
    transform.y -= speedY * dt;
    if (transform.y < -9) { transform.y = 9; transform.x = random(-15, 15); }
}
""")
        p.writeAsset("Game.js", """// Game manager: spawns enemies and tracks the score
var score = 0;
var over = false;
function start() {
    for (var i = 0; i < 40; i++) scene.spawn("Star", random(-15, 15), random(-9, 9));
    every(0.8, function () {
        if (!over) scene.spawn("Enemy", random(-11, 11), 10);
    });
}
function addScore(n) {
    score += n;
    scene.find("ScoreText").text = "Score: " + score;
}
function gameOver() {
    over = true;
    scene.find("ScoreText").text = "Game Over  -  Score: " + score;
    after(2.5, function () { scene.reload(); });
}
""")
        val s = Scene("Main")
        s.gravityY = 0f
        val cam = camera(s, 8f, 0xFF0B1026)
        obj(s, "ScoreText", 0f, 7f, parent = cam).text("Score: 0", 0.8f).also { it.order = 100 }
        obj(s, "Game", 0f, 0f).script("Game.js")
        val ship = obj(s, "Ship", 0f, -5f, 1f, 1.2f).sprite(0xFF4FC3F7, 2).box(true).body(type = 1)
            .script("Ship.js", "speed=9")
        ship.tag = "Player"; ship.order = 10
        val flame = obj(s, "Engine Flame", 0f, -0.5f, 1f, 1f, parent = ship)
        flame.add(ParticleEmitter().also {
            it.direction = -90f; it.spread = 20f; it.speed = 4f; it.rate = 60f; it.lifetime = 0.35f
            it.startSize = 0.3f; it.startColor = 0xFF80DEEA.toInt(); it.endColor = 0x000277BD
        })
        val bullet = obj(s, "Bullet", 0f, 0f, 0.15f, 0.5f).sprite(0xFFFFF176).box(true).body(type = 1)
            .script("Bullet.js")
        bullet.tag = "Bullet"; bullet.active = false
        bullet.getAny<Rigidbody2D>()!!.startVy = 16f
        val enemy = obj(s, "Enemy", 0f, 12f, 1f, 1f).sprite(0xFFEF5350, 0).box(true).body(type = 1)
            .script("Enemy.js")
        enemy.tag = "Enemy"; enemy.active = false; enemy.order = 5
        val star = obj(s, "Star", 0f, 0f, 0.08f, 0.08f).sprite(0xAAFFFFFF, 1).script("Star.js")
        star.active = false; star.order = -10
        val ex = obj(s, "Explosion", 0f, 0f)
        ex.active = false; ex.order = 20
        ex.add(ParticleEmitter().also {
            it.emitting = false; it.rate = 0f; it.spread = 360f; it.speed = 5f; it.lifetime = 0.6f
            it.startSize = 0.35f; it.startColor = 0xFFFFAB40.toInt(); it.endColor = 0x00D50000
        })
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- physics
    private val physics = Template("Physics Sandbox", "Tap anywhere to drop bouncy balls and boxes.") { p ->
        p.writeAsset("Spawner.js", """// Tap the screen to spawn objects
var n = 0;
function update(dt) {
    if (input.tapped) {
        var name = (n++ % 2 == 0) ? "Ball" : "Crate";
        var o = scene.spawn(name, input.touchX, input.touchY);
        if (o) {
            var colors = ["#FFEF5350", "#FF42A5F5", "#FF66BB6A", "#FFFFCA28", "#FFAB47BC"];
            o.color = colors[randomInt(0, colors.length - 1)];
        }
        scene.find("Counter").text = "Objects: " + n;
    }
}
""")
        val s = Scene("Main")
        camera(s, 7f, 0xFF263238)
        obj(s, "Counter", 0f, 6f).text("Tap to spawn!", 0.6f).also { it.order = 100 }
        obj(s, "Spawner", 0f, 0f).script("Spawner.js")
        obj(s, "Floor", 0f, -6.5f, 24f, 1f).sprite(0xFF546E7A).box()
        obj(s, "Wall Left", -12f, 0f, 1f, 14f).sprite(0xFF546E7A).box()
        obj(s, "Wall Right", 12f, 0f, 1f, 14f).sprite(0xFF546E7A).box()
        obj(s, "Ramp", -5f, -2f, 5f, 0.4f).sprite(0xFF78909C).box()
        for (row in 0 until 4) for (i in 0 until 4 - row) {
            obj(s, "Crate", 3f + i * 1.05f + row * 0.52f, -5.5f + row * 1.02f).sprite(0xFFA1887F).box().body()
        }
        val ball = obj(s, "Ball", 0f, 20f, 0.8f, 0.8f).sprite(0xFFFFFFFF, 1).circleCol().body(bounce = 0.7f)
        ball.active = false
        p.saveScene(s)
        p.startScene = "Main"
    }
}
