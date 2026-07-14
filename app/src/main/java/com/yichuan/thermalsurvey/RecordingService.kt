package com.yichuan.thermalsurvey

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI

class RecordingService : Service(), SensorEventListener, LocationListener {
    private lateinit var db: ThermalDb
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var rootThread: HandlerThread
    private lateinit var rootWorker: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var surveyId = -1L
    private var rootRequested = false
    private var rootGranted = false
    private var rootDenialLogged = false
    private var lastLocationTimeMs: Long? = null
    private var lastSpeed: Float? = null
    private var currentBatteryLevel: Int? = null
    private var currentBatteryTemperature: Double? = null
    private var currentScreenOn: Boolean? = null
    private var lightSum = 0.0
    private var lightMin: Float? = null
    private var lightMax: Float? = null
    private var lightCount = 0
    private var pitchSum = 0.0
    private var rollSum = 0.0
    private var orientationCount = 0
    private var fusedWindowStart = 0L

    private val deviceStatusTask = object : Runnable {
        override fun run() {
            runCatching { saveDeviceStatus() }.onFailure { recordError("DEVICE_STATUS_ERROR", it) }
            worker.postDelayed(this, 10_000L)
        }
    }

    private val fusedTask = object : Runnable {
        override fun run() {
            runCatching { saveFusedSample() }.onFailure { recordError("FUSED_SAMPLE_ERROR", it) }
            worker.postDelayed(this, 10_000L)
        }
    }

