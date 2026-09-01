# AdBlocker Force（LSPosed 强制拦截模块）

与 `AdBlocker` Accessibility Service 不同，本模块直接 Hook 应用进程，**在广告代码执行前就把路堵死**。

## 拦截能力

1. **Activity 启动拦截**：识别开屏、插屏、激励视频等广告 Activity 类名，直接阻止 `startActivity`。
2. **Dialog 拦截**：阻止含广告关键字的 Dialog 显示。
3. **PopupWindow 拦截**：阻止广告浮层弹出。
4. **Toast / Snackbar 拦截**：阻止广告提示条。
5. **WebView 拦截**：阻止加载广告 URL / HTML。
6. **CountDownTimer 破解**：让“看 N 秒才能关闭”的倒计时瞬间结束。
7. **广告视频 SDK Hook**：对常见 SDK 的视频播放器直接触发 onComplete / onReward。
8. **摇一摇彻底禁用**：Hook `SensorManager.registerListener`，让加速度/陀螺仪传感器注册失败。

## 前置条件

- 手机已 Root（Magisk / KernelSU 等）
- 已安装 LSPosed 或 Xposed 框架
- 在 LSPosed 中启用本模块，并勾选需要拦截的应用

## 编译与安装

1. 用 Android Studio 打开 `AdBlocker-Force`。
2. Build → Build APK，得到 APK。
3. 将 APK 安装到手机。
4. 打开 LSPosed，启用模块，勾选目标应用，强行停止目标应用后重新打开。

## 配置

编辑 `HookEntry.kt` 中的 `TARGET_PACKAGES`：

```kotlin
private val TARGET_PACKAGES = setOf(
    "com.example.app1",
    "com.example.app2"
)
```

留空则对所有非系统应用生效（可能误杀，建议逐步添加）。

## 与 Accessibility Service 配合使用

- **LSPosed 模块**：第一道防线，阻止广告 Activity / 弹窗 / 摇一摇 / 倒计时。
- **AdBlocker Accessibility Service**：第二道防线，对漏网之鱼自动点击关闭。

## 免责声明

仅供学习研究，请遵守当地法律法规及应用用户协议。
