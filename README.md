# Oreki Frame Observer

一个独立的 Android **画面与事件观测服务**：把设备上正在发生的事（画面帧、无障碍事件、控件树、Logcat 日志）通过本机 HTTP / WebSocket 暴露出来，供自动化测试脚本读取。

它是**观测基础设施**，不是自动化工具：只读画面与界面事件，**不注入点击、不改游戏、不做任何自动化操作**。

**目标读者**：需要装工具调画面的测试者 → 需要读帧数据的自动化开发者 → 需要集成接口的调用方 → 要改这个工具本身的开发者。

---

## 能做什么 / 不能做什么

| 能做 | 不能做 |
|---|---|
| 采集设备屏幕画面（JPEG 帧）并给出采集延迟、丢帧统计 | 不注入触摸 / 按键 / 滑动，不代为操作任何应用 |
| 读取无障碍事件、即时控件树（含文字与 bounds） | 不绕过 `MediaProjection` 系统授权（**授权必须由人在设备上确认**） |
| 指定时间点回放历史帧（按 `CLOCK_MONOTONIC` 对齐） | 不绕过 `FLAG_SECURE` / 截图限制（受保护窗口照常变黑） |
| 采集 Logcat、按 Tag / 关键词 / PID / 包名过滤、对齐时间线 | 不自动生成调用栈（普通 Logcat 没有调用栈，需手工插桩） |
| 本机 HTTP + WebSocket 双通道，含二进制帧推送 | 不对局域网或公网开放（只绑 `127.0.0.1`） |
| 会话落盘为 JSONL，可 `adb pull` 回 PC | 不采集音频、不需要存储权限 |

**定位一句话**：它解决「自动化脚本看不见屏幕、拿不到控件树、对不上时间」这三件事，其余一概不做。

## 主要能力与数据输出

### 三类数据

| 通道 | 内容 | 采集方式 |
|---|---|---|
| 画面 | JPEG 帧 + 每帧时间戳 / 尺寸 / 采集延迟 / 丢帧计数 | `MediaProjection` + `VirtualDisplay` + `ImageReader` |
| 事件 | 无障碍事件（窗口变化、点击、文本变化…）+ 有界控件树快照 | `AccessibilityService`（只记录白名单包名） |
| 日志 | 设备 Logcat（环形缓冲 4000 条） | app 内 `logcat -v threadtime` 子进程 |

### 会话目录结构

每次采集生成一个会话目录（app 外部私有目录，无需存储权限）：

```
/sdcard/Android/data/com.oreki.frameobserver/files/sessions/<UTC时间戳>/
├── meta.json        采集参数、设备、开始时间
├── frames.jsonl     每帧一行：frame_id / 时间戳 / 尺寸 / 像素格式 /
│                    capture_latency / dropped_frame_count / jpeg_bytes / saved
├── events.jsonl     每条无障碍事件一行（含 view_tree_snapshot 控件树）
├── stats.jsonl      每 5 秒：实际 FPS / 丢帧率 / CPU / java heap / PSS / 线程数
├── events/          按事件自动截的图（evt_<帧id>_<标签>.jpg）
└── frames/f_%06d.jpg
```

用 JSONL 而不是 JSON 数组的原因：录制被强杀或断电时，数组写法整份作废，JSONL 截断处之前全部可解析。

### 时间基准

**统一时间基准 = `System.nanoTime()`（CLOCK_MONOTONIC）**。帧时间戳（`Image.getTimestamp()`，SurfaceFlinger 交付时刻）与事件时间（`AccessibilityEvent.getEventTime()`，`uptimeMillis`）都换算到这条轴上，因此事件与帧可以直接比较先后。墙上时钟 `wall_ms` 只作人工对照，**不参与计算**。

```
事件相对帧的偏移(ms) = (event.t_mono_ns - frame.capture_timestamp_ns) / 1e6
```

## 系统要求

| 项目 | 要求 |
|---|---|
| Android | **最低 API 29**，目标 / 编译 API 34；Android 14 是主要验收环境 |
| 架构 | 不限制（纯 Kotlin，无 native 依赖） |
| 权限 | `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS`、`INTERNET`（仅本机回环） |
| 运行时额外权限 | `READ_LOGS`（Logcat 模块用）、`WRITE_SECURE_SETTINGS`（无障碍自愈用），均需 `adb shell pm grant` |
| 不需要 | root、存储权限、悬浮窗权限、录音权限、GMS |

