package com.yichuan.thermalsurvey

import android.content.Context
import android.hardware.Sensor

object SensorPreferences {
    private const val PREFS = "sensor_selection"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_SELECTED = "selected"

    fun sensorKey(sensor: Sensor): String = "${sensor.type}|${sensor.name}|${sensor.vendor}"

    fun isSelectable(sensor: Sensor): Boolean = sensor.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT

    fun selectedKeys(context: Context, sensors: List<Sensor>): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CONFIGURED, false)) {
            return sensors.filter(::isSelectable).map(::sensorKey).toSet()
        }
        return prefs.getStringSet(KEY_SELECTED, emptySet())?.toSet() ?: emptySet()
    }

    fun save(context: Context, keys: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putStringSet(KEY_SELECTED, keys)
            .apply()
    }
}

