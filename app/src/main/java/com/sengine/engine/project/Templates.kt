package com.sengine.engine.project

import com.sengine.engine.core.AnimatedSprite2D
import com.sengine.engine.core.Area2D
import com.sengine.engine.core.BodyType
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Label2D
import com.sengine.engine.core.NodeType
import com.sengine.engine.core.ParticleEmitter2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SignalEmitter
import com.sengine.engine.core.Sprite2D
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.core.TimerComponent
import com.sengine.engine.ui.ControlComponent
import com.sengine.engine.ui.ControlType
import com.sengine.engine.particles.ParticlePresets

/** A project template: settings + the scenes and scripts it ships with. */
abstract class Template {
    abstract val name: String
    abstract val description: String
    abstract val id: String

    /** Build the project content (scenes, scripts, assets). */
    abstract fun build(project: Project)

    protected fun applyDefaults(project: Project, pixelPerfect: Boolean = false, portrait: Boolean = false) {
        project.settings.pixelPerfect = pixelPerfect
        project.settings.orientation = if (portrait) 1 else 0
        if (portrait) {
            project.settings.windowWidth = 720
            project.settings.windowHeight = 1280
        }
        project.settings.uiDesignWidth = project.settings.windowWidth
        project.settings.uiDesignHeight = project.settings.windowHeight
    }

    protected fun writeScript(project: Project, name: String, source: String) {
        project.writeAsset(name, source.trimIndent())
    }

    protected fun newScene(name: String, project: Project, configure: Scene.() -> Unit): Scene {
        val scene = Scene(name)
        scene.settings.gravityY = project.settings.defaultGravity
        scene.configure()
        project.saveScene(scene)
        return scene
    }
}

/** Helpers shared by templates and the editor's "create node" actions. */
object SceneDsl {
    fun node(scene: Scene, name: String, x: Float = 0f, y: Float = 0f, parent: GameObject? = null, type: String = NodeType.NODE): GameObject {
        val go = scene.create(name, parent, type)
        go.x = x; go.y = y
        return go
    }

    fun sprite(go: GameObject, color: Int, w: Float, h: Float, shape: Int = 0): Sprite2D {
        val s = Sprite2D()
        s.color = color
        s.sizeX = w; s.sizeY = h
        s.shape = shape
        go.add(s)
        return s
    }

    fun body(go: GameObject, type: Int = BodyType.DYNAMIC, mass: Float = 1f): Rigidbody2D {
        val rb = Rigidbody2D()
        rb.bodyType = type
        rb.mass = mass
        go.add(rb)
        return rb
    }

    fun box(go: GameObject, w: Float, h: Float, trigger: Boolean = false): Collider2D {
        val c = Collider2D()
        c.shape = 0
        c.width = w; c.height = h
        c.isTrigger = trigger
        go.add(c)
        return c
    }

    fun circle(go: GameObject, r: Float, trigger: Boolean = false): Collider2D {
        val c = Collider2D()
        c.shape = 1
        c.radius = r
        c.isTrigger = trigger
        go.add(c)
        return c
    }

    fun camera(scene: Scene, x: Float = 0f, y: Float = 0f, size: Float = 6f, follow: String = ""): GameObject {
        val go = node(scene, "Camera", x, y, type = NodeType.CAMERA)
        val cam = Camera2D()
        cam.size = size
        cam.follow = follow
        cam.smoothing = 6f
        go.add(cam)
        return go
    }

    fun label(scene: Scene, name: String, text: String, x: Float, y: Float, size: Float = 0.5f): GameObject {
        val go = node(scene, name, x, y, type = NodeType.LABEL)
        val l = Label2D()
        l.text = text
        l.size = size
        go.add(l)
        return go
    }

    fun script(go: GameObject, file: String, params: String = ""): ScriptComponent {
        val s = ScriptComponent()
        s.script = file
        s.params = params
        go.add(s)
        return s
    }

