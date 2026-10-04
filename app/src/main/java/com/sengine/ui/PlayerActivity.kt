package com.sengine.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sengine.engine.Engine
import com.sengine.engine.render.SceneRenderer
import com.sengine.project.ProjectManager

/** Runs a project full-screen, exactly like an exported game. */
class PlayerActivity : AppCompatActivity() {

    private lateinit var engine: Engine
    private lateinit var glView: GLSurfaceView
    private lateinit var fpsText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val fpsTick = object : Runnable {
        override fun run() {
            fpsText.text = "${engine.fps.toInt()} FPS"
            handler.postDelayed(this, 500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val project = ProjectManager.open(this, intent.getStringExtra("project") ?: run { finish(); return })
        requestedOrientation = if (project.orientation == 1) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val sceneName = intent.getStringExtra("scene") ?: project.startScene
        engine = Engine(project, project.loadScene(sceneName))
        engine.listeners.add(object : Engine.Listener {
            override fun onLog(level: Int, message: String) {
                if (level >= 2) handler.post { android.widget.Toast.makeText(this@PlayerActivity, message, android.widget.Toast.LENGTH_SHORT).show() }
            }
        })

        val root = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(SceneRenderer(engine, null))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView.setOnTouchListener { v, e -> forwardTouch(v, e); true }
        root.addView(glView)
        root.addView(GameControlsView(this) { engine.input })
        fpsText = label("", 11f, 0x99FFFFFF.toInt()).apply { setPadding(dp(10), dp(6), 0, 0) }
        root.addView(fpsText, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        root.addView(button("✕", 0x55000000) { finish() },
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        setContentView(root)
        hideSystemUi()
        engine.play()
    }

    private fun forwardTouch(v: View, e: MotionEvent) {
        val inp = engine.input
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inp.rawTouching = true; inp.rawTouchSX = e.x; inp.rawTouchSY = e.y; inp.tapPending = true
            }
            MotionEvent.ACTION_MOVE -> { inp.rawTouchSX = e.x; inp.rawTouchSY = e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> inp.rawTouching = false
        }
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        engine.input.keys.add(keyCode); return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        engine.input.keys.remove(keyCode); return super.onKeyUp(keyCode, event)
    }

    override fun onResume() { super.onResume(); glView.onResume(); handler.post(fpsTick); hideSystemUi() }
    override fun onPause() { super.onPause(); glView.onPause(); handler.removeCallbacks(fpsTick) }

    override fun onDestroy() {
        super.onDestroy()
        if (::engine.isInitialized) engine.release()
    }
}
