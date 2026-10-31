package com.oreki.frameobserver.net;

import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import com.oreki.frameobserver.CaptureService;
import com.oreki.frameobserver.FrameBus;
import com.oreki.frameobserver.ProcStats;
import com.oreki.frameobserver.SessionLog;
import com.oreki.frameobserver.util.Json;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本机 HTTP + WebSocket 服务（Frame Bridge 的网络侧）。
 *
 * <p><b>为什么不用 NanoHTTPD / OkHttp</b>：本工程刻意零依赖（见 settings.gradle 注释）。
 * 这里只实现 WorkBuddy 需要的 6 个端点，代码量可控且没有版本漂移风险。
 *
 * <p><b>绑定地址</b>：只绑 {@code 127.0.0.1}。这是安全要求的一部分 —— 画面属于被测应用的
 * 隐私内容，不能对对端网络可见。WorkBuddy 侧通过 {@code adb forward tcp:8731 tcp:8731}
 * 走 USB / 本地回环拿数据，链路不经过任何网络。
 *
 * <p><b>端点</b>：
 * <pre>
 *   GET  /                  状态页（HTML，中文）
 *   GET  /snapshot.jpg      最新一帧 JPEG（no-store）
 *   GET  /stats             统计 JSON
 *   GET  /events?since=N    无障碍事件，返回第 N 条之后的部分（JSONL 文本）
 *   GET  /ui?depth=48&max=3000&flat=1  即时控件树（不需要 idle，动画中也能读）
 *   GET  /frames/&lt;id&gt;.jpg   历史帧
 *   GET  /ws                WebSocket：服务端推二进制 JPEG 帧 + 文本事件
 *   POST /capture/stop      停止采集
 *   POST /capture/record?on=0|1  切换录像
 * </pre>
 *
 * <p>{@code /capture/start} 有意不提供：MediaProjection 授权必须由用户在设备上亲手确认，
 * 任何「远程点一下就开始录屏」的接口都等于绕过系统授权流程，明确不做。
 */
public final class FrameHttpServer {

    private static final String TAG = "OrekiHttp";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_CONNECTIONS = 8;

    private static final FrameHttpServer INSTANCE = new FrameHttpServer();

    public static FrameHttpServer get() {
        return INSTANCE;
    }

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private final AtomicInteger conns = new AtomicInteger();
    private android.content.Context appContext;

    private FrameHttpServer() {
    }

    /**
     * 注入应用上下文。
     *
     * <p>为什么需要它：{@code POST /capture/stop} 第一版只改了状态字符串、<b>并没有真的停服务</b>，
     * 等于接口在说谎。要真停就必须能发 Intent 给 {@link CaptureService}，
     * 而 Intent 需要 Context。这里只持有 applicationContext（不持有 Activity，不会泄漏）。
     */
    public void init(android.content.Context ctx) {
        if (appContext == null && ctx != null) {
            appContext = ctx.getApplicationContext();
        }
    }

