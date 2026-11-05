package com.oreki.frameobserver

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 会话日志写入器（Frame Bridge 的落盘侧）。
 *
 * 输出四份文件，全部在 `<外部私有目录>/sessions/<UTC时间戳>/`：
 * ```
 * meta.json      会话参数（分辨率、帧率、设备、开始时间）
 * frames.jsonl   每帧一行：frame_id / 时间戳 / 尺寸 / 像素格式 / 采集延迟 / 累计丢帧
 * events.jsonl   每条无障碍事件一行（由 a11y 服务写入，与 frames 同一时间基准）
 * stats.jsonl    每 5 秒一行：实际 FPS / 丢帧率 / 延迟 / 内存 / CPU
 * ```
 *
 * **为什么是 JSONL 而不是一个 JSON 数组**：录制随时可能被强杀或断电，数组式写法在
 * 崩溃时会留下语法不完整的文件，整份日志作废；JSONL 每条记录自洽，截断处之前的
 * 全部可解析。这对「失败现场自动打包」是硬需求。
 *
 * **时间基准**：所有 `*_mono_ns` 字段都是 [System.nanoTime]，与 `Image.getTimestamp()`、
 * `AccessibilityEvent.getEventTime()` 同域（CLOCK_MONOTONIC）。
 * 另附 `wall_ms`（epoch 毫秒）仅用于人工对照，不用于计算。
 */
object SessionLog {

    private val LOCK = Any()

    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var dir: File? = null
    private var frames: BufferedWriter? = null
    private var events: BufferedWriter? = null
    private var stats: BufferedWriter? = null
    private var pending = 0
    private var lastFlushMono = 0L

    @JvmStatic
    fun currentDir(): File? = synchronized(LOCK) { dir }

    /** 新建一个会话目录，并把「当前会话」切过去。 */
    @JvmStatic
    fun openNewSession(baseDir: File, metaJson: String): File = synchronized(LOCK) {
        closeQuietlyLocked()
        val d = File(baseDir, "sessions/" + STAMP.format(Date()))
        File(d, "frames").mkdirs()
        dir = d
        writeText(File(d, "meta.json"), metaJson)
        try {
            frames = writer(File(d, "frames.jsonl"))
            events = writer(File(d, "events.jsonl"))
            stats = writer(File(d, "stats.jsonl"))
        } catch (_: IOException) {
            frames = null
            events = null
            stats = null
        }
        pending = 0
        lastFlushMono = System.nanoTime()
        FrameBus.sessionDir = d
        d
    }

    /** 无障碍服务可能先于采集启动，此时也要有地方写事件。 */
    @JvmStatic
    fun ensureSession(baseDir: File, metaJson: String): File = synchronized(LOCK) {
        dir ?: openNewSession(baseDir, metaJson)
    }

    @JvmStatic
    fun frame(line: String) = append(0, line)

    @JvmStatic
    fun event(line: String) = append(1, line)

    @JvmStatic
    fun stat(line: String) = append(2, line)

    private fun append(which: Int, line: String) {
        synchronized(LOCK) {
            val w = when (which) {
                0 -> frames
                1 -> events
                else -> stats
            } ?: return
            try {
                w.write(line)
                w.write('\n'.code)
                pending++
                val now = System.nanoTime()
                // 批量 flush：每 16 行或每 300ms 落一次，兼顾崩溃可回读与 syscall 开销。
                if (pending >= 16 || (now - lastFlushMono) > 300_000_000L) {
                    frames?.flush()
                    events?.flush()
                    stats?.flush()
                    pending = 0
                    lastFlushMono = now
                }
            } catch (_: IOException) {
                // 写盘失败不让采集线程崩：统计里 writeErrors 会体现，帧继续产。
            }
        }
    }

    @JvmStatic
    fun flush() = synchronized(LOCK) {
        try {
            frames?.flush()
            events?.flush()
            stats?.flush()
        } catch (_: IOException) {
        }
        pending = 0
    }

    @JvmStatic
    fun closeQuietly() = synchronized(LOCK) { closeQuietlyLocked() }

    private fun closeQuietlyLocked() {
        try {
            frames?.flush()
            events?.flush()
            stats?.flush()
        } catch (_: IOException) {
        }
        closeOne(frames)
        closeOne(events)
        closeOne(stats)
        frames = null
        events = null
        stats = null
        pending = 0
    }

    @JvmStatic
    fun isoNow(): String = ISO.format(Date())

    private fun closeOne(w: BufferedWriter?) {
        try {
            w?.close()
        } catch (_: IOException) {
        }
    }

    @Throws(IOException::class)
    private fun writer(f: File): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), "UTF-8"), 32 * 1024)

    private fun writeText(f: File, text: String) {
        try {
            FileOutputStream(f).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: IOException) {
        }
    }
}
