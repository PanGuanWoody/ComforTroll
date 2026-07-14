package com.yichuan.thermalsurvey

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class GuardModuleActivity : Activity() {
    private lateinit var statusValue: TextView
    private lateinit var rootSwitch: IosSwitch
    private lateinit var rootStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUi.prepare(this)
        val content = AppUi.column(this)
        content.addView(AppUi.title(this, "Root 增强", 28f))
        content.addView(AppUi.body(this, "权限控制与 ComforTroll Guard", 14f).apply {
            setPadding(0, dp(4), 0, dp(18))
        })

        val rootPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@GuardModuleActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(AppUi.title(this@GuardModuleActivity, "启用 Root 增强", 17f))
                rootStatus = AppUi.body(this@GuardModuleActivity, "普通模式", 13f).apply {
                    setPadding(0, dp(4), 0, 0)
                }
                addView(rootStatus)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rootSwitch = IosSwitch(this@GuardModuleActivity).apply {
                isChecked = rootEnabled()
                setOnCheckedChangeListener { enabled ->
                    if (enabled) requestRootEnhancement() else disableRootEnhancement()
                }
            }
            addView(rootSwitch)
        }
        content.addView(AppUi.card(this, rootPanel), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(AppUi.title(this@GuardModuleActivity, "模块状态", 18f))
            statusValue = AppUi.body(this@GuardModuleActivity, "正在检查…", 14f).apply {
                setPadding(0, dp(8), 0, 0)
            }
            addView(statusValue)
        }
        content.addView(AppUi.card(this, status), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val functions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(AppUi.title(this@GuardModuleActivity, "模块作用", 18f))
            addView(AppUi.body(this@GuardModuleActivity,
                "• 将 ComforTroll 加入 Doze 白名单\n• 放宽后台运行限制\n• 监测巡测心跳，异常时尝试恢复前台服务\n• 保存伴生模块运行日志",
                14f
            ).apply { setPadding(0, dp(8), 0, 0) })
        }
        content.addView(AppUi.card(this, functions), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        content.addView(AppUi.primaryButton(this, "安装或更新模块") { confirmInstall() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ))
        content.addView(AppUi.body(this, "需要 Magisk 与 Root 权限。安装完成后重启设备生效。", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(AppUi.BACKGROUND)
            addView(content)
        })
        updateRootStatus()
        if (rootEnabled()) refreshStatus() else statusValue.text = "启用 Root 增强后可检查"
    }

    private fun rootEnabled(): Boolean = getSharedPreferences("root_enhancement", MODE_PRIVATE)
        .getBoolean("enabled", false)

    private fun saveRootEnabled(enabled: Boolean) {
        getSharedPreferences("root_enhancement", MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    private fun updateRootStatus() {
        rootStatus.text = if (rootEnabled()) "已授权，巡测时采集扩展数据" else "普通模式，不申请 Root 权限"
    }

    private fun requestRootEnhancement() {
        rootStatus.text = "正在请求 Root 权限…"
        Thread {
            val granted = RootCollector().hasRoot()
            runOnUiThread {
                if (granted) {
                    saveRootEnabled(true)
                    rootSwitch.setChecked(true, true)
                    updateRootStatus()
                    refreshStatus()
                } else {
                    saveRootEnabled(false)
                    rootSwitch.setChecked(false, true)
                    rootStatus.text = "未获取 Root 权限"
                    statusValue.text = "未获得 Root 权限"
                    Toast.makeText(this, "未获取 Root 权限", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun disableRootEnhancement() {
        saveRootEnabled(false)
        updateRootStatus()
        statusValue.text = "Root 增强已关闭"
    }

    private fun refreshStatus() {
        Thread {
            val result = RootShell.run("if [ -d /data/adb/modules/comfortroll_guard ]; then echo installed; elif [ -d /data/adb/modules_update/comfortroll_guard ]; then echo pending; else echo missing; fi")
            runOnUiThread {
                statusValue.text = when {
                    result.exitCode != 0 -> "未获得 Root 权限"
                    result.output.contains("pending") -> "已安装，等待重启生效"
                    result.output.contains("installed") -> "已安装并启用"
                    else -> "尚未安装"
                }
            }
        }.start()
    }

    private fun confirmInstall() {
        AlertDialog.Builder(this)
            .setTitle("安装 ComforTroll Guard？")
            .setMessage("模块会在系统启动后配置后台白名单并监测巡测心跳。安装完成后需要重启设备。")
            .setNegativeButton("取消", null)
            .setPositiveButton("安装") { _, _ -> installModule() }
            .show()
    }

    private fun installModule() {
        statusValue.text = "正在安装…"
        Thread {
            val module = File(cacheDir, "comfortroll_guard_v1.zip")
            val result = runCatching {
                assets.open("comfortroll_guard_v1.zip").use { input ->
                    module.outputStream().use { output -> input.copyTo(output) }
                }
                RootShell.run("magisk --install-module ${RootShell.quote(module.absolutePath)}", 60L)
            }
            runOnUiThread {
                result.onSuccess { command ->
                    if (command.exitCode == 0) {
                        statusValue.text = "安装完成，重启后生效"
                        Toast.makeText(this, "伴生模块安装完成", Toast.LENGTH_LONG).show()
                    } else {
                        statusValue.text = "安装失败"
                        AlertDialog.Builder(this).setTitle("安装失败").setMessage(command.output.ifBlank { "Magisk 未返回详细信息" }).setPositiveButton("确定", null).show()
                    }
                }.onFailure {
                    statusValue.text = "安装失败"
                    AlertDialog.Builder(this).setTitle("安装失败").setMessage(it.message ?: "未知错误").setPositiveButton("确定", null).show()
                }
            }
        }.start()
    }
}
