package com.shiyuanyuan.adblockerforce

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.CountDownTimer
import android.webkit.WebView
import android.widget.PopupWindow
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 强制拦截模块（增强版）。
 *
 * 拦截层级：
 * 1. Activity.startActivityForResult：拦截广告 Activity 启动。
 * 2. Dialog.show：阻止广告 Dialog 显示。
 * 3. PopupWindow.show*：阻止浮层广告。
 * 4. Toast / Snackbar：拦截广告提示。
 * 5. WebView.loadUrl/loadData*：拦截广告 URL 与 HTML。
 * 6. CountDownTimer.start：让倒计时立即结束，破解“看几秒才能关”。
 * 7. 常见广告 SDK 视频播放器：直接回调 onReward / onVideoComplete。
 * 8. SensorManager.registerListener：屏蔽加速度/陀螺仪，废掉摇一摇。
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "AdBlockerForce"

        // 留空 = 对所有应用生效；否则只拦截列表内应用
        private val TARGET_PACKAGES = setOf<String>(
            // "com.example.app"
        )

        // 广告 Activity/类名关键词
        private val AD_ACTIVITY_KEYWORDS = listOf(
            "splash", "adactivity", "ad activity", "interstitial", "rewardvideo",
            "rewarded", "fullscreenad", "popupad", "feedad", "bannerad", "nativead",
            "ttrewardvideo", "ttfullscreenvideo", "ttrvactivity", "ttlandingpage",
            "gdtad", "gdtrewardvideo", "gdtsplash", "unifiedbanner", "unifiedinterstitial",
            "ksrewardvideo", "kssplash", "kssdk", "mimoactivity"
        )

        // 广告 Dialog/Popup/Fragment/View 关键词
        private val AD_VIEW_KEYWORDS = listOf(
            "ad", "advert", "popup", "interstitial", "splash", "reward", "banner",
            "native", "feed", "insert", "fullscreen", "float", "marquee", "pangle"
        )

        // 广告 URL 关键词
        private val AD_URL_KEYWORDS = listOf(
            "/ad.", "/ads/", "/adsvr/", "adsystem", "adserver", "advertising",
            "googleads", "doubleclick", "googlesyndication", "facebook.com/tr",
            "umeng.com/ad", "gdt.qq.com", "pangolin-sdk", "pangle", "mob.com/ad",
            "ad.api", "adnet", "admarvel", "admob", "adservice", "adtrack"
        )

        // 常见广告 SDK 视频播放类（包名/类名片段）
        private val AD_VIDEO_PLAYER_CLASSES = listOf(
            "com.bytedance.sdk.openadsdk.core.video",
            "com.bytedance.sdk.openadsdk.TTRewardVideoActivity",
            "com.qq.e.ads.rewardvideo",
            "com.qq.e.ads.splash",
            "com.kwad.sdk.reward",
            "com.kwad.sdk.splash",
            "com.miui.zeus.mimo.sdk.reward"
        )

        private val BLOCKED_SENSOR_TYPES = setOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GYROSCOPE
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == "com.shiyuanyuan.adblockerforce") return
        if (TARGET_PACKAGES.isNotEmpty() && pkg !in TARGET_PACKAGES) return

        XposedBridge.log("[$TAG] Hooking package: $pkg")

        hookActivityStart(lpparam)
        hookDialogShow(lpparam)
        hookPopupWindow(lpparam)
        hookToast(lpparam)
        hookSnackbar(lpparam)
        hookWebView(lpparam)
        hookCountDownTimer(lpparam)
        hookAdVideoPlayers(lpparam)
        hookSensorManager(lpparam)
    }

    private fun hookActivityStart(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "startActivityForResult",
            Intent::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args[0] as? Intent ?: return
                    val target = intent.component?.className ?: return
                    if (isAdLike(target, AD_ACTIVITY_KEYWORDS)) {
                        XposedBridge.log("[$TAG] Block ad Activity: $target")
                        param.result = null
                    }
                }
            }
        )
    }

    private fun hookDialogShow(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Dialog::class.java,
            "show",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as Dialog
                    val cls = dialog.javaClass.name
                    if (isAdLike(cls, AD_VIEW_KEYWORDS)) {
                        XposedBridge.log("[$TAG] Block Dialog: $cls")
                        param.result = null
                    }
                }
            }
        )
    }

    private fun hookPopupWindow(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val popupCls = XposedHelpers.findClass("android.widget.PopupWindow", lpparam.classLoader)
            arrayOf("showAsDropDown", "showAtLocation").forEach { methodName ->
                XposedHelpers.findAndHookMethod(
                    popupCls,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val popup = param.thisObject as PopupWindow
                            val content = popup.contentView
                            val cls = content?.javaClass?.name ?: ""
                            if (isAdLike(cls, AD_VIEW_KEYWORDS)) {
                                XposedBridge.log("[$TAG] Block PopupWindow: $cls")
                                param.result = null
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook PopupWindow failed: ${e.message}")
        }
    }

    private fun hookToast(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Toast::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val toast = param.thisObject as Toast
                        val text = toast.view?.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString() ?: ""
                        if (isAdLike(text, AD_VIEW_KEYWORDS)) {
                            XposedBridge.log("[$TAG] Block Toast ad: $text")
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook Toast failed: ${e.message}")
        }
    }

    private fun hookSnackbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Snackbar::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sb = param.thisObject as Snackbar
                        val text = sb.view.findViewById<android.widget.TextView>(
                            com.google.android.material.R.id.snackbar_text
                        )?.text?.toString() ?: ""
                        if (isAdLike(text, AD_VIEW_KEYWORDS)) {
                            XposedBridge.log("[$TAG] Block Snackbar ad: $text")
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook Snackbar failed: ${e.message}")
        }
    }

    private fun hookWebView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val urlHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val url = param.args[0] as? String ?: return
                    if (isAdUrl(url)) {
                        XposedBridge.log("[$TAG] Block WebView URL: $url")
                        param.result = null
                    }
                }
            }
            XposedHelpers.findAndHookMethod(WebView::class.java, "loadUrl", String::class.java, urlHook)
            XposedHelpers.findAndHookMethod(WebView::class.java, "loadData", String::class.java, String::class.java, String::class.java, urlHook)
            XposedHelpers.findAndHookMethod(WebView::class.java, "loadDataWithBaseURL", String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, urlHook)
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook WebView failed: ${e.message}")
        }
    }

    /**
     * 让 CountDownTimer 瞬间结束，破解“看 N 秒才能关闭”。
     */
    private fun hookCountDownTimer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                CountDownTimer::class.java,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val timer = param.thisObject
                        try {
                            // 调用 onFinish() 让倒计时直接结束
                            XposedHelpers.callMethod(timer, "onFinish")
                        } catch (e: Throwable) {
                            // ignore
                        }
                        XposedBridge.log("[$TAG] Skip CountDownTimer")
                        param.result = timer // 返回自身，假装已开始
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook CountDownTimer failed: ${e.message}")
        }
    }

    /**
     * Hook 常见广告 SDK 的视频播放相关方法，直接触发完成/奖励回调。
     */
    private fun hookAdVideoPlayers(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader
        AD_VIDEO_PLAYER_CLASSES.forEach { className ->
            try {
                val cls = XposedHelpers.findClass(className, loader)
                // 常见回调方法名
                arrayOf("onReward", "onVideoComplete", "onVideoAdComplete", "onAdShow",
                        "onAdVideoBarClick", "onSkippedVideo", "onAdClose").forEach { methodName ->
                    cls.declaredMethods.firstOrNull { it.name == methodName }?.let { method ->
                        try {
                            XposedHelpers.findAndHookMethod(cls, methodName, *method.parameterTypes,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        XposedBridge.log("[$TAG] Ad video callback: ${cls.name}.$methodName")
                                    }
                                }
                            )
                        } catch (e: Throwable) {
                            // ignore
                        }
                    }
                }
            } catch (e: Throwable) {
                // class not found is ok
            }
        }
    }

    private fun hookSensorManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                SensorManager::class.java,
                "registerListener",
                SensorEventListener::class.java,
                Sensor::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sensor = param.args[1] as? Sensor ?: return
                        if (sensor.type in BLOCKED_SENSOR_TYPES) {
                            XposedBridge.log("[$TAG] Block sensor type: ${sensor.type}")
                            param.result = true
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook SensorManager failed: ${e.message}")
        }
    }

    private fun isAdLike(target: String, keywords: List<String>): Boolean {
        val lower = target.lowercase()
        return keywords.any { lower.contains(it) }
    }

    private fun isAdUrl(url: String): Boolean {
        return AD_URL_KEYWORDS.any { url.contains(it, ignoreCase = true) }
    }
}
