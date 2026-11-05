package com.oreki.frameobserver.net

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.oreki.frameobserver.CaptureService
import com.oreki.frameobserver.FrameBus
import com.oreki.frameobserver.ProcStats
import com.oreki.frameobserver.SessionLog
import com.oreki.frameobserver.a11y.A11yBinder
import com.oreki.frameobserver.a11y.ObserverA11yService
import com.oreki.frameobserver.diag.LogcatRecorder
import com.oreki.frameobserver.util.Json
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * 本机 HTTP + WebSocket 服务（Frame Bridge 的网络侧）。
 *
 * **为什么不用 NanoHTTPD / OkHttp**：本工程刻意零依赖。这里只实现 WorkBuddy 需要的
 * 端点，代码量可控且没有版本漂移风险。
 *
 * **绑定地址**：只绑 `127.0.0.1`。画面属于被测应用的隐私内容，不能对对端网络可见。
 * WorkBuddy 侧通过 `adb forward tcp:8731 tcp:8731` 走 USB / 本地回环拿数据，
 * 链路不经过任何网络。
 *
 * 端点：
 * ```
 * GET  /                  状态页（HTML，中文）
 * GET  /snapshot.jpg      最新一帧 JPEG（no-store）
 * GET  /stats             统计 JSON
 * GET  /events?since=N    无障碍事件，返回第 N 条之后的部分（NDJSON）
 * GET  /ui?depth=48&max=3000&flat=1  即时控件树（不需要 idle，动画中也能读）
 * GET  /frames/<id>.jpg   历史帧
 * GET  /frame_at?t=<ns>   按 CLOCK_MONOTONIC 纳秒找最接近的一帧
 * GET  /timeline          事件与日志对齐的时间线
 * /logcat*                Logcat 诊断系列
 * GET  /ws                WebSocket：服务端推二进制 JPEG 帧 + 文本事件
 * POST /capture/stop      停止采集
 * POST /capture/record?on=0|1  切换录像
 * ```
 *
 * `/capture/start` 有意不提供：MediaProjection 授权必须由用户在设备上亲手确认，
 * 任何「远程点一下就开始录屏」的接口都等于绕过系统授权流程，明确不做。
 */
class FrameHttpServer private constructor() {

    @Volatile
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false
    private val conns = AtomicInteger()
    private var appContext: Context? = null

    /**
     * 注入应用上下文。
     *
     * 为什么需要它：`POST /capture/stop` 第一版只改状态字符串、并没有真的停服务，
     * 等于接口在说谎。要真停就必须能发 Intent 给 [CaptureService]，而 Intent 需要 Context。
     * 这里只持有 applicationContext（不持有 Activity，不会泄漏）。
     */
    fun init(ctx: Context?) {
        if (appContext == null && ctx != null) appContext = ctx.applicationContext
    }