    fun ui(scene: Scene, name: String, control: String, x: Float, y: Float, w: Float, h: Float, parent: GameObject? = null): GameObject {
        val go = node(scene, name, parent = parent, type = NodeType.CONTROL)
        val c = ControlComponent()
        go.add(c)
        val u = c.ui
        u.controlType = control
        // default to top-left anchoring with pixel offsets, the layout used by the UI editor
        u.anchorMinX = 0f; u.anchorMinY = 0f; u.anchorMaxX = 0f; u.anchorMaxY = 0f
        u.offsetLeft = x; u.offsetTop = y
        u.offsetRight = x + w; u.offsetBottom = y + h
        u.minWidth = w; u.minHeight = h
        return go
    }
}

// ---------------------------------------------------------------------------------------------
// Templates
// ---------------------------------------------------------------------------------------------

object Empty2DTemplate : Template() {
    override val id = "empty"
    override val name = "Empty 2D"
    override val description = "Camera, ground and a player square — the smallest playable scene"

    override fun build(project: Project) {
        applyDefaults(project)
        newScene("Main", project) {
            SceneDsl.camera(this, 0f, 1f, 6f)
            val ground = SceneDsl.node(this, "Ground", 0f, -3f)
            SceneDsl.sprite(ground, 0xFF3FA34D.toInt(), 20f, 1f)
            SceneDsl.body(ground, BodyType.STATIC)
            SceneDsl.box(ground, 20f, 1f)

            val player = SceneDsl.node(this, "Player", 0f, 0f)
            SceneDsl.sprite(player, 0xFF4C8DFF.toInt(), 1f, 1f)
            SceneDsl.body(player, BodyType.DYNAMIC)
            SceneDsl.box(player, 1f, 1f)
        }
        project.settings.startScene = "Main"
    }
}

object PlatformerTemplate : Template() {
    override val id = "platformer"
    override val name = "Platformer"
    override val description = "Run & jump controller, moving platform, coins with particles and a HUD"

    override fun build(project: Project) {
        applyDefaults(project, pixelPerfect = true)
        writeScript(
            project, "Player.js",
            """
            // Platformer controller — parameters: speed=6, jump=13
            var coins = 0;
            var coyote = 0;

            function update(dt) {
                var vx = input.axis('move_left', 'move_right') * speed;
                self.vx = vx;
                if (vx != 0) self.setFlipX(vx < 0);
                if (self.grounded) coyote = 0.12;
                else coyote -= dt;
                if (input.justPressed('jump') && coyote > 0) {
                    self.vy = jump;
                    coyote = 0;
                    audio.play('jump.wav');
                }
                if (self.y < -12) scene.reload();
                cameraFollow();
            }

            function cameraFollow() {
                var cam = scene.camera;
                if (cam) { cam.x = self.x * 0.4; cam.y = self.y * 0.4 + 1; }
            }

            function onTrigger(other) {
                if (other.tag == 'Coin') {
                    coins++;
                    other.burst('CoinFX');
                    other.destroy();
                    scene.find('Score').text = 'Coins: ' + coins;
                    audio.play('coin.wav');
                }
            }
            """.trimIndent()
        )
        writeScript(
            project, "Patrol.js",
            """
            // Moving platform — parameters: range=4, speed=1.5
            var dir = 1;
            var origin = 0;
            function start() { origin = self.x; }
            function update(dt) {
                self.x += dir * speed * dt;
                if (self.x > origin + range) dir = -1;
                if (self.x < origin - range) dir = 1;
            }
            """.trimIndent()
        )
        newScene("Main", project) {
            SceneDsl.camera(this, 0f, 2f, 7f)
            SceneDsl.label(this, "Score", "Coins: 0", -8f, 5.5f, 0.6f)

            val ground = SceneDsl.node(this, "Ground", 0f, -4f)
            SceneDsl.sprite(ground, 0xFF3A7D44.toInt(), 30f, 1f)
            SceneDsl.body(ground, BodyType.STATIC)
            SceneDsl.box(ground, 30f, 1f)

            val platform = SceneDsl.node(this, "Platform", 4f, 0f)
            SceneDsl.sprite(platform, 0xFF8D6E63.toInt(), 4f, 0.5f)
            SceneDsl.body(platform, BodyType.KINEMATIC)
            SceneDsl.box(platform, 4f, 0.5f)
            SceneDsl.script(platform, "Patrol.js", "range=4, speed=1.5")

            val player = SceneDsl.node(this, "Player", -6f, -1f)
            SceneDsl.sprite(player, 0xFF4C8DFF.toInt(), 0.9f, 1.2f)
            SceneDsl.body(player, BodyType.DYNAMIC).drag = 0.5f
            SceneDsl.box(player, 0.9f, 1.2f)
            SceneDsl.script(player, "Player.js", "speed=6, jump=13")

            val coin = SceneDsl.node(this, "Coin", 0f, 1f, type = NodeType.AREA)
            coin.tag = "Coin"
            SceneDsl.circle(coin, 0.4f, trigger = true)
            SceneDsl.sprite(coin, 0xFFFFC940.toInt(), 0.6f, 0.6f, shape = 1)

            val fx = SceneDsl.node(this, "CoinFX", 0f, 1f, type = NodeType.PARTICLES)
            val emitter = ParticleEmitter2D()
            ParticlePresets.apply("sparks", emitter.spec)
            emitter.spec.emitting = false
            emitter.spec.burst = 18
            fx.add(emitter)
            fx.active = false
        }
        project.settings.startScene = "Main"
    }
}

