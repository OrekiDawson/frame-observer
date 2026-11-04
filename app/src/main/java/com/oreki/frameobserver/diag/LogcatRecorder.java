package com.oreki.frameobserver.diag;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Logcat 实时捕获（诊断用）。
 *
 * <p><b>为什么在 app 内启动 {@code logcat -v threadtime} 子进程而不是走 ADB</b>：
 * 本工具的使用场景常常是「AVD 就绪、只想看日志」，此时没有 USB/网络 ADB 可用；
 * 而 app 自己有 {@code READ_LOGS} 权限（本工具是 debug 签名的测试基础设施），
 * 直接 runtime exec 就能拿到与 {@code adb logcat} 同源的数据，且不受连接影响。
 *
 * <p><b>不阻塞截图</b>：采集在独立线程，环形缓冲 + 有上限的落盘写入；
 * HTTP 接口只读缓冲，不做任何磁盘同步工作。
 *
 * <p><b>环形缓冲</b>：只保留最近 {@link #CAPACITY} 条，内存占用固定（约几百 KB），
 * 长时间挂着也不会把设备拖垮。
 */
public final class LogcatRecorder {

    private static final String TAG = "OrekiLogcat";

    /** 环形缓冲容量（条）。按平均 200 字节/条估算约 800 KB 上限。 */
    private static final int CAPACITY = 4000;

    private static final LogcatRecorder INSTANCE = new LogcatRecorder();

    public static LogcatRecorder get() {
        return INSTANCE;
    }

    /** 一条日志。 */
    public static final class Line {
        public final long seq;
        public final long wallMs;
        public final String level;
        public final String tag;
        public final int pid;
        public final String tid;
        public final String text;
        /** 若本条属于某个诊断会话，记录会话名；否则 null。 */
        public final String session;

        Line(long seq, long wallMs, String level, String tag, int pid, String tid,
             String text, String session) {
            this.seq = seq;
            this.wallMs = wallMs;
            this.level = level;
            this.tag = tag;
            this.pid = pid;
            this.tid = tid;
            this.text = text;
            this.session = session;
        }
    }

    private final ArrayDeque<Line> ring = new ArrayDeque<>(CAPACITY);
    private final Object lock = new Object();

    private Process proc;
    private Thread reader;
    private volatile boolean running;
    private long seq;

    /** 过滤条件（都可为空 = 不过滤）。 */
    private volatile int minLevel = 0;           // 0=V 1=D 2=I 3=W 4=E
    private volatile int pidFilter = 0;          // 0 = 不限
    private volatile String tagFilter = null;
    private volatile String keywordFilter = null;
    private volatile String pkgFilter = null;    // 按包名反查 PID 后过滤
    private volatile boolean paused;

    /** 诊断会话：方法名 → 规则。用于把插桩日志归到某次诊断下。 */
    private final List<DiagRule> rules = new ArrayList<>();

    private LogcatRecorder() {
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 开始捕获。重复调用是幂等的（已在跑就直接返回 true）。
     *
     * @param pkg 需要按包名过滤时的包名；null 表示不过滤
     */
    public synchronized boolean start(String pkg) {
        // [R75] 幂等判断必须带上 isAlive()。
        //
        // 早期只判 `running`：子进程被系统回收（或 logcat 自己异常退出）后
        // `running` 仍是 true，于是 `start()` 直接 return，永远起不来 ——
        // 表现为「kill 掉 logcat 子进程后，再点开始也没日志」。
        // 现在：只要子进程不在了，就先清理状态再重新拉起。
        if (running && isAlive()) {
            return true;
        }
        if (running) {
            Log.w(TAG, "logcat 子进程已退出，自动重新拉起");
            stop();
        }
        if (pkg != null && !pkg.isEmpty()) {
            pkgFilter = pkg;
            int pid = pidOf(pkg);
            if (pid > 0) {
                pidFilter = pid;
            }
        }
        // [R74] 不要一次给多个 `-b`：Android 13+ 的 logcat 对多缓冲组合有额外校验，
        // 组合不合法时进程会立刻退出且 stderr 被 redirectErrorStream 混进 stdout，
        // 结果是「进程在、缓冲永远 0」。只读 main（游戏与自身日志都在这里）。
        List<String> cmd = new ArrayList<>();
        cmd.add("logcat");
        cmd.add("-v");
        cmd.add("threadtime");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            running = true;
            paused = false;
            reader = new Thread(this::pump, "oreki-logcat");
            reader.setDaemon(true);
            reader.start();
            Log.i(TAG, "logcat started pidFilter=" + pidFilter + " pkg=" + pkgFilter);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "logcat start failed: " + e);
            running = false;
            return false;
        }
    }

    /** 停止捕获并回收子进程。 */
    public synchronized void stop() {
        running = false;
        Process p = proc;
        proc = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
        Thread t = reader;
        reader = null;
        if (t != null) {
            t.interrupt();
        }
        Log.i(TAG, "logcat stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /** 子进程是否还活着（用于检测「ADB 断了 / logcat 退出了」）。 */
    public boolean isAlive() {
        Process p = proc;
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;   // 已经退出
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    public void setPaused(boolean v) {
        paused = v;
    }

    public boolean isPaused() {
        return paused;
    }

    public void clear() {
        synchronized (lock) {
            ring.clear();
        }
    }

    // ------------------------------------------------------------------ 过滤

    public void setMinLevel(int level) {
        minLevel = Math.max(0, Math.min(4, level));
    }

    public int getMinLevel() {
        return minLevel;
    }

    public void setPidFilter(int pid) {
        pidFilter = pid;
    }

    public int getPidFilter() {
        return pidFilter;
    }

    public void setTagFilter(String tag) {
        tagFilter = (tag == null || tag.isEmpty()) ? null : tag;
    }

    public String getTagFilter() {
        return tagFilter;
    }

    public void setKeywordFilter(String kw) {
        keywordFilter = (kw == null || kw.isEmpty()) ? null : kw;
    }

    public String getKeywordFilter() {
        return keywordFilter;
    }

    public void setPkgFilter(String pkg) {
        pkgFilter = (pkg == null || pkg.isEmpty()) ? null : pkg;
        if (pkgFilter != null) {
            int pid = pidOf(pkgFilter);
            if (pid > 0) {
                pidFilter = pid;
            }
        }
    }

    public String getPkgFilter() {
        return pkgFilter;
    }

    // ------------------------------------------------------------------ 诊断规则（插桩归属）

    /** 一条临时诊断规则：把某类/某方法的日志归到某次诊断会话。 */
    public static final class DiagRule {
        public final String cls;
        public final String method;
        public final String session;

        public DiagRule(String cls, String method, String session) {
            this.cls = cls;
            this.method = method;
            this.session = session;
        }
    }

    /** 注册一条临时诊断规则。只影响“日志归属”，不做任何代码注入。 */
    public synchronized void addRule(String cls, String method, String session) {
        rules.add(new DiagRule(cls, method, session));
        Log.i(TAG, "diag rule + " + cls + "#" + method + " @ " + session);
    }

    /** 移除某次会话的全部诊断规则。 */
    public synchronized int removeSessionRules(String session) {
        int n = 0;
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (rules.get(i).session.equals(session)) {
                rules.remove(i);
                n++;
            }
        }
        Log.i(TAG, "diag rule - session=" + session + " removed=" + n);
        return n;
    }

    public synchronized List<DiagRule> rules() {
        return new ArrayList<>(rules);
    }

    // ------------------------------------------------------------------ 查询

    /** 按当前过滤条件取最近 {@code limit} 条。 */
    public List<Line> tail(int limit, Pattern extraKeyword) {
        List<Line> out = new ArrayList<>();
        synchronized (lock) {
            int skip = Math.max(0, ring.size() - Math.max(0, limit));
            int i = 0;
            for (Line l : ring) {
                if (i++ < skip) {
                    continue;
                }
                if (accept(l, extraKeyword)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    /** 取序号大于 {@code sinceSeq} 的条目（增量拉取）。 */
    public List<Line> since(long sinceSeq, Pattern extraKeyword) {
        List<Line> out = new ArrayList<>();
        synchronized (lock) {
            for (Line l : ring) {
                if (l.seq > sinceSeq && accept(l, extraKeyword)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    public int size() {
        synchronized (lock) {
            return ring.size();
        }
    }

    public long lastSeq() {
        synchronized (lock) {
            return seq;
        }
    }

    private boolean accept(Line l, Pattern extra) {
        if (levelRank(l.level) < minLevel) {
            return false;
        }
        if (pidFilter > 0 && l.pid != pidFilter) {
            return false;
        }
        if (tagFilter != null && !l.tag.contains(tagFilter)) {
            return false;
        }
        if (keywordFilter != null && !l.text.contains(keywordFilter)) {
            return false;
        }
        if (extra != null && !extra.matcher(l.text).find()) {
            return false;
        }
        return true;
    }

    private static int levelRank(String lv) {
        switch (lv) {
            case "V": return 0;
            case "D": return 1;
            case "I": return 2;
            case "W": return 3;
            case "E": return 4;
            case "F": return 4;
            default: return 2;
        }
    }

    // ------------------------------------------------------------------ 导出

    /** 导出为文本（便于直接贴进报告）。 */
    public String exportText(List<Line> lines) {
        StringBuilder sb = new StringBuilder(lines.size() * 96);
        for (Line l : lines) {
            sb.append(iso(l.wallMs)).append(' ')
              .append(l.level).append('/').append(l.tag)
              .append('(').append(l.pid).append(':').append(l.tid).append(") ")
              .append(l.text).append('\n');
        }
        return sb.toString();
    }

    /** 导出为 JSONL。 */
    public String exportJsonl(List<Line> lines) {
        StringBuilder sb = new StringBuilder(lines.size() * 128);
        for (Line l : lines) {
            StringBuilder o = new StringBuilder(160);
            o.append('{');
            o.append("\"seq\":").append(l.seq).append(',');
            o.append("\"wall_ms\":").append(l.wallMs).append(',');
            o.append("\"level\":\"").append(esc(l.level)).append("\",");
            o.append("\"tag\":\"").append(esc(l.tag)).append("\",");
            o.append("\"pid\":").append(l.pid).append(',');
            o.append("\"tid\":\"").append(esc(l.tid)).append("\",");
            o.append("\"text\":\"").append(esc(l.text)).append('"');
            if (l.session != null) {
                o.append(",\"session\":\"").append(esc(l.session)).append('"');
            }
            o.append('}');
            sb.append(o).append('\n');
        }
        return sb.toString();
    }

    /** 落盘到指定文件（追加）。 */
    public boolean exportTo(File f, List<Line> lines, boolean jsonl) {
        try (FileOutputStream fos = new FileOutputStream(f, true);
             Writer w = new OutputStreamWriter(fos, "UTF-8")) {
            w.write(jsonl ? exportJsonl(lines) : exportText(lines));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "export failed: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 内部

    private void pump() {
        int totalParsed = 0;
        int unparsed = 0;
        Process p = proc;
        if (p == null) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            while (running && (line = br.readLine()) != null) {
                if (paused) {
                    continue;
                }
                Line parsed = parse(line);
                if (parsed == null) {
                    // [R74] 解析失败要有痕迹，但**每条只可能出现一次**：
                    // 早期写法用一个「前 3 条才打印」的计数器，而该计数器只用在这个分支里，
                    // 成功解析时永不递增 => 一旦持续有解析失败，就永远满足 <3，
                    // 于是每条都 `Log.w(raw 行内容)`，而这条日志本身又被 logcat 读回来、
                    // 再次打印 —— 形成指数级自我复制，把缓冲与磁盘瞬间打满。
                    // 现在只统计数量，结束时汇总一条。
                    unparsed++;
                    continue;
                }
                synchronized (lock) {
                    if (ring.size() >= CAPACITY) {
                        ring.removeFirst();
                    }
                    ring.addLast(parsed);
                }
                totalParsed++;
            }
            Log.i(TAG, "logcat reader ended, parsed=" + totalParsed + " unparsed=" + unparsed);
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "logcat reader ended: " + e
                        + " parsed=" + totalParsed + " unparsed=" + unparsed);
            }
        }
    }

    /**
     * 解析 threadtime 行：
     * {@code 01-02 03:04:05.678  1234  1250 I Tag     : message}
     */
    private Line parse(String raw) {
        try {
            if (raw.length() < 33) {
                return null;
            }
            // 前 18 字符是 "MM-dd HH:mm:ss.SSS"
            String stamp = raw.substring(0, 18).trim();
            int p = 19;
            while (p < raw.length() && raw.charAt(p) == ' ') {
                p++;
            }
            int q = p;
            while (q < raw.length() && raw.charAt(q) != ' ') {
                q++;
            }
            if (q >= raw.length()) {
                return null;
            }
            int pid = Integer.parseInt(raw.substring(p, q));
            int r = q;
            while (r < raw.length() && raw.charAt(r) == ' ') {
                r++;
            }
            int s = r;
            while (s < raw.length() && raw.charAt(s) != ' ') {
                s++;
            }
            String tid = raw.substring(r, s);
            int t = s;
            while (t < raw.length() && raw.charAt(t) == ' ') {
                t++;
            }
            if (t >= raw.length()) {
                return null;
            }
            String level = String.valueOf(raw.charAt(t));
            int u = t + 1;
            while (u < raw.length() && raw.charAt(u) == ' ') {
                u++;
            }
            int colon = raw.indexOf(": ", u);
            if (colon < 0) {
                return null;
            }
            String tag = raw.substring(u, colon).trim();
            String text = raw.substring(colon + 2);

            String session = null;
            for (DiagRule rule : rules) {
                if (text.contains(rule.cls) && text.contains(rule.method)) {
                    session = rule.session;
                    break;
                }
            }
            long wall = parseWall(stamp);
            long s2;
            synchronized (lock) {
                s2 = ++seq;
            }
            return new Line(s2, wall, level, tag, pid, tid, text, session);
        } catch (Exception e) {
            return null;
        }
    }

    private static final ThreadLocal<SimpleDateFormat> IN_STAMP = ThreadLocal.withInitial(() -> {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f;
    });

    private static long parseWall(String stamp) {
        // [R74] logcat 的 threadtime 只给 `MM-dd HH:mm:ss.SSS`，**没有年份**。
        // 直接解析会落到 1970 年，得到 26 亿这类荒谬的 wall_ms，
        // 按时间窗口把「点击」与「日志」对齐时会全错。这里补当前年份再解析。
        try {
            int year = new java.util.GregorianCalendar().get(java.util.Calendar.YEAR);
            Date d = IN_STAMP.get().parse(year + "-" + stamp);
            return d == null ? System.currentTimeMillis() : d.getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static final ThreadLocal<SimpleDateFormat> OUT_ISO = ThreadLocal.withInitial(() -> {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    });

    static String iso(long ms) {
        return OUT_ISO.get().format(new Date(ms));
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    /** 包名 → PID。取不到返回 0。 */
    public static int pidOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return 0;
        }
        Process p = null;
        try {
            p = new ProcessBuilder("pidof", pkg).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        return Integer.parseInt(parts[0]);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    /** 设备信息，放进 Session 的 meta。 */
    public static String deviceInfo() {
        return "sdk=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE
                + " model=" + Build.MODEL
                + " device=" + Build.DEVICE;
    }
}
