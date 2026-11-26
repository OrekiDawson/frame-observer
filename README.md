# Oreki Frame Observer

**屏幕会说话，日志会补充细节。**

一个面向 Android 应用的屏幕观测工具。它把真实截图、帧统计、无障碍控件树、事件和 logcat 放到同一条时间线上，帮助开发者回答一个看似简单的问题：刚才屏幕上究竟发生了什么？

它最初服务于 [Oreki Edition Android](https://github.com/OrekiDawson/AD-Oreki-Edition-Android) 的界面与玩法验证。判断一个按钮是否出现、一个弹窗是否消失，不能只靠代码里的布尔值；真实画面也是证据。

## 能观察什么？

| 入口 | 可以看到 |
| --- | --- |
| `/` | 当前状态与帧预览 |
| `/snapshot.jpg` | 最新截图 |
| `/stats` | 采集状态和帧统计 |
| `/ui` | 即时无障碍控件树 |
| `/events` | 控件与无障碍事件增量 |
| `/timeline` | 截图、事件与日志的时间线 |
| `/logcat` | 受控日志读取和导出 |

完整参数与响应格式见 [接口说明](docs/接口说明.md)。服务默认仅绑定本机回环地址，可通过 adb 端口转发访问。

## 快速开始

准备 JDK、Android SDK、Build Tools、Gradle Wrapper 和 adb。

Windows PowerShell 7：

```powershell
.\gradlew.bat assembleDebug --offline --no-daemon
```

Linux/macOS：

```bash
./gradlew assembleDebug --offline --no-daemon
```

在指定设备或 AVD 上安装并启动应用（包名 `com.oreki.frameobserver`），按 Android 系统提示人工授权屏幕捕获；需要控件树时，另行启用无障碍服务。启动采集后按接口说明配置 adb 转发，打开本机预览。

## 观察，不干预

本工具不是自动点击器，也不是游戏修改器。只在明确授权的设备及应用范围内采集，不绕过 MediaProjection、FLAG_SECURE 或系统权限，不注入 APK、不发送点击或按键。敏感信息需要脱敏，结束时释放屏幕采集资源。

截图能告诉你界面长什么样，控件树能帮助解释结构，日志可以提供运行上下文。三者都不应单独冒充游戏业务已经正确结算的证明。

## 遇到一点小问题？

| 现象 | 排查方向 |
| --- | --- |
| 截图返回 503 | 检查屏幕捕获授权和采集状态 |
| 控件树为空 | 确认无障碍服务已启用并绑定 |
| 静止画面帧率低 | 区分内容未变化与实际丢帧 |
| 坐标对不上 | 检查分辨率、缩放和屏幕旋转 |
| 无法连接 | 核对 adb 转发，仅处理本工具自己的映射 |

## 一起完善观察工具

核心源码位于 `app/src/main/kotlin/`，构建与权限配置位于 Android 工程文件中。

[接口文档](docs/接口说明.md) · [协作与安全规则](AGENTS.md) · [问题反馈](https://github.com/OrekiDawson/frame-observer/issues)

报告问题时请提供设备、Android 版本、复现过程及脱敏证据。不要提交捕获到的私人内容或凭据。

好的观测工具不需要替应用作决定，只需要让真实发生的事情更容易被看见。
