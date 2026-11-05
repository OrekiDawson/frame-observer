package com.oreki.frameobserver.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.oreki.frameobserver.FrameBus
import com.oreki.frameobserver.SessionLog
import com.oreki.frameobserver.util.Json
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale

/**
 * 界面观测服务：只记录，不操作。
 *
 * **为什么不用它截图**（不得把 AccessibilityService 当作截图 API）：无障碍服务拿不到
 * 像素，只能拿控件树与事件；而且依赖它截图在游戏这种自绘 SurfaceView 上完全失效 ——
 * 游戏画面根本不在 View 树里，节点树只有一层 SurfaceView 容器。
 * 所以本类只负责「什么时候、哪个窗口、哪个控件、发生了什么」，画面交给 MediaProjection。
 *
 * **时间基准对齐**：`AccessibilityEvent.getEventTime()` 返回 uptimeMillis，
 * 与 [System.nanoTime] 同属 CLOCK_MONOTONIC，因此
 * `t_mono_ns ≈ System.nanoTime() - (SystemClock.uptimeMillis() - eventTime) * 1e6`
 * 可以把事件精确落到帧时间轴上；同时直接记录 `nearest_frame_id`，
 * 让「事件 → 最近一帧」的关联不需要任何插值。
 *
 * **隐私边界**：
 * - 只记录白名单包名（默认被测游戏 + 本工具），其他应用一律不落盘；
 * - 密码类输入框（`isPassword`）文本强制替换为 `***`；
 * - 不记录任何 `TYPE_VIEW_TEXT_CHANGED` 的原文，只记录事件本身 ——
 *   避免把账号密码逐字符写进日志，这是最容易漏的一条。
 */
class ObserverA11yService : AccessibilityService() {

    private val recent = ArrayDeque<Long>()
    private var enabledAt = 0L

