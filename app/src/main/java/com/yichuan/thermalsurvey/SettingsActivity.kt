package com.yichuan.thermalsurvey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)
        val content = AppUi.column(this)
        content.addView(AppUi.title(this, "设置", 28f))
        content.addView(AppUi.body(this, "界面外观与系统增强", 14f).apply {
            setPadding(0, dp(4), 0, dp(18))
        })
        content.addView(entry("主题", "选择应用主色", "●") {
            startActivity(Intent(this, ThemeSettingsActivity::class.java))
        }, itemParams())
        content.addView(entry("Root 增强", "权限控制与 ComforTroll Guard 伴生模块", "⌁") {
            startActivity(Intent(this, GuardModuleActivity::class.java))
        }, itemParams())
        content.addView(entry("关于 App", "应用版本与研发团队", "i") {
            startActivity(Intent(this, AboutActivity::class.java))
        }, itemParams())
        setContentView(ScrollView(this).apply {
            setBackgroundColor(AppUi.BACKGROUND)
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        AppUi.prepare(this)
    }

    private fun entry(title: String, subtitle: String, icon: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = AppUi.rounded(AppUi.CARD, dp(20))
        setPadding(dp(18), dp(15), dp(14), dp(15))
        addView(TextView(this@SettingsActivity).apply {
            text = icon
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(AppUi.PRIMARY)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(AppUi.title(this@SettingsActivity, title, 17f))
            addView(AppUi.body(this@SettingsActivity, subtitle, 13f))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        addView(AppUi.body(this@SettingsActivity, "›", 28f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(36), dp(44)))
        setOnClickListener { action() }
    }

    private fun itemParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }
}
