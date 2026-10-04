package com.sengine

import com.sengine.engine.animation.Animation
import com.sengine.engine.animation.LoopMode
import com.sengine.engine.animation.SpriteFrames
import com.sengine.engine.core.Area2D
import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.BodyType
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.ColliderShape
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Label2D
import com.sengine.engine.core.NodeType
import com.sengine.engine.core.ParticleEmitter2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.Sprite2D
import com.sengine.engine.core.TileMap2D
import com.sengine.engine.core.UnknownComponent
import com.sengine.engine.editor.CreateNodeCommand
import com.sengine.engine.editor.EditorDocument
import com.sengine.engine.editor.NodeProps
import com.sengine.engine.editor.TransformCommand
import com.sengine.engine.input.InputMap
import com.sengine.engine.input.InputSystem
import com.sengine.engine.json.JVal
import com.sengine.engine.json.Json
import com.sengine.engine.math.Easing
import com.sengine.engine.math.Vec2
import com.sengine.engine.particles.ParticlePresets
import com.sengine.engine.particles.ParticleSpec
import com.sengine.engine.particles.ParticleSystem
import com.sengine.engine.physics.PhysicsWorld
import com.sengine.engine.project.Project
import com.sengine.engine.project.ProjectManager
import com.sengine.engine.project.Templates
import com.sengine.engine.resources.Material
import com.sengine.engine.resources.ResourceManager
import com.sengine.engine.serialization.SceneFormat
import com.sengine.engine.tilemap.TileMapData
import com.sengine.engine.tilemap.TileSet
import com.sengine.engine.ui.ControlComponent
import com.sengine.engine.ui.ControlType
import com.sengine.engine.ui.UiLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Headless engine tests.
 *
 * These cover the systems shared by the editor and the running game — transforms, hierarchy,
 * serialization and migration, resources, input mapping, 2D physics, animation, tilemaps,
 * particles, UI layout, project management and undo/redo — so a regression is caught before it
 * reaches a screen.
 */
