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

/**
 * 基于 Android AccessibilityService 的全局广告关闭服务。
 *
 * 核心能力：
 * 1. 自动识别并点击关闭按钮。
 * 2. 检测倒计时广告（如“5s后可关闭”），轮询等待并自动关闭。
 * 3. 开机自启动 + Foreground Service 保活。
 * 4. 服务被杀后尝试重启。
 */
class AdBlockAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "AdBlock"
        const val CHANNEL_ID = "adblock_service"
        const val NOTIF_ID = 1

        // 直接关闭类文案
        private val CLOSE_TEXTS = setOf(
            "关闭", "关闭广告", "跳过", "跳过广告", "知道了", "朕知道了", "好的", "确定", "取消",
            "暂不", "稍后再说", "以后再说", "拒绝", "不同意", "不再提示", "不再显示", "忽略",
            "close", "skip", "skip ad", "got it", "dismiss", "cancel", "later", "no thanks",
            "拒绝授权", "不允许", "仅使用期间允许"
        )

        // 倒计时/等待类文案关键词
        private val COUNTDOWN_KEYWORDS = listOf(
            "秒后可关闭", "s后可关闭", "秒跳过", "s跳过", "倒计时", "后可跳过",
            "再看", "观看", "s后关闭", "秒后关闭"
        )

        // 常见广告 SDK 的关闭按钮 id 关键字
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

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        Log.i(TAG, "AdBlock service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return
        try {
            // 1. 先尝试直接关闭
            if (findAndClickCloseNode(root)) return

            // 2. 检测到倒计时广告时，进入轮询等待关闭
            if (containsCountdown(root) && !isPolling) {
                startPollingCloseButton()
            }

            // 3. 兜底：对 Dialog/Popup 点击角落小按钮
            val cls = event.className?.toString() ?: ""
            if (cls in DIALOG_CLASSES || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                clickCornerCloseButton(root)
            }
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        wakeLock?.let { if (it.isHeld) it.release() }
        // 服务被杀时尝试自救
        val restartIntent = Intent(applicationContext, AdBlockAccessibilityService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    /**
     * 轮询：每 400ms 检查一次关闭按钮，持续最多 30s。
     * 专门应对“观看 N 秒后可关闭”的广告。
     */
    private fun startPollingCloseButton() {
        isPolling = true
        pollStartTime = System.currentTimeMillis()
        Log.i(TAG, "Start polling close button")

        val runnable = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow
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
                    root.recycle()
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

    /**
     * 检测界面是否包含倒计时提示。
     */
    private fun containsCountdown(root: AccessibilityNodeInfo): Boolean {
        return findNodeByCondition(root) { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val all = "$text $desc"
            COUNTDOWN_KEYWORDS.any { all.contains(it) }
        } != null
    }

    private fun findAndClickCloseNode(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) {
                node.recycle()
                continue
            }

            if (isCloseNode(node)) {
                candidates.add(node)
            } else {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
                node.recycle()
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
        candidates.forEach { if (it != target) it.recycle() }
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
                        best?.recycle()
                        best = AccessibilityNodeInfo.obtain(node)
                        bestArea = area
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }

        best?.let { performClick(it); it.recycle() }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        val clickableParent = findClickableParent(node)
        val target = clickableParent ?: node
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val label = node.text ?: node.contentDescription ?: node.viewIdResourceName ?: "unknown"
        Log.i(TAG, "Auto clicked: $label -> $clicked")
        clickableParent?.recycle()
        return clicked
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) return parent
            val p = parent.parent
            parent.recycle()
            parent = p
            depth++
        }
        parent?.recycle()
        return null
    }

    private fun findNodeByCondition(root: AccessibilityNodeInfo, condition: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) {
                node.recycle()
                continue
            }
            if (condition(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
            node.recycle()
        }
        return null
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdBlocker::KeepAlive")
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error", e)
        }
    }

    private fun startForeground() {
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
    }
}
