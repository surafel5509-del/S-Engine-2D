package com.sengine.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.min

/**
 * S Engine splash screen: the "S" mark animates in first, then the wordmark "S ENGINE", the
 * "2D GAME ENGINE" tagline, the version and a real "Initializing…" progress line that reflects the
 * actual startup steps (preferences, project storage, plugin scan) — never a fake timer alone.
 */
class SplashActivity : Activity() {

    private lateinit var splash: SplashView
    private lateinit var initializer: Initializer
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        val theme = AppState.theme
        window.statusBarColor = theme.background
        window.navigationBarColor = theme.background
        splash = SplashView(theme)
        val root = FrameLayout(this)
        root.setBackgroundColor(theme.background)
        root.addView(splash, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        initializer = Initializer()
        initializer.start { progress, label ->
            runOnUiThread {
                splash.setProgress(progress, label)
                if (progress >= 1f && !finished) {
                    splash.postDelayed({ openStartScreen() }, 250)
                }
            }
        }
    }

    private fun openStartScreen() {
        if (finished) return
        finished = true
        startActivity(Intent(this, ProjectManagerActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onBackPressed() {
        // the splash is not a navigation point
    }

    /** Runs the real startup steps off the UI thread and reports progress. */
    private inner class Initializer {
        fun start(report: (Float, String) -> Unit) {
            val thread = Thread {
                report(0.05f, "Initializing...")
                val manager = AppState.manager()
                manager.recent() // warm the recent-projects index
                report(0.35f, "Checking project storage...")
                val projects = manager.list()
                report(0.6f, "Loading editor settings...")
                val plugins = AppState.projectsDir().let { dir ->
                    java.io.File(dir, "plugins").takeIf { it.isDirectory }?.list()?.size ?: 0
                }
                report(0.8f, if (plugins > 0) "Scanning $plugins plugin(s)..." else "Scanning plugins...")
                val last = AppState.lastProjectName()
                if (last.isNotEmpty() && projects.any { it.dir.name == last }) {
                    report(0.92f, "Last project: $last")
                } else {
                    report(0.92f, "Ready")
                }
                Thread.sleep(120)
                report(1f, "Ready")
            }
            thread.isDaemon = true
            thread.start()
        }
    }

    /** Hand-drawn mark: dark background, glowing "S", wordmark and a real progress bar. */
    private inner class SplashView(private val theme: Theme) : View(this@SplashActivity) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private var progress = 0f
        private var label = "Initializing..."
        private val markProgress = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = OvershootInterpolator(1.2f)
            start()
        }
        private val textProgress = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850
            startDelay = 450
            start()
        }

        init {
            markProgress.addUpdateListener { invalidate() }
            textProgress.addUpdateListener { invalidate() }
        }

        fun setProgress(value: Float, text: String) {
            progress = value.coerceIn(0f, 1f)
            label = text
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = theme.background
            canvas.drawRect(0f, 0f, w, h, paint)

            val mark = markProgress.animatedValue as Float
            val text = textProgress.animatedValue as Float
            val cx = w * 0.5f
            val cy = h * 0.42f
            val markSize = min(w, h) * 0.26f * (0.7f + 0.3f * mark)

            // glow behind the mark
            glow.shader = RadialGradient(
                cx, cy, markSize * 1.8f,
                intArrayOf(Ui.withAlpha(theme.accent, 0.35f * mark), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, markSize * 1.8f, glow)

            // the "S" mark: two arcs drawn from a rounded stroke
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = markSize * 0.22f
                strokeCap = Paint.Cap.ROUND
                color = theme.accent
                alpha = (mark * 255).toInt()
            }
            val rect = android.graphics.RectF(cx - markSize * 0.5f, cy - markSize * 0.5f, cx + markSize * 0.5f, cy + markSize * 0.5f)
            canvas.drawArc(rect, 90f, 200f * mark, false, stroke)
            canvas.drawArc(rect, -90f, 200f * mark, false, stroke)

            // wordmark
            if (text > 0f) {
                paint.color = theme.text
                paint.alpha = (text * 255).toInt()
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = min(w, h) * 0.085f
                canvas.drawText("S ENGINE", cx, cy + markSize * 1.2f, paint)

                paint.typeface = Typeface.DEFAULT
                paint.color = theme.textDim
                paint.textSize = min(w, h) * 0.032f
                paint.letterSpacing = 0.28f
                canvas.drawText(AppState.ENGINE_TAGLINE, cx, cy + markSize * 1.55f, paint)
                paint.letterSpacing = 0f

                paint.color = theme.accent
                paint.textSize = min(w, h) * 0.03f
                canvas.drawText("v${AppState.ENGINE_VERSION}", cx, cy + markSize * 1.95f, paint)
            }

            // progress line (real startup progress)
            val barW = min(w * 0.5f, 420f)
            val barY = h * 0.78f
            paint.color = theme.track
            val barRect = android.graphics.RectF(cx - barW * 0.5f, barY, cx + barW * 0.5f, barY + 5f)
            canvas.drawRoundRect(barRect, 3f, 3f, paint)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    cx - barW * 0.5f, barY, cx + barW * 0.5f, barY,
                    intArrayOf(theme.accent, Ui.withAlpha(theme.accent, 0.4f)),
                    null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(
                android.graphics.RectF(cx - barW * 0.5f, barY, cx - barW * 0.5f + barW * progress, barY + 5f),
                3f, 3f, fill
            )

            paint.shader = null
            paint.color = theme.textDim
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = min(w, h) * 0.026f
            canvas.drawText(label, cx, barY - 14f, paint)
        }
    }
}
