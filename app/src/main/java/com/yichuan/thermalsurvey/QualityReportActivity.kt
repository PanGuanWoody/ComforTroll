package com.yichuan.thermalsurvey

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class QualityReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)
        val surveyId = intent.getLongExtra(EXTRA_SURVEY_ID, -1L)
        val content = AppUi.column(this)
        content.addView(AppUi.title(this, "质量报告", 28f))
        content.addView(AppUi.body(this, "巡测数据完整性与连续性", 14f).apply {
            setPadding(0, dp(4), 0, dp(18))
        })
        val reportText = TextView(this).apply {
            text = "正在生成报告…"
            textSize = 13f
            setTextColor(AppUi.INK)
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
        }
        content.addView(AppUi.card(this, reportText), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(AppUi.BACKGROUND)
            addView(content)
        })

        Thread {
            val result = runCatching {
                SurveyQualityReport(this).let { generator ->
                    try { generator.generate(surveyId) } finally { generator.close() }
                }
            }
            runOnUiThread {
                reportText.text = result.getOrElse { "报告生成失败：${it.message}" }
            }
        }.start()
    }

    companion object {
        const val EXTRA_SURVEY_ID = "survey_id"
    }
}
