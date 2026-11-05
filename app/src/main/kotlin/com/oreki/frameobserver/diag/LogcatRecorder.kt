package com.oreki.frameobserver.diag

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * Logcat 实时捕获（诊断用）。
 *
 * **为什么在 app 内启动 `logcat -v threadtime` 子进程而不是走 ADB**：
 * 使用场景常常是「AVD 就绪、只想看日志」，此时没有 USB/网络 ADB 可用；
 * 而 app 自己有 `READ_LOGS` 权限（本工具是 debug 签名的测试基础设施），
 * 直接 runtime exec 就能拿到与 `adb logcat` 同源的数据，且不受连接影响。
 *
 * **不阻塞截图**：采集在独立线程，环形缓冲 + 有上限的落盘写入；
 * HTTP 接口只读缓冲，不做任何磁盘同步工作。
 *
 * **环形缓冲**：只保留最近 [CAPACITY] 条，内存占用固定（约几百 KB），
 * 长时间挂着也不会把设备拖垮。
 */
class LogcatRecorder private constructor() {

    /** 一条日志。 */
    data class Line(
        val seq: Long,
        val wallMs: Long,
        val level: String,
        val tag: String,
        val pid: Int,
        val tid: String,
        val text: String,
        /** 若本条属于某个诊断会话，记录会话名；否则 null。 */
        val session: String?,
    )

    /** 一条临时诊断规则：把某类/某方法的日志归到某次诊断会话。 */
    data class DiagRule(val cls: String?, val method: String?, val session: String?)

    private val ring = ArrayDeque<Line>(CAPACITY)
    private val lock = Any()

    @Volatile
    private var proc: Process? = null
    private var reader: Thread? = null

    @Volatile
    private var running = false

    private var seq = 0L

    /** 过滤条件（都可为空 = 不过滤）。 */
    @Volatile
    private var minLevel = 0 // 0=V 1=D 2=I 3=W 4=E

    @Volatile
    private var pidFilter = 0 // 0 = 不限

    @Volatile
    private var tagFilter: String? = null

    @Volatile
    private var keywordFilter: String? = null

    @Volatile
    private var pkgFilter: String? = null // 按包名反查 PID 后过滤

    @Volatile
    private var paused = false

    /** 诊断会话：方法名 → 规则。用于把插桩日志归到某次诊断下。 */
    private val rules = ArrayList<DiagRule>()