    @Synchronized
    fun start(port: Int) {
        if (running) return
        try {
            server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).apply {
                reuseAddress = true
            }
        } catch (e: IOException) {
            Log.w(TAG, "bind failed on $port: $e")
            return
        }
        running = true
        acceptThread = Thread({ acceptLoop() }, "oreki-http").apply { start() }
        Log.i(TAG, "listening on 127.0.0.1:$port")
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        acceptThread = null
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val s = server?.accept() ?: continue
                if (conns.incrementAndGet() > MAX_CONNECTIONS) {
                    // 连接数上限：防止误连一堆客户端把本进程拖死。
                    conns.decrementAndGet()
                    s.close()
                    continue
                }
                Thread({
                    try {
                        handle(s)
                    } catch (e: Throwable) {
                        Log.w(TAG, "conn error: $e")
                    } finally {
                        conns.decrementAndGet()
                        try {
                            s.close()
                        } catch (_: IOException) {
                        }
                    }
                }, "oreki-http-conn").apply { isDaemon = true }.start()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed: $e")
            }
        }
    }

    // ------------------------------------------------------------------ 分发

    @Throws(IOException::class)
    private fun handle(s: Socket) {
        s.soTimeout = 10_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()

        val requestLine = readLine(input) ?: return
        // 读完请求头（含 Host / Upgrade / WebSocket-Key 等），丢弃 body（本服务不接受 body）。
        var wsKey: String? = null
        var upgrade: String? = null
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c <= 0) continue
            val k = line.substring(0, c).trim().lowercase(Locale.US)
            val v = line.substring(c + 1).trim()
            when (k) {
                "sec-websocket-key" -> wsKey = v
                "upgrade" -> upgrade = v
            }
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        var path = parts[1]
        var query = ""
        val q = path.indexOf('?')
        if (q >= 0) {
            query = path.substring(q + 1)
            path = path.substring(0, q)
        }

        if ("/ws" == path && wsKey != null && upgrade != null && "websocket".equals(upgrade, true)) {
            s.soTimeout = 0
            serveWebSocket(s, input, out, wsKey)
            return
        }

        if ("GET" == method && "/" == path) {
            writeText(out, 200, "text/html; charset=utf-8", indexHtml())
            return
        }
        if ("GET" == method && "/snapshot.jpg" == path) {
            val jpeg = FrameBus.latestJpeg
            if (jpeg == null) {
                writeText(out, 503, "text/plain; charset=utf-8", "还没有可用画面（未开始采集）")
                return
            }
            writeBytes(
                out, 200, "image/jpeg", jpeg,
                "X-Frame-Id: " + FrameBus.latestFrameId + "\r\n" +
                    "X-Frame-Mono-Ns: " + FrameBus.latestFrameMonoNs + "\r\n" +
                    "X-Frame-Source-Ns: " + FrameBus.latestFrameSourceNs + "\r\n",
            )
            return
        }
        if ("GET" == method && "/stats" == path) {
            val body = FrameBus.stats.toJson().replace(Regex("\\}$"), "") +
                ",\"proc\":" + Json.obj()
                    .put("java_heap_mb", ProcStats.javaHeapBytes() / 1048576.0)
                    .put("pss_mb", ProcStats.nativeHeapBytes() / 1048576.0)
                    .put("threads", Thread.activeCount())
                    .end() +
                ",\"state\":" + Json.quoteRaw(FrameBus.state) +
                ",\"capturing\":" + FrameBus.capturing +
                // 无障碍绑定状态：让调用方一眼看出 /ui 会不会失败，
                // 不用等请求 /ui 拿到 503 才回头排查。
                ",\"a11y\":" + Json.quoteRaw(A11yBinder.state(appContext)) +
                ",\"session\":" + Json.quoteRaw(FrameBus.sessionDir?.absolutePath ?: "") +
                "}"
            writeText(out, 200, "application/json; charset=utf-8", body)
            return
        }
        if ("GET" == method && "/ui" == path) {
            // 需求驱动的即时控件树。存在的理由：`uiautomator dump` 在持续动画的画面上
            // 会 "ERROR: could not get idle state" 且不写文件，整条 UI 验证链会断。
            // 无障碍服务不需要等 idle。
            var depth = 48
            var max = 3000
            var flat = true
            for (kv in query.split("&")) {
                try {
                    when {
                        kv.startsWith("depth=") ->
                            depth = Math.max(1, Math.min(64, kv.substring(6).toInt()))
                        kv.startsWith("max=") ->
                            max = Math.max(1, Math.min(5000, kv.substring(4).toInt()))
                        kv == "flat=0" -> flat = false
                        kv == "flat=1" -> flat = true
                    }
                } catch (_: NumberFormatException) {
                }
            }
            var svc = ObserverA11yService.get()
            if (svc == null) {
                // 自愈一次：`am force-stop` 会把 Settings 里的无障碍绑定清空，
                // 这时干等不会恢复，先试着自己写回去（缺权限会在内部被吞掉并记日志）。
                A11yBinder.ensureBound(appContext)
                svc = ObserverA11yService.get()
            }
            if (svc == null) {
                // 仍然连不上：返回结构化错误体，把「现在什么状态、下一步跑什么命令」
                // 直接带出去，避免调用方只看到一句无法行动的 503。
                val st = A11yBinder.state(appContext)
                writeText(
                    out, 503, "application/json; charset=utf-8",
                    Json.obj()
                        .put("error", "a11y_not_connected")
                        .put("state", st)
                        .put(
                            "hint",
                            "无障碍服务未连接，/ui 不可用；此时可用 `adb shell uiautomator dump` 兜底取控件树",
                        )
                        .put("fix", A11yBinder.fixCommands())
                        .end(),
                )
                return
            }
            val tree = svc.dumpActiveTree(depth, max, flat)
            if (tree == null) {
                writeText(out, 503, "text/plain; charset=utf-8", "拿不到当前窗口（屏幕已锁 / 无活动窗口）")
                return
            }
            writeText(out, 200, "application/json; charset=utf-8", tree)
            return
        }
        // ---------------------------------------------------------------- logcat 诊断
        // 这一组端点存在的理由：本工具要能回答「这条日志是谁打出来的 / 这次点击触发了什么」。
        // 全部为只读或显式控制，不影响截图与点击路径。
        if (path.startsWith("/logcat")) {
            if (handleLogcat(method, path, query, out)) return
        }
        if ("GET" == method && "/timeline" == path) {
            writeText(out, 200, "application/json; charset=utf-8", buildTimeline(query))
            return
        }

        if ("GET" == method && "/events" == path) {
            var since = 0
            for (kv in query.split("&")) {
                if (kv.startsWith("since=")) {
                    try {
                        since = kv.substring(6).toInt()
                    } catch (_: NumberFormatException) {
                    }
                }
            }
            writeText(out, 200, "application/x-ndjson; charset=utf-8", tailEvents(since))
            return
        }
        if ("GET" == method && path.startsWith("/frames/")) {
            val name = path.substring("/frames/".length)
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                writeText(out, 400, "text/plain", "bad name")
                return
            }
            val dir = FrameBus.framesDir()
            val f = dir?.let { File(it, name) }
            if (f == null || !f.isFile) {
                writeText(out, 404, "text/plain; charset=utf-8", "没有这一帧")
                return
            }
            writeBytes(out, 200, "image/jpeg", readAll(f, 8 * 1024 * 1024), "")
            return
        }
        // 指定时间点回放：按 CLOCK_MONOTONIC 纳秒时刻找最接近的一帧。
        //   /frame_at?t=<ns>           → JSON（frame_id + 该帧的 URL 与误差）
        //   /frame_at?t=<ns>&raw=1     → 直接返回该帧 JPEG
        if ("GET" == method && "/frame_at" == path) {
            var t = -1L
            var raw = false
            for (kv in query.split("&")) {
                if (kv.startsWith("t=")) {
                    try {
                        t = kv.substring(2).toLong()
                    } catch (_: NumberFormatException) {
                    }
                } else if (kv == "raw=1") {
                    raw = true
                }
            }
            if (t < 0) {
                writeText(out, 400, "application/json", "{\"error\":\"需要参数 t=<CLOCK_MONOTONIC 纳秒>\"}")
                return
            }
            val hit = findFrameAt(t)
            if (hit == null) {
                writeText(out, 404, "application/json; charset=utf-8", "{\"error\":\"没有可用的帧索引（可能未开始录制）\"}")
                return
            }
            if (raw) {
                val dir = FrameBus.framesDir()
                val f = dir?.let { File(it, String.format(Locale.US, "f_%06d.jpg", hit[0])) }
                if (f == null || !f.isFile) {
                    writeText(out, 404, "text/plain; charset=utf-8", "该帧未落盘（录制关闭过）")
                    return
                }
                writeBytes(out, 200, "image/jpeg", readAll(f, 4 * 1024 * 1024), "")
                return
            }
            writeText(
                out, 200, "application/json; charset=utf-8",
                Json.obj()
                    .put("requested_ns", t)
                    .put("frame_id", hit[0])
                    .put("capture_timestamp_ns", hit[1])
                    .put("delta_ms", (hit[1] - t) / 1_000_000.0)
                    .put("url", "/frames/" + String.format(Locale.US, "f_%06d.jpg", hit[0]))
                    .end(),
            )
            return
        }
        if ("POST" == method && "/capture/stop" == path) {
            var ok = false
            appContext?.let { ctx ->
                try {
                    ctx.startService(
                        Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_STOP),
                    )
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "remote stop failed: $e")
                }
            }
            FrameBus.setState(if (ok) "收到远程停止指令" else "远程停止失败（上下文未注入）")
            writeText(
                out, 200, "application/json",
                Json.obj().put("ok", ok).put("capturing", FrameBus.capturing).end(),
            )
            return
        }
        if ("POST" == method && "/capture/record" == path) {
            val on = !query.contains("on=0")
            FrameBus.config?.recordToDisk = on
            writeText(out, 200, "application/json", "{\"ok\":true,\"record\":$on}")
            return
        }

        writeText(out, 404, "text/plain; charset=utf-8", "未知端点：$path")
    }

    // ------------------------------------------------------------------ WebSocket

    /**
     * 极简服务端推送。
     *
     * 协议只做三件事：握手、推帧（binary）、推事件（text）。不做分片、不做扩展协商、
     * 不做压缩 —— 客户端是本地脚本，能力可控。但**必须正确读客户端帧**：
     * 至少要把 close(0x8) 与 ping(0x9) 处理掉，否则客户端断开时我们会在 write 里
     * 一直收 EPIPE 而不知道对端已经走了。
     */
    @Throws(IOException::class)
    private fun serveWebSocket(s: Socket, input: InputStream, out: OutputStream, key: String) {
        val accept: String = try {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val d = sha1.digest((key + WS_GUID).toByteArray(UTF8))
            Base64.encodeToString(d, Base64.NO_WRAP)
        } catch (_: Exception) {
            writeText(out, 500, "text/plain", "sha1 unavailable")
            return
        }
        val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(resp.toByteArray(UTF8))
        out.flush()

        val writeLock = Any()
        // 读线程：只处理控制帧与文本指令，读到 EOF 就结束本连接。
        val reader = Thread({
            try {
                while (true) {
                    val b0 = input.read()
                    if (b0 < 0) break
                    val b1 = input.read()
                    if (b1 < 0) break
                    val opcode = b0 and 0x0F
                    val masked = (b1 and 0x80) != 0
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) {
                        len = (((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)).toLong()
                    } else if (len == 127L) {
                        len = 0
                        for (i in 0 until 8) len = (len shl 8) or (input.read() and 0xFF).toLong()
                    }
                    val mask = ByteArray(4)
                    if (masked) {
                        val r = input.read(mask, 0, 4)
                        if (r < 4) break
                    }
                    val payload = ByteArray(Math.min(len, (1 shl 20).toLong()).toInt())
                    var off = 0
                    while (off < payload.size) {
                        val r = input.read(payload, off, payload.size - off)
                        if (r < 0) break
                        off += r
                    }
                    if (masked) {
                        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                    }
                    if (opcode == 0x8) break
                    if (opcode == 0x9) {
                        synchronized(writeLock) { writeWsFrame(out, 0xA, payload) }
                    }
                    // 文本指令目前只有一种：{"cmd":"snapshot"} → 立即补推一帧
                    if (opcode == 0x1) {
                        val cmd = String(payload, UTF8)
                        if (cmd.contains("snapshot")) {
                            val jpeg = FrameBus.latestJpeg
                            if (jpeg != null) {
                                synchronized(writeLock) { writeWsFrame(out, 0x2, jpeg) }
                            }
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }, "oreki-ws-reader").apply { isDaemon = true }
        reader.start()

        var lastSentFrame = -1L
        var lastEventIdx = -1
        try {
            while (running && !s.isClosed) {
                val jpeg = FrameBus.latestJpeg
                val id = FrameBus.latestFrameId
                if (jpeg != null && id != lastSentFrame && FrameBus.previewFresh()) {
                    synchronized(writeLock) { writeWsFrame(out, 0x2, jpeg) }
                    lastSentFrame = id
                }
                // 事件增量推送：有新事件才写文本帧。
                val tail = tailEvents(lastEventIdx + 1)
                if (tail.isNotEmpty()) {
                    val lines = tail.split("\n")
                    lastEventIdx += lines.size
                    synchronized(writeLock) { writeWsFrame(out, 0x1, tail.toByteArray(UTF8)) }
                }
                out.flush()
                Thread.sleep(80)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IOException) {
        } finally {
            reader.interrupt()
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 在 frames.jsonl 里找与给定时刻最接近的一帧。
     *
     * 为什么线性扫描而不做二分索引：帧记录本身按 capture_timestamp_ns 单调递增，
     * 但文件是追加写的，随时可能读到半行。线性扫描天然容忍尾部半行（解析失败就跳过），
     * 而二分需要先把整份索引进内存。单会话上限 20000 帧，扫描成本可忽略。
     *
     * @return `[frameId, captureTimestampNs]`；没有可用记录时返回 null
     */
    private fun findFrameAt(t: Long): LongArray? {
        val dir = SessionLog.currentDir() ?: return null
        val f = File(dir, "frames.jsonl")
        if (!f.isFile) return null
        var bestId = -1L
        var bestTs = 0L
        var bestDiff = Long.MAX_VALUE
        try {
            BufferedReader(InputStreamReader(FileInputStream(f), UTF8)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    // 只抽取我们要的两个字段，避免为每行做完整 JSON 解析。
                    val id = extractLong(line, "\"frame_id\":", -1)
                    val ts = extractLong(line, "\"capture_timestamp_ns\":", -1)
                    if (id < 0 || ts < 0) continue
                    val diff = Math.abs(ts - t)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestId = id
                        bestTs = ts
                    }
                }
            }
        } catch (_: IOException) {
            return null
        }
        return if (bestId < 0) null else longArrayOf(bestId, bestTs)
    }

    private fun tailEvents(since: Int): String {
        val dir = SessionLog.currentDir() ?: return ""
        val f = File(dir, "events.jsonl")
        if (!f.isFile) return ""
        val lines = ArrayList<String>()
        try {
            BufferedReader(InputStreamReader(FileInputStream(f), UTF8)).use { br ->
                var idx = 0
                while (true) {
                    val l = br.readLine() ?: break
                    if (idx >= since) {
                        lines.add(l)
                        if (lines.size >= 500) break
                    }
                    idx++
                }
            }
        } catch (_: IOException) {
            return ""
        }
        val sb = StringBuilder()
        for (l in lines) sb.append(l).append('\n')
        return sb.toString()
    }

    // ------------------------------------------------------------------ logcat 诊断

    /**
     * 处理 `/logcat` 系列端点。
     *
     * ```
     * POST /logcat/start?pkg=...       启动捕获（可带包名过滤）
     * POST /logcat/stop                停止捕获
     * POST /logcat/pause?on=1|0        暂停 / 继续
     * POST /logcat/clear               清空缓冲
     * POST /logcat/level?min=0..4      等级过滤（0=V..4=E）
     * POST /logcat/filter?tag=&kw=&pid=&pkg=
     * GET  /logcat?limit=200&kw=       取最近 N 条（NDJSON）
     * GET  /logcat/since?seq=N&kw=     增量拉取
     * GET  /logcat/export?jsonl=1      导出
     * GET  /logcat/stats               状态
     * POST /logcat/rule?cls=&method=&session=
     * POST /logcat/rule/remove?session=
     * GET  /logcat/rule                列出规则
     * ```
     *
     * @return true 表示已处理并写好响应
     */
    @Throws(IOException::class)
    private fun handleLogcat(method: String, path: String, query: String, out: OutputStream): Boolean {
        val rec = LogcatRecorder.get()
        val q = parseQueryMap(query)

        if ("POST" == method && "/logcat/start" == path) {
            val ok = rec.start(q["pkg"])
            writeText(out, 200, "application/json; charset=utf-8", "{\"ok\":$ok,\"running\":${rec.isRunning()}}")
            return true
        }
        if ("POST" == method && "/logcat/stop" == path) {
            rec.stop()
            writeText(out, 200, "application/json; charset=utf-8", "{\"ok\":true}")
            return true
        }
        if ("POST" == method && "/logcat/pause" == path) {
            val on = "0" != q["on"]
            rec.setPaused(on)
            writeText(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"paused\":${rec.isPaused()}}")
            return true
        }
        if ("POST" == method && "/logcat/clear" == path) {
            rec.clear()
            writeText(out, 200, "application/json; charset=utf-8", "{\"ok\":true}")
            return true
        }
        if ("POST" == method && "/logcat/level" == path) {
            parseIntOrNull(q["min"])?.let { rec.setMinLevel(it) }
            writeText(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"min\":${rec.getMinLevel()}}")
            return true
        }
        if ("POST" == method && "/logcat/filter" == path) {
            // 三个过滤位互相独立：传空串 = 清掉这一位，不传 = 保持原样。
            // 早期写法不管有没有 key 都 set 一次，导致「只想改 keyword」会把 tag 清掉。
            if (q.containsKey("tag")) rec.setTagFilter(q["tag"])
            if (q.containsKey("kw")) rec.setKeywordFilter(q["kw"])
            val pid = parseIntOrNull(q["pid"])
            if (pid != null && pid > 0) {
                rec.setPidFilter(pid)
            } else if ("0" == q["pid"]) {
                rec.setPidFilter(0)
            }
            if (q.containsKey("pkg")) rec.setPkgFilter(q["pkg"])
            writeText(out, 200, "application/json; charset=utf-8", logcatFilterJson(rec))
            return true
        }
        if ("GET" == method && "/logcat" == path) {
            val limit = Math.min(2000, Math.max(1, parseInt(q["limit"], 200)))
            val kw = patternOrNull(q["kw"])
            val lines = rec.tail(limit, kw)
            writeText(out, 200, "application/x-ndjson; charset=utf-8", rec.exportJsonl(lines))
            return true
        }
        if ("GET" == method && "/logcat/since" == path) {
            val seq0 = parseLongVal(q["seq"], 0L)
            val limit = Math.min(2000, Math.max(1, parseInt(q["limit"], 500)))
            val kw = patternOrNull(q["kw"])
            var lines = rec.since(seq0, kw)
            if (lines.size > limit) lines = lines.subList(0, limit)
            writeText(out, 200, "application/x-ndjson; charset=utf-8", rec.exportJsonl(lines))
            return true
        }
        if ("GET" == method && "/logcat/export" == path) {
            val limit = Math.min(4000, Math.max(1, parseInt(q["limit"], 4000)))
            val jsonl = "0" != q["jsonl"]
            val kw = patternOrNull(q["kw"])
            val lines = rec.tail(limit, kw)
            val body = if (jsonl) rec.exportJsonl(lines) else rec.exportText(lines)
            val type = if (jsonl) "application/x-ndjson; charset=utf-8" else "text/plain; charset=utf-8"
            writeText(out, 200, type, body)
            return true
        }
        if ("GET" == method && "/logcat/stats" == path) {
            val b = StringBuilder(256)
            b.append('{')
                .append("\"running\":").append(rec.isRunning()).append(',')
                .append("\"alive\":").append(rec.isAlive()).append(',')
                .append("\"paused\":").append(rec.isPaused()).append(',')
                .append("\"buffered\":").append(rec.size()).append(',')
                .append("\"last_seq\":").append(rec.lastSeq()).append(',')
                .append(logcatFilterJsonBody(rec))
                .append(",\"device\":").append(Json.quoteRaw(LogcatRecorder.deviceInfo()))
                .append('}')
            writeText(out, 200, "application/json; charset=utf-8", b.toString())
            return true
        }
        if ("POST" == method && "/logcat/rule" == path) {
            rec.addRule(q["cls"], q["method"], q["session"])
            writeText(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"rules\":${rec.rules().size}}")
            return true
        }
        if ("POST" == method && "/logcat/rule/remove" == path) {
            val n = rec.removeSessionRules(q["session"])
            writeText(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"removed\":$n}")
            return true
        }
        if ("GET" == method && "/logcat/rule" == path) {
            val b = StringBuilder(128)
            b.append("{\"rules\":[")
            var first = true
            for (r in rec.rules()) {
                if (!first) b.append(',')
                first = false
                b.append("{\"cls\":").append(Json.quoteRaw(r.cls))
                    .append(",\"method\":").append(Json.quoteRaw(r.method))
                    .append(",\"session\":").append(Json.quoteRaw(r.session)).append('}')
            }
            b.append("]}")
            writeText(out, 200, "application/json; charset=utf-8", b.toString())
            return true
        }
        return false
    }

    /**
     * `/timeline`：把「用户交互事件」与「窗口内日志」拼成一条时间线。
     *
     * 为什么要它：排查「点了没反应」这类问题时，单看日志或单看截图都说不清；
     * 必须能对齐「这一秒点了哪里」和「这一秒打了什么日志」。
     */
    private fun buildTimeline(query: String): String {
        val q = parseQueryMap(query)
        val center = parseLongVal(q["center"], System.currentTimeMillis())
        val before = parseLongVal(q["before"], 2000L)
        val after = parseLongVal(q["after"], 2000L)
        val from = center - before
        val to = center + after

        val rec = LogcatRecorder.get()
        val all = rec.tail(4000, null)

        val b = StringBuilder(4096)
        b.append("{\"from\":").append(from)
            .append(",\"to\":").append(to)
            .append(",\"center\":").append(center)
            .append(",\"logs\":[")
        var first = true
        for (l in all) {
            if (l.wallMs < from || l.wallMs > to) continue
            if (!first) b.append(',')
            first = false
            b.append("{\"seq\":").append(l.seq)
                .append(",\"wall_ms\":").append(l.wallMs)
                .append(",\"level\":").append(Json.quoteRaw(l.level))
                .append(",\"tag\":").append(Json.quoteRaw(l.tag))
                .append(",\"pid\":").append(l.pid)
                .append(",\"text\":").append(Json.quoteRaw(l.text)).append('}')
        }
        b.append("]}")
        return b.toString()
    }

    private fun indexHtml(): String =
        "<!doctype html><meta charset=utf-8><title>Oreki Frame Observer</title>" +
            "<style>body{font-family:system-ui;margin:24px;line-height:1.6}" +
            "code{background:#eee;padding:2px 5px;border-radius:3px}" +
            "img{max-width:360px;border:1px solid #ccc;border-radius:6px}</style>" +
            "<h1>Oreki Frame Observer</h1>" +
            "<p>状态：<b>" + FrameBus.state + "</b></p>" +
            "<p>预览（每次刷新取最新帧）：</p>" +
            "<p><img src=\"/snapshot.jpg\" alt=\"当前帧\"></p>" +
            "<h3>端点</h3><ul>" +
            "<li><code>/snapshot.jpg</code> 最新一帧</li>" +
            "<li><code>/stats</code> 统计 JSON</li>" +
            "<li><code>/events?since=0</code> 无障碍事件</li>" +
            "<li><code>/ui</code> 即时控件树（动画中也能读，替代 uiautomator dump）</li>" +
            "<li><code>/frames/f_000001.jpg</code> 历史帧</li>" +
            "<li><code>/ws</code> WebSocket 实时流</li>" +
            "</ul>"

    companion object {
        private const val TAG = "OrekiHttp"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val UTF8: Charset = Charsets.UTF_8
        private const val MAX_CONNECTIONS = 8

        private val INSTANCE = FrameHttpServer()

        @JvmStatic
        fun get(): FrameHttpServer = INSTANCE

        private fun writeWsFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
            val len = payload.size
            out.write(0x80 or opcode)
            when {
                len < 126 -> out.write(len)
                len <= 0xFFFF -> {
                    out.write(126)
                    out.write((len ushr 8) and 0xFF)
                    out.write(len and 0xFF)
                }
                else -> {
                    out.write(127)
                    for (i in 7 downTo 0) out.write(((len.toLong() ushr (8 * i)) and 0xFF).toInt())
                }
            }
            out.write(payload)
        }

        private fun extractLong(line: String, key: String, def: Long): Long {
            val i = line.indexOf(key)
            if (i < 0) return def
            var p = i + key.length
            var end = p
            while (end < line.length) {
                val c = line[end]
                if (c == '-' || c in '0'..'9') end++ else break
            }
            if (end == p) return def
            return try {
                line.substring(p, end).toLong()
            } catch (_: NumberFormatException) {
                def
            }
        }

        private fun readAll(f: File, cap: Int): ByteArray {
            val len = Math.min(f.length(), cap.toLong())
            val buf = ByteArray(len.toInt())
            FileInputStream(f).use { fis ->
                var off = 0
                while (off < buf.size) {
                    val r = fis.read(buf, off, buf.size - off)
                    if (r < 0) break
                    off += r
                }
            }
            return buf
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder(64)
            var any = false
            while (true) {
                val c = input.read()
                if (c < 0) break
                any = true
                if (c == '\n'.code) {
                    val n = sb.length
                    if (n > 0 && sb[n - 1] == '\r') sb.setLength(n - 1)
                    return sb.toString()
                }
                sb.append(c.toChar())
                if (sb.length > 8192) break
            }
            return if (any) sb.toString() else null
        }

        private fun logcatFilterJson(rec: LogcatRecorder): String = "{" + logcatFilterJsonBody(rec) + "}"

        private fun logcatFilterJsonBody(rec: LogcatRecorder): String =
            "\"min_level\":" + rec.getMinLevel() +
                ",\"pid\":" + rec.getPidFilter() +
                ",\"tag\":" + Json.quoteRaw(rec.getTagFilter()) +
                ",\"keyword\":" + Json.quoteRaw(rec.getKeywordFilter()) +
                ",\"pkg\":" + Json.quoteRaw(rec.getPkgFilter())

        /** 解析 `a=1&b=2` 形式的查询串（值做 URL 解码）。 */
        private fun parseQueryMap(query: String?): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            if (query.isNullOrEmpty()) return m
            for (kv in query.split("&")) {
                val eq = kv.indexOf('=')
                if (eq <= 0) {
                    if (kv.isNotEmpty()) m[kv] = ""
                    continue
                }
                val k = kv.substring(0, eq)
                var v = kv.substring(eq + 1)
                try {
                    v = URLDecoder.decode(v, "UTF-8")
                } catch (_: Exception) {
                }
                m[k] = v
            }
            return m
        }

        private fun parseIntOrNull(s: String?): Int? {
            if (s.isNullOrEmpty()) return null
            return try {
                s.toInt()
            } catch (_: NumberFormatException) {
                null
            }
        }

        private fun parseInt(s: String?, def: Int): Int = parseIntOrNull(s) ?: def

        private fun parseLongVal(s: String?, def: Long): Long {
            if (s.isNullOrEmpty()) return def
            return try {
                s.toLong()
            } catch (_: NumberFormatException) {
                def
            }
        }

        private fun patternOrNull(s: String?): Pattern? {
            if (s.isNullOrEmpty()) return null
            return try {
                Pattern.compile(s)
            } catch (_: Exception) {
                Pattern.compile(Pattern.quote(s))
            }
        }

        private fun writeText(out: OutputStream, code: Int, type: String, body: String) {
            writeBytes(out, code, type, body.toByteArray(UTF8), "")
        }

        private fun writeBytes(
            out: OutputStream,
            code: Int,
            type: String,
            body: ByteArray,
            extraHeaders: String,
        ) {
            val status = if (code == 200) "OK" else if (code == 404) "Not Found" else "Status"
            val h = StringBuilder(160)
            h.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
            h.append("Content-Type: ").append(type).append("\r\n")
            h.append("Content-Length: ").append(body.size).append("\r\n")
            h.append("Cache-Control: no-store\r\n")
            h.append("Access-Control-Allow-Origin: *\r\n")
            h.append("Connection: close\r\n")
            h.append(extraHeaders)
            h.append("\r\n")
            out.write(h.toString().toByteArray(UTF8))
            out.write(body)
            out.flush()
        }
    }
}
