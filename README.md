# AdBlocker（Android 全局广告自动关闭）

一个基于 Android Accessibility Service 的原型应用，无需 Root 即可自动识别并点击关闭应用内的弹窗广告、开屏广告，并对“摇一摇”类广告、倒计时广告做处理。

## 核心能力

1. **自动点击关闭按钮**
   - 匹配常见文案：关闭、跳过、知道了、取消、暂不、拒绝、以后再说、不再提示等。
   - 匹配常见广告 SDK 的关闭按钮 id：`tt_video_ad_close`、`gdt_ad_close`、`splash_skip` 等。
   - 兜底识别屏幕左上角/右上角的小型可点击图标。

2. **倒计时广告自动等待关闭**
   - 检测到“N 秒后可关闭”、“倒计时”等文案时，进入轮询模式。
   - 每 400ms 扫描一次关闭按钮，一旦可点立即点击，持续最多 30 秒。

3. **摇一摇广告抑制**
   - Accessibility Service 持续监听窗口变化，在弹窗出现的 50ms~200ms 内处理掉，
     使摇一摇传感器事件来不及触发广告跳转/下载。
   - 注意：彻底禁用传感器需要 Root / Xposed 框架。

4. **开机自启动 + 后台保活**
   - `BOOT_COMPLETED` 广播：开机后自动尝试启动服务。
   - `KeepAliveReceiver`：监听解锁等广播，服务被杀后尝试重启。
   - Foreground Service + 通知：降低被系统回收概率。
   - 应用内提供“忽略电池优化”与“自启动设置”入口。

## 使用方式

1. 用 Android Studio 打开本项目。
2. 编译并安装 APK 到手机。
3. 打开应用：
   - 点击「开启无障碍服务」，在系统设置里启用 **AdBlocker**。
   - 点击「忽略电池优化」，允许应用后台运行。
   - 点击「设置开机自启动」，按厂商指引开启自启动/后台运行权限。
4. 返回应用，状态显示「运行中」即可。

## 项目结构

- `AdBlockAccessibilityService.kt`：核心无障碍服务，遍历节点并自动点击关闭按钮。
- `BootReceiver.kt`：开机启动。
- `KeepAliveReceiver.kt`：被杀后尝试复活。
- `MainActivity.kt`：主界面，引导用户开启各项权限。
- `adblock_service_config.xml`：服务配置。

## 进阶：更彻底的拦截

如果 Accessibility Service 仍无法覆盖某些广告，可配合使用 `AdBlocker-Force` LSPosed 模块：

| 方案 | 能力 | 是否需要 Root |
|---|---|---|
| Accessibility Service | 自动点击关闭、倒计时等待、兜底处理摇一摇 | 否 |
| LSPosed 模块 | Hook 广告 SDK、禁用传感器、屏蔽 Activity 跳转、跳过倒计时 | 是 |
| VPN/DNS 过滤（AdGuard 原理） | 屏蔽广告域名、过滤网络请求 | 否（需 VPN 权限） |

## 自动打包（云编译）

项目已配置 GitHub Actions，无需本地 Android 环境即可自动编译 APK：

1. 把整个 `shiyuanyuan` 目录 push 到 GitHub 仓库。
2. 进入仓库 `Actions` 页面，会看到两个工作流：
   - **Build AdBlocker APK**：编译无障碍服务版本。
   - **Build AdBlocker-Force APK**：编译 LSPosed 模块版本。
3. 工作流跑完后，进入对应 Run → Artifacts，下载 `AdBlocker-debug-apk` 或 `AdBlocker-Force-debug-apk`。
4. 也可以点击工作流页面的 **Run workflow** 手动触发编译。

> 注意：GitHub 生成的 Debug APK 默认使用测试签名，仅用于自用测试。

## 免责声明

本原型仅供学习与技术研究，请勿用于违反应用用户协议或法律法规的场景。