    /**
     * 开始捕获。重复调用是幂等的（已在跑就直接返回 true）。
     *
     * @param pkg 需要按包名过滤时的包名；null 表示不过滤
     */
    @Synchronized
    fun start(pkg: String?): Boolean {
        // 幂等判断必须带上 isAlive()：早期只判 running，子进程被系统回收后
        // running 仍是 true，于是 start() 直接 return，永远起不来。
        if (running && isAlive()) return true
        if (running) {
            Log.w(TAG, "logcat 子进程已退出，自动重新拉起")
            stop()
        }
        if (!pkg.isNullOrEmpty()) {
            pkgFilter = pkg
            val pid = pidOf(pkg)
            if (pid > 0) pidFilter = pid
        }
        // 不要一次给多个 `-b`：Android 13+ 的 logcat 对多缓冲组合有额外校验，
        // 组合不合法时进程会立刻退出且 stderr 被 redirectErrorStream 混进 stdout，
        // 结果是「进程在、缓冲永远 0」。只读 main。
        val cmd = listOf("logcat", "-v", "threadtime")
        return try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            proc = pb.start()
            running = true
            paused = false
            reader = Thread({ pump() }, "oreki-logcat").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "logcat started pidFilter=$pidFilter pkg=$pkgFilter")
            true
        } catch (e: Exception) {
            Log.e(TAG, "logcat start failed: $e")
            running = false
            false
        }
    }

    /** 停止捕获并回收子进程。 */
    @Synchronized
    fun stop() {
        running = false
        val p = proc
        proc = null
        try {
            p?.destroy()
        } catch (_: Exception) {
        }
        val t = reader
        reader = null
        t?.interrupt()
        Log.i(TAG, "logcat stopped")
    }

    fun isRunning(): Boolean = running

    /** 子进程是否还活着（用于检测「ADB 断了 / logcat 退出了」）。 */
    fun isAlive(): Boolean {
        val p = proc ?: return false
        return try {
            p.exitValue()
            false // 已经退出
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    fun setPaused(v: Boolean) {
        paused = v
    }

    fun isPaused(): Boolean = paused

    fun clear() {
        synchronized(lock) { ring.clear() }
    }

    // ------------------------------------------------------------------ 过滤

    fun setMinLevel(level: Int) {
        minLevel = Math.max(0, Math.min(4, level))
    }

    fun getMinLevel(): Int = minLevel

    fun setPidFilter(pid: Int) {
        pidFilter = pid
    }

    fun getPidFilter(): Int = pidFilter

    fun setTagFilter(tag: String?) {
        tagFilter = if (tag.isNullOrEmpty()) null else tag
    }

    fun getTagFilter(): String? = tagFilter

    fun setKeywordFilter(kw: String?) {
        keywordFilter = if (kw.isNullOrEmpty()) null else kw
    }

    fun getKeywordFilter(): String? = keywordFilter

    fun setPkgFilter(pkg: String?) {
        pkgFilter = if (pkg.isNullOrEmpty()) null else pkg
        pkgFilter?.let {
            val pid = pidOf(it)
            if (pid > 0) pidFilter = pid
        }
    }

    fun getPkgFilter(): String? = pkgFilter

    // ------------------------------------------------------------------ 诊断规则

    /** 注册一条临时诊断规则。只影响「日志归属」，不做任何代码注入。 */
    @Synchronized
    fun addRule(cls: String?, method: String?, session: String?) {
        rules.add(DiagRule(cls, method, session))
        Log.i(TAG, "diag rule + $cls#$method @ $session")
    }

    /** 移除某次会话的全部诊断规则。 */
    @Synchronized
    fun removeSessionRules(session: String?): Int {
        var n = 0
        for (i in rules.indices.reversed()) {
            if (rules[i].session == session) {
                rules.removeAt(i)
                n++
            }
        }
        Log.i(TAG, "diag rule - session=$session removed=$n")
        return n
    }

    @Synchronized
    fun rules(): List<DiagRule> = ArrayList(rules)

    // ------------------------------------------------------------------ 查询

    /** 按当前过滤条件取最近 [limit] 条。 */
    fun tail(limit: Int, extraKeyword: Pattern?): List<Line> {
        val out = ArrayList<Line>()
        synchronized(lock) {
            val skip = Math.max(0, ring.size - Math.max(0, limit))
            var i = 0
            for (l in ring) {
                if (i++ < skip) continue
                if (accept(l, extraKeyword)) out.add(l)
            }
        }
        return out
    }

    /** 取序号大于 [sinceSeq] 的条目（增量拉取）。 */
    fun since(sinceSeq: Long, extraKeyword: Pattern?): List<Line> {
        val out = ArrayList<Line>()
        synchronized(lock) {
            for (l in ring) {
                if (l.seq > sinceSeq && accept(l, extraKeyword)) out.add(l)
            }
        }
        return out
    }

    fun size(): Int = synchronized(lock) { ring.size }

    fun lastSeq(): Long = synchronized(lock) { seq }

    private fun accept(l: Line, extra: Pattern?): Boolean {
        if (levelRank(l.level) < minLevel) return false
        if (pidFilter > 0 && l.pid != pidFilter) return false
        tagFilter?.let { if (!l.tag.contains(it)) return false }
        keywordFilter?.let { if (!l.text.contains(it)) return false }
        if (extra != null && !extra.matcher(l.text).find()) return false
        return true
    }

    // ------------------------------------------------------------------ 导出

    /** 导出为文本（便于直接贴进报告）。 */
    fun exportText(lines: List<Line>): String {
        val sb = StringBuilder(lines.size * 96)
        for (l in lines) {
            sb.append(iso(l.wallMs)).append(' ')
                .append(l.level).append('/').append(l.tag)
                .append('(').append(l.pid).append(':').append(l.tid).append(") ")
                .append(l.text).append('\n')
        }
        return sb.toString()
    }

    /** 导出为 JSONL。 */
    fun exportJsonl(lines: List<Line>): String {
        val sb = StringBuilder(lines.size * 128)
        for (l in lines) {
            val o = StringBuilder(160)
            o.append('{')
            o.append("\"seq\":").append(l.seq).append(',')
            o.append("\"wall_ms\":").append(l.wallMs).append(',')
            o.append("\"level\":\"").append(esc(l.level)).append("\",")
            o.append("\"tag\":\"").append(esc(l.tag)).append("\",")
            o.append("\"pid\":").append(l.pid).append(',')
            o.append("\"tid\":\"").append(esc(l.tid)).append("\",")
            o.append("\"text\":\"").append(esc(l.text)).append('"')
            l.session?.let { o.append(",\"session\":\"").append(esc(it)).append('"') }
            o.append('}')
            sb.append(o).append('\n')
        }
        return sb.toString()
    }

    /** 落盘到指定文件（追加）。 */
    fun exportTo(f: File, lines: List<Line>, jsonl: Boolean): Boolean = try {
        FileOutputStream(f, true).use { fos ->
            OutputStreamWriter(fos, "UTF-8").use { w ->
                w.write(if (jsonl) exportJsonl(lines) else exportText(lines))
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "export failed: $e")
        false
    }

    // ------------------------------------------------------------------ 内部

    private fun pump() {
        var totalParsed = 0
        var unparsed = 0
        val p = proc ?: return
        try {
            BufferedReader(InputStreamReader(p.inputStream, "UTF-8")).use { br ->
                while (running) {
                    val line = br.readLine() ?: break
                    if (paused) continue
                    val parsed = parse(line)
                    if (parsed == null) {
                        // 解析失败要有痕迹，但每条只可能计一次：早期写法用「前 3 条才打印」
                        // 的计数器，而该计数器只在失败分支自增，成功解析时永不递增 ⇒
                        // 一旦持续失败就永远满足 <3，每条都打日志，而这条日志又被 logcat
                        // 读回来再次打印 —— 形成指数级自我复制，把缓冲与磁盘瞬间打满。
                        unparsed++
                        continue
                    }
                    synchronized(lock) {
                        if (ring.size >= CAPACITY) ring.removeFirst()
                        ring.addLast(parsed)
                    }
                    totalParsed++
                }
            }
            Log.i(TAG, "logcat reader ended, parsed=$totalParsed unparsed=$unparsed")
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "logcat reader ended: $e parsed=$totalParsed unparsed=$unparsed")
            }
        }
    }

    /**
     * 解析 threadtime 行：
     * `01-02 03:04:05.678  1234  1250 I Tag     : message`
     */
    private fun parse(raw: String): Line? = try {
        if (raw.length < 33) {
            null
        } else {
            // 前 18 字符是 "MM-dd HH:mm:ss.SSS"
            val stamp = raw.substring(0, 18).trim()
            var p = 19
            while (p < raw.length && raw[p] == ' ') p++
            var q = p
            while (q < raw.length && raw[q] != ' ') q++
            if (q >= raw.length) {
                null
            } else {
                val pid = raw.substring(p, q).toInt()
                var r = q
                while (r < raw.length && raw[r] == ' ') r++
                var s = r
                while (s < raw.length && raw[s] != ' ') s++
                val tid = raw.substring(r, s)
                var t = s
                while (t < raw.length && raw[t] == ' ') t++
                if (t >= raw.length) {
                    null
                } else {
                    val level = raw[t].toString()
                    var u = t + 1
                    while (u < raw.length && raw[u] == ' ') u++
                    val colon = raw.indexOf(": ", u)
                    if (colon < 0) {
                        null
                    } else {
                        val tag = raw.substring(u, colon).trim()
                        val text = raw.substring(colon + 2)
                        var session: String? = null
                        for (rule in rules) {
                            val cls = rule.cls
                            val method = rule.method
                            if (cls != null && method != null &&
                                text.contains(cls) && text.contains(method)
                            ) {
                                session = rule.session
                                break
                            }
                        }
                        val wall = parseWall(stamp)
                        val s2 = synchronized(lock) { ++seq }
                        Line(s2, wall, level, tag, pid, tid, text, session)
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "OrekiLogcat"

        /** 环形缓冲容量（条）。按平均 200 字节/条估算约 800 KB 上限。 */
        private const val CAPACITY = 4000

        private val INSTANCE = LogcatRecorder()

        @JvmStatic
        fun get(): LogcatRecorder = INSTANCE

        private val IN_STAMP = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
        }

        private val OUT_ISO = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }

        private fun parseWall(stamp: String): Long {
            // logcat 的 threadtime 只给 `MM-dd HH:mm:ss.SSS`，没有年份。
            // 直接解析会落到 1970 年，得到 26 亿这类荒谬的 wall_ms，
            // 按时间窗口把「点击」与「日志」对齐时会全错。这里补当前年份再解析。
            return try {
                val year = GregorianCalendar().get(Calendar.YEAR)
                val d = IN_STAMP.get()!!.parse("$year-$stamp")
                d?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }

        @JvmStatic
        fun iso(ms: Long): String = OUT_ISO.get()!!.format(Date(ms))

        private fun esc(s: String?): String {
            if (s == null) return ""
            val b = StringBuilder(s.length + 8)
            for (c in s) {
                when (c) {
                    '"' -> b.append("\\\"")
                    '\\' -> b.append("\\\\")
                    '\n' -> b.append("\\n")
                    '\r' -> b.append("\\r")
                    '\t' -> b.append("\\t")
                    else ->
                        if (c < ' ') b.append(String.format(Locale.US, "\\u%04x", c.code)) else b.append(c)
                }
            }
            return b.toString()
        }

        private fun levelRank(lv: String): Int = when (lv) {
            "V" -> 0
            "D" -> 1
            "I" -> 2
            "W" -> 3
            "E", "F" -> 4
            else -> 2
        }

        /** 包名 → PID。取不到返回 0。 */
        @JvmStatic
        fun pidOf(pkg: String?): Int {
            if (pkg.isNullOrEmpty()) return 0
            var p: Process? = null
            return try {
                p = ProcessBuilder("pidof", pkg).redirectErrorStream(true).start()
                BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                    val line = br.readLine()
                    if (line != null) {
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                            parts[0].toInt()
                        } else 0
                    } else 0
                }
            } catch (_: Exception) {
                0
            } finally {
                try {
                    p?.destroy()
                } catch (_: Exception) {
                }
            }
        }

        /** 设备信息，放进 Session 的 meta。 */
        @JvmStatic
        fun deviceInfo(): String =
            "sdk=" + Build.VERSION.SDK_INT +
                " release=" + Build.VERSION.RELEASE +
                " model=" + Build.MODEL +
                " device=" + Build.DEVICE
    }
}