**屏幕分辨率与 DPI 没有硬性限制**。工具按设备当前分辨率采集，可自行设置降采样比例。

> 历史实测数据来自 AVD `oreki_test`（1080×2340 @440dpi）。**那是一个具体测试环境，不是通用设备要求**。

## 快速安装与启动

### 前置条件（重要）

`tools/build_cli.py` 是**为本机工作区写的**，路径是硬编码的，它依赖：

| 依赖 | 脚本中的位置 | 说明 |
|---|---|---|
| Android SDK | `DEFAULT_SDK`（`tools/build_cli.py` 顶部的常量，写死为本机 Windows 绝对路径） | 非 Windows 或换机器必须改这一行，或用 `--sdk` 指定 |
| Build Tools | 优先 `../android/AD_Oreki_Edition_Android/tools/bt/android-14` | **不在本仓库**，在相邻仓库 |
| JDK 17 | `../android/AD_Oreki_Edition_Android/tools/jdk/jdk-17.0.20.1+1` | 同上 |
| 签名密钥 | `../android/AD_Oreki_Edition_Android/keys/oreki.jks` | 同上；口令见环境变量 `OREKI_KS_PASS` 或本地口令文件（不入库） |

**只 clone 本仓库无法直接构建。** 要么把 `AD_Oreki_Edition_Android` 一起放在同级目录，要么按上表改掉四个路径常量。

R95 起 Linux 是主要开发环境，Windows 11 是兼容环境。Linux 使用原生路径和权限，推荐通过 `--sdk /opt/android-sdk` 指定 SDK；不要把 `/opt/android-sdk` 或 Windows 绝对路径写死进跨机器脚本。当前 Linux 实测工具链版本见统一工作区 `FACTS.md`；本轮未构建或安装 APK。

### 构建

两条等价路径：

**路径 1（推荐，完全离线）**

```bash
python tools/build_cli.py
# 产物：仓库根 OrekiFrameObserver.apk（自动用签名密钥签名）
# --unsigned 时输出到 _build/OrekiFrameObserver-unsigned.apk
```

走 `aapt2 + kotlinc + d8 + zipalign + apksigner`，不需要 Gradle、不需要网络。

**路径 2（Android Studio / AGP）**

直接打开仓库根目录（`com.android.application` 8.7.3）。需要本机可用的 Gradle 环境与网络（首次要拉 AGP 与依赖）。

> **两条路径不是一回事**：路径 1 是「四件工具 + Python」，路径 2 是「Gradle + AGP 插件生态」。不要以为一条能跑另一条也能跑。日常构建默认走路径 1；改 Kotlin 或 AGP 版本、首次拉插件生态时才用路径 2，联网补完必须回到路径 1 验证一次离线可构建。

### 安装到设备

下文的 `$SERIAL` 是目标设备的序列号，先从 `adb devices` 读出来填进命令（例如模拟器通常是 `emulator-5554`，但**以实际输出为准**）。

```bash
export SERIAL=$(adb devices | awk 'NR==2 {print $1}')   # 先确认这一行是你想操作的那台
adb -s "$SERIAL" install -r OrekiFrameObserver.apk
adb -s "$SERIAL" shell pm grant com.oreki.frameobserver android.permission.POST_NOTIFICATIONS
adb -s "$SERIAL" shell am start -n com.oreki.frameobserver/.MainActivity
```

**多设备时必须写 `-s`**，否则 `adb` 报 "more than one device"。

## 首次授权与开始捕获

### 画面捕获授权（必须人工确认）

1. 在设备上打开 app，点「开始捕获」。
2. 系统弹出 `MediaProjection` 授权对话框 → 点 **START NOW**（中文环境为「开始」）。
3. 授权后前台服务启动，开始采集。

**没有 `/capture/start` 之类的远程触发接口，这是刻意的**：投影授权必须由人在设备上确认，提供远程触发等于绕过系统授权流程。工具**不提供任何绕过方式**。

授权会在以下情况失效，需重新走一遍：重启 app、系统回收、切后台过久。表现为 `/stats` 里 `capturing=false`。

### 无障碍服务（控件树 / 事件需要）

在系统设置 → 无障碍里启用「**Oreki Frame Observer 界面观测**」，或用命令：

