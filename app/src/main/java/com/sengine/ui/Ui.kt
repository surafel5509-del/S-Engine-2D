package com.sengine.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object C {
    const val BG = 0xFF1E1F22.toInt()
    const val PANEL = 0xFF2B2D31.toInt()
    const val PANEL2 = 0xFF383A40.toInt()
    const val HEADER = 0xFF232428.toInt()
    const val FIELD = 0xFF1A1B1E.toInt()
    const val ACCENT = 0xFF4C8DFF.toInt()
    const val TEXT = 0xFFE6E6E6.toInt()
    const val DIM = 0xFF9AA0A6.toInt()
    const val SEL = 0xFF34507F.toInt()
    const val RED = 0xFFE5534B.toInt()
    const val GREEN = 0xFF57AB5A.toInt()
    const val YELLOW = 0xFFE0B341.toInt()
}

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.label(text: String, size: Float = 13f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Flat rounded button with ripple. */
fun Context.button(text: String, color: Int = C.PANEL2, textColor: Int = C.TEXT, onClick: (View) -> Unit): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minWidth = dp(40)
        background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), round(color, dp(6).toFloat()), null)
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
    }

fun TextView.setButtonColor(color: Int) {
    background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), round(color, context.dp(6).toFloat()), null)
}

fun Context.field(value: String, numeric: Boolean = false, multiline: Boolean = false): EditText =
    EditText(this).apply {
        setText(value)
        setTextColor(C.TEXT)
        textSize = 13f
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = round(C.FIELD, dp(4).toFloat(), 1, 0xFF3A3C42.toInt())
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (!multiline) {
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        setSelectAllOnFocus(numeric)
    }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun LinearLayout.LayoutParams.margins(l: Int, t: Int, r: Int, b: Int) = apply { setMargins(l, t, r, b) }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}

fun fmt(v: Float): String {
    if (v == Math.round(v).toFloat() && kotlin.math.abs(v) < 1e7) return Math.round(v).toString()
    return String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}
