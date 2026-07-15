package com.yichuan.thermalsurvey

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var pageContainer: FrameLayout
    private lateinit var topTitle: TextView
    private val navigationItems = mutableListOf<TextView>()
    private var currentPage = 0
    private var projectInput: EditText? = null
    private var operatorInput: EditText? = null
    private var surveyToggleButton: Button? = null
    private var eventButton: Button? = null
    private val statusValues = linkedMapOf<String, TextView>()
    private val trendBindings = mutableListOf<TrendBinding>()
    private val trendByMetric = linkedMapOf<String, TrendBinding>()
    private val handler = Handler(Looper.getMainLooper())
    private data class TrendBinding(
        val metric: String,
        val title: String,
        val chart: LiveChartView,
        val provider: () -> FloatArray?
    )
    private data class PreflightItem(val label: String, val ok: Boolean, val detail: String)
    private val statusTask = object : Runnable {
        override fun run() {
            if (currentPage == 1) renderStatus()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)
        setContentView(buildShell())
        requestNeededPermissions()
        showPage(0)
    }

    override fun onResume() {
        super.onResume()
        AppUi.prepare(this)
        handler.removeCallbacks(statusTask)
        handler.post(statusTask)
        showPage(currentPage)
    }

    override fun onPause() {
        saveFormDraft()
        handler.removeCallbacks(statusTask)
        super.onPause()
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(AppUi.BACKGROUND)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(1).toFloat()
        }
        topTitle = TextView(this).apply {
            text = "首页"
            textSize = 28f
            setTextColor(AppUi.INK)
            setTypeface(typeface, Typeface.BOLD)
        }
        topBar.addView(topTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(TextView(this).apply {
            text = "⚙"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(AppUi.INK)
            background = AppUi.rounded(Color.TRANSPARENT, dp(22))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)))

        pageContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(pageContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.WHITE)
            elevation = dp(3).toFloat()
        }
        listOf("⌂\n首页", "●\n巡测", "▤\n记录", "◉\n传感器").forEachIndexed { index, label ->
            val item = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    playBounce(this)
                    showPage(index)
                }
            }
            navigationItems += item
            bottomBar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(74)))
        return root
    }

    private fun showPage(index: Int) {
        if (currentPage == 1 && index != 1) saveFormDraft()
        currentPage = index
        topTitle.text = listOf("首页", "巡测", "记录", "传感器")[index]
        navigationItems.forEachIndexed { itemIndex, item ->
            val selected = itemIndex == index
            item.setTextColor(if (selected) AppUi.PRIMARY else AppUi.MUTED)
            item.background = null
        }
        pageContainer.removeAllViews()
        val page = when (index) {
            1 -> buildSurveyPage()
            2 -> buildRecordsPage()
            3 -> buildSensorsPage()
            else -> buildHomePage()
        }
        pageContainer.addView(page, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        topTitle.alpha = 0.35f
        topTitle.translationX = -dp(10).toFloat()
        topTitle.animate().alpha(1f).translationX(0f).setDuration(280L).start()
        animatePageEntry(page)
    }

    private fun buildHomePage(): View {
        val content = pageColumn()
        content.addView(AppUi.body(this, "ComforTroll 科研采集终端", 14f), block(bottom = 16))

        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensors = manager.getSensorList(Sensor.TYPE_ALL)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val batteryTemperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val storageGb = Environment.getDataDirectory().usableSpace / 1_073_741_824.0
        val totalStorageGb = Environment.getDataDirectory().totalSpace / 1_073_741_824.0
        val memory = ActivityManager.MemoryInfo().also {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val totalMemoryGb = memory.totalMem / 1_073_741_824.0
        val availableMemoryGb = memory.availMem / 1_073_741_824.0
        val cpu = cpuName()
        val cpuFrequency = cpuMaxFrequency()
        val metrics = resources.displayMetrics
        val deviceInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardTitle("设备信息"))
            addView(infoRow("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}"))
            addView(infoRow("品牌", Build.BRAND))
            addView(infoRow("设备代号", Build.DEVICE))
            addView(infoRow("产品代号", Build.PRODUCT))
            addView(infoRow("主板", Build.BOARD))
            addView(infoRow("硬件平台", Build.HARDWARE))
            addView(infoRow("处理器", cpu))
            addView(infoRow("处理器核心", "${Runtime.getRuntime().availableProcessors()} 核"))
            if (cpuFrequency != "未知") addView(infoRow("最高频率", cpuFrequency))
            addView(infoRow("指令架构", Build.SUPPORTED_ABIS.joinToString("、")))
            addView(infoRow("运行内存", "%.1f GB（可用 %.1f GB）".format(totalMemoryGb, availableMemoryGb)))
            addView(infoRow("内部存储", "%.1f GB（可用 %.1f GB）".format(totalStorageGb, storageGb)))
            addView(infoRow("屏幕", "${metrics.widthPixels} × ${metrics.heightPixels} · ${metrics.densityDpi} dpi"))
            addView(infoRow("可用传感器", "${sensors.size} 个"))
            addView(infoRow("电池电量", if (batteryPercent >= 0) "$batteryPercent%" else "未知"))
            addView(infoRow("电池温度", batteryTemperature?.let { "%.1f °C".format(it) } ?: "未知"))
        }
        content.addView(AppUi.card(this, deviceInfo), block(bottom = 14))

        val rootEnabled = rootEnhancementEnabled()
        val rootTemperature = if (rootEnabled) AppUi.body(this, "正在读取…", 14f) else null
        val systemInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardTitle("系统信息"))
            addView(infoRow("安卓版本", Build.VERSION.RELEASE))
            addView(infoRow("系统接口", Build.VERSION.SDK_INT.toString()))
            addView(infoRow("安全补丁", Build.VERSION.SECURITY_PATCH.ifBlank { "未知" }))
            addView(infoRow("系统版本", Build.DISPLAY))
            addView(infoRow("构建编号", Build.ID))
            addView(infoRow("构建类型", "${Build.TYPE} · ${Build.TAGS}"))
            addView(infoRow("内核版本", System.getProperty("os.version") ?: "未知"))
            addView(infoRow("引导程序", Build.BOOTLOADER))
            addView(infoRow("系统语言", Locale.getDefault().toLanguageTag()))
            addView(infoRow("构建指纹", Build.FINGERPRINT))
            if (rootEnabled) {
                addView(infoRow("Root 权限", "已启用"))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    addView(AppUi.body(this@MainActivity, "设备温度", 13f), LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(rootTemperature, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
            }
        }
        content.addView(AppUi.card(this, systemInfo), block(bottom = if (rootEnabled) 14 else 0))
        if (rootEnabled) {
            val darkSisi = ImageView(this).apply {
                setImageResource(R.drawable.sisi_demon_warning)
                scaleType = ImageView.ScaleType.FIT_CENTER
                scaleX = 0.78f
                scaleY = 0.78f
            }
            val sealCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(darkSisi, LinearLayout.LayoutParams(dp(104), dp(104)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(AppUi.title(this@MainActivity, "封印解除", 19f))
                    addView(AppUi.body(this@MainActivity,
                        "Root 权限下可以解锁更多功能。\n但是，代价是什么呢……每一次授权，理智值 -1。",
                        13f
                    ).apply { setPadding(0, dp(5), 0, 0) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            }
            content.addView(AppUi.card(this, sealCard, 0xFFFFF1E8.toInt()))
            darkSisi.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(560L)
                .setInterpolator(OvershootInterpolator(2.1f)).start()
            Thread {
                val highest = RootCollector().collect().asSequence()
                    .filter { it.category == "thermal" }
                    .mapNotNull { it.numeric }
                    .map { if (it > 1000.0) it / 1000.0 else if (it > 200.0) it / 10.0 else it }
                    .filter { it in -30.0..150.0 }
                    .maxOrNull()
                runOnUiThread {
                    rootTemperature?.text = highest?.let { "%.1f °C".format(it) }
                        ?: "暂无可用数据"
                }
            }.start()
        }
        return scroll(content)
    }

    private fun buildSurveyPage(): View {
        val prefs = getSharedPreferences("survey_form", MODE_PRIVATE)
        val content = pageColumn()
        statusValues.clear()
        trendBindings.clear()
        trendByMetric.clear()
        prepareTrendCharts()
        content.addView(AppUi.body(this, "配置实验并记录现场事件", 14f), block(bottom = 16))

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardTitle("本次巡测"), block(bottom = 10))
            projectInput = AppUi.field(this@MainActivity, "地点", prefs.getString("project", "") ?: "")
            operatorInput = AppUi.field(this@MainActivity, "人员", prefs.getString("operator", "") ?: "")
            addView(projectInput, block(bottom = 9))
            addView(operatorInput)
        }
        content.addView(AppUi.card(this, form), block(bottom = 12))

        val monitor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardTitle("实时监控"), block(bottom = 8))
            val labels = mutableListOf(
                "运行状态", "持续时间", "定位精度", "定位坐标", "卫星", "环境光", "移动速度", "电池温度"
            )
            listOf("加速度", "陀螺仪", "磁场", "气压", "环境温度", "设备温度")
                .filterTo(labels) { it in trendByMetric }
            labels += listOf("采集传感器", "原始事件", "定位记录", "融合记录")
            if (rootEnhancementEnabled()) labels += listOf("Root 权限", "Root 样本")
            if (labels.size % 2 != 0) labels += ""
            labels.chunked(2).forEachIndexed { index, pair ->
                addView(metricRow(pair[0], pair[1]), block(bottom = if (index == labels.size / 2 - 1) 0 else 8))
            }
        }
        content.addView(AppUi.card(this, monitor), block(bottom = 12))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        surveyToggleButton = AppUi.primaryButton(this, "开始巡测") {
            if (RecordingService.isRunning) stopRecorder() else startRecorder()
        }
        controls.addView(surveyToggleButton, weighted(end = 6))
        eventButton = AppUi.secondaryButton(this, "现场事件") { showEventDialog() }
        controls.addView(eventButton, weighted(start = 6))
        content.addView(controls, block(height = 52, bottom = 18))

        renderStatus()
        return scroll(content)
    }

    private fun buildRecordsPage(): View {
        val content = pageColumn()
        content.addView(AppUi.body(this, "历次巡测与独立数据导出", 14f), block(bottom = 16))
        val records = ThermalDb(this).use { it.listSurveys() }
        if (records.isEmpty()) {
            content.addView(AppUi.card(this, AppUi.body(this, "尚无巡测记录。")))
            return scroll(content)
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        records.forEach { record ->
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(AppUi.title(this@MainActivity, record.project, 17f))
                addView(AppUi.body(this@MainActivity, buildString {
                    if (record.operator.isNotBlank()) append("人员：${record.operator}\n")
                    append(dateFormat.format(Date(record.startedMs)))
                    append(if (record.endedMs == null) " · 未结束" else " · ${duration(record.startedMs, record.endedMs)}")
                    append("\n传感器 ${record.sensorEvents} 条 · 定位 ${record.locations} 条 · 融合 ${record.fusedSamples} 条")
                }, 13f).apply { setPadding(0, dp(6), 0, dp(10)) })
                val exportActions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                val export = AppUi.secondaryButton(this@MainActivity, "导出 CSV") {
                    exportSurvey(record.id)
                }
                export.isEnabled = record.endedMs != null
                exportActions.addView(export, weighted(end = 6))
                val complete = AppUi.primaryButton(this@MainActivity, "完整 ZIP") { exportPackage(record.id) }
                complete.isEnabled = record.endedMs != null
                exportActions.addView(complete, weighted(start = 6))
                addView(exportActions, block(height = 46, bottom = 8))

                val reportActions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                val report = AppUi.secondaryButton(this@MainActivity, "质量报告") { openQualityReport(record.id) }
                report.isEnabled = record.endedMs != null
                reportActions.addView(report, weighted(end = 6))
                val delete = AppUi.outlineButton(this@MainActivity, "删除") { confirmDelete(record) }
                delete.isEnabled = record.endedMs != null
                reportActions.addView(delete, weighted(start = 6))
                addView(reportActions, block(height = 46))
            }
            content.addView(AppUi.card(this, panel), block(bottom = 12))
        }
        return scroll(content)
    }

    private fun buildSensorsPage(): View {
        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensors = manager.getSensorList(Sensor.TYPE_ALL)
        val selected = SensorPreferences.selectedKeys(this, sensors)
        val content = pageColumn()
        content.addView(AppUi.body(this, "按设备能力配置采集通道", 14f), block(bottom = 16))

        val overview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardTitle("当前设备"))
            addView(infoRow("系统公开", "${sensors.size} 个传感器"))
            addView(infoRow("连续记录", "已选择 ${selected.size} 个"))
            addView(infoRow("适配方式", "按本机硬件动态调用"))
        }
        content.addView(AppUi.card(this, overview), block(bottom = 14))
        content.addView(AppUi.primaryButton(this, "选择记录传感器") {
            if (RecordingService.isRunning) Toast.makeText(this, "请先停止巡测再修改", Toast.LENGTH_SHORT).show()
            else startActivity(Intent(this, SensorSelectionActivity::class.java))
        }, block(height = 52, bottom = 10))
        content.addView(AppUi.secondaryButton(this, "查看设备传感器清单") {
            startActivity(Intent(this, SensorInventoryActivity::class.java))
        }, block(height = 52))
        return scroll(content)
    }

    private fun cardTitle(value: String) = AppUi.title(this, value, 19f).apply { setPadding(0, 0, 0, dp(7)) }

    private fun infoRow(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(AppUi.body(this@MainActivity, label, 13f), LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 14f
            setTextColor(AppUi.INK)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun pageColumn() = AppUi.column(this, 18)
    private fun scroll(content: View) = ScrollView(this).apply {
        setBackgroundColor(AppUi.BACKGROUND)
        clipChildren = false
        clipToPadding = false
        isFillViewport = true
        addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun prepareTrendCharts() {
        val types = (getSystemService(SENSOR_SERVICE) as SensorManager)
            .getSensorList(Sensor.TYPE_ALL).map { it.type }.toSet()
        val xyzColors = listOf(AppUi.PRIMARY, AppUi.ACCENT, AppUi.MUTED)
        registerTrend("定位精度", "定位精度", listOf("水平误差"), listOf(AppUi.PRIMARY), " m") {
            RecordingService.lastLocationAccuracy?.let { floatArrayOf(it) }
        }
        registerTrend("卫星", "GNSS 卫星", listOf("参与定位", "可见"), listOf(AppUi.PRIMARY, AppUi.MUTED), " 颗") {
            floatArrayOf(RecordingService.usedSatelliteCount.toFloat(), RecordingService.visibleSatelliteCount.toFloat())
        }
        if (Sensor.TYPE_LIGHT in types) registerTrend("环境光", "环境光", listOf("照度"), listOf(AppUi.PRIMARY), " lux") {
            RecordingService.currentLightLux?.let { floatArrayOf(it) }
        }
        registerTrend("移动速度", "移动速度", listOf("速度"), listOf(AppUi.PRIMARY), " m/s") {
            RecordingService.currentSpeedMps?.let { floatArrayOf(it) }
        }
        registerTrend("电池温度", "电池温度", listOf("温度"), listOf(AppUi.ACCENT), " °C") {
            RecordingService.currentBatteryTemperatureC?.let { floatArrayOf(it) }
        }
        if (Sensor.TYPE_ACCELEROMETER in types) registerTrend("加速度", "三轴加速度", listOf("X", "Y", "Z"), xyzColors, " m/s²") {
            RecordingService.currentAcceleration
        }
        if (Sensor.TYPE_GYROSCOPE in types) registerTrend("陀螺仪", "三轴陀螺仪", listOf("X", "Y", "Z"), xyzColors, " rad/s") {
            RecordingService.currentGyroscope
        }
        if (Sensor.TYPE_MAGNETIC_FIELD in types) registerTrend("磁场", "三轴磁场", listOf("X", "Y", "Z"), xyzColors, " μT") {
            RecordingService.currentMagneticField
        }
        if (Sensor.TYPE_PRESSURE in types) registerTrend("气压", "气压", listOf("气压"), listOf(AppUi.PRIMARY), " hPa") {
            RecordingService.currentPressureHpa?.let { floatArrayOf(it) }
        }
        if (Sensor.TYPE_AMBIENT_TEMPERATURE in types) registerTrend("环境温度", "环境温度", listOf("温度"), listOf(AppUi.PRIMARY), " °C") {
            RecordingService.currentAmbientTemperatureC?.let { floatArrayOf(it) }
        }
        if (rootEnhancementEnabled()) {
            registerTrend("设备温度", "Root 设备温度", listOf("最高温度"), listOf(AppUi.ACCENT), " °C") {
                RecordingService.rootDeviceTemperatureC?.let { floatArrayOf(it) }
            }
        }
    }

    private fun registerTrend(
        metric: String,
        title: String,
        labels: List<String>,
        colors: List<Int>,
        unit: String,
        provider: () -> FloatArray?
    ) {
        val binding = TrendBinding(metric, title, LiveChartView(this, labels, colors, unit), provider)
        trendBindings += binding
        trendByMetric[metric] = binding
    }

    private fun showTrendDialog(binding: TrendBinding) {
        (binding.chart.parent as? android.view.ViewGroup)?.removeView(binding.chart)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
            addView(binding.chart, block())
        }
        AlertDialog.Builder(this)
            .setTitle(binding.title)
            .setView(holder)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showPreflightReport() {
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val selectedCount = SensorPreferences.selectedKeys(this, sensorManager.getSensorList(Sensor.TYPE_ALL)).size
        val locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val rootRequested = rootEnhancementEnabled()
        val usableBytes = Environment.getDataDirectory().usableSpace
        val items = listOf(
            PreflightItem("定位权限", locationGranted, if (locationGranted) "已授权" else "未授权"),
            PreflightItem("定位服务", gpsEnabled, if (gpsEnabled) "已开启" else "未开启"),
            PreflightItem("采集传感器", selectedCount > 0, "已选择 $selectedCount 个"),
            PreflightItem("剩余存储", usableBytes > 256L * 1024 * 1024, "%.1f GB".format(usableBytes / 1_073_741_824.0)),
            PreflightItem("电池电量", batteryPercent < 0 || batteryPercent >= 15, if (batteryPercent >= 0) "$batteryPercent%" else "未知"),
            PreflightItem("通知权限", notificationGranted, if (notificationGranted) "正常" else "未授权"),
            PreflightItem("Root 增强", true, if (rootRequested) "已在设置中启用" else "普通模式")
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            items.forEach { item ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    addView(TextView(this@MainActivity).apply {
                        text = "●"
                        textSize = 14f
                        setTextColor(if (item.ok) 0xFF2E7D63.toInt() else 0xFFC47A32.toInt())
                    }, LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(AppUi.title(this@MainActivity, item.label, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(AppUi.body(this@MainActivity, item.detail, 13f))
                })
            }
        }
        val allNormal = items.all { it.ok }
        AlertDialog.Builder(this)
            .setTitle("巡测前自检")
            .setMessage(if (allNormal) "检查通过，可以开始巡测。" else "发现异常项目，确认现场条件后仍可开始。")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (allNormal) "开始巡测" else "仍然开始") { _, _ -> startRecorderConfirmed() }
            .show()
    }

    private fun startRecorder() {
        if (RecordingService.isRunning) {
            Toast.makeText(this, "巡测已经在运行", Toast.LENGTH_SHORT).show()
            return
        }
        requestNeededPermissions()
        showPreflightReport()
    }

    private fun startRecorderConfirmed() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val project = projectInput?.text?.toString()?.trim().orEmpty().ifBlank { "未填写地点" }
        val route = ""
        val operator = operatorInput?.text?.toString()?.trim().orEmpty()
        val root = rootEnhancementEnabled()
        getSharedPreferences("survey_form", MODE_PRIVATE).edit()
            .putString("project", project).putString("route", "").putString("operator", operator).putBoolean("root", root).apply()
        val intent = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_PROJECT, project).putExtra(RecordingService.EXTRA_ROUTE, route)
            .putExtra(RecordingService.EXTRA_OPERATOR, operator).putExtra(RecordingService.EXTRA_ROOT_MODE, root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopRecorder() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    private fun showEventDialog() {
        if (!RecordingService.isRunning) {
            Toast.makeText(this, "请先开始巡测", Toast.LENGTH_SHORT).show()
            return
        }
        val fixedLabels = listOf("进入日照", "进入树荫", "建筑阴影", "到达测点", "异常干扰")
        val fixedValues = listOf("DIRECT_SUN", "TREE_SHADE", "BUILDING_SHADE", "MEASUREMENT_POINT", "ANOMALY")
        val custom = getSharedPreferences("custom_events", MODE_PRIVATE)
            .getStringSet("names", emptySet()).orEmpty().sorted()
        val labels = (fixedLabels + custom.map { "自定义 · $it" }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("记录现场事件")
            .setItems(labels) { _, index ->
                if (index < fixedValues.size) markEvent(fixedValues[index])
                else markEvent("CUSTOM_EVENT", custom[index - fixedValues.size])
            }
            .setNeutralButton("新增自定义事件") { _, _ -> showAddCustomEventDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddCustomEventDialog() {
        val field = AppUi.field(this, "事件名称", "")
        val holder = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field, block(height = 52))
        }
        AlertDialog.Builder(this)
            .setTitle("新增自定义事件")
            .setView(holder)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = field.text.toString().replace('\n', ' ').trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "事件名称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    val prefs = getSharedPreferences("custom_events", MODE_PRIVATE)
                    val names = prefs.getStringSet("names", emptySet()).orEmpty().toMutableSet()
                    names += name
                    prefs.edit().putStringSet("names", names).apply()
                    Toast.makeText(this, "自定义事件已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun markEvent(type: String, note: String? = null) {
        if (!RecordingService.isRunning) {
            Toast.makeText(this, "请先开始巡测", Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_MARK)
            .putExtra(RecordingService.EXTRA_EVENT_TYPE, type)
            .putExtra(RecordingService.EXTRA_NOTE, note))
        Toast.makeText(this, "事件已记录", Toast.LENGTH_SHORT).show()
    }

    private fun exportSurvey(id: Long) {
        Toast.makeText(this, "正在导出 CSV", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { SurveyExporter(this).exportSurvey(id) }
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "已导出到 $it", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun exportPackage(id: Long) {
        Toast.makeText(this, "正在生成完整实验包", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { SurveyExporter(this).exportPackage(id) }
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "完整实验包已导出到 $it", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun openQualityReport(id: Long) {
        startActivity(Intent(this, QualityReportActivity::class.java).putExtra(QualityReportActivity.EXTRA_SURVEY_ID, id))
    }

    private fun confirmDelete(record: ThermalDb.SurveySummary) {
        AlertDialog.Builder(this)
            .setTitle("删除这次巡测？")
            .setMessage("将永久删除“${record.project}”在应用内保存的全部传感器、定位和事件数据。已经导出的文件不会删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                ThermalDb(this).use { it.deleteSurvey(record.id) }
                Toast.makeText(this, "记录已删除", Toast.LENGTH_SHORT).show()
                showPage(2)
            }
            .show()
    }

    private fun cpuName(): String {
        val fromProc = runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull {
                    it.startsWith("Hardware", true) || it.startsWith("model name", true) || it.startsWith("Processor", true)
                }?.substringAfter(':')?.trim()
            }
        }.getOrNull()
        return fromProc?.takeIf { it.isNotBlank() } ?: Build.HARDWARE
    }

    private fun cpuMaxFrequency(): String {
        val frequencies = File("/sys/devices/system/cpu").listFiles()
            ?.filter { it.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { cpu ->
                runCatching { File(cpu, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }.orEmpty()
        val maximum = frequencies.maxOrNull() ?: return "未知"
        return "%.2f GHz".format(maximum / 1_000_000.0)
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) permissions += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
    }

    private fun saveFormDraft() {
        val project = projectInput?.text?.toString() ?: return
        getSharedPreferences("survey_form", MODE_PRIVATE).edit()
            .putString("project", project).putString("route", "")
            .putString("operator", operatorInput?.text?.toString().orEmpty())
            .putBoolean("root", rootEnhancementEnabled()).apply()
    }

    private fun rootEnhancementEnabled(): Boolean =
        getSharedPreferences("root_enhancement", MODE_PRIVATE).getBoolean("enabled", false)

    private fun renderStatus() {
        if (statusValues.isEmpty()) return
        val running = RecordingService.isRunning
        val elapsed = if (running) (System.currentTimeMillis() - RecordingService.startedAtMs) / 1000 else 0
        statusValues["运行状态"]?.text = if (running) "正在巡测" else "未运行"
        statusValues["运行状态"]?.setTextColor(if (running) AppUi.PRIMARY else AppUi.MUTED)
        statusValues["持续时间"]?.text = "%02d:%02d:%02d".format(elapsed / 3600, elapsed / 60 % 60, elapsed % 60)
        statusValues["定位精度"]?.text = RecordingService.lastLocationAccuracy?.let { "±%.1f m".format(it) } ?: "等待定位"
        statusValues["定位坐标"]?.text = if (RecordingService.lastLatitude != null && RecordingService.lastLongitude != null) {
            "%.5f, %.5f".format(RecordingService.lastLatitude, RecordingService.lastLongitude)
        } else "等待定位"
        statusValues["卫星"]?.text = "${RecordingService.usedSatelliteCount} / ${RecordingService.visibleSatelliteCount}"
        statusValues["环境光"]?.text = RecordingService.currentLightLux?.let { "%.1f lux".format(it) } ?: "等待数据"
        statusValues["移动速度"]?.text = RecordingService.currentSpeedMps?.let { "%.2f m/s".format(it) } ?: "等待数据"
        statusValues["电池温度"]?.text = RecordingService.currentBatteryTemperatureC?.let { "%.1f °C".format(it) } ?: "等待数据"
        statusValues["加速度"]?.text = RecordingService.currentAcceleration?.let { "%.2f m/s²".format(vectorMagnitude(it)) } ?: "等待数据"
        statusValues["陀螺仪"]?.text = RecordingService.currentGyroscope?.let { "%.2f rad/s".format(vectorMagnitude(it)) } ?: "等待数据"
        statusValues["磁场"]?.text = RecordingService.currentMagneticField?.let { "%.1f μT".format(vectorMagnitude(it)) } ?: "等待数据"
        statusValues["气压"]?.text = RecordingService.currentPressureHpa?.let { "%.1f hPa".format(it) } ?: "等待数据"
        statusValues["环境温度"]?.text = RecordingService.currentAmbientTemperatureC?.let { "%.1f °C".format(it) } ?: "等待数据"
        statusValues["设备温度"]?.text = RecordingService.rootDeviceTemperatureC?.let { "%.1f °C".format(it) } ?: "等待 Root 数据"
        statusValues["采集传感器"]?.text = "${RecordingService.registeredSensorCount} 个"
        statusValues["原始事件"]?.text = "${RecordingService.sensorEventCount} 条"
        statusValues["定位记录"]?.text = "${RecordingService.locationCount} 条"
        statusValues["融合记录"]?.text = "${RecordingService.fusedSampleCount} 条"
        statusValues["Root 权限"]?.text = RecordingService.rootStatus
        statusValues["Root 样本"]?.text = "${RecordingService.rootSampleCount} 条"
        surveyToggleButton?.text = if (running) "停止巡测" else "开始巡测"
        eventButton?.isEnabled = running
        eventButton?.alpha = if (running) 1f else 0.45f
        if (running) renderTrends()
    }

    private fun renderTrends() {
        trendBindings.forEach { binding ->
            binding.chart.addSample(binding.provider())
        }
    }

    private fun vectorMagnitude(values: FloatArray): Float =
        sqrt(values.take(3).sumOf { (it * it).toDouble() }).toFloat()

    private fun playBounce(view: View) {
        view.animate().cancel()
        ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.78f, 1.16f, 0.93f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.78f, 1.16f, 0.93f, 1f)
        ).apply {
            duration = 480L
            start()
        }
    }

    private fun animatePageEntry(page: View) {
        val content = (page as? ScrollView)?.getChildAt(0) as? LinearLayout
        if (content == null || content.childCount == 0) {
            page.alpha = 0f
            page.translationY = dp(18).toFloat()
            page.animate().alpha(1f).translationY(0f).setDuration(330L)
                .setInterpolator(OvershootInterpolator(1.15f)).start()
            return
        }
        repeat(content.childCount) { index ->
            val child = content.getChildAt(index)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = dp(24).toFloat()
            child.scaleX = 0.94f
            child.scaleY = 0.94f
            child.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(minOf(index, 8) * 38L)
                .setDuration(440L)
                .setInterpolator(OvershootInterpolator(1.35f))
                .start()
        }
    }

    private fun metricRow(left: String, right: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(metricBox(left), weighted(end = 4))
        addView(metricBox(right), weighted(start = 4))
    }

    private fun metricBox(label: String) = LinearLayout(this).apply {
        if (label.isBlank()) {
            visibility = View.INVISIBLE
            return@apply
        }
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = AppUi.rounded(AppUi.SOFT, dp(14))
        addView(AppUi.body(this@MainActivity, if (label in trendByMetric) "$label  ↗" else label, 12f))
        val value = AppUi.title(this@MainActivity, "—", if (label == "定位坐标") 13f else 16f).apply {
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
        }
        statusValues[label] = value
        addView(value)
        trendByMetric[label]?.let { binding ->
            isClickable = true
            isFocusable = true
            setOnClickListener { showTrendDialog(binding) }
        }
    }

    private fun duration(start: Long, end: Long): String {
        val seconds = ((end - start).coerceAtLeast(0L)) / 1000
        return "${seconds / 60}分${seconds % 60}秒"
    }

    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
        marginStart = dp(start)
        marginEnd = dp(end)
    }

    private fun block(height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, if (height > 0) dp(height) else height
    ).apply { bottomMargin = dp(bottom) }
}
