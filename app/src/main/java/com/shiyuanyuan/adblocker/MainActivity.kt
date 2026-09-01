package com.shiyuanyuan.adblocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.accessibility.AccessibilityManager

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvRootStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvRootStatus = findViewById(R.id.tvRootStatus)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请找到【AdBlocker】并开启无障碍服务", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        findViewById<Button>(R.id.btnAutoStart).setOnClickListener {
            openAutoStartSettings()
        }

        findViewById<Button>(R.id.btnCheckRoot).setOnClickListener {
            checkRootAndLSPosed()
        }

        // 首次进入时自动检测一次
        checkRootAndLSPosed()
    }

    override fun onResume() {
        super.onResume()
        tvStatus.text = if (isAccessibilityEnabled()) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return list.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun checkRootAndLSPosed() {
        val rooted = RootChecker.isDeviceRooted()
        val lsposed = RootChecker.isLSPosedInstalled()
        val status = when {
            rooted && lsposed -> "已 Root，已安装 LSPosed → 建议同时使用 AdBlocker-Force"
            rooted && !lsposed -> "已 Root，但未检测到 LSPosed → 可安装 LSPosed 获得更强拦截"
            else -> "未 Root → 只能使用本应用的基础自动点击功能"
        }
        tvRootStatus.text = status
        Toast.makeText(this, status, Toast.LENGTH_LONG).show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "已忽略电池优化，无需重复设置", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "当前系统版本无需此设置", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 跳转厂商自启动设置。由于各厂商意图不一致，这里尝试常见入口，
     * 失败时 fallback 到应用详情页。
     */
    private fun openAutoStartSettings() {
        val manufacturers = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.samsung.android.lool/.autobackup.AutoBackupActivity",
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity"
        )
        for (cls in manufacturers) {
            try {
                val parts = cls.split("/")
                val intent = Intent().apply {
                    setClassName(parts[0], parts[1])
                }
                startActivity(intent)
                Toast.makeText(this, "请找到 AdBlocker 并允许自启动/后台运行", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                // try next
            }
        }
        // 兜底：跳应用信息页
        Toast.makeText(this, "请在此页面开启：自启动、后台运行、关联启动", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}
