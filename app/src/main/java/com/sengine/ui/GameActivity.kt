package com.sengine.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.sengine.engine.Engine
import com.sengine.engine.core.Camera2D
import com.sengine.engine.project.Project
import com.sengine.platform.android.AndroidAudio
import com.sengine.platform.android.AndroidTextRenderer
import com.sengine.platform.android.AndroidTextures
import com.sengine.platform.gl.GLRenderer2D
import java.io.File

/**
 * Play mode: runs the project's scene with the real engine.
 *
 * Rendering, physics, scripting, audio, particles, animation and the 2D UI runtime are the same code
 * the editor previews — this activity simply drives [Engine.tick] from the GL thread, feeds the real
 * [com.sengine.engine.input.InputSystem] from touch/keyboard/gamepad, and maps touch into the input
 * actions via [TouchControls] (virtual stick + buttons), so a script using `SInput` behaves the same
 * on device and in the editor.
 */
class GameActivity : Activity() {

    private lateinit var project: Project
    private lateinit var engine: Engine
    private lateinit var glView: GLSurfaceView
    private lateinit var textures: AndroidTextures
    private lateinit var textRenderer: AndroidTextRenderer
    private lateinit var audio: AndroidAudio
    private var renderer: GLRenderer2D? = null
    private var showStats = true
    private var touchControls: TouchControls? = null
    private lateinit var statsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        val theme = AppState.theme
        val path = intent.getStringExtra("project")
        val dir = if (path != null) File(path) else AppState.manager().open(AppState.lastProjectName()).dir
        project = Project(dir)
        val sceneName = intent.getStringExtra("scene") ?: project.settings.startScene
        val scene = project.loadScene(sceneName)
        val settings = project.settings

        engine = Engine(project, scene)
        engine.requestMode(Engine.Mode.PLAY)
        textures = AndroidTextures { name -> project.assetFile(name).takeIf { it.exists() } }
        textRenderer = AndroidTextRenderer(textures)
        audio = AndroidAudio(this) { name -> project.assetFile(name).takeIf { it.exists() } }
        audio.onError = { clip, error -> com.sengine.engine.debug.Log.warn("Audio", "$clip: $error") }
        textures.onDecodeError = { name, error -> com.sengine.engine.debug.Log.error("Texture", "$name: $error") }
        engine.audio.backend = audio
        engine.input.map = settings.inputMap
        engine.project.settings.mixer.let { mixer ->
            engine.audio.fromJson(mixer.toJson())
        }
        engine.audio.listenerX = 0f
        engine.audio.listenerY = 0f

        val root = FrameLayout(this)
        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(object : GLSurfaceView.Renderer {
            private var last = System.nanoTime()

            override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
                renderer = GLRenderer2D(textures, textRenderer).apply {
                    materialSource = { name -> engine.resources.material(name) }
                    onShaderError = { name, log -> com.sengine.engine.debug.Log.error("Shader", "$name: $log") }
                }
            }

            override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
                renderer?.surfaceChanged(width, height)
            }

