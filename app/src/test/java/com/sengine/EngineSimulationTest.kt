package com.sengine

import com.sengine.engine.Engine
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.SceneSerializer
import com.sengine.project.Project
import com.sengine.project.Templates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Headless simulation of the bundled templates: runs the real engine loop,
 * physics and Rhino scripts without any rendering.
 */
class EngineSimulationTest {

    private fun newProject(template: Int): Project {
        val dir = Files.createTempDirectory("sengine").toFile()
        val p = Project(File(dir, "Test"))
        p.saveMeta()
        Templates.all[template].build(p)
        p.saveMeta()
        return p
    }

    private class Run(val engine: Engine, val errors: MutableList<String>, val logs: MutableList<String>)

    private fun start(p: Project): Run {
        val e = Engine(p, p.loadScene(p.startScene))
        e.gameView.widthPx = 1600; e.gameView.heightPx = 900
        val errors = ArrayList<String>()
        val logs = ArrayList<String>()
        e.listeners.add(object : Engine.Listener {
            override fun onLog(level: Int, message: String) {
                logs.add(message); if (level >= 2) errors.add(message)
                println("SIM log[$level]: $message")
            }
        })
        e.play()
        return Run(e, errors, logs)
    }

    private fun Run.frames(n: Int, each: (Int) -> Unit = {}) {
        repeat(n) { i -> each(i); synchronized(engine.lock) { engine.tick(1f / 60f) } }
    }

    @Test
    fun serializationRoundTrip() {
        val p = newProject(1)
        val s = p.loadScene("Main")
        val json = SceneSerializer.toJson(s).toString()
        val s2 = SceneSerializer.fromJson(JSONObject(json))
        assertEquals(s.objects.size, s2.objects.size)
        assertEquals(json, SceneSerializer.toJson(s2).toString())
        println("SIM platformer objects=${s.objects.size}")
    }

    @Test
    fun platformerPlayerMovesJumpsAndCollects() {
        val r = start(newProject(1))
        val player = r.engine.scene.find("Player")!!
        val x0 = player.x
        // settle on the ground
        r.frames(60)
        val rb = player.getAny<Rigidbody2D>()!!
        println("SIM settled y=${player.y} grounded=${rb.grounded}")
        assertTrue("player should be grounded", rb.grounded)
        // run right for 1s
        r.frames(60) { r.engine.input.joyX = 1f }
        println("SIM after run x=${player.x}")
        assertTrue("player should move right", player.x > x0 + 3f)
        // jump
        var maxY = player.y
        r.frames(50) { i -> r.engine.input.rawA = i < 3; maxY = maxOf(maxY, player.y) }
        println("SIM jump maxY=$maxY")
        assertTrue("player should jump", maxY > -0.5f)
        r.engine.input.rawA = false
        r.engine.input.joyX = 0f
        // teleport onto a coin to test trigger + spawn + destroy + text update
        val coinsBefore = r.engine.scene.objects.count { it.tag == "Coin" }
        val coin = r.engine.scene.objects.first { it.tag == "Coin" }
        synchronized(r.engine.lock) { player.x = coin.x; player.y = coin.y }
        r.frames(10)
        val coinsAfter = r.engine.scene.objects.count { it.tag == "Coin" }
        val label = r.engine.scene.find("ScoreText")!!.getAny<com.sengine.engine.core.TextRenderer>()!!.text
        println("SIM coins $coinsBefore -> $coinsAfter label='$label' objects=${r.engine.scene.objects.size}")
        assertEquals(coinsBefore - 1, coinsAfter)
        assertEquals("Coins: ${7 - coinsAfter}", label)
        r.frames(120) // FX cleanup timer
        assertTrue("CoinFX clone should be destroyed", r.engine.scene.objects.none { it.name.startsWith("CoinFX (") })
        assertTrue("script errors: ${r.errors}", r.errors.isEmpty())
        r.engine.stop()
        r.frames(1)
        assertEquals(Engine.Mode.EDIT, r.engine.mode)
        assertEquals(7, r.engine.scene.objects.count { it.tag == "Coin" }) // scene restored on stop
    }

    @Test
    fun shooterRunsWithoutErrors() {
        val r = start(newProject(2))
        r.frames(600) { r.engine.input.rawA = true; r.engine.input.joyX = if ((it / 60) % 2 == 0) 1f else -1f }
        val stars = r.engine.scene.objects.count { it.name.startsWith("Star (") }
        val score = r.engine.scene.find("ScoreText")!!.getAny<com.sengine.engine.core.TextRenderer>()!!.text
        println("SIM shooter stars=$stars objects=${r.engine.scene.objects.size} score='$score'")
        assertEquals(40, stars)
        assertTrue("script errors: ${r.errors}", r.errors.isEmpty())
    }

    @Test
    fun physicsSandboxTapSpawns() {
        val r = start(newProject(3))
        val before = r.engine.scene.objects.size
        r.frames(30)
        r.engine.input.rawTouchSX = 800f; r.engine.input.rawTouchSY = 200f; r.engine.input.tapPending = true
        r.frames(2)
        r.engine.input.tapPending = true
        r.frames(120)
        val after = r.engine.scene.objects.size
        val crates = r.engine.scene.objects.filter { it.name.startsWith("Crate") }
        println("SIM sandbox objects $before -> $after, lowest crate y=${crates.minOf { it.y }}")
        assertEquals(before + 2, after)
        assertTrue("crates should rest on the floor", crates.minOf { it.y } > -6.5f)
        assertTrue("script errors: ${r.errors}", r.errors.isEmpty())
    }

    @Test
    fun scriptErrorsAreReportedNotThrown() {
        val p = newProject(0)
        p.writeAsset("Bad.js", "function update(dt) { undefinedThing.foo(); }")
        val s = p.loadScene("Main")
        s.find("Square")!!.add(com.sengine.engine.core.ScriptComponent().also { it.script = "Bad.js" })
        p.saveScene(s)
        val r = start(p)
        r.frames(5)
        println("SIM errors=${r.errors}")
        assertEquals(1, r.errors.size)
    }
}
