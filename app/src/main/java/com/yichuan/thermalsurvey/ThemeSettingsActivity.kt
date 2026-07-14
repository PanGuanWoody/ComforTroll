package com.yichuan.thermalsurvey

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class ThemeSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)
        title = "主题"
        val current = ThemeStore.current(this)
        val content = AppUi.column(this)
        content.addView(AppUi.title(this, "主题", 28f))
        content.addView(AppUi.body(this, "选择界面主色，返回后立即生效。", 14f).apply {
            setPadding(0, dp(4), 0, dp(18))
        })
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        ThemeStore.palettes.forEach { palette ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = AppUi.rounded(palette.soft, dp(16))
                val swatch = TextView(this@ThemeSettingsActivity).apply {
                    text = "●"
                    textSize = 28f
                    setTextColor(palette.primary)
                    gravity = android.view.Gravity.CENTER
                }
                addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(RadioButton(this@ThemeSettingsActivity).apply {
                    id = android.view.View.generateViewId()
                    tag = palette.key
                    text = palette.name
                    textSize = 16f
                    setTextColor(AppUi.INK)
                    isChecked = palette.key == current.key
                    setOnClickListener {
                        ThemeStore.save(this@ThemeSettingsActivity, palette.key)
                        finish()
                    }
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
            group.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
        }
        content.addView(group)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(AppUi.BACKGROUND)
            addView(content)
        })
    }
}
