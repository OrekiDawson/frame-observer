package com.oreki.frameobserver

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程内单例总线。
 *
 * 存在的理由：UI（[MainActivity]）、采集服务（[CaptureService]）、本机 HTTP 服务
 * （`net.FrameHttpServer`）三者生命周期不同步 —— 服务可以在 Activity 销毁后继续跑，
 * HTTP 服务可以在服务重启后存活。用一个极轻的静态总线交换「最新一帧」和「统计快照」，
 * 避免为了一帧数据去做 Binder / LiveData / 广播。
 *
 * **线程安全约定**：
 * - [latestJpeg] 是「写一次、整体替换」的不可变 ByteArray，读侧拿到引用后可直接用，
 *   不需要加锁（volatile 保证可见性；数组内容不会被原地修改）。
 * - [stats] 用 volatile 字段 + 内部 synchronized 环形缓冲，可跨线程读。
 * - [listeners] 用写时复制列表，遍历时不需要加锁；回调里不要做重活。
 *
 * 刻意不做的事：不缓存多帧 —— 缓存多帧等于自实现无界队列，正是验收标准里
 * 明令禁止的「内存无限增长」。
 */
object FrameBus {

    interface Listener {
        /** 有新帧可用（回调在主线程，频率已节流到 ≤12.5Hz）。 */
        fun onNewFrame(frameId: Long)

        /** 采集状态变化。 */
        fun onStateChanged(state: String)
    }

    @JvmField
    val listeners: CopyOnWriteArrayList<Listener> = CopyOnWriteArrayList()

    /** 最新一帧的 JPEG 字节。null 表示还没采到。 */
    @JvmStatic
    @Volatile
    var latestJpeg: ByteArray? = null

    @JvmStatic
    @Volatile
    var latestFrameId: Long = -1

    /** 该帧的采集时刻（CLOCK_MONOTONIC，纳秒），与 `Image.getTimestamp()` 同域。 */
    @JvmStatic
    @Volatile
    var latestFrameMonoNs: Long = 0

    /** 该帧在屏幕上的产生时刻（生产者时间戳，纳秒，CLOCK_MONOTONIC）。 */
    @JvmStatic
    @Volatile
    var latestFrameSourceNs: Long = 0

    @JvmStatic
    @Volatile
    var latestFrameBytes: Long = 0

    @JvmField
    val stats: FrameStats = FrameStats()

    @JvmStatic
    @Volatile
    var config: CaptureConfig? = null

    @JvmStatic
    @Volatile
    var sessionDir: File? = null

    @JvmStatic
    @Volatile
    var capturing: Boolean = false

    /**
     * 采集状态文本。
     *
     * 内部用 [stateValue] 存值，对外只暴露 getter：Kotlin 属性的自动 setter 会生成
     * `setState(String)`，与下面的 `setState(String)` 方法在 JVM 层签名完全相同，
     * 编译期会报 platform declaration clash。
     */
    @Volatile
    private var stateValue: String = "未开始"

    @JvmStatic
    val state: String
        get() = stateValue

    @JvmStatic
    fun setState(s: String) {
        stateValue = s
        for (l in listeners) l.onStateChanged(s)
    }

    /**
     * 预览节流闸门。
     *
     * 采集可以到 60fps，但 UI 预览与 `/snapshot.jpg` 的消费方不需要 60Hz。
     * 若每帧都回调主线程，等于把 60 次/秒的跨线程投递压在 UI 上，
     * 正好违反「截图采集不得阻塞 UI 主线程」。
     *
     * 80ms ≈ 12.5Hz：足够连续，又不会把主线程排满。
     * 只节流**通知**，不节流统计 —— 帧计数必须逐帧精确。
     */
    @JvmStatic
    fun previewFresh(): Boolean {
        val now = System.nanoTime()
        if (now - lastPublishMonoNs < 80_000_000L) return false
        lastPublishMonoNs = now
        return true
    }

    @Volatile
    private var lastPublishMonoNs: Long = 0

    @JvmStatic
    fun publishFrame(frameId: Long, jpeg: ByteArray?, monoNs: Long, sourceNs: Long) {
        latestJpeg = jpeg
        latestFrameId = frameId
        latestFrameMonoNs = monoNs
        latestFrameSourceNs = sourceNs
        latestFrameBytes = jpeg?.size?.toLong() ?: 0L
        for (l in listeners) l.onNewFrame(frameId)
    }

    @JvmStatic
    fun framesDir(): File? {
        val d = sessionDir ?: return null
        return File(d, "frames")
    }

    @JvmStatic
    fun pathsHuman(): String {
        val d = sessionDir ?: return "会话目录：尚未创建"
        return "会话目录：\n" + d.absolutePath + "\nadb pull \"" + d.absolutePath + "\""
    }
}
