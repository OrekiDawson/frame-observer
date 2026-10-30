package com.oreki.frameobserver.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.oreki.frameobserver.FrameBus;
import com.oreki.frameobserver.SessionLog;
import com.oreki.frameobserver.util.Json;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 界面观测服务：只记录，不操作。
 *
 * <p><b>为什么不用它截图</b>（用户需求二.A 的原话：不得把 AccessibilityService 当作截图 API）：
 * 无障碍服务拿不到像素，只能拿控件树与事件；而且依赖它截图在游戏这种自绘 SurfaceView 上
 * 完全失效 —— 游戏画面根本不在 View 树里，节点树只有一层 SurfaceView 容器。
 * 所以本类只负责「什么时候、哪个窗口、哪个控件、发生了什么」，画面交给 MediaProjection。
 *
 * <p><b>时间基准对齐</b>：{@code AccessibilityEvent.getEventTime()} 返回 uptimeMillis，
 * 与 {@code System.nanoTime()} 同属 CLOCK_MONOTONIC，因此
 * {@code t_mono_ns ≈ System.nanoTime() - (SystemClock.uptimeMillis() - eventTime) * 1e6}
 * 可以把事件精确落到帧时间轴上；同时直接记录 {@code nearest_frame_id}，
 * 让「事件 → 最近一帧」的关联不需要任何插值。
 *
 * <p><b>隐私边界</b>：
 * <ul>
 *   <li>只记录白名单包名（默认被测游戏 + 本工具），其他应用一律不落盘；</li>
 *   <li>密码类输入框（{@code isPassword()}）文本强制替换为 {@code ***}；</li>
 *   <li>不记录任何 {@code TYPE_VIEW_TEXT_CHANGED} 的原文，只记录长度变化 —— 避免把
 *       账号密码逐字符写进日志，这是最容易漏的一条。</li>
 * </ul>
 */
public class ObserverA11yService extends AccessibilityService {

    private static final String TAG = "OrekiA11y";

    /** 默认白名单：被测游戏 + 本工具自身。 */
    private static final Set<String> ALLOW = new HashSet<>(Arrays.asList(
            "com.oreki.antimatterdimensions",
            "com.oreki.frameobserver"
    ));

    /** 事件速率上限：超过就丢弃（游戏高刷屏上 TYPE_WINDOW_CONTENT_CHANGED 可以刷到上千/秒）。 */
    private static final int MAX_EVENTS_PER_SEC = 200;

    /**
     * 自动快照的最大深度。
     *
     * <p>早期固定 12，实测**会把游戏的正文全部切掉**：游戏的内容区在第 13~18 层，
     * 深度 12 的节点 {@code children} 恒为空数组，于是 32 个节点里只有 1 个带文字，
     * 「还原弹了什么」这个用途完全失效。24 层能覆盖绝大多数页面，
     * 又不至于在自动快照上花太多时间（自动快照是每个窗口切换都要跑一次的）。
     * 需要更深时用 {@code GET /ui?depth=48}。
     */
    private static final int AUTO_DUMP_MAX_DEPTH = 24;

    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private long enabledAt;

    /**
     * 自动截图规则：{关键词, 标签}。
     *
     * <p><b>关键词必须"只在特定时刻出现"</b>。第一版我一度想用「永恒」「现实」这种页签名，
     * 那是错的 —— 页签名在任何停留于该页面的时刻都存在于控件树里，等于每 3 秒存一张，
     * 既灌满磁盘又没有任何信息量。规则只收「弹窗/提示类」文本。
     *
     * <p>需要扩展时在这里加一行即可，不需要改逻辑。
     */
    private static final String[][] AUTO_SHOT_RULES = {
            {"在你离开", "offline_dialog"},      // 离线结算弹窗
            {"可以完成", "challenge_done"},      // 挑战可完成提示
            {"你已达成", "achievement"},         // 成就解锁
            {"成就已解锁", "achievement"},
            {"确定要", "confirm_dialog"},        // 需要二次确认的破坏性操作
    };

    private static final int MAX_AUTO_SHOTS = 200;
    private int shotCount;
    private final java.util.Map<String, Long> lastShotAt = new java.util.HashMap<>();

