package com.sengine.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sengine.engine.project.ProjectSettings

/**
 * Editor theme, UI scaling and localization.
 *
 * Colours are plain ints owned by the editor (not resource values) so the theme can be switched at
 * runtime between dark, light and high-contrast, and so the whole UI layer compiles without
 * generated resources. Everything scales through [scale] which is driven by the user's
 * "UI Scaling" preference — accessible on phones, tablets and desktops alike.
 */
class Theme(var dark: Boolean = true, var scale: Float = 1f, var highContrast: Boolean = false) {

    var background = 0xFF10151C.toInt()
    var panel = 0xFF171E27.toInt()
    var panelAlt = 0xFF1D2632.toInt()
    var border = 0xFF273140.toInt()
    var text = 0xFFD6DEE8.toInt()
    var textDim = 0xFF8A97A6.toInt()
    var accent = 0xFF4DA6FF.toInt()
    var accentText = 0xFF0B1017.toInt()
    var warning = 0xFFFFC24D.toInt()
    var error = 0xFFFF5C5C.toInt()
    var ok = 0xFF5CE65C.toInt()
    var selection = 0xFF2A4A6B.toInt()
    var track = 0xFF0C1219.toInt()

    fun applyDark() {
        dark = true
        if (highContrast) {
            background = 0xFF000000.toInt(); panel = 0xFF101010.toInt(); panelAlt = 0xFF1A1A1A.toInt()
            border = 0xFF6A6A6A.toInt(); text = 0xFFFFFFFF.toInt(); textDim = 0xFFC0C0C0.toInt()
        } else {
            background = 0xFF10151C.toInt(); panel = 0xFF171E27.toInt(); panelAlt = 0xFF1D2632.toInt()
            border = 0xFF273140.toInt(); text = 0xFFD6DEE8.toInt(); textDim = 0xFF8A97A6.toInt()
        }
        accent = 0xFF4DA6FF.toInt(); selection = 0xFF2A4A6B.toInt()
    }

    fun applyLight() {
        dark = false
        background = 0xFFF2F4F7.toInt()
        panel = 0xFFE7EBF0.toInt()
        panelAlt = 0xFFDCE2E9.toInt()
        border = 0xFFBFC7D1.toInt()
        text = 0xFF1B2229.toInt()
        textDim = 0xFF5A6672.toInt()
        accent = 0xFF1F6FEB.toInt()
        accentText = 0xFFFFFFFF.toInt()
        selection = 0xFFCFE0F5.toInt()
        track = 0xFFC9D2DC.toInt()
        if (highContrast) {
            background = 0xFFFFFFFF.toInt(); panel = 0xFFF0F0F0.toInt(); border = 0xFF7A7A7A.toInt(); text = 0xFF000000.toInt()
        }
    }

    fun apply(settings: ProjectSettings) {
        highContrast = settings.highContrast
        if (settings.theme == "light") applyLight() else applyDark()
        scale = settings.uiScale.coerceIn(0.75f, 2f)
    }

    // ---------------------------------------------------------------- sizing

    fun dp(value: Float): Int = Math.round(value * scale)

    fun textSize(sp: Float): Float = sp * scale

    fun pad(v: Float): Int = dp(v)

    fun clear(view: View) = view.setBackgroundColor(background)

    fun rounded(color: Int, radius: Float = 4f, stroke: Int = 0, strokeWidth: Float = 1f): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) d.setStroke(dp(strokeWidth).coerceAtLeast(1), stroke)
        return d
    }

    companion object {
        fun from(settings: ProjectSettings): Theme {
            val t = Theme()
            t.apply(settings)
            return t
        }
    }
}

/** Editor strings. English, Amharic and Arabic ship built in; more can be added at runtime. */
object Loc {

    private val tables = HashMap<String, Map<String, String>>()

    @Volatile var language = "en"

    init {
        tables["en"] = emptyMap()
        tables["am"] = mapOf(
            "scene" to "ትዕይንት",
            "project" to "ፕሮጀክት",
            "editor" to "ኤዲተር",
            "debug" to "ማረሚያ",
            "search" to "ፍለጋ",
            "inspector" to "መመርመሪያ",
            "assets" to "ግብዓቶች",
            "output" to "ውጤት",
            "profiler" to "ፕሮፋይለር",
            "settings" to "ቅንብሮች",
            "save" to "አስቀምጥ",
            "run" to "አስኪድ",
            "stop" to "አቁም",
            "create_project" to "ፕሮጀክት ፍጠር",
            "import_project" to "ፕሮጀክት አስገባ",
            "recent_projects" to "የቅርብ ጊዜ ፕሮጀክቶች",
            "open_scene" to "ትዕይንት ክፈት",
            "documentation" to "ሰነድ",
            "examples" to "ምሳሌዎች"
        )
        tables["ar"] = mapOf(
            "scene" to "المشهد",
            "project" to "المشروع",
            "editor" to "المحرر",
            "debug" to "التصحيح",
            "search" to "بحث",
            "inspector" to "المفتش",
            "assets" to "الأصول",
            "output" to "المخرجات",
            "profiler" to "المحلل",
            "settings" to "الإعدادات",
            "save" to "حفظ",
            "run" to "تشغيل",
            "stop" to "إيقاف",
            "create_project" to "إنشاء مشروع",
            "import_project" to "استيراد مشروع",
            "recent_projects" to "المشاريع الأخيرة",
            "open_scene" to "فتح مشهد",
            "documentation" to "التوثيق",
            "examples" to "أمثلة"
        )
    }

    val available: List<Pair<String, String>> = listOf(
        "en" to "English",
        "am" to "አማርኛ",
        "ar" to "العربية"
    )

    fun t(key: String, fallback: String): String = tables[language]?.get(key) ?: fallback

    /** True when the current language is right-to-left (the editor mirrors its panels). */
    val rtl get() = language == "ar"

    fun register(code: String, table: Map<String, String>) {
        tables[code] = table
    }
}

/** Shared dp/px helpers for code-built layouts. */
object Ui {
    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun sp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    fun darken(color: Int, factor: Float): Int {
        val r = ((color shr 16) and 0xFF) * factor
        val g = ((color shr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        val a = (color ushr 24) and 0xFF
        return Color.argb(a, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun linear(parent: ViewGroup, horizontal: Boolean, theme: Theme): LinearLayout {
        val l = LinearLayout(parent.context)
        l.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        l.setBackgroundColor(theme.background)
        parent.addView(l)
        return l
    }

    fun label(context: Context, text: String, theme: Theme, size: Float = 12f, color: Int = -1, bold: Boolean = false): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(if (color == -1) theme.text else color)
        tv.textSize = theme.textSize(size)
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.setPadding(theme.pad(6f), theme.pad(3f), theme.pad(6f), theme.pad(3f))
        return tv
    }

    fun edit(context: Context, text: String, theme: Theme, hint: String = "", single: Boolean = true): EditText {
        val e = EditText(context)
        e.setText(text)
        e.hint = hint
        e.setTextColor(theme.text)
        e.setHintTextColor(theme.textDim)
        e.textSize = theme.textSize(12f)
        e.setPadding(theme.pad(6f), theme.pad(3f), theme.pad(6f), theme.pad(3f))
        e.setBackgroundColor(theme.panelAlt)
        e.isSingleLine = single
        return e
    }

    fun params(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT): ViewGroup.LayoutParams {
        val p = ViewGroup.LayoutParams(w, h)
        return p
    }

    fun weightParams(weight: Float, w: Int = 0, h: Int = ViewGroup.LayoutParams.MATCH_PARENT): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.weight = weight
        return p
    }
}
