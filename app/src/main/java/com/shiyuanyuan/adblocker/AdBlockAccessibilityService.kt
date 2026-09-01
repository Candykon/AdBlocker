package com.shiyuanyuan.adblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class AdBlockAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "AdBlock"
        const val CHANNEL_ID = "adblock_service"
        const val NOTIF_ID = 1

        private val CLOSE_TEXTS = setOf(
            "关闭", "关闭广告", "跳过", "跳过广告", "知道了", "朕知道了", "好的", "确定", "取消",
            "暂不", "稍后再说", "以后再说", "拒绝", "不同意", "不再提示", "不再显示", "忽略",
            "close", "skip", "skip ad", "got it", "dismiss", "cancel", "later", "no thanks",
            "拒绝授权", "不允许", "仅使用期间允许"
        )

        private val COUNTDOWN_KEYWORDS = listOf(
            "秒后可关闭", "s后可关闭", "秒跳过", "s跳过", "倒计时", "后可跳过",
            "再看", "观看", "s后关闭", "秒后关闭"
        )

        private val CLOSE_ID_PATTERNS = listOf(
            "close", "close_btn", "skip", "skip_btn", "dismiss", "cancel", "iv_close",
            "img_close", "btn_close", "tt_video_ad_close", "tt_splash_skip_btn",
            "gdt_ad_close", "gdt_btn_close", "umeng_ad", "splash_skip", "interstitial_close"
        )

        private val DIALOG_CLASSES = setOf(
            "android.app.Dialog", "android.widget.PopupWindow",
            "com.android.internal.app.AlertController",
            "androidx.appcompat.app.AlertDialog"
        )

        const val MAX_DEPTH = 32
        const val POLL_INTERVAL_MS = 400L
        const val POLL_MAX_DURATION_MS = 30000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var isPolling = false
    private var pollStartTime = 0L
    private var isConnected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                notificationTimeout = 50
            }
            acquireWakeLock()
            startForeground()
            isConnected = true
            Log.i(TAG, "AdBlock service connected")
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isConnected) return
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            if (findAndClickCloseNode(root)) return
            if (containsCountdown(root) && !isPolling) {
                startPollingCloseButton()
            }
            val cls = event.className?.toString() ?: ""
            if (cls in DIALOG_CLASSES || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                clickCornerCloseButton(root)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent error", e)
        } finally {
            try { root.recycle() } catch (e: Exception) { }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        stopPolling()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
    }

    private fun startPollingCloseButton() {
        isPolling = true
        pollStartTime = System.currentTimeMillis()
        Log.i(TAG, "Start polling close button")

        val runnable = object : Runnable {
            override fun run() {
                if (!isConnected) {
                    isPolling = false
                    return
                }
                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                if (root == null) {
                    stopPollingIfTimeout()
                    return
                }
                try {
                    val clicked = findAndClickCloseNode(root)
                    if (clicked) {
                        Log.i(TAG, "Polling clicked close button")
                        isPolling = false
                        return
                    }
                } finally {
                    try { root.recycle() } catch (e: Exception) { }
                }

                if (System.currentTimeMillis() - pollStartTime < POLL_MAX_DURATION_MS) {
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                } else {
                    Log.i(TAG, "Polling timeout")
                    isPolling = false
                }
            }
        }
        handler.postDelayed(runnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        isPolling = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun stopPollingIfTimeout() {
        if (System.currentTimeMillis() - pollStartTime >= POLL_MAX_DURATION_MS) {
            isPolling = false
        } else {
            handler.postDelayed({ startPollingCloseButton() }, POLL_INTERVAL_MS)
        }
    }

    private fun containsCountdown(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByCondition(root) { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val all = "$text $desc"
            COUNTDOWN_KEYWORDS.any { all.contains(it) }
        }
        return node != null
    }

    private fun findAndClickCloseNode(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) {
                try { node.recycle() } catch (e: Exception) { }
                continue
            }

            if (isCloseNode(node)) {
                candidates.add(node)
            } else {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
                try { node.recycle() } catch (e: Exception) { }
            }
        }

        if (candidates.isEmpty()) return false

        val target = candidates.minWithOrNull(compareBy(
            { node ->
                val t = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                when {
                    t == "关闭" || t == "关闭广告" -> 0
                    t == "跳过" || t == "跳过广告" -> 1
                    CLOSE_TEXTS.contains(t) -> 2
                    else -> 3
                }
            },
            { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.width() * rect.height()
            }
        ))

        val clicked = target?.let { performClick(it) } ?: false
        candidates.forEach { if (it != target) try { it.recycle() } catch (e: Exception) { } }
        return clicked
    }

    private fun isCloseNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable && node.isEnabled) return false

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        if (text.isNotEmpty() && CLOSE_TEXTS.any { it.equals(text, ignoreCase = true) }) return true
        if (desc.isNotEmpty() && CLOSE_TEXTS.any { it.equals(desc, ignoreCase = true) }) return true
        if (CLOSE_ID_PATTERNS.any { id.contains(it) }) return true

        if ((id.contains("close") || id.contains("skip")) && text.isEmpty() && desc.isEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() < 200 && rect.height() < 200) return true
        }
        return false
    }

    private fun clickCornerCloseButton(root: AccessibilityNodeInfo) {
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val cornerSize = (screen.width() * 0.2).toInt()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val inTopLeft = rect.left < cornerSize && rect.top < cornerSize
                val inTopRight = rect.right > screen.width() - cornerSize && rect.top < cornerSize
                if (inTopLeft || inTopRight) {
                    val area = rect.width() * rect.height()
                    if (area in 1..10000 && area < bestArea) {
                        best?.let { try { it.recycle() } catch (e: Exception) { } }
                        best = AccessibilityNodeInfo.obtain(node)
                        bestArea = area
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            try { node.recycle() } catch (e: Exception) { }
        }

        best?.let {
            performClick(it)
            try { it.recycle() } catch (e: Exception) { }
        }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        val clickableParent = findClickableParent(node)
        val target = clickableParent ?: node
        val clicked = try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            false
        }
        val label = node.text ?: node.contentDescription ?: node.viewIdResourceName ?: "unknown"
        Log.i(TAG, "Auto clicked: $label -> $clicked")
        clickableParent?.let { try { it.recycle() } catch (e: Exception) { } }
        return clicked
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent: AccessibilityNodeInfo? = null
        var depth = 0
        try {
            parent = node.parent
            while (parent != null && depth < 5) {
                if (parent.isClickable) return parent
                val p = parent.parent
                try { parent.recycle() } catch (e: Exception) { }
                parent = p
                depth++
            }
        } catch (e: Exception) {
            Log.e(TAG, "findClickableParent error", e)
        }
        parent?.let { try { it.recycle() } catch (e: Exception) { } }
        return null
    }

    private fun findNodeByCondition(root: AccessibilityNodeInfo, condition: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) {
                try { node.recycle() } catch (e: Exception) { }
                continue
            }
            if (condition(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
            try { node.recycle() } catch (e: Exception) { }
        }
        return null
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdBlocker::KeepAlive")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error", e)
        }
    }

    private fun startForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "广告拦截服务",
                    NotificationManager.IMPORTANCE_LOW
                )
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AdBlocker 运行中")
                .setContentText("正在自动识别并关闭广告弹窗")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setOngoing(true)
                .build()
            startForeground(NOTIF_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
        }
    }
}
