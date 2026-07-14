package com.yichuan.thermalsurvey

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SurveyExporter(private val context: Context) {
    fun exportLatest(): String {
        val db = ThermalDb(context)
        val id = db.readableDatabase.rawQuery("SELECT id FROM surveys ORDER BY id DESC LIMIT 1", null).use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalStateException("没有可导出的巡测")
            cursor.getLong(0)
        }
        db.close()
        return exportSurvey(id)
    }

    fun exportSurvey(surveyId: Long): String {
        val db = ThermalDb(context)
        val survey = db.readableDatabase.rawQuery(
            "SELECT id,project_name,route_name,started_ms FROM surveys WHERE id=? LIMIT 1",
            arrayOf(surveyId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalStateException("找不到这次巡测记录")
            SurveyInfo(cursor.getLong(0), cursor.getString(1), cursor.getString(2) ?: "", cursor.getLong(3))
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(survey.startedMs))
        val exportStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val folder = sanitize("${survey.project}_${survey.route}_${stamp}_S${survey.id}_export_$exportStamp")
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/ThermalSurvey/$folder"
        val tables = listOf(
            "surveys", "sensor_inventory", "sensor_events", "locations", "gnss_status",
            "device_status", "events", "fused_samples_10s", "root_samples"
        )
        for (table in tables) {
            val where = if (table == "surveys") "id=?" else "survey_id=?"
            val cursor = db.readableDatabase.query(table, null, where, arrayOf(survey.id.toString()), null, null, "id")
            writeFile(relative, "$table.csv", "text/csv") { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer -> writeCsv(cursor, writer) }
            }
        }
        val locationCursor = db.readableDatabase.query(
            "locations", null, "survey_id=?", arrayOf(survey.id.toString()), null, null, "unix_time_ms"
        )
        writeFile(relative, "track.geojson", "application/geo+json") { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer -> writeGeoJson(locationCursor, writer) }
        }
        db.close()
        return relative
    }

    fun exportPackage(surveyId: Long): String {
        val db = ThermalDb(context)
        val survey = db.readableDatabase.rawQuery(
            "SELECT id,project_name,route_name,started_ms FROM surveys WHERE id=? LIMIT 1",
            arrayOf(surveyId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalStateException("找不到这次巡测记录")
            SurveyInfo(cursor.getLong(0), cursor.getString(1), cursor.getString(2) ?: "", cursor.getLong(3))
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(survey.startedMs))
        val name = sanitize("${survey.project}_${stamp}_S${survey.id}_完整实验包") + ".zip"
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/ThermalSurvey"
        val tables = listOf(
            "surveys", "sensor_inventory", "sensor_events", "locations", "gnss_status",
            "device_status", "events", "fused_samples_10s", "root_samples"
        )
        val quality = SurveyQualityReport(context).let { generator ->
            try { generator.generate(surveyId) } finally { generator.close() }
        }

        writeFile(relative, name, "application/zip") { output ->
            ZipOutputStream(output).use { zip ->
                tables.forEach { table ->
                    val where = if (table == "surveys") "id=?" else "survey_id=?"
                    val cursor = db.readableDatabase.query(table, null, where, arrayOf(survey.id.toString()), null, null, "id")
                    zip.putNextEntry(ZipEntry("data/$table.csv"))
                    val writer = OutputStreamWriter(zip, Charsets.UTF_8)
                    writeCsv(cursor, writer)
                    writer.flush()
                    zip.closeEntry()
                }
                val locationCursor = db.readableDatabase.query(
                    "locations", null, "survey_id=?", arrayOf(survey.id.toString()), null, null, "unix_time_ms"
                )
                zip.putNextEntry(ZipEntry("track.geojson"))
                val geoWriter = OutputStreamWriter(zip, Charsets.UTF_8)
                writeGeoJson(locationCursor, geoWriter)
                geoWriter.flush()
                zip.closeEntry()

                writeZipText(zip, "quality_report.txt", quality)
                writeZipText(zip, "device_info.txt", buildDeviceInfo())
                writeZipText(zip, "README.txt", "ComforTroll 完整实验包\n应用版本：1.0\n地点：${survey.project}\n巡测编号：${survey.id}\n")
            }
        }
        db.close()
        return "$relative/$name"
    }

    private fun writeCsv(cursor: Cursor, writer: OutputStreamWriter) {
        cursor.use {
            writer.write(cursor.columnNames.joinToString(",") { csvEscape(it) })
            writer.write("\n")
            while (cursor.moveToNext()) {
                writer.write((0 until cursor.columnCount).joinToString(",") { index ->
                    if (cursor.isNull(index)) "" else csvEscape(cursor.getString(index))
                })
                writer.write("\n")
            }
        }
    }

    private fun writeGeoJson(cursor: Cursor, writer: OutputStreamWriter) {
        cursor.use {
            val lon = cursor.getColumnIndexOrThrow("longitude")
            val lat = cursor.getColumnIndexOrThrow("latitude")
            val time = cursor.getColumnIndexOrThrow("unix_time_ms")
            val accuracy = cursor.getColumnIndexOrThrow("horizontal_accuracy")
            val provider = cursor.getColumnIndexOrThrow("provider")
            val speed = cursor.getColumnIndexOrThrow("speed")
            writer.write("{\"type\":\"FeatureCollection\",\"features\":[")
            var first = true
            while (cursor.moveToNext()) {
                if (!first) writer.write(",")
                first = false
                writer.write("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                writer.write(cursor.getDouble(lon).toString())
                writer.write(",")
                writer.write(cursor.getDouble(lat).toString())
                writer.write("]},\"properties\":{")
                writer.write("\"unix_time_ms\":${cursor.getLong(time)},")
                writer.write("\"provider\":\"${jsonEscape(cursor.getString(provider) ?: "")}\",")
                writer.write("\"horizontal_accuracy\":${if (cursor.isNull(accuracy)) "null" else cursor.getDouble(accuracy)},")
                writer.write("\"speed\":${if (cursor.isNull(speed)) "null" else cursor.getDouble(speed)}")
                writer.write("}}")
            }
            writer.write("]}")
        }
    }

    private fun writeZipText(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildDeviceInfo(): String {
        val sensors = (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getSensorList(Sensor.TYPE_ALL).sortedWith(compareBy({ it.type }, { it.name }))
        return buildString {
            appendLine("ComforTroll 设备信息")
            appendLine("制造商：${Build.MANUFACTURER}")
            appendLine("型号：${Build.MODEL}")
            appendLine("设备代号：${Build.DEVICE}")
            appendLine("产品代号：${Build.PRODUCT}")
            appendLine("硬件平台：${Build.HARDWARE}")
            appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("安全补丁：${Build.VERSION.SECURITY_PATCH}")
            appendLine("构建指纹：${Build.FINGERPRINT}")
            appendLine("指令架构：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine("系统公开传感器：${sensors.size} 个")
            sensors.forEach { sensor ->
                appendLine("[${sensor.type}] ${SensorPurpose.name(sensor)}｜${sensor.name}｜${sensor.vendor}")
            }
        }
    }

    private fun writeFile(relativePath: String, name: String, mime: String, write: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建导出文件 $name")
            context.contentResolver.openOutputStream(uri, "w")!!.use(write)
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ThermalSurvey/${relativePath.substringAfterLast('/')}").apply { mkdirs() }
            FileOutputStream(File(dir, name)).use(write)
        }
    }

    private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun sanitize(value: String): String = value.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5_-]+"), "_").trim('_').ifBlank { "survey" }
    private data class SurveyInfo(val id: Long, val project: String, val route: String, val startedMs: Long)
}
