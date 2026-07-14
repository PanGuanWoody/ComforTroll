package com.yichuan.thermalsurvey

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SurveyHistoryActivity : Activity() {
    private lateinit var content: LinearLayout
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)
        title = "巡测记录"
        content = AppUi.column(this)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(AppUi.BACKGROUND)
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    private fun renderHistory() {
        content.removeAllViews()
        content.addView(AppUi.title(this, "巡测记录", 24f))
        content.addView(AppUi.body(this, "每次开始并停止巡测都会独立保存。选择任意记录导出 CSV 与轨迹 GeoJSON。").apply {
            setPadding(0, dp(4), 0, dp(14))
        })
        val records = ThermalDb(this).use { it.listSurveys() }
        if (records.isEmpty()) {
            content.addView(AppUi.card(this, AppUi.body(this, "还没有巡测记录。")))
            return
        }
        for (record in records) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(AppUi.title(this@SurveyHistoryActivity, record.project, 17f))
                addView(AppUi.body(this@SurveyHistoryActivity, buildString {
                    append(if (record.route.isBlank()) "未填写路线" else "路线：${record.route}")
                    if (record.operator.isNotBlank()) append(" · ${record.operator}")
                    append("\n${dateFormat.format(Date(record.startedMs))}")
                    append(if (record.endedMs == null) " · 未正常结束" else " · ${duration(record.startedMs, record.endedMs)}")
                    append("\n传感器 ${record.sensorEvents} 条 · 定位 ${record.locations} 条 · 融合 ${record.fusedSamples} 条")
                }, 13f).apply { setPadding(0, dp(6), 0, dp(10)) })
                addView(AppUi.primaryButton(this@SurveyHistoryActivity, "导出这次记录") { export(record.id) })
            }
            content.addView(AppUi.card(this, panel), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
    }

    private fun export(surveyId: Long) {
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { SurveyExporter(this).exportSurvey(surveyId) }
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "已导出到 $it", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun duration(start: Long, end: Long): String {
        val seconds = ((end - start).coerceAtLeast(0L)) / 1000L
        return "${seconds / 60}分${seconds % 60}秒"
    }
}

