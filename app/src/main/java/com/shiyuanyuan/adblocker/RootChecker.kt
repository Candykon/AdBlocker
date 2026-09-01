package com.shiyuanyuan.adblocker

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 检测设备 Root 状态与 LSPosed 框架是否安装。
 */
object RootChecker {

    fun isDeviceRooted(): Boolean {
        return checkTestKeys() || checkSuperUserApk() || checkSuBinary() || checkMagisk()
    }

    fun isLSPosedInstalled(): Boolean {
        // LSPosed 常见包名
        val packages = listOf(
            "com.android.shell",
            "org.lsposed.manager",
            "com.google.android.gms",
            "org.lsposed.lspd"
        )
        return packages.any { isPackageInstalled(it) }
    }

    private fun checkTestKeys(): Boolean {
        return android.os.Build.TAGS?.contains("test-keys") == true
    }

    private fun checkSuperUserApk(): Boolean {
        return File("/system/app/Superuser.apk").exists()
    }

    private fun checkSuBinary(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/magisk/.core/bin/su", "/system/sbin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkMagisk(): Boolean {
        return File("/sbin/.magisk").exists() || File("/data/adb/magisk").exists()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            Runtime.getRuntime().exec("pm path $packageName").let { process ->
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readLine()?.startsWith("package:") == true
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