object TopDownTemplate : Template() {
    override val id = "topdown"
    override val name = "Top Down"
    override val description = "8-way movement with a follow camera, walls and collectible pickups"

    override fun build(project: Project) {
        applyDefaults(project)
        writeScript(
            project, "Player.js",
            """
            // Top-down controller — parameters: speed=5
            var facing = 'down';
            function update(dt) {
                var x = input.axis('move_left', 'move_right');
                var y = input.axis('move_down', 'move_up');
                var len = Math.sqrt(x * x + y * y);
                if (len > 1) { x /= len; y /= len; }
                self.vx = x * speed;
                self.vy = y * speed;
                if (Math.abs(x) > 0.1) { facing = x > 0 ? 'right' : 'left'; self.setFlipX(x < 0); }
                if (Math.abs(y) > 0.1) facing = y > 0 ? 'up' : 'down';
                var cam = scene.camera;
                if (cam) { cam.x = self.x; cam.y = self.y; }
            }
            """.trimIndent()
        )
        writeScript(
            project, "Pickup.js",
            """
            // Pickup — parameter: points=10
            function onTrigger(other) {
                if (other.name == 'Player') {
                    audio.play('pickup.wav');
                    scene.find('Score').text = 'Score: ' + points;
                    self.destroy();
                }
            }
            """.trimIndent()
        )
        newScene("Main", project) {
            SceneDsl.camera(this, 0f, 0f, 6f)
            SceneDsl.label(this, "Score", "Score: 0", -6f, 5f, 0.55f)

            val walls = SceneDsl.node(this, "Walls", 0f, 0f)
            for ((i, pos) in listOf(-7f, 7f).withIndex()) {
                val wall = SceneDsl.node(this, "Wall${i + 1}", pos, 0f, walls)
                SceneDsl.sprite(wall, 0xFF37474F.toInt(), 1f, 14f)
                SceneDsl.body(wall, BodyType.STATIC)
                SceneDsl.box(wall, 1f, 14f)
            }

            val player = SceneDsl.node(this, "Player", 0f, 0f)
            SceneDsl.sprite(player, 0xFF4C8DFF.toInt(), 0.8f, 0.8f)
            SceneDsl.body(player, BodyType.DYNAMIC)
            SceneDsl.box(player, 0.8f, 0.8f)
            SceneDsl.script(player, "Player.js", "speed=5")

            val pickup = SceneDsl.node(this, "Pickup", 3f, 2f, type = NodeType.AREA)
            SceneDsl.circle(pickup, 0.5f, trigger = true)
            SceneDsl.sprite(pickup, 0xFFFFD54F.toInt(), 0.7f, 0.7f, shape = 1)
            SceneDsl.script(pickup, "Pickup.js", "points=10")
        }
        project.settings.startScene = "Main"
    }
}

