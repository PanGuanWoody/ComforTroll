package com.yichuan.thermalsurvey

import android.hardware.Sensor

object SensorPurpose {
    fun name(sensor: Sensor): String = nameByType(sensor.type)

    fun nameByType(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "加速度与运动"
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "未校准加速度"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "环境温度"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "游戏姿态"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "地磁姿态"
        Sensor.TYPE_GRAVITY -> "重力方向"
        Sensor.TYPE_GYROSCOPE -> "陀螺仪与转动"
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "未校准陀螺仪"
        Sensor.TYPE_HEART_BEAT -> "心跳检测"
        Sensor.TYPE_HEART_RATE -> "心率"
        Sensor.TYPE_HINGE_ANGLE -> "折叠铰链角度"
        Sensor.TYPE_LIGHT -> "环境光"
        Sensor.TYPE_LINEAR_ACCELERATION -> "线性加速度"
        Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> "离体检测"
        Sensor.TYPE_MAGNETIC_FIELD -> "磁场与方向"
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "未校准磁场"
        Sensor.TYPE_MOTION_DETECT -> "运动检测"
        @Suppress("DEPRECATION") Sensor.TYPE_ORIENTATION -> "设备方向"
        Sensor.TYPE_POSE_6DOF -> "六自由度姿态"
        Sensor.TYPE_PRESSURE -> "气压与相对高度"
        Sensor.TYPE_PROXIMITY -> "接近与遮挡"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "相对湿度"
        Sensor.TYPE_ROTATION_VECTOR -> "设备姿态"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "显著运动"
        Sensor.TYPE_STATIONARY_DETECT -> "静止检测"
        Sensor.TYPE_STEP_COUNTER -> "累计步数"
        Sensor.TYPE_STEP_DETECTOR -> "步伐检测"
        else -> "未知"
    }
}