    public synchronized void start(int port) {
        if (running) {
            return;
        }
        try {
            server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
            server.setReuseAddress(true);
        } catch (IOException e) {
            Log.w(TAG, "bind failed on " + port + ": " + e);
            return;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "oreki-http");
        acceptThread.start();
        Log.i(TAG, "listening on 127.0.0.1:" + port);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
        server = null;
        acceptThread = null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                if (conns.incrementAndGet() > MAX_CONNECTIONS) {
                    // 连接数上限：防止误连一堆客户端把本进程拖死。
                    conns.decrementAndGet();
                    s.close();
                    continue;
                }
                Thread t = new Thread(() -> {
                    try {
                        handle(s);
                    } catch (Throwable e) {
                        Log.w(TAG, "conn error: " + e);
                    } finally {
                        conns.decrementAndGet();
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                }, "oreki-http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept failed: " + e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 分发

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(10_000);
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null) {
            return;
        }
        // 读完请求头（含 Host / Upgrade / WebSocket-Key 等），丢弃 body（本服务不接受 body）。
        String line;
        String wsKey = null;
        String upgrade = null;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String k = line.substring(0, c).trim().toLowerCase(Locale.US);
            String v = line.substring(c + 1).trim();
            if ("sec-websocket-key".equals(k)) {
                wsKey = v;
            } else if ("upgrade".equals(k)) {
                upgrade = v;
            }
        }

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return;
        }
        String method = parts[0];
        String path = parts[1];
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }

        if ("/ws".equals(path) && wsKey != null && upgrade != null
                && "websocket".equalsIgnoreCase(upgrade)) {
            s.setSoTimeout(0);
            serveWebSocket(s, in, out, wsKey);
            return;
        }

        if ("GET".equals(method) && "/".equals(path)) {
            writeText(out, 200, "text/html; charset=utf-8", indexHtml());
            return;
        }
        if ("GET".equals(method) && "/snapshot.jpg".equals(path)) {
            byte[] jpeg = FrameBus.latestJpeg;
            if (jpeg == null) {
                writeText(out, 503, "text/plain; charset=utf-8", "还没有可用画面（未开始采集）");
                return;
            }
            writeBytes(out, 200, "image/jpeg", jpeg,
                    "X-Frame-Id: " + FrameBus.latestFrameId + "\r\n"
                            + "X-Frame-Mono-Ns: " + FrameBus.latestFrameMonoNs + "\r\n"
                            + "X-Frame-Source-Ns: " + FrameBus.latestFrameSourceNs + "\r\n");
            return;
        }
        if ("GET".equals(method) && "/stats".equals(path)) {
            String body = FrameBus.stats.toJson().replaceAll("\\}$",
                    ",\"proc\":" + Json.obj()
                            .put("java_heap_mb", ProcStats.javaHeapBytes() / 1048576d)
                            .put("pss_mb", ProcStats.nativeHeapBytes() / 1048576d)
                            .put("threads", Thread.activeCount())
                            .end()
                            + ",\"state\":" + quote(FrameBus.state)
                            + ",\"capturing\":" + FrameBus.capturing
                            // 无障碍绑定状态：让调用方一眼看出 /ui 会不会失败，
                            // 不用等请求 /ui 拿到 503 才回头排查。
                            + ",\"a11y\":" + quote(com.oreki.frameobserver.a11y.A11yBinder.state(appContext))
                            + ",\"session\":" + quote(FrameBus.sessionDir == null ? "" : FrameBus.sessionDir.getAbsolutePath())
                            + "}");
            writeText(out, 200, "application/json; charset=utf-8", body);
            return;
        }
        if ("GET".equals(method) && "/ui".equals(path)) {
            // 需求驱动的即时控件树。存在的理由：`uiautomator dump` 在持续动画的画面上
            // 会 "ERROR: could not get idle state" 且**不写文件**，整条 UI 验证链会断。
            // 无障碍服务不需要等 idle。
            int depth = 48;
            int max = 3000;
            boolean flat = true;
            for (String kv : query.split("&")) {
                try {
                    if (kv.startsWith("depth=")) {
                        depth = Math.max(1, Math.min(64, Integer.parseInt(kv.substring(6))));
                    } else if (kv.startsWith("max=")) {
                        max = Math.max(1, Math.min(5000, Integer.parseInt(kv.substring(4))));
                    } else if (kv.equals("flat=0")) {
                        flat = false;
                    } else if (kv.equals("flat=1")) {
                        flat = true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            com.oreki.frameobserver.a11y.ObserverA11yService svc =
                    com.oreki.frameobserver.a11y.ObserverA11yService.get();
            if (svc == null) {
                // 自愈一次：`am force-stop` 会把 Settings 里的无障碍绑定清空，
                // 这时干等不会恢复，先试着自己写回去（缺权限会在内部被吞掉并记日志）。
                com.oreki.frameobserver.a11y.A11yBinder.ensureBound(appContext);
                svc = com.oreki.frameobserver.a11y.ObserverA11yService.get();
            }
            if (svc == null) {
                // 仍然连不上：返回结构化错误体，把「现在什么状态、下一步跑什么命令」
                // 直接带出去，避免调用方只看到一句无法行动的 503。
                String st = com.oreki.frameobserver.a11y.A11yBinder.state(appContext);
                writeText(out, 503, "application/json; charset=utf-8", Json.obj()
                        .put("error", "a11y_not_connected")
                        .put("state", st)
                        .put("hint", "无障碍服务未连接，/ui 不可用；"
                                + "此时可用 `adb shell uiautomator dump` 兜底取控件树")
                        .put("fix", com.oreki.frameobserver.a11y.A11yBinder.fixCommands())
                        .end());
                return;
            }
            String tree = svc.dumpActiveTree(depth, max, flat);
            if (tree == null) {
                writeText(out, 503, "text/plain; charset=utf-8",
                        "拿不到当前窗口（屏幕已锁 / 无活动窗口）");
                return;
            }
            writeText(out, 200, "application/json; charset=utf-8", tree);
            return;
        }
        if ("GET".equals(method) && "/events".equals(path)) {
            int since = 0;
            for (String kv : query.split("&")) {
                if (kv.startsWith("since=")) {
                    try {
                        since = Integer.parseInt(kv.substring(6));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            writeText(out, 200, "application/x-ndjson; charset=utf-8", tailEvents(since));
            return;
        }
        if ("GET".equals(method) && path.startsWith("/frames/")) {
            String name = path.substring("/frames/".length());
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                writeText(out, 400, "text/plain", "bad name");
                return;
            }
            File dir = FrameBus.framesDir();
            File f = dir == null ? null : new File(dir, name);
            if (f == null || !f.isFile()) {
                writeText(out, 404, "text/plain; charset=utf-8", "没有这一帧");
                return;
            }
            byte[] data = readAll(f, 8 * 1024 * 1024);
            writeBytes(out, 200, "image/jpeg", data, "");
            return;
        }
        // 指定时间点回放：按 CLOCK_MONOTONIC 纳秒时刻找最接近的一帧。
        //   /frame_at?t=<ns>           → JSON（frame_id + 该帧的 URL 与误差）
        //   /frame_at?t=<ns>&raw=1     → 直接返回该帧 JPEG
        if ("GET".equals(method) && "/frame_at".equals(path)) {
            long t = -1;
            boolean raw = false;
            for (String kv : query.split("&")) {
                if (kv.startsWith("t=")) {
                    try {
                        t = Long.parseLong(kv.substring(2));
                    } catch (NumberFormatException ignored) {
                    }
                } else if (kv.equals("raw=1")) {
                    raw = true;
                }
            }
            if (t < 0) {
                writeText(out, 400, "application/json", "{\"error\":\"需要参数 t=<CLOCK_MONOTONIC 纳秒>\"}");
                return;
            }
            long[] hit = findFrameAt(t);
            if (hit == null) {
                writeText(out, 404, "application/json; charset=utf-8",
                        "{\"error\":\"没有可用的帧索引（可能未开始录制）\"}");
                return;
            }
            if (raw) {
                File dir = FrameBus.framesDir();
                File f = dir == null ? null
                        : new File(dir, String.format(Locale.US, "f_%06d.jpg", hit[0]));
                if (f == null || !f.isFile()) {
                    writeText(out, 404, "text/plain; charset=utf-8", "该帧未落盘（录制关闭过）");
                    return;
                }
                writeBytes(out, 200, "image/jpeg", readAll(f, 4 * 1024 * 1024), "");
                return;
            }
            writeText(out, 200, "application/json; charset=utf-8", Json.obj()
                    .put("requested_ns", t)
                    .put("frame_id", hit[0])
                    .put("capture_timestamp_ns", hit[1])
                    .put("delta_ms", (hit[1] - t) / 1_000_000d)
                    .put("url", "/frames/" + String.format(Locale.US, "f_%06d.jpg", hit[0]))
                    .end());
            return;
        }
        if ("POST".equals(method) && "/capture/stop".equals(path)) {
            boolean ok = false;
            if (appContext != null) {
                try {
                    Intent i = new Intent(appContext, CaptureService.class)
                            .setAction(CaptureService.ACTION_STOP);
                    appContext.startService(i);
                    ok = true;
                } catch (Exception e) {
                    Log.w(TAG, "remote stop failed: " + e);
                }
            }
            FrameBus.setState(ok ? "收到远程停止指令" : "远程停止失败（上下文未注入）");
            writeText(out, 200, "application/json",
                    Json.obj().put("ok", ok).put("capturing", FrameBus.capturing).end());
            return;
        }
        if ("POST".equals(method) && "/capture/record".equals(path)) {
            boolean on = !query.contains("on=0");
            if (FrameBus.config != null) {
                FrameBus.config.recordToDisk = on;
            }
            writeText(out, 200, "application/json", "{\"ok\":true,\"record\":" + on + "}");
            return;
        }

        writeText(out, 404, "text/plain; charset=utf-8", "未知端点：" + path);
    }

    // ------------------------------------------------------------------ WebSocket

    /**
     * 极简服务端推送。
     *
     * <p>协议只做三件事：握手、推帧（binary）、推事件（text）。不做分片、不做扩展协商、
     * 不做压缩 —— 客户端是 WorkBuddy 的本地脚本，能力可控。
     * 但**必须正确读客户端帧**：至少要把 close(0x8) 与 ping(0x9) 处理掉，否则客户端断开时
     * 我们会在 write 里一直收 EPIPE 而不知道对端已经走了。
     */
    private void serveWebSocket(Socket s, InputStream in, OutputStream out, String key) throws IOException {
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((key + WS_GUID).getBytes(UTF8));
            accept = Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception e) {
            writeText(out, 500, "text/plain", "sha1 unavailable");
            return;
        }
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(resp.getBytes(UTF8));
        out.flush();

        final Object writeLock = new Object();
        // 读线程：只处理控制帧与文本指令，读到 EOF 就结束本连接。
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    int b0 = in.read();
                    if (b0 < 0) {
                        break;
                    }
                    int b1 = in.read();
                    if (b1 < 0) {
                        break;
                    }
                    int opcode = b0 & 0x0F;
                    boolean masked = (b1 & 0x80) != 0;
                    long len = b1 & 0x7F;
                    if (len == 126) {
                        len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                    } else if (len == 127) {
                        len = 0;
                        for (int i = 0; i < 8; i++) {
                            len = (len << 8) | (in.read() & 0xFF);
                        }
                    }
                    byte[] mask = new byte[4];
                    if (masked) {
                        int r = in.read(mask, 0, 4);
                        if (r < 4) {
                            break;
                        }
                    }
                    byte[] payload = new byte[(int) Math.min(len, 1 << 20)];
                    int off = 0;
                    while (off < payload.length) {
                        int r = in.read(payload, off, payload.length - off);
                        if (r < 0) {
                            break;
                        }
                        off += r;
                    }
                    if (masked) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] ^= mask[i & 3];
                        }
                    }
                    if (opcode == 0x8) {
                        break;
                    }
                    if (opcode == 0x9) {
                        synchronized (writeLock) {
                            writeWsFrame(out, 0xA, payload);
                        }
                    }
                    // 文本指令目前只有一种：{"cmd":"snapshot"} → 立即补推一帧
                    if (opcode == 0x1) {
                        String cmd = new String(payload, UTF8);
                        if (cmd.contains("snapshot")) {
                            byte[] jpeg = FrameBus.latestJpeg;
                            if (jpeg != null) {
                                synchronized (writeLock) {
                                    writeWsFrame(out, 0x2, jpeg);
                                }
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "oreki-ws-reader");
        reader.setDaemon(true);
        reader.start();

        long lastSentFrame = -1;
        int lastEventIdx = -1;
        try {
            while (running && !s.isClosed()) {
                byte[] jpeg = FrameBus.latestJpeg;
                long id = FrameBus.latestFrameId;
                if (jpeg != null && id != lastSentFrame && FrameBus.previewFresh()) {
                    synchronized (writeLock) {
                        writeWsFrame(out, 0x2, jpeg);
                    }
                    lastSentFrame = id;
                }
                // 事件增量推送：有新事件才写文本帧。
                String tail = tailEvents(lastEventIdx + 1);
                if (!tail.isEmpty()) {
                    String[] lines = tail.split("\n");
                    lastEventIdx += lines.length;
                    synchronized (writeLock) {
                        writeWsFrame(out, 0x1, tail.getBytes(UTF8));
                    }
                }
                out.flush();
                Thread.sleep(80);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        } finally {
            reader.interrupt();
        }
    }

    private static void writeWsFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        out.write(0x80 | opcode);
        if (len < 126) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((((long) len) >>> (8 * i)) & 0xFF));
            }
        }
        out.write(payload);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 在 frames.jsonl 里找与给定时刻最接近的一帧。
     *
     * <p>为什么线性扫描而不做二分索引：帧记录本身按 capture_timestamp_ns 单调递增，
     * 但文件是**追加写**的，随时可能读到半行。线性扫描天然容忍尾部半行（解析失败就跳过），
     * 而二分需要先把整份索引进内存。单会话上限 20000 帧，扫描成本可忽略。
     *
     * @return {frameId, captureTimestampNs}；没有可用记录时返回 null
     */
    private long[] findFrameAt(long t) {
        File dir = SessionLog.currentDir();
        if (dir == null) {
            return null;
        }
        File f = new File(dir, "frames.jsonl");
        if (!f.isFile()) {
            return null;
        }
        long bestId = -1;
        long bestTs = 0;
        long bestDiff = Long.MAX_VALUE;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), UTF8))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 只抽取我们要的两个字段，避免为每行做完整 JSON 解析。
                long id = extractLong(line, "\"frame_id\":", -1);
                long ts = extractLong(line, "\"capture_timestamp_ns\":", -1);
                if (id < 0 || ts < 0) {
                    continue;
                }
                long diff = Math.abs(ts - t);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestId = id;
                    bestTs = ts;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return bestId < 0 ? null : new long[]{bestId, bestTs};
    }

    private static long extractLong(String line, String key, long def) {
        int i = line.indexOf(key);
        if (i < 0) {
            return def;
        }
        int p = i + key.length();
        int end = p;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (c == '-' || (c >= '0' && c <= '9')) {
                end++;
            } else {
                break;
            }
        }
        if (end == p) {
            return def;
        }
        try {
            return Long.parseLong(line.substring(p, end));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String tailEvents(int since) {
        File dir = SessionLog.currentDir();
        if (dir == null) {
            return "";
        }
        File f = new File(dir, "events.jsonl");
        if (!f.isFile()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8))) {
            String l;
            int idx = 0;
            while ((l = br.readLine()) != null) {
                if (idx >= since) {
                    lines.add(l);
                    if (lines.size() >= 500) {
                        break;
                    }
                }
                idx++;
            }
        } catch (IOException ignored) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private static byte[] readAll(File f, int cap) throws IOException {
        long len = Math.min(f.length(), cap);
        byte[] buf = new byte[(int) len];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int r = fis.read(buf, off, buf.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192) {
                break;
            }
        }
        return any ? sb.toString() : null;
    }

    private static void writeText(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(UTF8);
        writeBytes(out, code, type, b, "");
    }

    private static void writeBytes(OutputStream out, int code, String type, byte[] body, String extraHeaders)
            throws IOException {
        StringBuilder h = new StringBuilder(160);
        h.append("HTTP/1.1 ").append(code).append(' ').append(code == 200 ? "OK" : code == 404 ? "Not Found" : "Status")
                .append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Connection: close\r\n");
        if (extraHeaders != null) {
            h.append(extraHeaders);
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(UTF8));
        out.write(body);
        out.flush();
    }

    private static String quote(String s) {
        return com.oreki.frameobserver.util.Json.obj().put("v", s).end();
    }

    private String indexHtml() {
        return "<!doctype html><meta charset=utf-8><title>Oreki Frame Observer</title>"
                + "<style>body{font-family:system-ui;margin:24px;line-height:1.6}"
                + "code{background:#eee;padding:2px 5px;border-radius:3px}"
                + "img{max-width:360px;border:1px solid #ccc;border-radius:6px}</style>"
                + "<h1>Oreki Frame Observer</h1>"
                + "<p>状态：<b>" + FrameBus.state + "</b></p>"
                + "<p>预览（每次刷新取最新帧）：</p>"
                + "<p><img src=\"/snapshot.jpg\" alt=\"当前帧\"></p>"
                + "<h3>端点</h3><ul>"
                + "<li><code>/snapshot.jpg</code> 最新一帧</li>"
                + "<li><code>/stats</code> 统计 JSON</li>"
                + "<li><code>/events?since=0</code> 无障碍事件</li>"
                + "<li><code>/ui</code> 即时控件树（动画中也能读，替代 uiautomator dump）</li>"
                + "<li><code>/frames/f_000001.jpg</code> 历史帧</li>"
                + "<li><code>/ws</code> WebSocket 实时流</li>"
                + "</ul>";
    }
}