    /**
     * 当前连接的服务实例。
     *
     * <p>为什么需要它：{@code GET /ui} 是**需求驱动**的即时控件树查询，
     * 只有无障碍服务本身能调 {@code getRootInActiveWindow()}。HTTP 服务与它在同一个进程里，
     * 拿一个静态引用比走 Binder 便宜得多。
     *
     * <p>这是「观测」而不是「操作」：本字段只被 /ui 用来**读**当前窗口，
     * 不参与任何 performAction。
     */
    private static volatile ObserverA11yService instance;

    /** HTTP 侧取实例；未连接时返回 null（调用方负责回 503）。 */
    public static ObserverA11yService get() {
        return instance;
    }

    /**
     * 需求驱动的即时控件树。
     *
     * <p>为什么要有它：{@code uiautomator dump} 在游戏这种持续动画的画面上会
     * {@code ERROR: could not get idle state}，**连文件都不写**，整条 UI 验证链就断了。
     * 无障碍服务不需要等 idle，直接读当前窗口即可。
     *
     * @param maxDepth 最大深度（uiautomator 默认递归到底；这里默认 48 足够覆盖游戏的 15~20 层）
     * @param maxNodes 节点预算
     * @param flat     true = 只回「有文字或可点」的扁平列表（体积小、直接能用）；
     *                 false = 回完整嵌套树（保留结构）
     * @return JSON 字符串；拿不到窗口时返回 null
     */
    public String dumpActiveTree(int maxDepth, int maxNodes, boolean flat) {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
        if (root == null) {
            return null;
        }
        int[] budget = {maxNodes};
        try {
            if (flat) {
                java.util.List<String> items = new java.util.ArrayList<>();
                nodeFlat(root, items, 0, maxDepth, budget);
                StringBuilder sb = new StringBuilder(4096);
                sb.append('[');
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(items.get(i));
                }
                sb.append(']');
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder(4096);
            nodeJson(root, sb, 0, budget, maxDepth);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                root.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    /** 扁平模式：只收「有文字 / 有描述 / 可点 / 可滚」的节点，顺序即树的先序（≈ 视觉顺序）。 */
    private void nodeFlat(AccessibilityNodeInfo n, java.util.List<String> out,
                          int depth, int maxDepth, int[] budget) {
        if (depth > maxDepth || budget[0] <= 0) {
            return;
        }
        budget[0]--;
        String id = n.getViewIdResourceName();
        CharSequence cls = n.getClassName();
        CharSequence txt = n.isPassword() ? "***" : n.getText();
        CharSequence desc = n.getContentDescription();
        boolean clickable = n.isClickable();
        boolean scrollable = n.isScrollable();
        if ((txt != null && txt.length() > 0) || (desc != null && desc.length() > 0)
                || clickable || scrollable) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            out.add(Json.obj()
                    .put("cls", cls == null ? null : simple(cls.toString()))
                    .put("id", id)
                    .put("text", cut(txt))
                    .put("desc", cut(desc))
                    .put("bounds", r.left + "," + r.top + "," + r.right + "," + r.bottom)
                    .put("clickable", clickable)
                    .put("scrollable", scrollable)
                    .put("depth", depth)
                    .end());
        }
        int children = n.getChildCount();
        for (int i = 0; i < children; i++) {
            if (budget[0] <= 0) {
                break;
            }
            AccessibilityNodeInfo c = null;
            try {
                c = n.getChild(i);
                if (c == null) {
                    continue;
                }
                nodeFlat(c, out, depth + 1, maxDepth, budget);
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try {
                        c.recycle();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.notificationTimeout = 50;
            setServiceInfo(info);
        }
        enabledAt = System.nanoTime();
        SessionLog.ensureSession(getExternalFilesDir(null), meta());
        SessionLog.event(Json.obj()
                .put("t_mono_ns", enabledAt)
                .put("event_type", "service_connected")
                .put("package_name", getPackageName())
                .end());
        Log.i(TAG, "a11y connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (!ALLOW.contains(pkg)) {
            // 非白名单应用：直接丢弃，不落盘、不缓存（隐私要求）。
            return;
        }
        if (!allowRate()) {
            return;
        }

        long nowNs = System.nanoTime();
        long eventTimeMs = event.getEventTime();
        // 把 uptimeMillis 事件时刻换算到 nanoTime 轴上
        long deltaMs = SystemClock.uptimeMillis() - eventTimeMs;
        long eventMonoNs = nowNs - deltaMs * 1_000_000L;

        int type = event.getEventType();
        String typeName = AccessibilityEvent.eventTypeToString(type);

        String viewId = null;
        String desc = null;
        String cls = event.getClassName() == null ? null : event.getClassName().toString();
        String text = null;
        String bounds = null;
        boolean password = false;

        AccessibilityNodeInfo src = null;
        try {
            src = event.getSource();
            if (src != null) {
                if (src.getViewIdResourceName() != null) {
                    viewId = src.getViewIdResourceName();
                }
                if (src.getContentDescription() != null) {
                    desc = src.getContentDescription().toString();
                }
                password = src.isPassword();
                CharSequence t = src.getText();
                if (t != null) {
                    text = password ? "***" : t.toString();
                    if (text.length() > 200) {
                        text = text.substring(0, 200);
                    }
                }
                Rect r = new Rect();
                src.getBoundsInScreen(r);
                bounds = r.left + "," + r.top + "," + r.right + "," + r.bottom;
            }
        } catch (Exception ignored) {
        } finally {
            if (src != null) {
                try {
                    src.recycle();
                } catch (Exception ignored) {
                }
            }
        }

        // TYPE_VIEW_TEXT_CHANGED 一律不写原文（可能是密码逐字符输入）。
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            text = null;
        }

        SessionLog.event(Json.obj()
                .put("t_mono_ns", eventMonoNs)
                .put("event_time_uptime_ms", eventTimeMs)
                .put("record_mono_ns", nowNs)
                .put("wall_ms", System.currentTimeMillis())
                .put("event_type", typeName)
                .put("event_type_id", type)
                .put("package_name", pkg)
                .put("window_id", event.getWindowId())
                .put("class_name", cls)
                .put("view_id", viewId)
                .put("content_description", desc)
                .put("text", text)
                .put("is_password", password)
                .put("bounds", bounds)
                .put("item_count", event.getItemCount())
                .put("scroll_delta", type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                        ? (event.getScrollDeltaY()) : 0)
                // 直接给出「事件发生时最近一帧」的 id，省掉下游插值
                .put("nearest_frame_id", FrameBus.latestFrameId)
                .end());

        // 窗口切换时顺带 dump 一棵有界控件树，便于事后还原「当时屏幕上有什么」。
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            dumpTree(pkg);
        }
    }

    /**
     * 有界控件树导出。
     *
     * <p>为什么必须有界：游戏或设置页的控件树可以大到一两千节点，逐节点序列化会明显卡顿，
     * 而且日志体积爆炸。这里限制深度 12、节点 400、单节点文本 80 字，超出部分记
     * {@code truncated:true}，保证「结构可见且不拖慢系统」。
     */
    private void dumpTree(String pkg) {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return;
        }
        if (root == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(2048);
        int[] budget = {500};
        try {
            nodeJson(root, sb, 0, budget, AUTO_DUMP_MAX_DEPTH);
        } catch (Exception ignored) {
        } finally {
            try {
                root.recycle();
            } catch (Exception ignored) {
            }
        }
        String tree = sb.toString();
        String shot = maybeAutoShot(tree);
        SessionLog.event(Json.obj()
                .put("t_mono_ns", System.nanoTime())
                .put("event_type", "view_tree_snapshot")
                .put("package_name", pkg)
                .put("nearest_frame_id", FrameBus.latestFrameId)
                .put("tree", tree)
                .put("truncated", budget[0] <= 0)
                .put("auto_shot", shot)
                .end());
    }

    /**
     * 按游戏事件自动保存截图（需求 第二阶段）。
     *
     * <p>触发方式是**文本关键词**而不是控件 id：游戏的关键文案（离线结算、挑战完成、
     * 成就解锁）都来自本地化字符串，id 反而常是混淆后的短名（如 {@code b8}），
     * 用 id 匹配会在版本更新后静默失效。关键词失效至少是「一眼能看出没匹配上」。
     *
     * <p>三条限流，防止把磁盘写满：
     * <ol>
     *   <li>同一标签 3 秒内只存一张（事件会连发多条窗口变化）；</li>
     *   <li>单会话上限 200 张；</li>
     *   <li>没有可用帧时直接跳过，不写空文件。</li>
     * </ol>
     *
     * @return 相对会话目录的路径，未触发则 null
     */
    private String maybeAutoShot(String tree) {
        for (String[] rule : AUTO_SHOT_RULES) {
            if (!tree.contains(rule[0])) {
                continue;
            }
            long now = SystemClock.uptimeMillis();
            Long last = lastShotAt.get(rule[1]);
            if (last != null && now - last < 3000) {
                continue;
            }
            if (shotCount >= MAX_AUTO_SHOTS) {
                return null;
            }
            byte[] jpeg = FrameBus.latestJpeg;
            if (jpeg == null) {
                return null;
            }
            java.io.File dir = SessionLog.currentDir();
            if (dir == null) {
                return null;
            }
            java.io.File shotDir = new java.io.File(dir, "events");
            //noinspection ResultOfMethodCallIgnored
            shotDir.mkdirs();
            String name = String.format(Locale.US, "evt_%06d_%s.jpg", FrameBus.latestFrameId, rule[1]);
            java.io.File f = new java.io.File(shotDir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(jpeg);
                lastShotAt.put(rule[1], now);
                shotCount++;
                return "events/" + name;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void nodeJson(AccessibilityNodeInfo n, StringBuilder sb, int depth, int[] budget,
                          int maxDepth) {
        if (depth > maxDepth || budget[0] <= 0) {
            sb.append("null");
            return;
        }
        budget[0]--;
        // 先递归生成子树，再把它作为 children 字段塞进本节点对象。
        // 早期版本是「先 append 对象、再 append ,"children":[...]」，
        // 结果 children 落在对象闭合括号之外，整棵树不是合法 JSON（{...},"children":[]）。
        // 这个错误只有真正拿它去 json.loads 才会暴露，结构 lint 查不出来。
        StringBuilder kids = new StringBuilder(256);
        int children = n.getChildCount();
        boolean first = true;
        for (int i = 0; i < children; i++) {
            if (budget[0] <= 0) {
                break;
            }
            AccessibilityNodeInfo c = null;
            try {
                c = n.getChild(i);
                if (c == null) {
                    continue;
                }
                if (!first) {
                    kids.append(',');
                }
                first = false;
                nodeJson(c, kids, depth + 1, budget, maxDepth);
            } catch (Exception ignored) {
                // 单个子节点取不到就当它不存在，不影响整棵树
            } finally {
                if (c != null) {
                    try {
                        c.recycle();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        String id = n.getViewIdResourceName();
        CharSequence cls = n.getClassName();
        CharSequence txt = n.isPassword() ? "***" : n.getText();
        CharSequence desc = n.getContentDescription();
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append(Json.obj()
                .put("cls", cls == null ? null : simple(cls.toString()))
                .put("id", id)
                .put("text", cut(txt))
                .put("desc", cut(desc))
                .put("bounds", r.left + "," + r.top + "," + r.right + "," + r.bottom)
                .put("clickable", n.isClickable())
                .put("scrollable", n.isScrollable())
                .put("depth", depth)
                .putRaw("children", "[" + kids + "]")
                .end());
    }

    private static String cut(CharSequence cs) {
        if (cs == null) {
            return null;
        }
        String s = cs.toString();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String simple(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }

    /** 每秒事件数闸门（滑动 1 秒窗口）。 */
    private boolean allowRate() {
        long now = SystemClock.uptimeMillis();
        recent.addLast(now);
        while (!recent.isEmpty() && now - recent.peekFirst() > 1000) {
            recent.pollFirst();
        }
        return recent.size() <= MAX_EVENTS_PER_SEC;
    }

    private String meta() {
        return Json.obj()
                .put("tool", "Oreki Frame Observer")
                .put("started_by", "a11y")
                .put("started_iso", SessionLog.isoNow())
                .put("allow_packages", String.join(",", ALLOW))
                .put("time_base", "System.nanoTime / CLOCK_MONOTONIC (ns)")
                .end();
    }

    @Override
    public void onInterrupt() {
        // 无语音反馈，无需处理。
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        SessionLog.event(Json.obj()
                .put("t_mono_ns", System.nanoTime())
                .put("event_type", "service_disconnected")
                .put("package_name", getPackageName())
                .put("uptime_ms", (System.nanoTime() - enabledAt) / 1_000_000L)
                .end());
        SessionLog.flush();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "ObserverA11yService(allow=%s)", ALLOW);
    }
}
