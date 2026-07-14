package com.yichuan.thermalsurvey

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.hardware.Sensor

class ThermalDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE surveys (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_name TEXT NOT NULL,
                route_name TEXT,
                operator_name TEXT,
                started_ms INTEGER NOT NULL,
                ended_ms INTEGER,
                root_requested INTEGER NOT NULL DEFAULT 0,
                root_granted INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE sensor_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                survey_id INTEGER NOT NULL,
                sensor_type INTEGER NOT NULL,
                sensor_name TEXT NOT NULL,
                vendor TEXT,
                version INTEGER,
                resolution REAL,
                maximum_range REAL,
                power_ma REAL,
                min_delay_us INTEGER,
                max_delay_us INTEGER,
                fifo_max_events INTEGER,
                fifo_reserved_events INTEGER,
                is_wakeup INTEGER,
                reporting_mode INTEGER,
                FOREIGN KEY(survey_id) REFERENCES surveys(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE sensor_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                survey_id INTEGER NOT NULL,
                elapsed_realtime_ns INTEGER NOT NULL,
                unix_time_ms INTEGER NOT NULL,
                sensor_type INTEGER NOT NULL,
                sensor_name TEXT NOT NULL,
                value_0 REAL, value_1 REAL, value_2 REAL, value_3 REAL,
                values_text TEXT NOT NULL,
                accuracy INTEGER NOT NULL,
                FOREIGN KEY(survey_id) REFERENCES surveys(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                survey_id INTEGER NOT NULL,
                elapsed_realtime_ns INTEGER NOT NULL,
                unix_time_ms INTEGER NOT NULL,
                provider TEXT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                altitude REAL,
                horizontal_accuracy REAL,
                vertical_accuracy REAL,
                speed REAL,
                speed_accuracy REAL,
                bearing REAL,
                bearing_accuracy REAL,
                is_mock INTEGER NOT NULL,
                FOREIGN KEY(survey_id) REFERENCES surveys(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE gnss_status (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                survey_id INTEGER NOT NULL,
                unix_time_ms INTEGER NOT NULL,
                satellite_index INTEGER NOT NULL,
                constellation_type INTEGER NOT NULL,
                svid INTEGER NOT NULL,
                cn0_db_hz REAL,
                elevation_degrees REAL,
                azimuth_degrees REAL,
                used_in_fix INTEGER NOT NULL,
                has_almanac INTEGER NOT NULL,
                has_ephemeris INTEGER NOT NULL,
                FOREIGN KEY(survey_id) REFERENCES surveys(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE device_status (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                survey_id INTEGER NOT NULL,
                unix_time_ms INTEGER NOT NULL,
                battery_level INTEGER,
                battery_temperature_c REAL,
                charging_state INTEGER,
                screen_on INTEGER,
                app_memory_bytes INTEGER,
                available_storage_bytes INTEGER,
                FOREIGN KEY(survey_id) REFERENCES surveys(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                survey_id INTEGER NOT NULL,
                unix_time_ms INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                note TEXT,
                latitude REAL,
                longitude REAL,
                light_lux REAL,
                FOREIGN KEY(survey_id) REFERENCES surveys(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE fused_samples_10s (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                survey_id INTEGER NOT NULL,
                sample_time_ms INTEGER NOT NULL,
                window_start_ms INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                location_time_ms INTEGER,
                location_age_ms INTEGER,
                horizontal_accuracy REAL,
                speed REAL,
                visible_satellites INTEGER,
                used_satellites INTEGER,
                light_mean_lux REAL,
                light_min_lux REAL,
                light_max_lux REAL,
                light_event_count INTEGER,
                pitch_mean_deg REAL,
                roll_mean_deg REAL,
                orientation_event_count INTEGER,
                battery_level INTEGER,
                battery_temperature_c REAL,
                screen_on INTEGER,
                FOREIGN KEY(survey_id) REFERENCES surveys(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE root_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                survey_id INTEGER NOT NULL,
                unix_time_ms INTEGER NOT NULL,
                category TEXT NOT NULL,
                source_path TEXT NOT NULL,
                metric_name TEXT NOT NULL,
                value_text TEXT,
                value_numeric REAL,
                FOREIGN KEY(survey_id) REFERENCES surveys(id)
            )
        """.trimIndent())
        val timeColumns = mapOf(
            "sensor_events" to "unix_time_ms",
            "locations" to "unix_time_ms",
            "gnss_status" to "unix_time_ms",
            "device_status" to "unix_time_ms",
            "events" to "unix_time_ms",
            "fused_samples_10s" to "sample_time_ms",
            "root_samples" to "unix_time_ms"
        )
        for ((table, timeColumn) in timeColumns) {
            db.execSQL("CREATE INDEX idx_${table}_survey_time ON $table(survey_id, $timeColumn)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun startSurvey(project: String, route: String, operator: String, rootRequested: Boolean): Long {
        return writableDatabase.insertOrThrow("surveys", null, ContentValues().apply {
            put("project_name", project)
            put("route_name", route)
            put("operator_name", operator)
            put("started_ms", System.currentTimeMillis())
            put("root_requested", if (rootRequested) 1 else 0)
        })
    }

    @Synchronized
    fun endSurvey(surveyId: Long) {
        writableDatabase.update("surveys", ContentValues().apply { put("ended_ms", System.currentTimeMillis()) }, "id=?", arrayOf(surveyId.toString()))
    }

    @Synchronized
    fun setRootGranted(surveyId: Long, granted: Boolean) {
        writableDatabase.update("surveys", ContentValues().apply { put("root_granted", if (granted) 1 else 0) }, "id=?", arrayOf(surveyId.toString()))
    }

    @Synchronized
    fun insertSensorInventory(surveyId: Long, sensor: Sensor) {
        writableDatabase.insert("sensor_inventory", null, ContentValues().apply {
            put("survey_id", surveyId)
            put("sensor_type", sensor.type)
            put("sensor_name", sensor.name)
            put("vendor", sensor.vendor)
            put("version", sensor.version)
            put("resolution", sensor.resolution)
            put("maximum_range", sensor.maximumRange)
            put("power_ma", sensor.power)
            put("min_delay_us", sensor.minDelay)
            put("max_delay_us", sensor.maxDelay)
            put("fifo_max_events", sensor.fifoMaxEventCount)
            put("fifo_reserved_events", sensor.fifoReservedEventCount)
            put("is_wakeup", if (sensor.isWakeUpSensor) 1 else 0)
            put("reporting_mode", sensor.reportingMode)
        })
    }

    @Synchronized
    fun insertSensor(surveyId: Long, elapsedNs: Long, unixMs: Long, type: Int, name: String, values: FloatArray, accuracy: Int) {
        writableDatabase.insert("sensor_events", null, ContentValues().apply {
            put("survey_id", surveyId)
            put("elapsed_realtime_ns", elapsedNs)
            put("unix_time_ms", unixMs)
            put("sensor_type", type)
            put("sensor_name", name)
            for (i in 0..3) if (i < values.size) put("value_$i", values[i])
            put("values_text", values.joinToString(";") { it.toString() })
            put("accuracy", accuracy)
        })
    }

    @Synchronized fun insertLocation(row: ContentValues) { writableDatabase.insert("locations", null, row) }
    @Synchronized fun insertGnss(row: ContentValues) { writableDatabase.insert("gnss_status", null, row) }
    @Synchronized fun insertDeviceStatus(row: ContentValues) { writableDatabase.insert("device_status", null, row) }
    @Synchronized fun insertFusedSample(row: ContentValues) { writableDatabase.insert("fused_samples_10s", null, row) }

    @Synchronized
    fun insertRootSample(surveyId: Long, timeMs: Long, category: String, path: String, metric: String, text: String, numeric: Double?) {
        writableDatabase.insert("root_samples", null, ContentValues().apply {
            put("survey_id", surveyId)
            put("unix_time_ms", timeMs)
            put("category", category)
            put("source_path", path)
            put("metric_name", metric)
            put("value_text", text)
            put("value_numeric", numeric)
        })
    }

    @Synchronized
    fun insertEvent(surveyId: Long, type: String, note: String? = null, latitude: Double? = null, longitude: Double? = null, light: Float? = null) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("survey_id", surveyId)
            put("unix_time_ms", System.currentTimeMillis())
            put("event_type", type)
            put("note", note)
            put("latitude", latitude)
            put("longitude", longitude)
            put("light_lux", light)
        })
    }

    @Synchronized
    fun listSurveys(): List<SurveySummary> {
        val result = mutableListOf<SurveySummary>()
        readableDatabase.rawQuery(
            """
            SELECT s.id,s.project_name,s.route_name,s.operator_name,s.started_ms,s.ended_ms,
                   (SELECT COUNT(*) FROM sensor_events e WHERE e.survey_id=s.id),
                   (SELECT COUNT(*) FROM locations l WHERE l.survey_id=s.id),
                   (SELECT COUNT(*) FROM fused_samples_10s f WHERE f.survey_id=s.id)
            FROM surveys s ORDER BY s.started_ms DESC
            """.trimIndent(), null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SurveySummary(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2) ?: "",
                    cursor.getString(3) ?: "", cursor.getLong(4),
                    if (cursor.isNull(5)) null else cursor.getLong(5),
                    cursor.getLong(6), cursor.getLong(7), cursor.getLong(8)
                )
            }
        }
        return result
    }

    @Synchronized
    fun deleteSurvey(surveyId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf(
                "sensor_inventory", "sensor_events", "locations", "gnss_status",
                "device_status", "events", "fused_samples_10s", "root_samples"
            ).forEach { table -> db.delete(table, "survey_id=?", arrayOf(surveyId.toString())) }
            db.delete("surveys", "id=?", arrayOf(surveyId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class SurveySummary(
        val id: Long,
        val project: String,
        val route: String,
        val operator: String,
        val startedMs: Long,
        val endedMs: Long?,
        val sensorEvents: Long,
        val locations: Long,
        val fusedSamples: Long
    )

    companion object {
        const val DB_NAME = "thermal_survey_v2.db"
    }
}
