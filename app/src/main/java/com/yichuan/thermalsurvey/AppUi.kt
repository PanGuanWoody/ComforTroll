package com.yichuan.thermalsurvey

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object AppUi {
    const val BACKGROUND = 0xFFFAFAFA.toInt()
    const val CARD = 0xFFF1F1F1.toInt()
    const val INK = 0xFF17252A.toInt()
    const val MUTED = 0xFF627278.toInt()
    var PRIMARY = 0xFF087F73.toInt()
    var PRIMARY_DARK = 0xFF075E56.toInt()
    var ACCENT = 0xFFFF7A45.toInt()
    var SOFT = 0xFFE7F5F2.toInt()
    const val BORDER = 0xFFD8E1E3.toInt()

    fun prepare(activity: Activity) {
        val palette = ThemeStore.current(activity)
        PRIMARY = palette.primary
        PRIMARY_DARK = palette.dark
        ACCENT = palette.accent
        SOFT = palette.soft
        activity.window.statusBarColor = PRIMARY_DARK
        activity.window.navigationBarColor = BACKGROUND
    }

    fun column(activity: Activity, padding: Int = 18): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(padding), activity.dp(18), activity.dp(padding), activity.dp(32))
    }

    fun title(activity: Activity, value: String, size: Float = 20f): TextView = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(INK)
        setTypeface(typeface, Typeface.BOLD)
    }

    fun body(activity: Activity, value: String, size: Float = 14f): TextView = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(MUTED)
        setLineSpacing(0f, 1.15f)
    }

    fun card(activity: Activity, content: View, color: Int = CARD): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(18), activity.dp(18), activity.dp(18), activity.dp(18))
        background = rounded(color, activity.dp(20))
        elevation = 0f
        addView(content)
    }

    fun primaryButton(activity: Activity, label: String, action: () -> Unit): Button = outlineButton(activity, label, action)

    fun secondaryButton(activity: Activity, label: String, action: () -> Unit): Button = outlineButton(activity, label, action)

    fun outlineButton(activity: Activity, label: String, action: () -> Unit): Button = Button(activity).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(PRIMARY)
        elevation = 0f
        translationZ = 0f
        stateListAnimator = null
        minHeight = 0
        minWidth = 0
        includeFontPadding = false
        background = RippleDrawable(
            ColorStateList.valueOf((PRIMARY and 0x00FFFFFF) or 0x33000000),
            rounded(Color.TRANSPARENT, activity.dp(14), PRIMARY, activity.dp(1)),
            rounded(Color.WHITE, activity.dp(14))
        )
        setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        setOnClickListener { action() }
    }

    fun field(activity: Activity, hintValue: String, value: String): EditText = EditText(activity).apply {
        hint = hintValue
        setText(value)
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(0xFF8A999E.toInt())
        setSingleLine(true)
        setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
        background = rounded(0xFFF1F1F1.toInt(), activity.dp(12))
    }

    fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }
}

fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

object ThemeStore {
    data class Palette(val key: String, val name: String, val primary: Int, val dark: Int, val accent: Int, val soft: Int)

    val palettes = listOf(
        Palette("gray", "白灰", 0xFF566166.toInt(), 0xFF30383C.toInt(), 0xFF768187.toInt(), 0xFFECEFF0.toInt()),
        Palette("teal", "深海青", 0xFF087F73.toInt(), 0xFF075E56.toInt(), 0xFFE36D3D.toInt(), 0xFFE5F4F1.toInt()),
        Palette("blue", "学术蓝", 0xFF3567A8.toInt(), 0xFF234A7A.toInt(), 0xFFE0833E.toInt(), 0xFFE8EFF8.toInt()),
        Palette("red", "砖石红", 0xFFA54C45.toInt(), 0xFF74332E.toInt(), 0xFFD19A38.toInt(), 0xFFF7EAE8.toInt()),
        Palette("purple", "沉静紫", 0xFF71558F.toInt(), 0xFF4E3966.toInt(), 0xFFD98156.toInt(), 0xFFF0EAF5.toInt())
    )

    fun current(context: android.content.Context): Palette {
        val key = context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
            .getString("palette", "teal")
        return palettes.firstOrNull { it.key == key } ?: palettes.first()
    }

    fun save(context: android.content.Context, key: String) {
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().putString("palette", key).apply()
    }
}