    private var shotCount = 0
    private val lastShotAt = HashMap<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.notificationTimeout = 50
            setServiceInfo(info)
        }
        enabledAt = System.nanoTime()
        SessionLog.ensureSession(getExternalFilesDir(null) ?: File("/sdcard"), meta())
        SessionLog.event(
            Json.obj()
                .put("t_mono_ns", enabledAt)
                .put("event_type", "service_connected")
                .put("package_name", packageName)
                .end(),
        )
        Log.i(TAG, "a11y connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: ""
        // 非白名单应用：直接丢弃，不落盘、不缓存（隐私要求）。
        if (pkg !in ALLOW) return
        if (!allowRate()) return

        val nowNs = System.nanoTime()
        val eventTimeMs = event.eventTime
        // 把 uptimeMillis 事件时刻换算到 nanoTime 轴上
        val deltaMs = SystemClock.uptimeMillis() - eventTimeMs
        val eventMonoNs = nowNs - deltaMs * 1_000_000L

        val type = event.eventType
        val typeName = AccessibilityEvent.eventTypeToString(type)

        var viewId: String? = null
        var desc: String? = null
        val cls = event.className?.toString()
        var text: String? = null
        var bounds: String? = null
        var password = false

        var src: AccessibilityNodeInfo? = null
        try {
            src = event.source
            if (src != null) {
                viewId = src.viewIdResourceName
                desc = src.contentDescription?.toString()
                password = src.isPassword
                val t = src.text
                if (t != null) {
                    text = if (password) "***" else t.toString()
                    if (text!!.length > 200) text = text!!.substring(0, 200)
                }
                val r = Rect()
                src.getBoundsInScreen(r)
                bounds = "${r.left},${r.top},${r.right},${r.bottom}"
            }
        } catch (_: Exception) {
        } finally {
            try {
                src?.recycle()
            } catch (_: Exception) {
            }
        }

        // TYPE_VIEW_TEXT_CHANGED 一律不写原文（可能是密码逐字符输入）。
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) text = null

        SessionLog.event(
            Json.obj()
                .put("t_mono_ns", eventMonoNs)
                .put("event_time_uptime_ms", eventTimeMs)
                .put("record_mono_ns", nowNs)
                .put("wall_ms", System.currentTimeMillis())
                .put("event_type", typeName)
                .put("event_type_id", type)
                .put("package_name", pkg)
                .put("window_id", event.windowId)
                .put("class_name", cls)
                .put("view_id", viewId)
                .put("content_description", desc)
                .put("text", text)
                .put("is_password", password)
                .put("bounds", bounds)
                .put("item_count", event.itemCount)
                .put(
                    "scroll_delta",
                    if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) event.scrollDeltaY else 0,
                )
                // 直接给出「事件发生时最近一帧」的 id，省掉下游插值
                .put("nearest_frame_id", FrameBus.latestFrameId)
                .end(),
        )

        // 窗口切换时顺带 dump 一棵有界控件树，便于事后还原「当时屏幕上有什么」。
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) dumpTree(pkg)
    }

    /**
     * 需求驱动的即时控件树。
     *
     * 为什么要有它：`uiautomator dump` 在游戏这种持续动画的画面上会
     * `ERROR: could not get idle state`，连文件都不写，整条 UI 验证链就断了。
     * 无障碍服务不需要等 idle，直接读当前窗口即可。
     *
     * @param maxDepth 最大深度（默认 48 足够覆盖游戏的 15~20 层）
     * @param maxNodes 节点预算
     * @param flat true = 只回「有文字或可点」的扁平列表（体积小、直接能用）；
     *             false = 回完整嵌套树（保留结构）
     * @return JSON 字符串；拿不到窗口时返回 null
     */
    fun dumpActiveTree(maxDepth: Int, maxNodes: Int, flat: Boolean): String? {
        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow ?: return null
        } catch (_: Exception) {
            return null
        }
        return try {
            if (flat) {
                val items = ArrayList<String>()
                nodeFlat(root, items, 0, maxDepth, intArrayOf(maxNodes))
                val sb = StringBuilder(4096)
                sb.append('[')
                for (i in items.indices) {
                    if (i > 0) sb.append(',')
                    sb.append(items[i])
                }
                sb.append(']')
                sb.toString()
            } else {
                val sb = StringBuilder(4096)
                nodeJson(root, sb, 0, intArrayOf(maxNodes), maxDepth)
                sb.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** 扁平模式：只收「有文字 / 有描述 / 可点 / 可滚」的节点，顺序即树的先序（≈ 视觉顺序）。 */
    private fun nodeFlat(
        n: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
        maxDepth: Int,
        budget: IntArray,
    ) {
        if (depth > maxDepth || budget[0] <= 0) return
        budget[0]--
        val id = n.viewIdResourceName
        val cls = n.className
        val txt = if (n.isPassword) "***" else n.text
        val desc = n.contentDescription
        val clickable = n.isClickable
        val scrollable = n.isScrollable
        if ((txt != null && txt.isNotEmpty()) || (desc != null && desc.isNotEmpty()) ||
            clickable || scrollable
        ) {
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(
                Json.obj()
                    .put("cls", cls?.toString()?.let { simple(it) })
                    .put("id", id)
                    .put("text", cut(txt))
                    .put("desc", cut(desc))
                    .put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
                    .put("clickable", clickable)
                    .put("scrollable", scrollable)
                    .put("depth", depth)
                    .end(),
            )
        }
        val children = n.childCount
        for (i in 0 until children) {
            if (budget[0] <= 0) break
            var c: AccessibilityNodeInfo? = null
            try {
                c = n.getChild(i) ?: continue
                nodeFlat(c, out, depth + 1, maxDepth, budget)
            } catch (_: Exception) {
            } finally {
                try {
                    c?.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 有界控件树导出。
     *
     * 为什么必须有界：游戏或设置页的控件树可以大到一两千节点，逐节点序列化会明显卡顿，
     * 日志体积也会爆炸。这里限制深度 24、节点 500、单节点文本 80 字，
     * 超出部分记 `truncated:true`，保证「结构可见且不拖慢系统」。
     */
    private fun dumpTree(pkg: String) {
        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow ?: return
        } catch (_: Exception) {
            return
        }
        val sb = StringBuilder(2048)
        val budget = intArrayOf(500)
        try {
            nodeJson(root, sb, 0, budget, AUTO_DUMP_MAX_DEPTH)
        } catch (_: Exception) {
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
        val tree = sb.toString()
        val shot = maybeAutoShot(tree)
        SessionLog.event(
            Json.obj()
                .put("t_mono_ns", System.nanoTime())
                .put("event_type", "view_tree_snapshot")
                .put("package_name", pkg)
                .put("nearest_frame_id", FrameBus.latestFrameId)
                .put("tree", tree)
                .put("truncated", budget[0] <= 0)
                .put("auto_shot", shot)
                .end(),
        )
    }

    /**
     * 按游戏事件自动保存截图。
     *
     * 触发方式是**文本关键词**而不是控件 id：游戏的关键文案（离线结算、挑战完成、
     * 成就解锁）都来自本地化字符串，id 反而常是混淆后的短名（如 `b8`），
     * 用 id 匹配会在版本更新后静默失效。关键词失效至少是「一眼能看出没匹配上」。
     *
     * 三条限流，防止把磁盘写满：
     * 1. 同一标签 3 秒内只存一张（事件会连发多条窗口变化）；
     * 2. 单会话上限 200 张；
     * 3. 没有可用帧时直接跳过，不写空文件。
     *
     * @return 相对会话目录的路径，未触发则 null
     */
    private fun maybeAutoShot(tree: String): String? {
        for (rule in AUTO_SHOT_RULES) {
            if (!tree.contains(rule[0])) continue
            val now = SystemClock.uptimeMillis()
            val last = lastShotAt[rule[1]]
            if (last != null && now - last < 3000) continue
            if (shotCount >= MAX_AUTO_SHOTS) return null
            val jpeg = FrameBus.latestJpeg ?: return null
            val dir = SessionLog.currentDir() ?: return null
            val shotDir = File(dir, "events")
            shotDir.mkdirs()
            val name = String.format(Locale.US, "evt_%06d_%s.jpg", FrameBus.latestFrameId, rule[1])
            val f = File(shotDir, name)
            return try {
                FileOutputStream(f).use { it.write(jpeg) }
                lastShotAt[rule[1]] = now
                shotCount++
                "events/$name"
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun nodeJson(
        n: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        budget: IntArray,
        maxDepth: Int,
    ) {
        if (depth > maxDepth || budget[0] <= 0) {
            sb.append("null")
            return
        }
        budget[0]--
        // 先递归生成子树，再把它作为 children 字段塞进本节点对象。
        // 早期版本是「先 append 对象、再 append ,"children":[...]」，
        // 结果 children 落在对象闭合括号之外，整棵树不是合法 JSON（{...},"children":[]）。
        // 这个错误只有真正拿它去 json.loads 才会暴露，结构 lint 查不出来。
        val kids = StringBuilder(256)
        val children = n.childCount
        var first = true
        for (i in 0 until children) {
            if (budget[0] <= 0) break
            var c: AccessibilityNodeInfo? = null
            try {
                c = n.getChild(i) ?: continue
                if (!first) kids.append(',')
                first = false
                nodeJson(c, kids, depth + 1, budget, maxDepth)
            } catch (_: Exception) {
                // 单个子节点取不到就当它不存在，不影响整棵树
            } finally {
                try {
                    c?.recycle()
                } catch (_: Exception) {
                }
            }
        }
        val id = n.viewIdResourceName
        val cls = n.className
        val txt = if (n.isPassword) "***" else n.text
        val desc = n.contentDescription
        val r = Rect()
        n.getBoundsInScreen(r)
        sb.append(
            Json.obj()
                .put("cls", cls?.toString()?.let { simple(it) })
                .put("id", id)
                .put("text", cut(txt))
                .put("desc", cut(desc))
                .put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
                .put("clickable", n.isClickable)
                .put("scrollable", n.isScrollable)
                .put("depth", depth)
                .putRaw("children", "[$kids]")
                .end(),
        )
    }

    /** 每秒事件数闸门（滑动 1 秒窗口）。 */
    private fun allowRate(): Boolean {
        val now = SystemClock.uptimeMillis()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.peekFirst() > 1000) {
            recent.pollFirst()
        }
        return recent.size <= MAX_EVENTS_PER_SEC
    }

    private fun meta(): String = Json.obj()
        .put("tool", "Oreki Frame Observer")
        .put("started_by", "a11y")
        .put("started_iso", SessionLog.isoNow())
        .put("allow_packages", ALLOW.joinToString(","))
        .put("time_base", "System.nanoTime / CLOCK_MONOTONIC (ns)")
        .end()

    override fun onInterrupt() {
        // 无语音反馈，无需处理。
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SessionLog.event(
            Json.obj()
                .put("t_mono_ns", System.nanoTime())
                .put("event_type", "service_disconnected")
                .put("package_name", packageName)
                .put("uptime_ms", (System.nanoTime() - enabledAt) / 1_000_000L)
                .end(),
        )
        SessionLog.flush()
        instance = null
        return super.onUnbind(intent)
    }

    override fun toString(): String =
        String.format(Locale.US, "ObserverA11yService(allow=%s)", ALLOW)

    companion object {
        private const val TAG = "OrekiA11y"

        /** 默认白名单：被测游戏 + 本工具自身。 */
        private val ALLOW = setOf(
            "com.oreki.antimatterdimensions",
            "com.oreki.frameobserver",
        )

        /** 事件速率上限：超过就丢弃（游戏高刷屏上 TYPE_WINDOW_CONTENT_CHANGED 可刷到上千/秒）。 */
        private const val MAX_EVENTS_PER_SEC = 200

        /**
         * 自动快照的最大深度。
         *
         * 早期固定 12，实测会把游戏的正文全部切掉：内容区在第 13~18 层，
         * 深度 12 的节点 children 恒为空数组，于是 32 个节点里只有 1 个带文字，
         * 「还原弹了什么」这个用途完全失效。24 层能覆盖绝大多数页面，
         * 又不至于在自动快照上花太多时间（每个窗口切换都要跑一次）。
         * 需要更深时用 `GET /ui?depth=48`。
         */
        private const val AUTO_DUMP_MAX_DEPTH = 24

        /**
         * 自动截图规则：{关键词, 标签}。
         *
         * 关键词必须「只在特定时刻出现」。页签名（「永恒」「现实」）在任何停留于该页面的
         * 时刻都存在于控件树里，等于每 3 秒存一张，既灌满磁盘又没有任何信息量。
         * 规则只收「弹窗/提示类」文本。需要扩展时在这里加一行即可。
         */
        private val AUTO_SHOT_RULES = arrayOf(
            arrayOf("在你离开", "offline_dialog"), // 离线结算弹窗
            arrayOf("可以完成", "challenge_done"), // 挑战可完成提示
            arrayOf("你已达成", "achievement"), // 成就解锁
            arrayOf("成就已解锁", "achievement"),
            arrayOf("确定要", "confirm_dialog"), // 需要二次确认的破坏性操作
        )

        private const val MAX_AUTO_SHOTS = 200

        /**
         * 当前连接的服务实例。
         *
         * 为什么需要它：`GET /ui` 是需求驱动的即时控件树查询，只有无障碍服务本身能调
         * `getRootInActiveWindow()`。HTTP 服务与它在同一个进程里，拿一个静态引用比走
         * Binder 便宜得多。这是「观测」而不是「操作」：只被 `/ui` 用来**读**当前窗口，
         * 不参与任何 performAction。
         */
        @Volatile
        private var instance: ObserverA11yService? = null

        /** HTTP 侧取实例；未连接时返回 null（调用方负责回 503）。 */
        @JvmStatic
        fun get(): ObserverA11yService? = instance

        private fun cut(cs: CharSequence?): String? {
            if (cs == null) return null
            val s = cs.toString()
            return if (s.length > 80) s.substring(0, 80) else s
        }

        private fun simple(fqcn: String): String {
            val i = fqcn.lastIndexOf('.')
            return if (i < 0) fqcn else fqcn.substring(i + 1)
        }
    }
}
