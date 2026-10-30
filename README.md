# Oreki Frame Observer

为 **反物质维度 Oreki Edition（Android）** 的自动化开发提供的独立画面观测工具。
它是一个**测试基础设施**：只读取画面与界面事件，不修改游戏、不注入点击、不做任何自动化操作。

- 目标环境：Android 14（API 34）· AVD `oreki_test`（1080×2340 @440dpi）
- 依赖：**零第三方依赖**（framework API 手写，无 AndroidX / 无 Kotlin / 无网络库）
- 体积：约 41 KB

## 快速开始（本机已验证的流程）

```bash
# 1. 构建（完全离线，不需要 Gradle 网络）
python tools/build_cli.py
#    产物：OrekiFrameObserver.apk（自动用仓库共享密钥签名）

# 2. 安装并授权
adb install -r OrekiFrameObserver.apk
adb shell pm grant com.oreki.frameobserver android.permission.POST_NOTIFICATIONS
adb shell am start -n com.oreki.frameobserver/.MainActivity

# 3. 在设备上点「开始捕获」→ 系统授权对话框 →「START NOW」
#    （MediaProjection 授权必须由人确认，工具不提供任何绕过接口）

# 4. WorkBuddy 侧读取
adb forward tcp:8731 tcp:8731
curl http://127.0.0.1:8731/snapshot.jpg     # 最新一帧 JPEG
curl http://127.0.0.1:8731/stats            # 统计 JSON
```

## 架构

```
┌─ MainActivity ──── 申请授权、预览、参数
│
├─ CaptureService ── MediaProjection（通道 B：画面）
│     startForeground(mediaProjection) → getMediaProjection
│     → registerCallback → VirtualDisplay(降采样在合成层完成)
│     → ImageReader(RGBA_8888, maxImages=4)
│
│     captureThread: acquireLatestImage → 有界队列(容量3，满丢最旧)
│     saveThread:    拷像素 → Bitmap → JPEG → 落盘/发布   ← 全部重活在这
│
├─ ObserverA11yService ── AccessibilityService（通道 A：事件与控件树）
│     只记录白名单包名（com.oreki.antimatterdimensions + 自身）
│     不执行 performAction，不作为截图 API
│
├─ FrameBus ────────── 进程内单例：最新一帧 + 统计 + 状态
├─ SessionLog ──────── JSONL 落盘（见下）
└─ net.FrameHttpServer ─ 127.0.0.1:8731，HTTP + WebSocket
```

**统一时间基准 = `System.nanoTime()`（CLOCK_MONOTONIC）**。
`Image.getTimestamp()`（SurfaceFlinger 交付时刻）与 `AccessibilityEvent.getEventTime()`
（uptimeMillis，同域）都换算到这条轴上，事件与帧可以直接比较先后。
墙上时钟（`wall_ms`）只作人工对照，不参与计算。

## 数据输出

每个会话一个目录（app 外部私有目录，无需存储权限，`adb pull` 可取）：

```
/sdcard/Android/data/com.oreki.frameobserver/files/sessions/<UTC时间戳>/
├── meta.json        采集参数、设备、开始时间
├── frames.jsonl     每帧一行：frame_id / 时间戳 / 尺寸 / 像素格式 /
│                    capture_latency / dropped_frame_count / jpeg_bytes / saved
├── events.jsonl     每条无障碍事件一行（含 view_tree_snapshot 控件树）
├── stats.jsonl      每 5 秒：实际 FPS / 丢帧率 / CPU / java heap / PSS / 线程数
├── events/              按事件自动截的图（evt_<帧id>_<标签>.jpg）
└── frames/f_%06d.jpg
```

JSONL 而非 JSON 数组：录制被强杀或断电时，数组写法整份作废，JSONL 截断处之前全部可解析。

## WorkBuddy 接口（先 `adb forward tcp:8731 tcp:8731`）