object ShooterTemplate : Template() {
    override val id = "shooter"
    override val name = "Space Shooter"
    override val description = "Auto-firing ship, waves of enemies, explosions and a score HUD"

    override fun build(project: Project) {
        applyDefaults(project)
        writeScript(
            project, "Ship.js",
            """
            // Player ship — parameters: speed=8, fireRate=0.18
            var cooldown = 0;
            function update(dt) {
                self.vx = input.axis('move_left', 'move_right') * speed;
                self.vy = input.axis('move_down', 'move_up') * speed;
                cooldown -= dt;
                if ((input.pressed('attack') || input.pressed('jump')) && cooldown <= 0) {
                    var b = scene.spawn('Bullet', self.x, self.y + 1);
                    if (b) audio.play('shoot.wav');
                    cooldown = fireRate;
                }
            }
            function onTrigger(other) {
                if (other.tag == 'Enemy') {
                    other.burst('Explosion');
                    other.destroy();
                    self.destroy();
                    scene.find('Score').text = 'Game Over';
                }
            }
            """.trimIndent()
        )
        writeScript(
            project, "Enemy.js",
            """
            // Enemy — parameters: speed=2, zigzag=3
            var t = 0;
            function update(dt) {
                t += dt;
                self.y -= speed * dt;
                self.x += Math.sin(t * 2) * zigzag * dt;
                if (self.y < -8) self.y = 8;
            }
            """.trimIndent()
        )
        writeScript(
            project, "Bullet.js",
            """
            // Bullet — parameter: speed=14
            function update(dt) { self.y += speed * dt; if (self.y > 9) self.destroy(); }
            """.trimIndent()
        )
        newScene("Main", project) {
            SceneDsl.camera(this, 0f, 0f, 9f)
            SceneDsl.label(this, "Score", "Score: 0", -8f, 8f, 0.5f)

            val ship = SceneDsl.node(this, "Ship", 0f, -6f)
            SceneDsl.sprite(ship, 0xFF4C8DFF.toInt(), 0.9f, 1f, shape = 2)
            SceneDsl.body(ship, BodyType.KINEMATIC)
            SceneDsl.box(ship, 0.8f, 0.8f, trigger = true)
            SceneDsl.script(ship, "Ship.js", "speed=8, fireRate=0.18")

            val bullet = SceneDsl.node(this, "Bullet", 0f, 0f, type = NodeType.AREA)
            bullet.active = false
            SceneDsl.sprite(bullet, 0xFFFFF176.toInt(), 0.18f, 0.6f)
            SceneDsl.body(bullet, BodyType.KINEMATIC)
            SceneDsl.box(bullet, 0.18f, 0.6f, trigger = true)
            SceneDsl.script(bullet, "Bullet.js", "speed=14")

            val enemy = SceneDsl.node(this, "Enemy", 0f, 7f, type = NodeType.AREA)
            enemy.tag = "Enemy"
            enemy.active = false
            SceneDsl.sprite(enemy, 0xFFE5534B.toInt(), 0.8f, 0.8f, shape = 2)
            SceneDsl.circle(enemy, 0.45f, trigger = true)
            SceneDsl.script(enemy, "Enemy.js", "speed=2, zigzag=3")

            val explosion = SceneDsl.node(this, "Explosion", 0f, 0f, type = NodeType.PARTICLES)
            explosion.active = false
            val fx = ParticleEmitter2D()
            ParticlePresets.apply("explosion", fx.spec)
            fx.spec.emitting = false
            explosion.add(fx)

            val spawner = SceneDsl.node(this, "EnemySpawner", 0f, 0f)
            SceneDsl.script(spawner, "Spawner.js", "interval=1.2")
        }
        writeScript(
            project, "Spawner.js",
            """
            // Wave spawner — parameter: interval=1.2
            var timer = 0;
            function update(dt) {
                timer -= dt;
                if (timer <= 0) {
                    timer = interval;
                    var e = scene.spawn('Enemy', random(-7, 7), 8);
                    if (e) e.setVelocity(0, -2);
                }
            }
            """.trimIndent()
        )
        project.settings.startScene = "Main"
    }
}

