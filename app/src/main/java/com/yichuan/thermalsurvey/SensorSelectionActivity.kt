package com.yichuan.thermalsurvey

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class SensorSelectionActivity : Activity() {
    private val switches = linkedMapOf<Sensor, IosSwitch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)

        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensors = manager.getSensorList(Sensor.TYPE_ALL).sortedWith(compareBy({ it.type }, { it.name }))
        val selected = SensorPreferences.selectedKeys(this, sensors)
        val content = AppUi.column(this)
        content.addView(AppUi.title(this, "记录传感器", 28f))
        content.addView(AppUi.body(this, "按当前设备实际能力选择采集通道", 14f).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(AppUi.secondaryButton(this, "全选") {
            switches.forEach { (sensor, toggle) -> if (SensorPreferences.isSelectable(sensor)) toggle.isChecked = true }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        controls.addView(AppUi.secondaryButton(this, "清空") {
            switches.values.forEach { it.isChecked = false }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        content.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        sensors.forEach { sensor ->
            val selectable = SensorPreferences.isSelectable(sensor)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@SensorSelectionActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(AppUi.title(this@SensorSelectionActivity, sensor.name, 15f))
                    addView(AppUi.body(this@SensorSelectionActivity,
                        "${SensorPurpose.name(sensor)} · 类型 ${sensor.type} · ${sensor.vendor} · ${modeName(sensor)}${if (!selectable) " · 不支持连续记录" else ""}", 12f))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
                val toggle = IosSwitch(this@SensorSelectionActivity).apply {
                    isEnabled = selectable
                    alpha = if (selectable) 1f else 0.35f
                    isChecked = selectable && SensorPreferences.sensorKey(sensor) in selected
                }
                switches[sensor] = toggle
                addView(toggle)
            }
            content.addView(AppUi.card(this, row), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        content.addView(AppUi.primaryButton(this, "保存选择") {
            val keys = switches.filter { (sensor, toggle) -> SensorPreferences.isSelectable(sensor) && toggle.isChecked }
                .keys.map(SensorPreferences::sensorKey).toSet()
            SensorPreferences.save(this, keys)
            Toast.makeText(this, "已选择 ${keys.size} 个传感器", Toast.LENGTH_SHORT).show()
            finish()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })

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
        else -> "模式 ${sensor.reportingMode}"
    }
}