```bash
adb -s "$SERIAL" shell settings put secure enabled_accessibility_services \
  com.oreki.frameobserver/com.oreki.frameobserver.a11y.ObserverA11yService
adb -s "$SERIAL" shell settings put secure accessibility_enabled 1
```

工具会在 `MainActivity.onCreate` 与 `/ui` 失败路径各尝试自愈一次（写回绑定）。自愈需要 `WRITE_SECURE_SETTINGS`，未授予时按 `/ui` 返回体里的 `fix` 命令手动授权。

> 已知现象：`adb shell am force-stop com.oreki.frameobserver` 会把 `enabled_accessibility_services` 清成 `null`。所以采集期间**不要为了省事 force-stop**；确需重启，先记录原因与当时的 `frames_processed`。

### Logcat 模块（可选）

```bash
adb -s "$SERIAL" shell pm grant com.oreki.frameobserver android.permission.READ_LOGS
# 然后 GET/POST /logcat/start
```

首次 `/logcat/start` 时系统还会弹一次「Allow … to access all device logs?」（Android 14 的 `LogAccessDialogActivity`），必须点 **Allow one-time access**。不点的话表现是：`running=true`、`alive=true`、但 `buffered` 恒为 0。

未授权不影响截图 / `/ui` / 事件等既有功能。

## 如何读取帧数据

### 连接

```bash
adb -s "$SERIAL" forward tcp:8731 tcp:8731
```

之后所有请求打 `http://127.0.0.1:8731`。服务只绑设备回环地址，`adb forward` 走 USB / 本地通道，**链路不过网络**。

### 取最新一帧

```bash
curl http://127.0.0.1:8731/snapshot.jpg -o now.jpg
curl http://127.0.0.1:8731/stats
```

Python（无第三方库）示例：

```python
import urllib.request
r = urllib.request.urlopen("http://127.0.0.1:8731/snapshot.jpg", timeout=5)
frame_id = int(r.headers["X-Frame-Id"])
open(f"f_{frame_id:06d}.jpg", "wb").write(r.read())
```

响应头带 `X-Frame-Id` / `X-Frame-Mono-Ns` / `X-Frame-Source-Ns`，可直接与 `frames.jsonl` 的 `frame_id` 对应。

### 拉回整个会话

```bash
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash 必需，否则 /sdcard 会被改写
adb pull /sdcard/Android/data/com.oreki.frameobserver/files/sessions ./sessions
```

## HTTP / WebSocket 接口概览

完整参数、响应示例与边界见 **[`docs/接口说明.md`](docs/接口说明.md)**。这里只给入口清单。

### 画面与统计

| 端点 | 说明 |
|---|---|
| `GET /` | 状态页（HTML，含当前帧预览） |
| `GET /snapshot.jpg` | 最新一帧 JPEG；未开始采集返回 503 |
| `GET /stats` | 统计 JSON（目标 FPS 与实际 FPS 分开报告，含 `capturing` / `a11y` 状态） |
| `GET /frames/f_000123.jpg` | 历史帧 |
| `GET /frame_at?t=<ns>` | 按 `CLOCK_MONOTONIC` 时刻找最近一帧（`&raw=1` 直出 JPEG） |

### 事件与控件树

| 端点 | 说明 |
|---|---|
| `GET /events?since=N` | 第 N 条之后的无障碍事件（NDJSON） |
| `GET /ui?depth=48&max=3000&flat=1` | **即时控件树**（有文字 / 可点的节点 + bounds），不需要等 idle |

`/ui` 是为绕开 `uiautomator dump` 的硬限制做的：在持续动画的画面上 `uiautomator` 会报 `ERROR: could not get idle state`，而且**连 XML 文件都不写**（磁盘上留着上一次的旧文件，极易误读）。无障碍服务读控件树不需要等 idle。

### WebSocket

`GET /ws`：binary 帧 = JPEG（≤12.5 Hz），text 帧 = 事件增量。客户端发文本 `{"cmd":"snapshot"}` 可让服务端立刻补推一帧。

### 控制

| 端点 | 说明 |
|---|---|
| `POST /capture/stop` | 停止采集 |
| `POST /capture/record?on=0\|1` | 切换逐帧落盘（长时间观察时省磁盘） |

**刻意不提供 `POST /capture/start`。**

### Logcat 与时间线