object PuzzleTemplate : Template() {
    override val id = "puzzle"
    override val name = "Puzzle"
    override val description = "Grid based puzzle board with a UI panel, move counter and win signal"

    override fun build(project: Project) {
        applyDefaults(project, portrait = true)
        writeScript(
            project, "Board.js",
            """
            // Simple slide puzzle board logic — parameter: size=5
            var moves = 0;
            function start() {
                scene.find('Moves').text = 'Moves: 0';
            }
            function update(dt) {
                if (input.justPressed('ui_accept')) {
                    moves++;
                    scene.find('Moves').text = 'Moves: ' + moves;
                    if (moves >= 20) self.emit('solved');
                }
            }
            """.trimIndent()
        )
        newScene("Main", project) {
            SceneDsl.camera(this, 0f, 0f, 8f)
            val board = SceneDsl.node(this, "Board", 0f, 0f)
            SceneDsl.script(board, "Board.js", "size=5")
            for (r in 0 until 5) for (c in 0 until 5) {
                val cell = SceneDsl.node(this, "Cell_${r}_$c", c - 2f, r - 2f, board, NodeType.COLLIDER)
                SceneDsl.sprite(cell, if ((r + c) % 2 == 0) 0xFF37474F.toInt() else 0xFF455A64.toInt(), 0.96f, 0.96f)
            }
            // UI layer
            val hud = SceneDsl.ui(this, "HUD", ControlType.PANEL, 0f, 0f, 720f, 90f)
            hud.ui!!.minWidth = 720f; hud.ui!!.minHeight = 90f
            val movesLabel = SceneDsl.ui(this, "Moves", ControlType.LABEL, 24f, 28f, 240f, 40f, hud)
            movesLabel.ui!!.text = "Moves: 0"
            movesLabel.ui!!.fontSize = 28f
            val resetBtn = SceneDsl.ui(this, "ResetButton", ControlType.BUTTON, 520f, 20f, 170f, 52f, hud)
            resetBtn.ui!!.text = "Reset"
            resetBtn.ui!!.onClick = "onReset"
            val timer = SceneDsl.node(this, "MoveTimer", type = NodeType.NODE)
            val t = TimerComponent()
            t.waitTime = 30f
            t.signalName = "time_up"
            timer.add(t)
            val emitter = SignalEmitter()
            emitter.onStartSignal = "level_started"
            timer.add(emitter)
        }
        project.settings.startScene = "Main"
    }
}

object PixelArtTemplate : Template() {
    override val id = "pixelart"
    override val name = "Pixel Art"
    override val description = "16×16 pixels-per-unit setup, nearest filtering, pixel-perfect camera and a tilemap"

    override fun build(project: Project) {
        applyDefaults(project, pixelPerfect = true)
        project.settings.pixelScale = 4
        project.settings.windowWidth = 960
        project.settings.windowHeight = 540
        writeScript(
            project, "Player.js",
            """
            // Pixel art character — parameters: speed=40 (pixels per second)
            function update(dt) {
                var x = input.axis('move_left', 'move_right');
                self.vx = x * speed;
                self.setFlipX(x < 0);
                if (input.justPressed('jump') && self.grounded) self.vy = 90;
            }
            """.trimIndent()
        )
        newScene("Main", project) {
            settings.pixelPerfect = true
            settings.pixelSnap = true
            SceneDsl.camera(this, 0f, 0f, 7.5f).let { cam ->
                val c = cam.get<Camera2D>()!!
                c.pixelPerfect = true
                c.snapToPixels = true
            }
            val map = SceneDsl.node(this, "TileMap", 0f, 0f, type = NodeType.TILEMAP)
            val tm = TileMap2D()
            tm.pixelsPerUnit = 16f
            tm.tileWidth = 16
            tm.tileHeight = 16
            val layer = tm.data.addLayer("Ground", 48, 32)
            // paint a floor and a few platforms so the template starts with real content
            for (x in 0 until 48) for (y in 0 until 4) layer[x, 28 + y] = 0
            map.add(tm)
            val player = SceneDsl.node(this, "Player", -4f, -2f)
            val sprite = SceneDsl.sprite(player, 0xFFFFFFFF.toInt(), 1f, 1.5f)
            sprite.pixelsPerUnit = 16f
            SceneDsl.body(player, BodyType.DYNAMIC)
            SceneDsl.box(player, 1f, 1.5f)
            SceneDsl.script(player, "Player.js", "speed=40")
        }
        project.settings.startScene = "Main"
    }
}