    private val rootTask = object : Runnable {
        override fun run() {
            if (!isRunning || !rootGranted) return
            val now = System.currentTimeMillis()
            RootShell.run("mkdir -p /data/adb/comfortroll; date +%s > /data/adb/comfortroll/heartbeat", 5L)
            val readings = RootCollector().collect()
            rootDeviceTemperatureC = readings.asSequence()
                .filter { it.category == "thermal" }
                .mapNotNull { normalizeTemperature(it.numeric) }
                .filter { it in -30.0..150.0 }
                .maxOrNull()?.toFloat()
            for (reading in readings) {
                db.insertRootSample(surveyId, now, reading.category, reading.path, reading.metric, reading.text, reading.numeric)
                rootSampleCount++
            }
            rootWorker.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = ThermalDb(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        workerThread = HandlerThread("thermal-recorder", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        worker = Handler(workerThread.looper)
        rootThread = HandlerThread("thermal-root", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        rootWorker = Handler(rootThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_MARK -> if (isRunning) {
                db.insertEvent(
                    surveyId,
                    intent.getStringExtra(EXTRA_EVENT_TYPE) ?: "MANUAL_MARK",
                    intent.getStringExtra(EXTRA_NOTE),
                    lastLatitude,
                    lastLongitude,
                    currentLightLux
                )
            }
            else -> if (!isRunning) startRecording(intent)
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent?) {
        startForeground(NOTIFICATION_ID, buildNotification("正在初始化采集通道"))
        resetRuntimeState()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedSurvey = prefs.getLong(PREF_ACTIVE_SURVEY, -1L)
        rootRequested = intent?.getBooleanExtra(EXTRA_ROOT_MODE, false)
            ?: prefs.getBoolean(PREF_ROOT_MODE, false)
        currentProject = intent?.getStringExtra(EXTRA_PROJECT)?.ifBlank { "未命名项目" }
            ?: prefs.getString(PREF_PROJECT, "未命名项目")!!
        currentRoute = intent?.getStringExtra(EXTRA_ROUTE) ?: prefs.getString(PREF_ROUTE, "")!!
        currentOperator = intent?.getStringExtra(EXTRA_OPERATOR) ?: prefs.getString(PREF_OPERATOR, "")!!
        val isNewSurvey = savedSurvey <= 0L
        surveyId = if (isNewSurvey) {
            db.startSurvey(currentProject, currentRoute, currentOperator, rootRequested)
        } else savedSurvey
        prefs.edit()
            .putLong(PREF_ACTIVE_SURVEY, surveyId)
            .putBoolean(PREF_ROOT_MODE, rootRequested)
            .putString(PREF_PROJECT, currentProject)
            .putString(PREF_ROUTE, currentRoute)
            .putString(PREF_OPERATOR, currentOperator)
            .apply()

        isRunning = true
        startedAtMs = System.currentTimeMillis()
        fusedWindowStart = startedAtMs
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ThermalSurvey")
            .apply { acquire() }
        db.insertEvent(surveyId, if (isNewSurvey) "RECORDING_STARTED" else "RECORDING_RESUMED")
        registerSensors(isNewSurvey)
        registerLocationAndGnss()
        worker.post(deviceStatusTask)
        worker.postDelayed(fusedTask, 10_000L)
        if (rootRequested) initializeRootMode()
        updateNotification("${currentProject} · 正在记录已选传感器与位置")
    }

    private fun resetRuntimeState() {
        sensorEventCount = 0L
        locationCount = 0L
        fusedSampleCount = 0L
        rootSampleCount = 0L
        registeredSensorCount = 0
        currentLightLux = null
        currentAcceleration = null
        currentGyroscope = null
        currentMagneticField = null
        currentPressureHpa = null
        currentAmbientTemperatureC = null
        currentBatteryTemperatureC = null
        currentSpeedMps = null
        rootDeviceTemperatureC = null
        lastLatitude = null
        lastLongitude = null
        lastLocationAccuracy = null
        visibleSatelliteCount = 0
        usedSatelliteCount = 0
        rootStatus = "未启用"
    }

    private fun initializeRootMode() {
        rootStatus = "等待授权"
        rootWorker.post { attemptRootConnection() }
    }

    private fun attemptRootConnection() {
        if (!isRunning || !rootRequested) return
        rootGranted = RootCollector().hasRoot()
        db.setRootGranted(surveyId, rootGranted)
        if (rootGranted) {
            rootStatus = "已连接"
            db.insertEvent(surveyId, "ROOT_ENABLED")
            RootShell.run("mkdir -p /data/adb/comfortroll; date +%s > /data/adb/comfortroll/recording.active; date +%s > /data/adb/comfortroll/heartbeat", 5L)
            rootTask.run()
        } else {
            rootStatus = "授权失败，30秒后重试"
            if (!rootDenialLogged) {
                db.insertEvent(surveyId, "ROOT_PERMISSION_DENIED")
                rootDenialLogged = true
            }
            rootWorker.postDelayed({ attemptRootConnection() }, 30_000L)
        }
    }

    private fun registerSensors(saveInventory: Boolean) {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val selected = SensorPreferences.selectedKeys(this, sensors)
        for (sensor in sensors) {
            if (saveInventory) db.insertSensorInventory(surveyId, sensor)
            if (!SensorPreferences.isSelectable(sensor) || SensorPreferences.sensorKey(sensor) !in selected) continue
            val periodUs = if (sensor.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS) 50_000 else 200_000
            if (sensorManager.registerListener(this, sensor, periodUs, worker)) {
                registeredSensorCount++
            } else {
                db.insertEvent(surveyId, "SENSOR_REGISTER_FAILED", "${sensor.type}:${sensor.name}")
            }
        }
    }

    private fun registerLocationAndGnss() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            db.insertEvent(surveyId, "LOCATION_PERMISSION_MISSING")
            return
        }
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 5_000L, 0f, this, worker.looper)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssCallback = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        val now = System.currentTimeMillis()
                        visibleSatelliteCount = status.satelliteCount
                        usedSatelliteCount = (0 until status.satelliteCount).count { status.usedInFix(it) }
                        for (index in 0 until status.satelliteCount) {
                            db.insertGnss(ContentValues().apply {
                                put("survey_id", surveyId)
                                put("unix_time_ms", now)
                                put("satellite_index", index)
                                put("constellation_type", status.getConstellationType(index))
                                put("svid", status.getSvid(index))
                                put("cn0_db_hz", status.getCn0DbHz(index))
                                put("elevation_degrees", status.getElevationDegrees(index))
                                put("azimuth_degrees", status.getAzimuthDegrees(index))
                                put("used_in_fix", if (status.usedInFix(index)) 1 else 0)
                                put("has_almanac", if (status.hasAlmanacData(index)) 1 else 0)
                                put("has_ephemeris", if (status.hasEphemerisData(index)) 1 else 0)
                            })
                        }
                    }
                }.also { locationManager.registerGnssStatusCallback(it, worker) }
            }
        } catch (error: Exception) {
            recordError("LOCATION_REGISTER_ERROR", error)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            val unixMs = System.currentTimeMillis() - ((SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L)
            db.insertSensor(surveyId, event.timestamp, unixMs, event.sensor.type, event.sensor.name, event.values, event.accuracy)
            sensorEventCount++
            if (event.sensor.type == Sensor.TYPE_LIGHT && event.values.isNotEmpty()) {
                val value = event.values[0]
                currentLightLux = value
                lightSum += value
                lightMin = lightMin?.coerceAtMost(value) ?: value
                lightMax = lightMax?.coerceAtLeast(value) ?: value
                lightCount++
            }
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> currentAcceleration = event.values.copyOf(3)
                Sensor.TYPE_GYROSCOPE -> currentGyroscope = event.values.copyOf(3)
                Sensor.TYPE_MAGNETIC_FIELD -> currentMagneticField = event.values.copyOf(3)
                Sensor.TYPE_PRESSURE -> currentPressureHpa = event.values.firstOrNull()
                Sensor.TYPE_AMBIENT_TEMPERATURE -> currentAmbientTemperatureC = event.values.firstOrNull()
            }
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val matrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                pitchSum += orientation[1] * 180.0 / PI
                rollSum += orientation[2] * 180.0 / PI
                orientationCount++
            }
        } catch (error: Exception) {
            recordError("SENSOR_WRITE_ERROR", error)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        try {
            db.insertLocation(ContentValues().apply {
                put("survey_id", surveyId)
                put("elapsed_realtime_ns", location.elapsedRealtimeNanos)
                put("unix_time_ms", location.time)
                put("provider", location.provider)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                if (location.hasAltitude()) put("altitude", location.altitude)
                if (location.hasAccuracy()) put("horizontal_accuracy", location.accuracy)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) put("vertical_accuracy", location.verticalAccuracyMeters)
                if (location.hasSpeed()) put("speed", location.speed)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) put("speed_accuracy", location.speedAccuracyMetersPerSecond)
                if (location.hasBearing()) put("bearing", location.bearing)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) put("bearing_accuracy", location.bearingAccuracyDegrees)
                val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
                put("is_mock", if (mock) 1 else 0)
            })
            lastLatitude = location.latitude
            lastLongitude = location.longitude
            lastLocationAccuracy = location.accuracy
            lastLocationTimeMs = location.time
            lastSpeed = if (location.hasSpeed()) location.speed else null
            currentSpeedMps = lastSpeed
            locationCount++
        } catch (error: Exception) {
            recordError("LOCATION_WRITE_ERROR", error)
        }
    }

    private fun saveDeviceStatus() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val runtime = Runtime.getRuntime()
        currentBatteryLevel = if (scale > 0) level * 100 / scale else level
        currentBatteryTemperature = if (tempTenths == Int.MIN_VALUE) null else tempTenths / 10.0
        currentBatteryTemperatureC = currentBatteryTemperature?.toFloat()
        currentScreenOn = power.isInteractive
        db.insertDeviceStatus(ContentValues().apply {
            put("survey_id", surveyId)
            put("unix_time_ms", System.currentTimeMillis())
            put("battery_level", currentBatteryLevel)
            currentBatteryTemperature?.let { put("battery_temperature_c", it) }
            put("charging_state", charging)
            put("screen_on", if (power.isInteractive) 1 else 0)
            put("app_memory_bytes", runtime.totalMemory() - runtime.freeMemory())
            put("available_storage_bytes", Environment.getDataDirectory().usableSpace)
        })
    }

    private fun saveFusedSample() {
        val now = System.currentTimeMillis()
        db.insertFusedSample(ContentValues().apply {
            put("survey_id", surveyId)
            put("sample_time_ms", now)
            put("window_start_ms", fusedWindowStart)
            lastLatitude?.let { put("latitude", it) }
            lastLongitude?.let { put("longitude", it) }
            lastLocationTimeMs?.let {
                put("location_time_ms", it)
                put("location_age_ms", now - it)
            }
            lastLocationAccuracy?.let { put("horizontal_accuracy", it) }
            lastSpeed?.let { put("speed", it) }
            put("visible_satellites", visibleSatelliteCount)
            put("used_satellites", usedSatelliteCount)
            if (lightCount > 0) {
                put("light_mean_lux", lightSum / lightCount)
                put("light_min_lux", lightMin)
                put("light_max_lux", lightMax)
            }
            put("light_event_count", lightCount)
            if (orientationCount > 0) {
                put("pitch_mean_deg", pitchSum / orientationCount)
                put("roll_mean_deg", rollSum / orientationCount)
            }
            put("orientation_event_count", orientationCount)
            currentBatteryLevel?.let { put("battery_level", it) }
            currentBatteryTemperature?.let { put("battery_temperature_c", it) }
            currentScreenOn?.let { put("screen_on", if (it) 1 else 0) }
        })
        fusedSampleCount++
        fusedWindowStart = now
        lightSum = 0.0
        lightMin = null
        lightMax = null
        lightCount = 0
        pitchSum = 0.0
        rollSum = 0.0
        orientationCount = 0
    }

    private fun stopRecording() {
        if (!isRunning) {
            stopSelf()
            return
        }
        runCatching { if (System.currentTimeMillis() > fusedWindowStart) saveFusedSample() }
        db.insertEvent(surveyId, "RECORDING_STOPPED", null, lastLatitude, lastLongitude, currentLightLux)
        db.endSurvey(surveyId)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_ACTIVE_SURVEY).apply()
        isRunning = false
        if (rootGranted) RootShell.run("rm -f /data/adb/comfortroll/recording.active /data/adb/comfortroll/heartbeat", 5L)
        worker.removeCallbacksAndMessages(null)
        rootWorker.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) gnssCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        rootStatus = if (rootRequested) "已停止" else "未启用"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun recordError(type: String, error: Throwable) {
        Log.e(TAG, type, error)
        if (surveyId > 0) runCatching { db.insertEvent(surveyId, type, error.javaClass.simpleName + ": " + error.message) }
    }

    private fun normalizeTemperature(value: Double?): Double? {
        value ?: return null
        return when {
            kotlin.math.abs(value) >= 1000.0 -> value / 1000.0
            kotlin.math.abs(value) >= 200.0 -> value / 10.0
            else -> value
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ComforTroll 巡测记录", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("ComforTroll 正在记录")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        if (isRunning) stopRecording()
        workerThread.quitSafely()
        rootThread.quitSafely()
        db.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.yichuan.thermalsurvey.START"
        const val ACTION_STOP = "com.yichuan.thermalsurvey.STOP"
        const val ACTION_MARK = "com.yichuan.thermalsurvey.MARK"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_OPERATOR = "operator"
        const val EXTRA_ROOT_MODE = "root_mode"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_NOTE = "note"
        private const val CHANNEL_ID = "thermal_recording"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ThermalSurvey"
        private const val PREFS = "active_survey"
        private const val PREF_ACTIVE_SURVEY = "active_survey_id"
        private const val PREF_ROOT_MODE = "root_mode"
        private const val PREF_PROJECT = "project"
        private const val PREF_ROUTE = "route"
        private const val PREF_OPERATOR = "operator"

        @Volatile var isRunning = false
        @Volatile var startedAtMs = 0L
        @Volatile var currentProject = ""
        @Volatile var currentRoute = ""
        @Volatile var currentOperator = ""
        @Volatile var currentLightLux: Float? = null
        @Volatile var currentAcceleration: FloatArray? = null
        @Volatile var currentGyroscope: FloatArray? = null
        @Volatile var currentMagneticField: FloatArray? = null
        @Volatile var currentPressureHpa: Float? = null
        @Volatile var currentAmbientTemperatureC: Float? = null
        @Volatile var currentBatteryTemperatureC: Float? = null
        @Volatile var currentSpeedMps: Float? = null
        @Volatile var rootDeviceTemperatureC: Float? = null
        @Volatile var lastLatitude: Double? = null
        @Volatile var lastLongitude: Double? = null
        @Volatile var lastLocationAccuracy: Float? = null
        @Volatile var visibleSatelliteCount = 0
        @Volatile var usedSatelliteCount = 0
        @Volatile var registeredSensorCount = 0
        @Volatile var sensorEventCount = 0L
        @Volatile var locationCount = 0L
        @Volatile var fusedSampleCount = 0L
        @Volatile var rootSampleCount = 0L
        @Volatile var rootStatus = "未启用"
    }
}
