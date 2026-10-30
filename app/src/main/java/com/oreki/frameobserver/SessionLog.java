package com.oreki.frameobserver;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 会话日志写入器（Frame Bridge 的落盘侧）。
 *
 * <p>输出四份文件，全部在 {@code <外部私有目录>/sessions/<UTC时间戳>/}：
 * <pre>
 *   meta.json      会话参数（分辨率、帧率、设备、开始时间）
 *   frames.jsonl   每帧一行：frame_id / 时间戳 / 尺寸 / 像素格式 / 采集延迟 / 累计丢帧
 *   events.jsonl   每条无障碍事件一行（由 a11y 服务写入，与 frames 同一时间基准）
 *   stats.jsonl    每 5 秒一行：实际 FPS / 丢帧率 / 延迟 / 内存 / CPU
 * </pre>
 *
 * <p><b>为什么是 JSONL 而不是一个 JSON 数组</b>：录制随时可能被强杀或断电，数组式写法在
 * 崩溃时会留下一个语法不完整的文件，整份日志作废；JSONL 每条记录自洽，截断处之前的
 * 全部可解析。这对「失败现场自动打包」是硬需求。
 *
 * <p><b>时间基准</b>：所有 {@code *_mono_ns} 字段都是 {@link System#nanoTime()}，
 * 与 {@code Image.getTimestamp()}、{@code AccessibilityEvent.getEventTime()} 同域（CLOCK_MONOTONIC）。
 * 另附 {@code wall_ms}（epoch 毫秒）仅用于人工对照，不用于计算。
 */
public final class SessionLog {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private static final SimpleDateFormat ISO =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);

    static {
        STAMP.setTimeZone(TimeZone.getTimeZone("UTC"));
        ISO.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private static File dir;
    private static BufferedWriter frames;
    private static BufferedWriter events;
    private static BufferedWriter stats;
    private static int pending;
    private static long lastFlushMono;

    private SessionLog() {
    }

    public static synchronized File currentDir() {
        return dir;
    }

    /** 新建一个会话目录，并把「当前会话」切过去。 */
    public static synchronized File openNewSession(File baseDir, String metaJson) {
        closeQuietly();
        File d = new File(baseDir, "sessions/" + STAMP.format(new Date()));
        File f = new File(d, "frames");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        dir = d;
        writeText(new File(d, "meta.json"), metaJson);
        try {
            frames = writer(new File(d, "frames.jsonl"));
            events = writer(new File(d, "events.jsonl"));
            stats = writer(new File(d, "stats.jsonl"));
        } catch (IOException e) {
            frames = null;
            events = null;
            stats = null;
        }
        pending = 0;
        lastFlushMono = System.nanoTime();
        FrameBus.sessionDir = d;
        return d;
    }

    /** 无障碍服务可能先于采集启动，此时也要有地方写事件。 */
    public static synchronized File ensureSession(File baseDir, String metaJson) {
        if (dir == null) {
            return openNewSession(baseDir, metaJson);
        }
        return dir;
    }

    public static void frame(String line) {
        append(0, line);
    }

    public static void event(String line) {
        append(1, line);
    }

    public static void stat(String line) {
        append(2, line);
    }

    private static synchronized void append(int which, String line) {
        BufferedWriter w = which == 0 ? frames : which == 1 ? events : stats;
        if (w == null) {
            return;
        }
        try {
            w.write(line);
            w.write('\n');
            pending++;
            long now = System.nanoTime();
            // 批量 flush：每 16 行或每 300ms 落一次，兼顾崩溃可回读与 syscall 开销。
            if (pending >= 16 || (now - lastFlushMono) > 300_000_000L) {
                frames.flush();
                events.flush();
                stats.flush();
                pending = 0;
                lastFlushMono = now;
            }
        } catch (IOException ignored) {
            // 写盘失败不让采集线程崩：统计里 writeErrors 会体现，帧继续产。
        }
    }

    public static synchronized void flush() {
        try {
            if (frames != null) frames.flush();
            if (events != null) events.flush();
            if (stats != null) stats.flush();
        } catch (IOException ignored) {
        }
        pending = 0;
    }

    public static synchronized void closeQuietly() {
        flush();
        closeOne(frames);
        closeOne(events);
        closeOne(stats);
        frames = null;
        events = null;
        stats = null;
    }

    public static String isoNow() {
        return ISO.format(new Date());
    }

    private static void closeOne(BufferedWriter w) {
        if (w == null) {
            return;
        }
        try {
            w.close();
        } catch (IOException ignored) {
        }
    }

    private static BufferedWriter writer(File f) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8"), 32 * 1024);
    }

    private static void writeText(File f, String text) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(text.getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
    }
}