object UiTemplate : Template() {
    override val id = "ui"
    override val name = "UI Project"
    override val description = "Menu layout: title, buttons, tabs and a settings panel built with the UI editor"

    override fun build(project: Project) {
        applyDefaults(project, portrait = true)
        writeScript(
            project, "Menu.js",
            """
            // Menu logic — connect buttons via their On Click method name
            function onStart() { log('start pressed'); scene.load('Game'); }
            function onSettings() { log('settings pressed'); }
            function onQuit() { log('quit pressed'); }
            """.trimIndent()
        )
        newScene("Main", project) {
            SceneDsl.camera(this, 0f, 0f, 6f)
            val root = SceneDsl.node(this, "UI", type = NodeType.CONTROL)
            val rootControl = ControlComponent()
            root.add(rootControl)
            root.ui!!.controlType = ControlType.PANEL
            root.ui!!.anchorMaxX = 1f
            root.ui!!.anchorMaxY = 1f
            root.ui!!.offsetRight = 0f
            root.ui!!.offsetBottom = 0f
            root.ui!!.minWidth = 720f
            root.ui!!.minHeight = 1280f

            val logo = SceneDsl.ui(this, "Title", ControlType.LABEL, 0f, 160f, 720f, 90f, root)
            logo.ui!!.text = "S ENGINE"
            logo.ui!!.fontSize = 54f
            logo.ui!!.textAlign = 1

            val column = SceneDsl.ui(this, "Buttons", ControlType.COLUMN, 60f, 320f, 600f, 300f, root)
            column.ui!!.spacing = 18f
            column.ui!!.minWidth = 600f
            column.ui!!.minHeight = 300f

            for ((i, spec) in listOf("Start" to "onStart", "Settings" to "onSettings", "Quit" to "onQuit").withIndex()) {
                val b = SceneDsl.ui(this, "${spec.first}Button", ControlType.BUTTON, 0f, i * 90f, 600f, 74f, column)
                b.ui!!.text = spec.first
                b.ui!!.fontSize = 26f
                b.ui!!.minWidth = 600f
                b.ui!!.minHeight = 74f
                b.ui!!.onClick = spec.second
                SceneDsl.script(b, "Menu.js")
            }

            val slider = SceneDsl.ui(this, "VolumeSlider", ControlType.SLIDER, 60f, 700f, 600f, 60f, root)
            slider.ui!!.value = 0.8f
            slider.ui!!.minWidth = 600f
            slider.ui!!.minHeight = 60f

            val check = SceneDsl.ui(this, "FullscreenCheck", ControlType.CHECKBOX, 60f, 780f, 600f, 60f, root)
            check.ui!!.text = "Fullscreen"
            check.ui!!.checked = true
        }
        newScene("Game", project) {
            SceneDsl.camera(this, 0f, 0f, 6f)
        }
        project.settings.startScene = "Main"
    }
}

/** All templates offered by the project manager. */
object Templates {
    val all: List<Template> = listOf(
        Empty2DTemplate,
        PlatformerTemplate,
        TopDownTemplate,
        ShooterTemplate,
        PuzzleTemplate,
        PixelArtTemplate,
        UiTemplate
    )

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }
}
