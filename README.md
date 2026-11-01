# Oreki Frame Observer

为 **反物质维度 Oreki Edition（Android）** 的自动化开发提供的独立画面观测工具。
它是一个**测试基础设施**：只读取画面与界面事件，不修改游戏、不注入点击、不做任何自动化操作。

- 目标环境：Android 14（API 34）· 真机 **Redmi K70**（Xiaomi `23113RKC6C`，代号 `vermeer`）
  - 屏幕：**1440×3200**、**560 dpi**、**20:9**
  - 应用可用区：**1440×2896**（`statusBars` 136 px + `navigationBars` 168 px，3200 − 136 − 168 = 2896）
- 历史验证环境（早期）：AVD `oreki_test` 1080×2340 @440dpi —— 下面的「已实测数据」表出自这里
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

### 无障碍绑定自愈（2026-10-31 新增）

**问题**：`adb shell am force-stop com.oreki.frameobserver` 会把
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` **清成 `null`**、
`ACCESSIBILITY_ENABLED` 变成 `0`。之后 `/ui` 永远返回 503「无障碍服务未连接」，
只能人工进设置里重新打开。

**改法**：新增 `a11y/A11yBinder.java`，在 `MainActivity.onCreate` 与 `/ui` 失败路径各自愈一次：

| 方法 | 作用 |
|---|---|
| `state(ctx)` | 返回 `connected` / `notconnected` / `missing` / `disabled` / `unknown` |
| `ensureBound(ctx)` | 只在 `missing` / `disabled` 时写回绑定；`notconnected` 不动（避免打断正在进行的绑定） |
| `fixCommands()` | 返回可直接执行的修复命令，写进 `/ui` 错误体 |

**配套改动**：
- `/stats` 新增 `a11y` 字段（值为 `{"v":"<state>"}`，与既有 `state` / `session` 的编码约定一致），
  不必等 `/ui` 失败就能看出无障碍是否可用。
- `/ui` 在服务未连接时改为返回**结构化 JSON**（`error` / `state` / `hint` / `fix`），
  HTTP 仍是 503，但把「下一步跑什么命令」直接带出来。
- 清单新增 `WRITE_SECURE_SETTINGS`（`tools:ignore="ProtectedPermissions"`）。
  普通安装不会授予，需 `adb shell pm grant com.oreki.frameobserver android.permission.WRITE_SECURE_SETTINGS`；
  未授予时自愈失败被吞掉并记 `OrekiA11y` 日志，不影响其它功能。**投影授权不受影响，仍需人工确认。**

**实测（AVD `emulator-5554`，2026-10-31 22:05）**：人为把绑定清成 `null` / `0` 后冷启动，
日志出现 `已写回无障碍绑定: ...ObserverA11yService` → `onCreate ensureBound=true state=notconnected`
→ 1.2 s 后 `a11y connected`；随后 `/stats` 报 `a11y={"v":"connected"}`、`/ui` 返回 3,259 B 控件树。


## 分辨率口径（必读，避免把降采样当成设备分辨率）

| 概念 | 数值 | 说明 |
|---|---|---|
| 目标设备物理分辨率 | 1440×3200 | Redmi K70 全屏，**含系统栏** |
| 目标设备应用可用区 | 1440×2896 | 扣掉 statusBars 136 + navigationBars 168；游戏窗口实际拿到的画布 |
| 早期 AVD 物理分辨率 | 1080×2340 @440dpi | 下表「已实测数据」的来源环境 |
| 早期实测的**采集**分辨率 | **540×1170** | 上面 1080×2340 的 **50% 降采样**，由 `VirtualDisplay` 合成层完成 |

**540×1170 只是采集端的降采样尺寸，不是任何设备的屏幕分辨率，也不是 1440×3200 的比例。**
若要为 K70 沿用同一降采样比例（50%），对应值应为 **720×1600**（含系统栏）或
**720×1448**（应用可用区）。报告里提到「采集分辨率」时必须与「设备分辨率」分开写。

## 已实测数据（2026-10-30，AVD `oreki_test` 1080×2340 @440dpi）

> 这一节的数据来自 **AVD**，不是 Redmi K70 真机。真机上尚未重测（见下节「未验证项」）。

| 项 | 实测值 |
|---|---|
| 授权流程 | 系统对话框正常弹出并接受 |
| 采集（**降采样**） | 540×1170 @目标 60fps，持续运行（= AVD 1080×2340 的 50%，**不是设备分辨率**） |
| 实际 FPS | 静止画面 6~8（SurfaceFlinger 只在有内容变化时产帧，**这是正常行为**）；游戏前台后数字随内容更新 |
| 采集延迟 EMA | ~1.0 ms |
| 编码延迟 EMA | ~9-12 ms |
| 丢帧 | 0.14%（队列满丢弃，有计数） |
| 端到端验证 | WS binary 帧与 /snapshot.jpg 均为有效游戏画面 |
| 数据导出 | 708 帧全部拉回 PC，0 损坏 |

## 未验证项（**写报告时必须照抄「未验证」**）

以下三条是 R42 在 K70 真机上读到的**能力上限**，不是「已验证能力」。
任何文档、验收报告、对外说明都不得把它们写成「支持 / 已开启 / 已达标」：

| 探测项 | 读数 | 正确表述 | **禁止的表述** |
|---|---|---|---|
| `mSupportedHdrTypes` | `[]` | 设备当前**不报告任何 HDR 类型**，HDR 采集路径**未验证** | 「支持 HDR」「已开启 HDR」 |
| `supportedColorModes` | `[0]` | 只有默认色彩模式，**广色域采集未验证** | 「支持广色域」「覆盖 DCI-P3」 |
| `renderFrameRate` / `refreshRateOverride` | `60` / `60` | 渲染侧**被锁在 60 Hz**，高刷采集**未验证** | 「已验证 120 Hz 采集」 |

补充：`refreshRateOverride = 60` 是**渲染帧率覆盖值**，不能用来推断屏幕硬件刷新率；
K70 面板支持高刷，但本工具在 60 Hz 下运行，任何 120 Hz 结论都必须由真机重测支撑。

## 安全与隐私边界

- 只绑定 `127.0.0.1`，不对局域网开放；WorkBuddy 走 `adb forward`，链路不过网络。
- 无障碍事件只记录白名单包名；其他应用内容不落盘。
- 密码输入框（`isPassword()`）文本强制替换 `***`；`TYPE_VIEW_TEXT_CHANGED` 一律不记原文。
- 不绕过 FLAG_SECURE / 截图限制；受保护窗口照常变黑。
- 停止采集即释放 VirtualDisplay / ImageReader / MediaProjection 并退出前台。

## 常见问题与故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `/stats` 里 `capturing=false` | MediaProjection 授权失效（重启 App、系统回收、切后台过久） | 重新走「开始捕获」→ 系统对话框确认；**工具不提供任何绕过授权的接口** |
| `/ui` 返回 503 `a11y:"missing"` | 无障碍服务未绑定 | 到系统设置里重新开启本应用的无障碍服务；`am force-stop` 会把 `enabled_accessibility_services` 清成 null，必须重新授权 |
| 冷启动后 `/ui` 首次必 503 | 无障碍服务尚未绑定完成 | 等绑定完成，或先用 `uiautomator dump` 过渡 |
| `/ui` 超时（`http=000`） | 控件树较大时响应慢 | 放宽 curl 超时（如 `-m 60`），不要用 5 秒级超时判定失败 |
| `/ui` 报 `could not get idle state` | 直接用 `uiautomator dump` 抓动画界面时的已知限制 | 改用本工具的 `/ui`（不等待 idle） |
| 截图与控件坐标对不上 | 画面是**半分辨率**、控件树是**物理分辨率** | 见上文「分辨率口径」一节，按 2 倍换算 |
| `adb forward` 后连不上 | 端口未转发或已被占用 | `adb forward --list` 检查，`adb forward --remove-all` 后重设 `tcp:8731 tcp:8731` |
| 授权对话框找不到按钮 | 系统对话框文案随语言变化 | 用 `/ui` 取 bounds 后点击，不要猜坐标；英文环境为 `START NOW`/`CANCEL`，中文为「开始」/「取消」 |

**排查顺序**：`/stats` 看 `capturing` 与 `frames_processed` 是否增长 → `adb forward` 是否在位 →
无障碍是否绑定 → 再怀疑工具本身。**连续两次走完链路仍失败**才可判定工具不可用，并附 `/stats` 原文与 logcat。

## 与 Oreki Mod 测试流程的关系

本工具是 **Android 端验收的观测通道**，不参与游戏逻辑：

- **用它取证据，再下结论**：Android UI 验收一律先取画面 + 控件树，不允许凭「应该没问题」断言
- 画面用 `/snapshot.jpg` 确认（**没看图不得断言正常**），控件树用 `/ui`，时序用 `/frame_at`，事件用 `/events`
- 证据统一落 `android/AD_Oreki_Edition_Android/reports/` 下，便于回查
- 采集期间**不要为省事 `am force-stop`**；确需重启，先记录原因与当时的 `frames_processed`
- 工具本身可按需求修改（源码/资源/构建脚本/文档），但**投影授权必须人工确认**这条底线不可改

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
