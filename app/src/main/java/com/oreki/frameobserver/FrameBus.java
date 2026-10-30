package com.oreki.frameobserver;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内单例总线。
 *
 * <p>存在的理由：UI（{@link MainActivity}）、采集服务（{@link CaptureService}）、
 * 本机 HTTP 服务（{@code net.FrameHttpServer}）三者生命周期不同步 —— 服务可以在 Activity
 * 销毁后继续跑，HTTP 服务可以在服务重启后存活。用一个极轻的静态总线交换「最新一帧」和
 * 「统计快照」，避免为了一帧数据去做 Binder / LiveData / 广播。
 *
 * <p><b>线程安全约定</b>：
 * <ul>
 *   <li>{@code latestJpeg} 是「写一次、整体替换」的不可变 byte[]，读侧拿到引用后可以直接用，
 *       不需要加锁（volatile 保证可见性；数组内容不会被原地修改）。</li>
 *   <li>{@link FrameStats} 用 volatile 字段 + 内部 synchronized 环形缓冲，可以跨线程读。</li>
 *   <li>{@link #listeners} 只在主线程遍历，回调里不要做重活。</li>
 * </ul>
 *
 * <p>刻意不做的事：不缓存多帧。缓存多帧等于自己实现一个无界队列，正是验收标准里
 * 明令禁止的「内存无限增长」。
 */
public final class FrameBus {

    private FrameBus() {
    }

    public interface Listener {
        /** 有新帧可用（回调在主线程，频率已节流到 ≤10Hz）。 */
        void onNewFrame(long frameId);

        /** 采集状态变化。 */
        void onStateChanged(String state);
    }

    public static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 最新一帧的 JPEG 字节。null 表示还没采到。 */
    public static volatile byte[] latestJpeg;
    public static volatile long latestFrameId = -1;
    /** 该帧的采集时刻（CLOCK_MONOTONIC，纳秒），与 Image.getTimestamp() 同域。 */
    public static volatile long latestFrameMonoNs;
    /** 该帧在屏幕上的产生时刻（生产者时间戳，纳秒，CLOCK_MONOTONIC）。 */
    public static volatile long latestFrameSourceNs;
    public static volatile long latestFrameBytes;

    public static final FrameStats stats = new FrameStats();

    public static volatile CaptureConfig config;
    public static volatile File sessionDir;
    public static volatile boolean capturing;
    public static volatile String state = "未开始";

    public static void setState(String s) {
        state = s;
        for (Listener l : listeners) {
            l.onStateChanged(s);
        }
    }

    /**
     * 预览节流闸门。
     *
     * <p>为什么要节流：采集可以到 60fps，但 UI 预览与 {@code /snapshot.jpg} 的消费方
     * 不可能也不需要 60Hz。若每帧都回调主线程，等于把 60 次/秒的跨线程投递压在 UI 上，
     * 正好违反「截图采集不得阻塞 UI 主线程」。
     *
     * <p>80ms ≈ 12.5Hz：足够让预览看起来连续，又不会把主线程排满。
     * 注意这里只节流<b>通知</b>，不节流统计数据 —— 帧计数必须逐帧精确。
     */
    public static boolean previewFresh() {
        long now = System.nanoTime();
        if (now - lastPublishMonoNs < 80_000_000L) {
            return false;
        }
        lastPublishMonoNs = now;
        return true;
    }

    private static volatile long lastPublishMonoNs;

    public static void publishFrame(long frameId, byte[] jpeg, long monoNs, long sourceNs) {
        latestJpeg = jpeg;
        latestFrameId = frameId;
        latestFrameMonoNs = monoNs;
        latestFrameSourceNs = sourceNs;
        latestFrameBytes = jpeg == null ? 0 : jpeg.length;
        for (Listener l : listeners) {
            l.onNewFrame(frameId);
        }
    }

    public static File framesDir() {
        File d = sessionDir;
        return d == null ? null : new File(d, "frames");
    }

    public static String pathsHuman() {
        File d = sessionDir;
        if (d == null) {
            return "会话目录：尚未创建";
        }
        return "会话目录：\n" + d.getAbsolutePath()
                + "\nadb pull \"" + d.getAbsolutePath() + "\"";
    }
}
