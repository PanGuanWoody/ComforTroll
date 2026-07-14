package com.yichuan.thermalsurvey

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

class SensorInventoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)
        title = "设备传感器清单"
        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensors = manager.getSensorList(Sensor.TYPE_ALL).sortedWith(compareBy({ it.type }, { it.name }))
        val content = AppUi.column(this)
        content.addView(AppUi.title(this, "设备传感器清单", 24f))
        content.addView(AppUi.body(this, "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n系统公开 ${sensors.size} 个传感器").apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        for (sensor in sensors) {
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(AppUi.title(this@SensorInventoryActivity, "[${sensor.type}] ${sensor.name}", 16f))
                addView(AppUi.body(this@SensorInventoryActivity, buildString {
                    append("用途：${SensorPurpose.name(sensor)}\n")
                    append("厂商：${sensor.vendor} · 版本：${sensor.version}\n")
                    append("模式：${modeName(sensor)} · ${if (sensor.isWakeUpSensor) "唤醒型" else "非唤醒型"}\n")
                    append("分辨率：${sensor.resolution} · 最大量程：${sensor.maximumRange}\n")
                    append("功耗：${sensor.power} mA · 最小延迟：${sensor.minDelay} μs\n")
                    append("FIFO：${sensor.fifoReservedEventCount}/${sensor.fifoMaxEventCount}")
                }, 12f))
            }
            content.addView(AppUi.card(this, details), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(AppUi.BACKGROUND)
            addView(content)
        })
    }

    private fun modeName(sensor: Sensor): String = when (sensor.reportingMode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "连续"
        Sensor.REPORTING_MODE_ON_CHANGE -> "变化时"
        Sensor.REPORTING_MODE_ONE_SHOT -> "单次触发"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "特殊触发"
        else -> sensor.reportingMode.toString()
    }
}