| 端点 | 说明 |
|---|---|
| `GET /` | 状态页（HTML） |
| `GET /snapshot.jpg` | 最新一帧，响应头带 `X-Frame-Id` / `X-Frame-Mono-Ns` / `X-Frame-Source-Ns` |
| `GET /stats` | 统计 JSON（目标/实际 FPS 分开报告） |
| `GET /events?since=N` | 第 N 条之后的无障碍事件（JSONL 文本） |
| `GET /ui?depth=48&max=3000&flat=1` | **即时控件树**（有文字 / 可点的节点 + bounds），不需要等 idle |
| `GET /frames/f_000123.jpg` | 历史帧 |
| `GET /frame_at?t=<ns>` | **指定时间点回放**：按 CLOCK_MONOTONIC 时刻找最近一帧（`&raw=1` 直出 JPEG） |
| `GET /ws` | WebSocket：binary=JPEG 帧（≤12.5Hz），text=事件增量 |
| `POST /capture/stop` | 停止采集 |
| `POST /capture/record?on=0|1` | 切换落盘 |

> `/ui` 是为绕开 `uiautomator dump` 的硬限制加的：在持续动画的画面上 uiautomator 会
> `ERROR: could not get idle state`，而且**连 XML 文件都不写**。无障碍服务不需要等 idle。
> `flat=1` 只回「有文字 / 有描述 / 可点 / 可滚」的节点，体积小且直接能用来点；
> `flat=0` 回完整嵌套树。深度默认 48（uiautomator 递归到底；早期固定 12 会把游戏正文整段切掉）。

**刻意不提供 `POST /capture/start`**：投影授权必须由人在设备上确认，
提供远程触发接口等于绕过系统授权流程。

## 已实测数据（2026-10-30，oreki_test AVD）

| 项 | 实测值 |
|---|---|
| 授权流程 | 系统对话框正常弹出并接受 |
| 采集 | 540×1170 @目标60fps，持续运行 |
| 实际 FPS | 静止画面 6~8（SurfaceFlinger 只在有内容变化时产帧，**这是正常行为**）；游戏前台后数字随内容更新 |
| 采集延迟 EMA | ~1.0 ms |
| 编码延迟 EMA | ~9-12 ms |
| 丢帧 | 0.14%（队列满丢弃，有计数） |
| 端到端验证 | WS binary 帧与 /snapshot.jpg 均为有效游戏画面 |
| 数据导出 | 708 帧全部拉回 PC，0 损坏 |

## 安全与隐私边界

- 只绑定 `127.0.0.1`，不对局域网开放；WorkBuddy 走 `adb forward`，链路不过网络。
- 无障碍事件只记录白名单包名；其他应用内容不落盘。
- 密码输入框（`isPassword()`）文本强制替换 `***`；`TYPE_VIEW_TEXT_CHANGED` 一律不记原文。
- 不绕过 FLAG_SECURE / 截图限制；受保护窗口照常变黑。
- 停止采集即释放 VirtualDisplay / ImageReader / MediaProjection 并退出前台。

## 构建

两条等价路径：

1. **离线命令行**（推荐，不依赖网络）：`python tools/build_cli.py`
2. **Android Studio / AGP**：直接打开根目录（`com.android.application` 8.7.3）。

差异说明：AGP 8 禁止清单里的 `package` 属性而 aapt2 又必须有包名，
命令行脚本在构建期把 `package` 注入到临时清单（`_build/manifest_cli.xml`），仓库清单不动。

## 目录

```
app/src/main/java/com/oreki/frameobserver/
├── MainActivity.java        界面（薄壳，无采集逻辑）
├── CaptureService.java      采集核心（MediaProjection + ImageReader + 有界队列）
├── CaptureConfig.java       参数（分辨率降采样在 VirtualDisplay 层完成）
├── FrameBus.java            进程内总线（最新帧 + 统计）
├── FrameStats.java          实际 FPS / 丢帧 / 延迟（目标与实际分开报告）
├── SessionLog.java          JSONL 会话日志
├── ProcStats.java           CPU / 内存采样（/proc/self/stat + Debug.MemoryInfo）
├── util/Json.java           极简 JSON 序列化（单行、稳定字段序）
├── net/FrameHttpServer.java HTTP + WebSocket（零依赖，只绑回环）
└── a11y/ObserverA11yService.java  事件 + 有界控件树 dump
tools/
├── build_cli.py             离线构建（aapt2+javac+d8+zipalign+apksigner）
└── soak_test.py             30 分钟 soak 采样与判定
```
