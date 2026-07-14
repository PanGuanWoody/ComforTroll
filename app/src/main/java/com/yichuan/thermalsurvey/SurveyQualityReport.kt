package com.yichuan.thermalsurvey

import android.content.Context
import android.hardware.Sensor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SurveyQualityReport(context: Context) {
    private val db = ThermalDb(context)

    fun generate(surveyId: Long): String {
        val database = db.readableDatabase
        val survey = database.rawQuery(
            "SELECT project_name,operator_name,started_ms,ended_ms,root_requested,root_granted FROM surveys WHERE id=?",
            arrayOf(surveyId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalStateException("找不到巡测记录")
            Survey(
                cursor.getString(0), cursor.getString(1) ?: "", cursor.getLong(2),
                if (cursor.isNull(3)) System.currentTimeMillis() else cursor.getLong(3),
                cursor.getInt(4) == 1, cursor.getInt(5) == 1
            )
        }

        val reportingModes = mutableMapOf<String, Int>()
        database.rawQuery(
            "SELECT sensor_type,sensor_name,reporting_mode FROM sensor_inventory WHERE survey_id=?",
            arrayOf(surveyId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) reportingModes["${cursor.getInt(0)}:${cursor.getString(1)}"] = cursor.getInt(2)
        }

        val sensorStats = linkedMapOf<String, StreamStats>()
        database.rawQuery(
            "SELECT sensor_type,sensor_name,unix_time_ms FROM sensor_events WHERE survey_id=? ORDER BY sensor_type,sensor_name,unix_time_ms",
            arrayOf(surveyId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = "${cursor.getInt(0)}:${cursor.getString(1)}"
                sensorStats.getOrPut(key) { StreamStats(cursor.getInt(0), cursor.getString(1)) }.add(cursor.getLong(2))
            }
        }

        val location = LocationStats()
        database.rawQuery(
            "SELECT unix_time_ms,horizontal_accuracy FROM locations WHERE survey_id=? ORDER BY unix_time_ms",
            arrayOf(surveyId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) location.add(cursor.getLong(0), if (cursor.isNull(1)) null else cursor.getDouble(1))
        }

        val device = StreamStats(0, "设备状态")
        database.rawQuery(
            "SELECT unix_time_ms FROM device_status WHERE survey_id=? ORDER BY unix_time_ms",
            arrayOf(surveyId.toString())
        ).use { cursor -> while (cursor.moveToNext()) device.add(cursor.getLong(0)) }

        val root = StreamStats(0, "Root 样本")
        database.rawQuery(
            "SELECT unix_time_ms FROM root_samples WHERE survey_id=? ORDER BY unix_time_ms",
            arrayOf(surveyId.toString())
        ).use { cursor -> while (cursor.moveToNext()) root.add(cursor.getLong(0)) }

        val fusedCount = scalarLong("SELECT COUNT(*) FROM fused_samples_10s WHERE survey_id=?", surveyId)
        val resumeCount = scalarLong("SELECT COUNT(*) FROM events WHERE survey_id=? AND event_type='RECORDING_RESUMED'", surveyId)
        val issues = mutableListOf<String>()
        database.rawQuery(
            "SELECT event_type,note FROM events WHERE survey_id=? AND (event_type LIKE '%ERROR%' OR event_type IN ('SENSOR_REGISTER_FAILED','LOCATION_PERMISSION_MISSING','ROOT_PERMISSION_DENIED')) ORDER BY unix_time_ms",
            arrayOf(surveyId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) issues += cursor.getString(0) + (cursor.getString(1)?.let { "：$it" } ?: "")
        }

        val warnings = mutableListOf<String>()
        if (location.count == 0L) warnings += "未记录到定位数据"
        if (location.maxGapMs > 30_000L) warnings += "定位最大缺测超过 30 秒"
        if (location.accuracyCount > 0 && location.goodAccuracyCount.toDouble() / location.accuracyCount < 0.7) warnings += "定位精度 20 m 以内的比例低于 70%"
        if (resumeCount > 0) warnings += "采集服务发生 $resumeCount 次恢复"
        if (issues.isNotEmpty()) warnings += "记录到 ${issues.size} 个采集错误"
        if (survey.rootRequested && !survey.rootGranted) warnings += "Root 增强未获得授权"
        if (survey.rootGranted && root.count == 0L) warnings += "已获 Root 权限但没有 Root 样本"
        if (survey.rootGranted && root.maxGapMs > 30_000L) warnings += "Root 样本最大缺测超过 30 秒"
        sensorStats.values.forEach { stat ->
            if (reportingModes["${stat.type}:${stat.name}"] == Sensor.REPORTING_MODE_CONTINUOUS && stat.maxGapMs > 10_000L) {
                warnings += "${SensorPurpose.nameByType(stat.type)}最大缺测超过 10 秒"
            }
        }

        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("ComforTroll 巡测质量报告")
            appendLine("地点：${survey.location}")
            appendLine("人员：${survey.operator.ifBlank { "未填写" }}")
            appendLine("开始：${timeFormat.format(Date(survey.startedMs))}")
            appendLine("结束：${timeFormat.format(Date(survey.endedMs))}")
            appendLine("时长：${formatDuration(survey.endedMs - survey.startedMs)}")
            appendLine()
            appendLine("【总体结论】")
            if (warnings.isEmpty()) appendLine("未发现明显采集异常。") else warnings.forEach { appendLine("• $it") }
            appendLine()
            appendLine("【定位质量】")
            appendLine("定位记录：${location.count} 条")
            appendLine("平均水平精度：${location.averageAccuracy()?.let { "%.1f m".format(it) } ?: "无数据"}")
            appendLine("20 m 以内比例：${location.goodRate()?.let { "%.1f%%".format(it * 100) } ?: "无数据"}")
            appendLine("最大定位间隔：${formatGap(location.maxGapMs)}")
            appendLine()
            appendLine("【传感器连续性】")
            if (sensorStats.isEmpty()) appendLine("无传感器事件")
            sensorStats.values.forEach { stat ->
                appendLine("${SensorPurpose.nameByType(stat.type)}｜${stat.name}")
                appendLine("  ${stat.count} 条 · ${stat.rate()?.let { "%.2f Hz".format(it) } ?: "采样率不可计算"} · 最大间隔 ${formatGap(stat.maxGapMs)}")
            }
            appendLine()
            appendLine("【其他连续性】")
            appendLine("融合记录：$fusedCount 条")
            appendLine("设备状态：${device.count} 条 · 最大间隔 ${formatGap(device.maxGapMs)}")
            appendLine("服务恢复：$resumeCount 次")
            appendLine()
            appendLine("【Root 连续性】")
            appendLine("请求 Root：${if (survey.rootRequested) "是" else "否"}")
            appendLine("获得授权：${if (survey.rootGranted) "是" else "否"}")
            appendLine("Root 样本：${root.count} 条 · 最大间隔 ${formatGap(root.maxGapMs)}")
            appendLine()
            appendLine("【错误事件】")
            if (issues.isEmpty()) appendLine("无") else issues.forEach { appendLine("• $it") }
        }
    }

    fun close() = db.close()

    private fun scalarLong(sql: String, surveyId: Long): Long = db.readableDatabase.rawQuery(sql, arrayOf(surveyId.toString())).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    private fun formatGap(ms: Long): String = if (ms <= 0) "—" else "%.2f 秒".format(ms / 1000.0)
    private fun formatDuration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000
        return "${seconds / 3600}小时${seconds / 60 % 60}分${seconds % 60}秒"
    }

    private data class Survey(
        val location: String,
        val operator: String,
        val startedMs: Long,
        val endedMs: Long,
        val rootRequested: Boolean,
        val rootGranted: Boolean
    )

    private class StreamStats(val type: Int, val name: String) {
        var count = 0L
        var firstMs = 0L
        var lastMs = 0L
        var maxGapMs = 0L
        fun add(timeMs: Long) {
            if (count == 0L) firstMs = timeMs else maxGapMs = maxOf(maxGapMs, timeMs - lastMs)
            lastMs = timeMs
            count++
        }
        fun rate(): Double? = if (count > 1 && lastMs > firstMs) (count - 1) * 1000.0 / (lastMs - firstMs) else null
    }

    private class LocationStats {
        var count = 0L
        var lastMs = 0L
        var maxGapMs = 0L
        var accuracySum = 0.0
        var accuracyCount = 0L
        var goodAccuracyCount = 0L
        fun add(timeMs: Long, accuracy: Double?) {
            if (count > 0) maxGapMs = maxOf(maxGapMs, timeMs - lastMs)
            lastMs = timeMs
            count++
            if (accuracy != null) {
                accuracySum += accuracy
                accuracyCount++
                if (accuracy <= 20.0) goodAccuracyCount++
            }
        }
        fun averageAccuracy(): Double? = if (accuracyCount > 0) accuracySum / accuracyCount else null
        fun goodRate(): Double? = if (accuracyCount > 0) goodAccuracyCount.toDouble() / accuracyCount else null
    }
}
