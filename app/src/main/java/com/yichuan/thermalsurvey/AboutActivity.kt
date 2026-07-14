package com.yichuan.thermalsurvey

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)
        val content = AppUi.column(this)
        content.addView(AppUi.title(this, "关于 App", 28f))
        content.addView(AppUi.body(this, "环境设计户外数据采集平台", 14f).apply {
            setPadding(0, dp(4), 0, dp(18))
        })

        val app = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(AppUi.title(this@AboutActivity, "ComforTroll", 20f))
            addView(row("应用版本", "1.0"))
            addView(AppUi.title(this@AboutActivity, "应用简介", 15f).apply {
                setPadding(0, dp(12), 0, 0)
            })
            addView(AppUi.body(this@AboutActivity,
                "面向环境设计研究与户外现场调查，协同记录位置、环境光、姿态、运动及设备传感器数据。适用于热舒适巡测、田野调查与空间环境观察，并支持分次保存、质量检查和完整数据导出。",
                14f
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        content.addView(AppUi.card(this, app), params())

        val team = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(AppUi.title(this@AboutActivity, "研发团队", 20f))
            addView(row("依托单位", "浙江理工大学"))
            addView(row("指导教师", "王鑫"))
            addView(row("开发人员", "张惠子 陈一川"))
        }
        content.addView(AppUi.card(this, team), params())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(AppUi.BACKGROUND)
            addView(content)
        })
    }

    private fun row(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, 0)
        addView(AppUi.body(this@AboutActivity, label, 13f), LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@AboutActivity).apply {
            text = value
            textSize = 14f
            setTextColor(AppUi.INK)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun params() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }
}