`/logcat/start`、`/logcat/stop`、`/logcat/pause`、`/logcat/clear`、`/logcat/level`、`/logcat/filter`、`/logcat`、`/logcat/since`、`/logcat/export`、`/logcat/stats`、`/logcat/rule`、`/logcat/rule/remove`、`/logcat/rule`，以及用于对齐"点了哪里 / 打了什么"的 `GET /timeline?center=&before=&after=`。

诊断规则只影响「日志归属」（把命中 `cls` + `method` 的行打上 `session` 标记），**不做任何代码注入**。

## 安全与隐私边界

- **只绑定 `127.0.0.1`**，不对局域网开放；调用方走 `adb forward`，链路不过网络。
- **无障碍事件只记录白名单包名**（`com.oreki.antimatterdimensions` 与自身），其他应用内容不落盘。
- **密码框强制脱敏**：`isPassword()` 为真的节点文本替换为 `***`；`TYPE_VIEW_TEXT_CHANGED` 一律不记原文。
- **不绕过系统保护**：不绕过 `MediaProjection` 授权，不绕过 `FLAG_SECURE` / 截图限制。
- **投影授权必须人工确认**，工具不提供任何远程启动接口。
- **不做自动化操作**：不执行 `performAction`，不注入点击，不推送按键。
- 停止采集即释放 `VirtualDisplay` / `ImageReader` / `MediaProjection` 并退出前台。

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `/stats` 里 `capturing=false` | MediaProjection 授权失效 | 重新走「开始捕获」→ 系统对话框确认；**没有绕过授权的接口** |
| `/snapshot.jpg` 返回 503 | 没开始采集，或已停止 | 同上 |
| `actual_fps` 只有个位数 | 画面静止，SurfaceFlinger 只在内容变化时产帧 | **正常行为，不是丢帧**。换有动画/滚动的页面测吞吐，并同时看 `frames_dropped` |
| `capture_latency_ms` 偶发上百 ms 但 EMA 很低 | 单帧排队抖动 | 看 `capture_latency_ema_ms` 判断趋势 |
| `/ui` 返回 503 `a11y:"missing"` | 绑定被清空（`am force-stop` 会清） | 工具会自愈一次；需 `WRITE_SECURE_SETTINGS`，未授予时按返回体 `fix` 手动授权 |
| 自愈后仍是 `notconnected` | 系统正在异步绑定，属正常过渡态 | 等 1~2 秒重试；工具不会重复写（重复写会打断绑定） |
| `/ui` 超时（`http=000`） | 控件树较大时响应慢 | 放宽 curl 超时（如 `-m 60`），不要用 5 秒级超时判定失败 |
| 只有 `stats` 没有 `events` | 无障碍服务没开 | 见上文「无障碍服务」一节 |
| 截图与控件坐标对不上 | 画面是**降采样**、控件树是**物理分辨率** | 按采集分辨率与设备分辨率的比例换算 |
| `adb forward` 后连不上 | 端口未转发或已被占用 | `adb forward --list` 看清现有转发，只移除本工具那条：`adb forward --remove tcp:8731`。**不要用 `--remove-all`**，它会移掉同一 ADB Server 下其他工具的转发 |
| `adb pull` 报 `failed to stat remote object` | Git Bash 改写了 `/sdcard` 路径 | 加 `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'` |
| 授权对话框找不到按钮 | 系统对话框文案随语言变化 | 用 `/ui` 取 bounds 后点击，不要猜坐标 |
| `/logcat/start` 后 `buffered` 恒为 0 | 没点系统的「Allow one-time access」 | 重新 `/logcat/start` 并在弹窗点允许 |

**排查顺序**：`/stats` 看 `capturing` 与 `frames_processed` 是否增长 → `adb forward` 是否在位 → 无障碍是否绑定 → 再怀疑工具本身。**连续两次走完链路仍失败**才判定工具不可用，并附 `/stats` 原文与 logcat。

## 开发环境与构建

### 技术形态

| 项 | 值 |
|---|---|
| 语言 | **Kotlin**（`app/src/main/kotlin/`），**无 Java 业务源码** |
| 界面 | framework API 手写，不引 AndroidX |
| 第三方依赖 | **零**。唯一运行时依赖是 Kotlin 标准库（语言本身） |
| 网络 | 手写 HTTP + WebSocket 服务端（`app/src/main/kotlin/com/oreki/frameobserver/net/FrameHttpServer.kt`），只绑回环 |
| 构建 | 两条路径：`tools/build_cli.py`（离线）/ AGP（Android Studio） |
| 版本 | `versionCode 1` / `versionName 0.1.0` |