            override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                last = now
                val r = renderer ?: return
                synchronized(engine.lock) {
                    engine.tick(dt)
                    val view = engine.gameView
                    view.widthPx = r.widthPx
                    view.heightPx = r.heightPx
                    engine.updateCameraView()
                    engine.buildRenderList(view, null, false)
                    r.frame(engine.renderList, view)
                }
                if (showStats) runOnUiThread { updateStats() }
            }
        })
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        touchControls = TouchControls(this, engine, theme)
        root.addView(touchControls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statsView = Ui.label(this, "", theme, 11f, 0xFFB8FFB8.toInt())
        val statsParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP)
        root.addView(statsView, statsParams)

        val close = Ui.label(this, "◀ Editor", theme, 12f, 0xFFB8FFB8.toInt())
        close.setPadding(theme.pad(10f), theme.pad(8f), theme.pad(10f), theme.pad(8f))
        close.isClickable = true
        close.setOnClickListener { finish() }
        root.addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP))
        setContentView(root)

        // hide system bars for a real game frame
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        com.sengine.engine.debug.Log.info("Game", "Running '${scene.name}' from ${project.name}")
    }

    private fun updateStats() {
        val p = engine.profiler
        val stats = engine.renderList.stats
        statsView.text = "%.0f fps  %.2f ms  draws %d  sprites %d  bodies %d".format(
            p.smoothFps, p.frameMs.last(), stats.drawCalls, stats.sprites, engine.physics.bodyCount
        )
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        audio.resumeAll()
        engine.requestMode(Engine.Mode.PLAY)
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        audio.pauseAll()
        engine.requestMode(Engine.Mode.PAUSED)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.scripts.destroyAll()
        audio.release()
        textures.releaseAll()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        engine.input.devices.keys.add(keyCode)
        engine.input.devices.keysPressedThisFrame.add(keyCode)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            showStats = !showStats
            statsView.visibility = if (showStats) View.VISIBLE else View.GONE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        engine.input.devices.keys.remove(keyCode)
        engine.input.devices.keysReleasedThisFrame.add(keyCode)
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // gamepad axes and buttons
        val device = event.device
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) || event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)) {
            engine.input.devices.axes[0] = event.getAxisValue(MotionEvent.AXIS_X)
            engine.input.devices.axes[1] = event.getAxisValue(MotionEvent.AXIS_Y)
            engine.input.devices.gamepadConnected = true
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * On-screen controls: a real virtual stick writing into the input devices (exactly what a gamepad
     * axis does) and buttons that press the engine's jump/attack/interact keys. They are hidden when
     * a hardware gamepad is connected.
     */
    @SuppressLint("ViewConstructor")
    private class TouchControls(context: android.content.Context, val engine: Engine, val theme: Theme) : View(context) {
        private var stickId = -1
        private var stickOriginX = 0f
        private var stickOriginY = 0f
        private val buttonIds = HashMap<Int, Int>()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (engine.profiler.activeNodes < 0) return
            val cx = width * 0.16f
            val cy = height * 0.72f
            val r = Math.min(width, height) * 0.12f
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = theme.dp(2f).toFloat()
            paint.color = Ui.withAlpha(theme.text, 0.35f)
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = Ui.withAlpha(theme.accent, 0.35f)
            canvas.drawCircle(cx + engine.input.devices.axes[0] * r, cy + engine.input.devices.axes[1] * r, r * 0.35f, paint)
            // buttons
            for ((i, key) in BUTTON_KEYS.withIndex()) {
                val bx = width - r * (1.2f + i * 1.6f)
                val by = height * 0.72f
                paint.color = Ui.withAlpha(if (key in engine.input.devices.keys) theme.accent else theme.panel, 0.5f)
                canvas.drawCircle(bx, by, r * 0.55f, paint)
                paint.color = theme.text
                paint.textSize = r * 0.4f
                canvas.drawText(BUTTON_LABELS[i], bx - r * 0.18f, by + r * 0.15f, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val r = Math.min(width, height) * 0.12f
                    if (x < width * 0.4f) {
                        stickId = event.getPointerId(index)
                        stickOriginX = x
                        stickOriginY = y
                        invalidate()
                        return true
                    }
                    for ((i, key) in BUTTON_KEYS.withIndex()) {
                        val bx = width - r * (1.2f + i * 1.6f)
                        val by = height * 0.72f
                        if (Math.hypot((x - bx).toDouble(), (y - by).toDouble()) < r * 0.8) {
                            buttonIds[event.getPointerId(index)] = key
                            engine.input.devices.keys.add(key)
                            engine.input.devices.keysPressedThisFrame.add(key)
                            invalidate()
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = event.findPointerIndex(stickId)
                    if (idx >= 0) {
                        val r = Math.min(width, height) * 0.12f
                        val dx = (event.getX(idx) - stickOriginX) / r
                        val dy = (event.getY(idx) - stickOriginY) / r
                        engine.input.devices.axes[0] = dx.coerceIn(-1f, 1f)
                        engine.input.devices.axes[1] = dy.coerceIn(-1f, 1f)
                        engine.input.devices.stickActive = true
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val index = event.actionIndex
                    val id = event.getPointerId(index)
                    if (id == stickId) {
                        stickId = -1
                        engine.input.devices.axes[0] = 0f
                        engine.input.devices.axes[1] = 0f
                        engine.input.devices.stickActive = false
                    }
                    buttonIds.remove(id)?.let { key ->
                        engine.input.devices.keys.remove(key)
                        engine.input.devices.keysReleasedThisFrame.add(key)
                    }
                    invalidate()
                    return true
                }
            }
            return true
        }

        companion object {
            private val BUTTON_KEYS = intArrayOf(62, 38, 33) // jump (space), attack (J), interact (E)
            private val BUTTON_LABELS = listOf("A", "B", "X")
        }
    }
}
