package com.shiyuanyuan.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 兜底保活：监听系统解锁、屏幕开关等广播，在服务被回收时尝试重启。
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("AdBlock", "KeepAliveReceiver: ${intent.action}")
        val serviceIntent = Intent(context, AdBlockAccessibilityService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
