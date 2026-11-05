package com.oreki.frameobserver

import java.util.Locale

/**
 * 采集统计。
 *
 * **核心纪律：目标帧率与实际帧率必须分开报告，永不混用。**
 * 「能够准确记录实际 FPS」指的是 [actualFps]，而不是 [CaptureConfig.fps]。
 * 常见工具把「我们请求了 60fps」当成「我们拿到了 60fps」，这是错的 ——
 * ImageReader 的实际可用帧率受 SurfaceFlinger 合成节奏、编码耗时、CPU 抢占三重限制。
 *
 * 时间基准统一为 [System.nanoTime]（CLOCK_MONOTONIC）。选它而不是
 * `currentTimeMillis`（墙上时钟，会被 NTP 校时跳变）也不是 `elapsedRealtimeNanos`
 * （CLOCK_BOOTTIME，含深睡时间），因为：
 * - `Image.getTimestamp()` 来自 SurfaceTexture，用的就是 CLOCK_MONOTONIC；
 * - `AccessibilityEvent.getEventTime()` 是 uptimeMillis，同为 CLOCK_MONOTONIC。
 *
 * 只有三者同域，才能把「无障碍点击事件」和「它引起的画面变化」放在同一条时间轴上比较。
 */
class FrameStats {

    /** onImageAvailable 被回调的次数（= 生产者交付的帧数）。 */
    @Volatile var framesAvailable: Long = 0
    /** 我们真正取走并处理的帧数。 */
    @Volatile var framesProcessed: Long = 0
    /** 主动丢弃的帧数（队列满 / 磁盘保护 / 暂停）。 */
    @Volatile var framesDropped: Long = 0
    /** 已写入磁盘的字节数。 */
    @Volatile var bytesWritten: Long = 0
    /** 写盘失败次数。 */
    @Volatile var writeErrors: Long = 0

    /** 最近一帧的采集延迟：从生产者交付(timestamp)到我们取到，毫秒。 */
    @Volatile var lastCaptureLatencyMs: Double = 0.0
    /** 采集延迟指数滑动平均。 */
    @Volatile var emaCaptureLatencyMs: Double = 0.0
    /** 最近一帧的编码+落盘耗时，毫秒。 */
    @Volatile var lastEncodeLatencyMs: Double = 0.0
    /** 编码耗时指数滑动平均。 */
    @Volatile var emaEncodeLatencyMs: Double = 0.0

    @Volatile var startedAtMonoNs: Long = 0
    @Volatile var targetWidth: Int = 0
    @Volatile var targetHeight: Int = 0
    @Volatile var targetFps: Int = 0

    /** 实际帧率统计窗口：只保留最近 [FPS_WINDOW] 帧的取帧时刻。 */
    private val fpsRing = LongArray(FPS_WINDOW)
    private var fpsCount = 0
    private var fpsHead = 0

    @Synchronized
    fun reset() {
        framesAvailable = 0
        framesProcessed = 0
        framesDropped = 0
        bytesWritten = 0
        writeErrors = 0
        lastCaptureLatencyMs = 0.0
        emaCaptureLatencyMs = 0.0
        lastEncodeLatencyMs = 0.0
        emaEncodeLatencyMs = 0.0
        startedAtMonoNs = System.nanoTime()
        fpsCount = 0
        fpsHead = 0
        fpsRing.fill(0L)
    }

    @Synchronized
    fun onFrameProcessed(monoNs: Long, captureLatencyMs: Double, encodeLatencyMs: Double) {
        framesProcessed++
        lastCaptureLatencyMs = captureLatencyMs
        lastEncodeLatencyMs = encodeLatencyMs
        emaCaptureLatencyMs =
            if (emaCaptureLatencyMs == 0.0) captureLatencyMs else emaCaptureLatencyMs * 0.9 + captureLatencyMs * 0.1
        emaEncodeLatencyMs =
            if (emaEncodeLatencyMs == 0.0) encodeLatencyMs else emaEncodeLatencyMs * 0.9 + encodeLatencyMs * 0.1
        fpsRing[fpsHead] = monoNs
        fpsHead = (fpsHead + 1) % FPS_WINDOW
        if (fpsCount < FPS_WINDOW) fpsCount++
    }

    /**
     * 实际帧率（按最近一窗口内的取帧间隔计算）。
     *
     * 用「首末间隔 / 帧数」而不是「帧数 / 累计时长」，是为了不被启动阶段的长尾拉低。
     * 窗口未满 2 帧时返回 0（此时任何数字都是编的）。
     */
    @Synchronized
    fun actualFps(): Double {
        if (fpsCount < 2) return 0.0
        val newestIdx = (fpsHead - 1 + FPS_WINDOW) % FPS_WINDOW
        val oldestIdx = (fpsHead - fpsCount + FPS_WINDOW) % FPS_WINDOW
        val span = fpsRing[newestIdx] - fpsRing[oldestIdx]
        if (span <= 0) return 0.0
        return (fpsCount - 1) * 1_000_000_000.0 / span
    }

    fun dropRate(): Double {
        val avail = framesAvailable
        return if (avail <= 0) 0.0 else framesDropped * 100.0 / avail
    }

    fun uptimeMs(): Long {
        val s = startedAtMonoNs
        return if (s == 0L) 0L else (System.nanoTime() - s) / 1_000_000L
    }

    /** 供 `/stats` 与 `stats.jsonl` 用的快照。字段名与 `docs/接口说明.md` 一一对应。 */
    fun toJson(): String = com.oreki.frameobserver.util.Json.obj()
        .put("target_fps", targetFps)
        .put("actual_fps", actualFps())
        .put("width", targetWidth)
        .put("height", targetHeight)
        .put("frames_available", framesAvailable)
        .put("frames_processed", framesProcessed)
        .put("frames_dropped", framesDropped)
        .put("drop_rate_pct", dropRate())
        .put("capture_latency_ms", lastCaptureLatencyMs)
        .put("capture_latency_ema_ms", emaCaptureLatencyMs)
        .put("encode_latency_ms", lastEncodeLatencyMs)
        .put("encode_latency_ema_ms", emaEncodeLatencyMs)
        .put("bytes_written", bytesWritten)
        .put("write_errors", writeErrors)
        .put("uptime_ms", uptimeMs())
        .put("t_mono_ns", System.nanoTime())
        .end()

    fun toHuman(): String = String.format(
        Locale.US,
        "目标 %d fps / 实际 %.1f fps\n分辨率 %d×%d  丢帧 %d (%.1f%%)\n" +
            "采集延迟 %.1f ms (均值 %.1f)  编码 %.1f ms (均值 %.1f)\n" +
            "已处理 %d 帧  写盘 %.2f MB  错误 %d  运行 %.1f s",
        targetFps, actualFps(), targetWidth, targetHeight,
        framesDropped, dropRate(),
        lastCaptureLatencyMs, emaCaptureLatencyMs,
        lastEncodeLatencyMs, emaEncodeLatencyMs,
        framesProcessed, bytesWritten / 1048576.0, writeErrors, uptimeMs() / 1000.0,
    )

    companion object {
        private const val FPS_WINDOW = 90
    }
}
