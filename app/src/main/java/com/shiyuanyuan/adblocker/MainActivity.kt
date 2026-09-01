package com.shiyuanyuan.adblocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        findViewById<Button>(R.id.btnAutoStart).setOnClickListener {
            openAutoStartSettings()
        }

        requestIgnoreBatteryOptimizations()
    }

    override fun onResume() {
        super.onResume()
        tvStatus.text = if (isAccessibilityEnabled()) getString(R.string.status_running)
                        else getString(R.string.status_stopped)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return list.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    /**
     * 跳转厂商自启动设置。由于各厂商意图不一致，这里只尝试常见入口。
     */
    private fun openAutoStartSettings() {
        val manufacturers = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.samsung.android.lool/.autobackup.AutoBackupActivity"
        )
        for (cls in manufacturers) {
            try {
                val parts = cls.split("/")
                val intent = Intent().apply {
                    setClassName(parts[0], parts[1])
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                // try next
            }
        }
        // 兜底：跳应用信息页
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}