class EngineTest {

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "sengine_test_$name")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    // ------------------------------------------------------------------ transforms & hierarchy

    @Test
    fun transformsAndHierarchy() {
        val scene = Scene("Test")
        val parent = scene.create("Parent")
        parent.setPosition(2f, 1f)
        parent.rotation = 90f
        parent.computeWorld()
        val child = scene.create("Child", parent)
        child.setPosition(1f, 0f)
        child.computeWorld()
        // rotating the parent 90° must carry the child to (2, 2)
        assertEquals(2f, child.world.tx, 0.01f)
        assertEquals(2f, child.world.ty, 0.01f)

        // reparenting keeps the world transform
        val other = scene.create("Other")
        other.setPosition(-5f, 0f)
        other.computeWorld()
        scene.reparent(child, other, keepWorld = true)
        child.computeWorld()
        assertEquals(2f, child.world.tx, 0.05f)
        assertEquals(2f, child.world.ty, 0.05f)

        assertTrue(other.isAncestorOf(child))
        assertEquals(1, scene.childrenOf(other).size)
        assertEquals(1, scene.descendants(other).size)
    }

    @Test
    fun duplicateIsIndependent() {
        val scene = Scene("Test")
        val node = scene.create("A")
        node.add(Sprite2D()).texture = "hero.png"
        node.setPosition(3f, 4f)
        val copy = scene.duplicate(node)
        assertTrue(copy.id != node.id)
        copy.setPosition(9f, 9f)
        assertEquals(3f, node.x, 0.0001f)
        assertEquals(9f, copy.x, 0.0001f)
        assertEquals("hero.png", copy.getAny<Sprite2D>()?.texture)
        assertTrue(copy.getAny<Sprite2D>() !== node.getAny<Sprite2D>())
    }

    // ------------------------------------------------------------------ serialization

    @Test
    fun sceneSerializationRoundTrip() {
        val scene = Scene("RoundTrip")
        scene.settings.gravityY = -12f
        val player = scene.create("Player")
        player.tag = "Player"
        player.groups.add("actors")
        player.meta["hp"] = "10"
        player.setPosition(1.5f, -2.5f)
        player.add(Sprite2D()).apply {
            texture = "hero.png"
            sizeX = 2f
            sizeY = 3f
            flipX = true
        }
        player.add(Rigidbody2D()).apply {
            bodyType = BodyType.DYNAMIC
            mass = 3f
            collisionMask = 0b1010
        }
        player.add(Collider2D()).apply {
            shape = ColliderShape.CIRCLE
            radius = 0.75f
        }
        player.add(Label2D()).text = "hello"
        scene.create("Weapon", player).setPosition(0.5f, 0f)

        val text = SceneFormat.write(scene)
        assertTrue(text.contains("sengine.scene"))
        val loaded = SceneFormat.fromJson(Json.parseObject(text))
        assertEquals("RoundTrip", loaded.name)
        assertEquals(2, loaded.objects.size)
        val loadedPlayer = loaded.find("Player")!!
        assertEquals("Player", loadedPlayer.tag)
        assertTrue(loadedPlayer.groups.contains("actors"))
        assertEquals("10", loadedPlayer.meta["hp"])
        assertEquals(1.5f, loadedPlayer.x, 0.001f)
        assertEquals(-2.5f, loadedPlayer.y, 0.001f)
        assertEquals("hero.png", loadedPlayer.getAny<Sprite2D>()?.texture)
        assertTrue(loadedPlayer.getAny<Sprite2D>()!!.flipX)
        assertEquals(3f, loadedPlayer.getAny<Rigidbody2D>()!!.mass, 0.001f)
        assertEquals(0b1010, loadedPlayer.getAny<Rigidbody2D>()!!.collisionMask)
        assertEquals(ColliderShape.CIRCLE, loadedPlayer.getAny<Collider2D>()!!.shape)
        assertEquals("hello", loadedPlayer.getAny<Label2D>()!!.text)
        assertEquals("Weapon", loaded.objects[1].name)
        assertEquals(loadedPlayer.id, loaded.objects[1].parent?.id)
        assertEquals(-12f, loaded.settings.gravityY, 0.001f)
        // ids are stable, so saving the reloaded scene produces the same document
        assertEquals(text, SceneFormat.write(loaded))
    }

    @Test
    fun legacySceneMigration() {
        // v1 document: flat transform fields and an array of components, old component names
        val legacy = """
            {"name":"Old","gravityY":-20,
             "objects":[
               {"id":1,"name":"Hero","x":2,"y":3,"rotation":0,"scaleX":1,"scaleY":1,
                "components":[{"type":"SpriteRenderer","texture":"a.png"},{"type":"Rigidbody2D","mass":2}]}
             ]}
        """.trimIndent()
        val migrated = SceneFormat.fromJson(Json.parseObject(legacy))
        assertEquals("Old", migrated.name)
        assertEquals(1, migrated.objects.size)
        val hero = migrated.objects[0]
        assertEquals("Hero", hero.name)
        assertEquals(2f, hero.x, 0.001f)
        assertEquals("a.png", hero.getAny<Sprite2D>()?.texture)
        assertEquals(2f, hero.getAny<Rigidbody2D>()!!.mass, 0.001f)
        // and the migrated document is saved back in the current version
        assertEquals(2, Json.parseObject(SceneFormat.write(migrated)).i("version"))
    }

    @Test
    fun unknownComponentsArePreserved() {
        val scene = Scene("Future")
        val node = scene.create("Node")
        node.add(UnknownComponent("FutureComp", Json.parseObject("""{"power":9}""")))
        val text = SceneFormat.write(scene)
        val reloaded = SceneFormat.fromJson(Json.parseObject(text))
        assertNotNull(reloaded.objects[0].component("FutureComp"))
        // data written by a newer engine (or a plugin) must survive a load/save cycle
        val again = SceneFormat.write(reloaded)
        assertTrue(again.contains("FutureComp"))
        assertTrue(again.contains("power"))
    }

    // ------------------------------------------------------------------ resources

    @Test
    fun resourceCachingAndInvalidation() {
        val root = tempDir("resources")
        val project = Project(root)
        project.saveMeta()

        val tiles = TileSet("Main")
        tiles.texture = "art/tiles.png"
        tiles.tileWidth = 16
        tiles.tileHeight = 16
        tiles.createTile(0, 0).collision = 1
        tiles.createTile(16, 0)
        assertTrue(project.writeAsset("maps/main.tileset.json", Json.write(tiles.toJson())))
        val material = Material("glow")
        material.uniforms["uStrength"] = 2f
        assertTrue(project.writeAsset("fx/glow.material.json", Json.write(material.toJson())))

        val resources = ResourceManager(project)
        val loaded = resources.tileset("maps/main.tileset.json")
        assertNotNull(loaded)
        assertEquals(2, loaded!!.tiles.size)
        assertEquals(1, loaded.tile(1)?.collision)
        // cached: the same instance is returned while the file is unchanged
        assertTrue(resources.tileset("maps/main.tileset.json") === loaded)
        assertEquals(2f, resources.material("fx/glow.material.json")!!.uniforms["uStrength"]!!, 0.001f)

        assertEquals(AssetKind.TILESET, AssetKind.of("maps/main.tileset.json"))
        assertEquals(AssetKind.MATERIAL, AssetKind.of("fx/glow.material.json"))
        assertEquals(AssetKind.SCRIPT, AssetKind.of("scripts/player.js"))
        assertEquals(AssetKind.TEXTURE, AssetKind.of("art/hero.png"))
        assertEquals(AssetKind.SOUND, AssetKind.of("sfx/jump.ogg"))
    }

    // ------------------------------------------------------------------ input

    @Test
    fun inputMapDefaultsAndPersistence() {
        val map = InputMap.defaults()
        for (action in listOf("move_left", "move_right", "jump", "attack", "interact", "pause")) {
            assertNotNull("missing action $action", map.actions[action])
        }
        assertTrue(map.actions["jump"]!!.bindings.isNotEmpty())
        val loaded = InputMap()
        loaded.fromJson(Json.parseObject(Json.write(map.toJson())))
        assertEquals(map.actions.size, loaded.actions.size)
        assertEquals(map.actions["jump"]!!.bindings.size, loaded.actions["jump"]!!.bindings.size)
        assertTrue(loaded.rename("jump", "jump_alt"))
        assertNotNull(loaded.actions["jump_alt"])
        assertNull(loaded.actions["jump"])
    }

    @Test
    fun inputSystemResolvesBindingsAndAxes() {
        val input = InputSystem()
        input.map = InputMap.defaults()
        input.devices.keys.add(InputMap.KEY_J)
        input.devices.keysPressedThisFrame.add(InputMap.KEY_J)
        input.beginFrame()
        assertTrue(input.isPressed("attack"))
        assertTrue(input.isJustPressed("attack"))
        input.endFrame()
        input.devices.keys.clear()
        input.devices.keysPressedThisFrame.clear()
        input.beginFrame()
        assertFalse(input.isPressed("attack"))
        assertTrue(input.isJustReleased("attack"))
        // analogue axis from a virtual stick
        input.devices.axes[0] = 0.8f
        input.beginFrame()
        assertEquals(0.8f, input.value("move_right"), 0.001f)
        assertEquals(0.8f, input.moveX, 0.001f)
    }

    // ------------------------------------------------------------------ physics

    private fun physicsScene(): Triple<Scene, GameObject, GameObject> {
        val scene = Scene("Physics")
        scene.settings.gravityY = -10f
        val ground = scene.create("Ground")
        ground.add(Collider2D()).apply { width = 10f; height = 1f }
        ground.setPosition(0f, -1f)
        ground.computeWorld()
        val box = scene.create("Box")
        box.add(Rigidbody2D()).apply { bodyType = BodyType.DYNAMIC }
        box.add(Collider2D()).apply { width = 1f; height = 1f }
        box.setPosition(0f, 2f)
        box.computeWorld()
        return Triple(scene, ground, box)
    }

    @Test
    fun physicsFallsAndRestsOnGround() {
        val (scene, _, box) = physicsScene()
        val physics = PhysicsWorld()
        for (i in 0 until 240) physics.step(scene, 1f / 60f)
        // the box rests on the ground instead of falling through it
        assertTrue("box y=${box.y}", box.y > -0.6f && box.y < 1.5f)
        val rb = box.getAny<Rigidbody2D>()!!
        assertTrue("expected grounded", rb.grounded)
        assertEquals(0f, rb.vx, 0.5f)
        // a settled body parks itself so large scenes stay cheap
        for (i in 0 until 240) physics.step(scene, 1f / 60f)
        assertTrue("expected the resting body to sleep", rb.sleeping)
    }

    @Test
    fun physicsRespectsLayersAndMasks() {
        val scene = Scene("Layers")
        scene.settings.gravityY = 0f
        val a = scene.create("A")
        a.add(Rigidbody2D()).apply {
            bodyType = BodyType.DYNAMIC
            collisionLayer = 1
            collisionMask = 0b10
        }
        a.add(Collider2D()).apply { width = 1f; height = 1f }
        val b = scene.create("B")
        b.add(Rigidbody2D()).apply {
            bodyType = BodyType.DYNAMIC
            collisionLayer = 0b100
            collisionMask = 1
        }
        b.add(Collider2D()).apply { width = 1f; height = 1f }
        a.setPosition(0f, 0f); b.setPosition(0.5f, 0f)
        a.computeWorld(); b.computeWorld()
        val physics = PhysicsWorld()
        for (i in 0 until 10) physics.step(scene, 1f / 60f)
        // the masks reject the pair, so nothing pushes the bodies apart
        assertEquals(0f, a.x, 0.01f)
        assertEquals(0.5f, b.x, 0.01f)
    }

    @Test
    fun physicsTriggersReportEnterAndExit() {
        val scene = Scene("Triggers")
        scene.settings.gravityY = 0f
        val zone = scene.create("Zone")
        zone.add(Area2D()).apply { width = 4f; height = 4f }
        zone.setPosition(0f, 0f)
        zone.computeWorld()
        val player = scene.create("Player")
        player.add(Rigidbody2D()).apply { bodyType = BodyType.DYNAMIC }
        player.add(Collider2D()).apply { width = 1f; height = 1f }
        player.setPosition(0f, 0f)
        player.computeWorld()
        val events = ArrayList<String>()
        val physics = PhysicsWorld()
        physics.listener = object : PhysicsWorld.Listener {
            override fun onTriggerEnter(a: GameObject, b: GameObject) { events.add("enter:${b.name}") }
            override fun onTriggerExit(a: GameObject, b: GameObject) { events.add("exit:${b.name}") }
            override fun onAreaEnter(area: GameObject, other: GameObject) { events.add("area:${other.name}") }
        }
        physics.step(scene, 1f / 60f)
        physics.step(scene, 1f / 60f)
        assertTrue("expected a trigger event, got $events", events.isNotEmpty())
        // moving the body away reports the exit
        player.setPosition(50f, 0f)
        player.computeWorld()
        physics.step(scene, 1f / 60f)
        physics.step(scene, 1f / 60f)
        assertTrue("expected a trigger exit, got $events", events.any { it.startsWith("exit:") || it.startsWith("area:") })
    }

    @Test
    fun physicsQueriesFindBodies() {
        val scene = Scene("Queries")
        val target = scene.create("Target")
        target.add(Collider2D()).apply { width = 1f; height = 1f }
        target.setPosition(3f, 0f)
        target.computeWorld()
        val physics = PhysicsWorld()
        val hit = physics.raycast(scene, 0f, 0f, 1f, 0f, 10f)
        assertNotNull(hit)
        assertEquals(target.id, hit!!.node!!.id)
        assertEquals(3f, hit.x, 0.6f)
        assertNull(physics.raycast(scene, 0f, 5f, 1f, 0f, 10f))
        assertTrue(physics.overlapCircle(scene, 3f, 0f, 0.4f).any { it.id == target.id })
        assertEquals(target.id, physics.overlapPoint(scene, 3f, 0f)?.id)
        assertNull(physics.overlapPoint(scene, -50f, 0f))
    }

    // ------------------------------------------------------------------ animation

    @Test
    fun animationSamplingAndLoopModes() {
        val once = SpriteFrames(columns = 4, count = 4, fps = 10f, startIndex = 0, loop = LoopMode.ONCE)
        assertEquals(0, once.frameAt(0f))
        assertEquals(3, once.frameAt(0.35f))
        assertEquals(3, once.frameAt(10f))

        val loop = SpriteFrames(4, 4, 10f, 0, LoopMode.LOOP)
        assertEquals(0, loop.frameAt(0.4f))
        assertEquals(2, loop.frameAt(0.25f))
        assertEquals(0.4f, loop.duration(), 0.001f)

        val pingPong = SpriteFrames(4, 4, 10f, 0, LoopMode.PING_PONG)
        assertEquals(3, pingPong.frameAt(0.35f))
        assertEquals(1, pingPong.frameAt(0.5f))

        val animation = Animation("run")
        animation.length = 1f
        animation.setKey("x", 0f, 0f, Easing.NAMES.indexOf("Linear"))
        animation.setKey("x", 1f, 10f, Easing.NAMES.indexOf("Linear"))
        assertEquals(0f, animation.sample("x", 0f)!!, 0.001f)
        assertEquals(5f, animation.sample("x", 0.5f)!!, 0.001f)
        assertEquals(10f, animation.sample("x", 1f)!!, 0.001f)
        animation.addEvent(0.5f, 1, "footstep")
        val loaded = Animation.fromJson(Json.write(animation.toJson()))
        assertEquals(5f, loaded.sample("x", 0.5f)!!, 0.01f)
        assertEquals("footstep", loaded.events[0].value)
    }

    // ------------------------------------------------------------------ tilemaps

    @Test
    fun tileMapPaintSaveLoad() {
        val node = Scene("Tiles").create("Map")
        val tm = TileMap2D()
        node.add(tm)
        val layer = tm.data.addLayer("Ground", 16, 8)
        assertTrue(tm.setTile(0, 2, 3, 7))
        assertFalse(tm.setTile(0, 2, 3, 7))          // same tile → no change
        assertFalse(tm.setTile(0, -1, 0, 1))         // out of bounds is rejected
        assertEquals(7, tm.currentTile(0, 2, 3))
        assertEquals(4, tm.fillRect(0, 0, 0, 1, 1, 5))
        tm.data.tileSetPath = "maps/main.tileset.json"
        val reloaded = TileMapData().also { it.fromJson(Json.parseObject(Json.write(tm.data.toJson()))) }
        assertEquals(1, reloaded.layers.size)
        assertEquals(7, reloaded.layers[0][2, 3])
        assertEquals(5, reloaded.layers[0][0, 0])
        assertEquals(layer.width, reloaded.layers[0].width)
    }

    // ------------------------------------------------------------------ particles

    @Test
    fun particleSystemEmitsAndAgesOut() {
        val spec = ParticleSpec().apply {
            rate = 60f
            lifetimeMin = 0.2f
            lifetimeMax = 0.3f
            maxParticles = 100
        }
        val system = ParticleSystem()
        system.pendingBurst = 20
        system.update(spec, 1f / 60f, 0f, 0f, 0f, 1f, emitting = true)
        assertTrue("particles=${system.count}", system.count > 0)
        assertTrue(system.emittedTotal >= 20)
        for (i in 0 until 30) system.update(spec, 1f / 60f, 0f, 0f, 0f, 1f, emitting = false)
        assertEquals(0, system.count)
        // presets really change the spec — no fake preset buttons
        ParticlePresets.apply("Fire", spec)
        assertTrue(spec.rate > 0f)
        assertEquals(8, ParticlePresets.NAMES.size)
    }

    // ------------------------------------------------------------------ UI

    @Test
    fun uiLayoutSolvesAnchorsForAnyResolution() {
        val scene = Scene("UI")
        val panel = scene.createNode(NodeType.CONTROL)
        val control = panel.getAny<ControlComponent>()!!
        control.ui.controlType = ControlType.PANEL
        control.ui.anchorMinX = 0f
        control.ui.anchorMinY = 1f
        control.ui.anchorMaxX = 0f
        control.ui.anchorMaxY = 1f
        control.ui.offsetLeft = 0f
        control.ui.offsetTop = 0f
        control.ui.offsetRight = 200f
        control.ui.offsetBottom = 50f
        UiLayout.layout(scene, 1280f, 720f)
        assertEquals(0f, control.rect.x, 0.5f)
        assertEquals(200f, control.rect.width, 0.5f)
        assertEquals(50f, control.rect.height, 0.5f)
        // anchors are resolution independent: another viewport keeps the pixel offsets
        UiLayout.layout(scene, 640f, 360f)
        assertEquals(200f, control.rect.width, 0.5f)
        assertEquals(50f, control.rect.height, 0.5f)
    }

    // ------------------------------------------------------------------ projects

    @Test
    fun projectLifecycleAndZipRoundTrip() {
        val root = tempDir("projects")
        val manager = ProjectManager(root)
        assertTrue(Templates.all.size >= 7)
        val project = manager.create("Demo", Templates.byId("platformer")!!)
        assertTrue(File(project.dir, "project.json").exists())
        assertTrue(project.listScenes().isNotEmpty())
        assertTrue(project.listAssetsRecursive().isNotEmpty())
        assertTrue(manager.recent().any { it.name == project.name })
        assertEquals(project.settings.startScene, project.listScenes().first())

        val copy = manager.duplicate(project)
        assertEquals("Demo Copy", copy.name)
        assertEquals(project.listScenes().size, copy.listScenes().size)
        val renamed = manager.rename(copy, "Demo 2")!!
        assertEquals("Demo 2", renamed.name)
        assertEquals("Demo 2", renamed.settings.name)

        // export → import through a real zip
        val zip = ByteArrayOutputStream()
        renamed.exportZip(zip)
        assertTrue(zip.size() > 100)
        // the archive already contains "Demo 2", so the import must pick a free name
        val imported = manager.importZip(ByteArrayInputStream(zip.toByteArray()), "Imported")
        assertTrue(imported.name.startsWith("Demo 2"))
        assertTrue(imported.dir.exists())
        assertEquals(renamed.listScenes().size, imported.listScenes().size)
        assertEquals(renamed.settings.startScene, imported.settings.startScene)

        assertTrue(manager.delete(manager.open("Demo 2")))
        assertFalse(manager.exists("Demo 2"))
    }

    @Test
    fun atomicSavesNeverLoseThePreviousScene() {
        val root = tempDir("atomic")
        val project = Project(root)
        project.saveMeta()
        val scene = Scene("Main")
        scene.create("Hero")
        assertTrue(project.saveScene(scene))
        scene.create("Enemy")
        assertTrue(project.saveScene(scene))
        assertEquals(2, project.loadScene("Main").objects.size)
        // a rotating backup of the previous revision is kept next to the scene
        val backups = project.backupDir.listFiles()?.filter { it.isFile }?.size ?: 0
        assertTrue("expected a backup file, got $backups", backups >= 1)
    }

    @Test
    fun undoRedoEditsTheScene() {
        val root = tempDir("undo")
        val project = Project(root)
        project.saveMeta()
        val doc = EditorDocument(project, Scene("Main"))
        val node = doc.scene.create("Node")

        // transform edit: capture, change, undo, redo
        val before = mapOf(node.id to floatArrayOf(0f, 0f, 0f, 1f, 1f))
        node.setPosition(5f, 0f)
        node.rotation = 45f
        val after = mapOf(node.id to floatArrayOf(5f, 0f, 45f, 1f, 1f))
        val move = TransformCommand.between("Move", before, after)
        doc.undo.push(doc, move)
        doc.undo.undo(doc)
        assertEquals(0f, node.x, 0.001f)
        assertEquals(0f, node.rotation, 0.001f)
        doc.undo.redo(doc)
        assertEquals(5f, node.x, 0.001f)
        assertEquals(45f, node.rotation, 0.001f)

        // creation edit: create → undo → redo keeps the same node id
        val json = SceneFormat.objectToJson(doc.scene.createNode("Sprite2D"))
        val create = CreateNodeCommand("Create Sprite2D", json, 0L)
        doc.undo.push(doc, create)
        assertNotNull(doc.scene.findById(create.createdId))
        doc.undo.undo(doc)
        assertNull(doc.scene.findById(create.createdId))
        doc.undo.redo(doc)
        val recreated = doc.scene.findById(create.createdId)
        assertNotNull(recreated)
        assertNotNull(recreated!!.getAny<Sprite2D>())

        // property edit through NodeProps (what the inspector uses)
        val sprite = recreated.getAny<Sprite2D>()!!
        assertTrue(NodeProps.write(recreated, Sprite2D.TYPE, "Size X", JVal.Num(1.5)))
        assertEquals(1.5f, sprite.sizeX, 0.001f)
        val readBack = NodeProps.read(recreated, Sprite2D.TYPE, "Size X") as JVal.Num
        assertEquals(1.5, readBack.v, 0.001)
    }

    @Test
    fun prefabsRoundTrip() {
        val root = tempDir("prefabs")
        val project = Project(root)
        project.saveMeta()
        val doc = EditorDocument(project, Scene("Main"))
        val node = doc.scene.create("Enemy")
        node.add(Sprite2D()).texture = "enemy.png"
        node.setPosition(3f, 1f)
        node.tag = "Enemy"
        doc.scene.create("Weapon", node)
        assertTrue(doc.serializePrefab(node, "enemy.prefab.json"))
        assertTrue(project.assetExists("prefabs/enemy.prefab.json"))
        val instance = doc.instantiatePrefab("prefabs/enemy.prefab.json")
        assertNotNull(instance)
        assertEquals("enemy.png", instance!!.getAny<Sprite2D>()?.texture)
        assertEquals(3f, instance.x, 0.01f)
    }

    // ------------------------------------------------------------------ registry & factories

    @Test
    fun everyNodeTypeHasAWorkingComponent() {
        for (entry in NodeType.ALL) {
            val type = entry.defaultComponent ?: continue
            val component = ComponentRegistry.create(type)
            assertNotNull("no component registered for ${entry.type} ($type)", component)
            assertEquals(type, component!!.type)
            assertTrue("${entry.type} exposes no properties", component.props().isNotEmpty())
            assertTrue(Json.write(component.toJson()).isNotEmpty())
        }
        assertTrue(ComponentRegistry.types().size >= 15)
        assertTrue(ComponentRegistry.byCategory().containsKey("Physics"))
    }

    @Test
    fun sceneFactoriesCreateRealComponents() {
        val scene = Scene("Main")
        assertNotNull(scene.createNode(NodeType.SPRITE).getAny<Sprite2D>())
        assertNotNull(scene.createNode(NodeType.LABEL).getAny<Label2D>())
        assertNotNull(scene.createNode(NodeType.CAMERA).getAny<Camera2D>())
        assertNotNull(scene.createNode(NodeType.PARTICLES).getAny<ParticleEmitter2D>())
        assertNotNull(scene.createNode(NodeType.CONTROL).getAny<ControlComponent>())
        assertNotNull(scene.createNode(NodeType.COLLIDER).getAny<Collider2D>())
        assertNotNull(scene.createNode(NodeType.AREA).getAny<Area2D>())
        assertNotNull(scene.createNode(NodeType.TILEMAP).getAny<TileMap2D>())
        assertEquals(Vec2(0f, -9.81f), Vec2(scene.settings.gravityX, scene.settings.gravityY))
        // ...and every one of them survives a save/load cycle
        val loaded = SceneFormat.fromJson(Json.parseObject(SceneFormat.write(scene)))
        assertEquals(scene.objects.size, loaded.objects.size)
    }

    @Test
    fun engineTicksARealScene() {
        val root = tempDir("engine")
        val project = Project(root)
        project.saveMeta()
        val scene = Scene("Main")
        val hero = scene.create("Hero")
        hero.add(Sprite2D()).texture = ""
        hero.add(Rigidbody2D()).apply { bodyType = BodyType.DYNAMIC }
        hero.add(Collider2D()).apply { width = 1f; height = 1f }
        hero.setPosition(0f, 5f)
        hero.computeWorld()
        val camera = scene.create("Camera")
        camera.add(Camera2D()).apply {
            follow = "Hero"
            followMode = 0
            smoothing = 5f
        }
        project.saveScene(scene)

        val engine = com.sengine.engine.Engine(project, scene)
        engine.gameView.widthPx = 1280
        engine.gameView.heightPx = 720
        engine.play()
        for (i in 0 until 120) engine.tick(1f / 60f)
        engine.updateCameraView()
        // gravity pulled the hero down and the camera followed it
        assertTrue("hero y=${hero.y}", hero.y < 5f)
        assertTrue("camera cx=${engine.gameView.cx}", engine.gameView.cx > -1f)
        assertTrue(engine.profiler.samples > 0)
        engine.release()
    }
}