### 代码结构

```
app/src/main/kotlin/com/oreki/frameobserver/
├── MainActivity.kt          界面（薄壳，无采集逻辑）
├── CaptureService.kt        采集核心（MediaProjection + ImageReader + 有界队列）
├── CaptureConfig.kt         参数（分辨率降采样在 VirtualDisplay 层完成）
├── FrameBus.kt              进程内总线（最新帧 + 统计）
├── FrameStats.kt            实际 FPS / 丢帧 / 延迟（目标与实际分开报告）
├── SessionLog.kt            JSONL 会话日志
├── ProcStats.kt             CPU / 内存采样（/proc/self/stat + Debug.MemoryInfo）
├── util/Json.kt             极简 JSON 序列化（单行、稳定字段序）
├── net/FrameHttpServer.kt   HTTP + WebSocket（零依赖，只绑回环）
├── diag/LogcatRecorder.kt   Logcat 实时捕获与环形缓冲
└── a11y/
    ├── A11yBinder.kt              无障碍绑定与自愈
    └── ObserverA11yService.kt     事件 + 有界控件树 dump
tools/
├── build_cli.py             离线构建（aapt2+kotlinc+d8+zipalign+apksigner）
├── check_observer.py        观测链路检查
├── check_logcat.py          Logcat 模块检查
└── soak_test.py             30 分钟 soak 采样与判定
```

采集线程模型：`captureThread` 取帧 → 有界队列（容量 3，满则丢最旧）→ `saveThread` 拷像素、编 JPEG、落盘/发布。**全部重活都在 saveThread**，采集线程不阻塞。

### 自检脚本

三个脚本都需要设备已安装 app、`adb forward` 在位：

```bash
python tools/check_observer.py      # 观测链路自检（默认连 127.0.0.1:8731）
python tools/check_logcat.py        # Logcat 模块自检
python tools/soak_test.py           # 30 分钟 soak 采样（结果写 _buildlog/soak_samples.jsonl）
```

`check_observer.py` / `check_logcat.py` 会把每条验收标准变成可失败的断言，任何一条不过就以非零退出码结束 —— **改完代码必须跑一遍**，不能靠肉眼看日志判断。

### 改动原则

- 本工具**可按需修改**（源码 / 资源 / 构建脚本 / 文档），但「投影授权必须人工确认」这条底线不可改。
- 不要把设备相关的实测参数写成通用要求。
- 改 Kotlin 后必须走一次路径 1（离线构建）验证，确保不依赖网络。

## 架构与接口文档

| 文档 | 内容 |
|---|---|
| [`docs/接口说明.md`](docs/接口说明.md) | **完整接口契约**：连接、时间基准、每个端点的参数与响应、错误码、边界条件 |
| `app/src/main/kotlin/com/oreki/frameobserver/net/FrameHttpServer.kt` | 设备侧实现（接口的最终依据） |
| [`README.md`](README.md) | 本文件（概览与使用） |

## 与 Oreki Mod 测试流程的关系

本工具是 **Android 端验收的观测通道**，不参与游戏逻辑：

- **用它取证据，再下结论**：UI 验收一律先取画面 + 控件树，不允许凭「应该没问题」断言
- 画面用 `/snapshot.jpg` 确认（**没看图不得断言正常**），控件树用 `/ui`，时序用 `/frame_at`，事件用 `/events`
- 证据统一落到 Android 仓库的 `reports/` 下，便于回查
- 采集期间不要 `am force-stop`；确需重启，先记录原因与当时的 `frames_processed`

## 许可

本仓库当前**没有** `LICENSE` 文件，源码未声明开源许可。

本工具是私有测试基础设施，不是面向公众分发的产品。如需在其它项目中使用或再分发，请先与仓库所有者确认。

## 规则与文档标准

本 README 的编写与维护遵循工作区长期规则 **R-README-01~10**。权威源在 `AD-Oreki-Edition-Web` 仓库的
[AGENTS_GLOBAL（Web 源码仓库）](https://github.com/OrekiDawson/AD-Oreki-Edition-Web/blob/main/docs/AGENTS_GLOBAL.md)
§6.2.3.7（工作区根 `AGENTS.md`）。**本仓库不持有也不复制规则正文** —— frame-observer 不是本工作区的平台仓库，
没有 `AGENTS_GLOBAL.md` 副本，新建副本会产生第六个漂移源。
