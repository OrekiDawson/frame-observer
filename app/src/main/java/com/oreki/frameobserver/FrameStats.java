package com.oreki.frameobserver;

import java.util.Locale;

/**
 * 采集统计。
 *
 * <p><b>核心纪律：目标帧率与实际帧率必须分开报告，永不混用。</b>
 * 验收标准里「能够准确记录实际 FPS」指的是本类 {@link #actualFps()}，
 * 而不是 {@link CaptureConfig#fps}。很多同类工具把「我们请求了 60fps」当成「我们拿到了 60fps」，
 * 这是错的 —— ImageReader 的实际可用帧率受 SurfaceFlinger 合成节奏、编码耗时、CPU 抢占三重限制。
 *
 * <p>时间基准统一为 {@link System#nanoTime()}（CLOCK_MONOTONIC）。
 * 选它而不是 {@code currentTimeMillis()}（墙上时钟，会被 NTP 校时跳变）也不是
 * {@code elapsedRealtimeNanos()}（CLOCK_BOOTTIME，含深睡时间）。原因是：
 * <ul>
 *   <li>{@code Image.getTimestamp()} 来自 SurfaceTexture，用的就是 CLOCK_MONOTONIC；</li>
 *   <li>{@code AccessibilityEvent.getEventTime()} 是 uptimeMillis，同为 CLOCK_MONOTONIC。</li>
 * </ul>
 * 只有三者同域，才能把「无障碍点击事件」和「它引起的画面变化」放在同一条时间轴上比较。
 */
public final class FrameStats {

    /** onImageAvailable 被回调的次数（= 生产者交付的帧数）。 */
    public volatile long framesAvailable;
    /** 我们真正取走并处理的帧数。 */
    public volatile long framesProcessed;
    /** 主动丢弃的帧数（队列满 / 磁盘保护 / 暂停）。 */
    public volatile long framesDropped;
    /** 已写入磁盘的字节数。 */
    public volatile long bytesWritten;
    /** 写盘失败次数。 */
    public volatile long writeErrors;

    /** 最近一帧的采集延迟：从生产者交付(timestamp)到我们取到，毫秒。 */
    public volatile double lastCaptureLatencyMs;
    /** 采集延迟指数滑动平均。 */
    public volatile double emaCaptureLatencyMs;
    /** 最近一帧的编码+落盘耗时，毫秒。 */
    public volatile double lastEncodeLatencyMs;
    /** 编码耗时指数滑动平均。 */
    public volatile double emaEncodeLatencyMs;

    public volatile long startedAtMonoNs;
    public volatile int targetWidth;
    public volatile int targetHeight;
    public volatile int targetFps;

    /** 实际帧率统计窗口：只保留最近 {@link #FPS_WINDOW} 帧的取帧时刻。 */
    private static final int FPS_WINDOW = 90;
    private final long[] fpsRing = new long[FPS_WINDOW];
    private int fpsCount;
    private int fpsHead;

    public synchronized void reset() {
        framesAvailable = 0;
        framesProcessed = 0;
        framesDropped = 0;
        bytesWritten = 0;
        writeErrors = 0;
        lastCaptureLatencyMs = 0;
        emaCaptureLatencyMs = 0;
        lastEncodeLatencyMs = 0;
        emaEncodeLatencyMs = 0;
        startedAtMonoNs = System.nanoTime();
        fpsCount = 0;
        fpsHead = 0;
        java.util.Arrays.fill(fpsRing, 0L);
    }

    public synchronized void onFrameProcessed(long monoNs, double captureLatencyMs, double encodeLatencyMs) {
        framesProcessed++;
        lastCaptureLatencyMs = captureLatencyMs;
        lastEncodeLatencyMs = encodeLatencyMs;
        emaCaptureLatencyMs = emaCaptureLatencyMs == 0
                ? captureLatencyMs
                : emaCaptureLatencyMs * 0.9 + captureLatencyMs * 0.1;
        emaEncodeLatencyMs = emaEncodeLatencyMs == 0
                ? encodeLatencyMs
                : emaEncodeLatencyMs * 0.9 + encodeLatencyMs * 0.1;
        fpsRing[fpsHead] = monoNs;
        fpsHead = (fpsHead + 1) % FPS_WINDOW;
        if (fpsCount < FPS_WINDOW) {
            fpsCount++;
        }
    }

    /**
     * 实际帧率（按最近一窗口内的取帧间隔计算）。
     *
     * <p>用「首末间隔 / 帧数」而不是「帧数 / 累计时长」，是为了不被启动阶段的长尾拉低。
     * 窗口未满 2 帧时返回 0（此时任何数字都是编的）。
     */
    public synchronized double actualFps() {
        if (fpsCount < 2) {
            return 0d;
        }
        int newestIdx = (fpsHead - 1 + FPS_WINDOW) % FPS_WINDOW;
        int oldestIdx = (fpsHead - fpsCount + FPS_WINDOW) % FPS_WINDOW;
        long span = fpsRing[newestIdx] - fpsRing[oldestIdx];
        if (span <= 0) {
            return 0d;
        }
        return (fpsCount - 1) * 1_000_000_000d / span;
    }

    public double dropRate() {
        long avail = framesAvailable;
        if (avail <= 0) {
            return 0d;
        }
        return framesDropped * 100d / avail;
    }

    public long uptimeMs() {
        long s = startedAtMonoNs;
        return s == 0 ? 0 : (System.nanoTime() - s) / 1_000_000L;
    }

    /** 供 /stats 与 stats.jsonl 用的快照。字段名与 docs/接口说明 一一对应。 */
    public String toJson() {
        return com.oreki.frameobserver.util.Json.obj()
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
                .end();
    }

    public String toHuman() {
        return String.format(Locale.US,
                "目标 %d fps / 实际 %.1f fps\n分辨率 %d×%d  丢帧 %d (%.1f%%)\n"
                        + "采集延迟 %.1f ms (均值 %.1f)  编码 %.1f ms (均值 %.1f)\n"
                        + "已处理 %d 帧  写盘 %.2f MB  错误 %d  运行 %.1f s",
                targetFps, actualFps(), targetWidth, targetHeight,
                framesDropped, dropRate(),
                lastCaptureLatencyMs, emaCaptureLatencyMs,
                lastEncodeLatencyMs, emaEncodeLatencyMs,
                framesProcessed, bytesWritten / 1048576d, writeErrors, uptimeMs() / 1000d);
    }
}
