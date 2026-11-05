package com.oreki.frameobserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.oreki.frameobserver.util.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 采集服务：MediaProjection → VirtualDisplay → ImageReader → JPEG。
 *
 * **Android 14 的三条硬约束**（任一条不满足都会抛异常，且报错信息不直观）：
 * 1. 持有投影的服务必须以 `mediaProjection` 类型前台运行，且 `startForeground()` 必须在
 *    `getMediaProjection()` **之前**调用，否则 SecurityException。
 * 2. `createVirtualDisplay()` 之前必须 `registerCallback()`，否则
 *    IllegalStateException("Must register a callback before starting capture")。
 * 3. 每轮投影授权只能用于一次捕获会话；`onStop()` 后令牌失效，必须重新向用户申请。
 *
 * **线程模型**（「不得阻塞 UI 主线程」的落点）：
 * ```
 * 主线程            startForeground / 建 VirtualDisplay
 * captureThread     onImageAvailable → acquireLatestImage → 入有界队列（只做指针交接，零拷贝）
 * saveThread        取 Image → 拷像素 → Bitmap → JPEG 编码 → 落盘 → close()
 * ```
 * 像素拷贝与 JPEG 编码全部发生在 saveThread，captureThread 上只有一次
 * `acquireLatestImage()` 和一次 `queue.offer()`（纳秒级）。
 * 队列容量 3 且**满时丢最旧**，保证内存有界、优先最新画面。
 */
class CaptureService : Service() {

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var saveThread: Thread? = null

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private val queue = ArrayBlockingQueue<Image>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    private var frameId = 0L
    private var outW = 0
    private var outH = 0
    private var cfg: CaptureConfig? = null

    // —— saveThread 独占的可复用缓冲，避免每帧分配 ——
    private var reuseBitmap: Bitmap? = null
    private var reusePixels: IntArray? = null
    private val baos = ByteArrayOutputStream(512 * 1024)

    private val proc = ProcStats()
    private var lastStatMono = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_STOP -> {
                stopCapture("用户停止")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RECORD -> {
                cfg?.let { c ->
                    c.recordToDisk = !c.recordToDisk
                    FrameBus.config?.recordToDisk = c.recordToDisk
                    FrameBus.setState(if (c.recordToDisk) "采集中（录像开）" else "采集中（仅预览）")
                    updateNotification()
                }
                return START_STICKY
            }
            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null) {
            FrameBus.setState("授权数据缺失，未开始")
            stopSelf()
            return START_NOT_STICKY
        }

        // 步骤 1：必须先以 mediaProjection 类型进入前台（Android 14 硬性要求）。
        val n = buildNotification("正在启动采集…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }

        val divisor = intent.getIntExtra(EXTRA_DIVISOR, 2)
        val fps = intent.getIntExtra(EXTRA_FPS, 60)
        val quality = intent.getIntExtra(EXTRA_QUALITY, 80)
        val record = intent.getBooleanExtra(EXTRA_RECORD, true)

        if (!beginCapture(resultCode, resultData, divisor, fps, quality, record)) stopSelf()
        return START_STICKY
    }

    private fun beginCapture(
        resultCode: Int,
        data: Intent,
        divisor: Int,
        fps: Int,
        quality: Int,
        record: Boolean,
    ): Boolean {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            FrameBus.setState("拿不到 WindowManager")
            return false
        }
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val c = CaptureConfig.of(dm.widthPixels, dm.heightPixels, dm.densityDpi, divisor, fps, quality, record)
        cfg = c
        outW = c.outWidth
        outH = c.outHeight

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mgr == null) {
            FrameBus.setState("系统不支持屏幕捕获")
            return false
        }
        projection = mgr.getMediaProjection(resultCode, data)
        if (projection == null) {
            // 用户点了「取消」，或令牌已被消耗。
            FrameBus.setState("投影授权失败（已取消或令牌失效）")
            return false
        }

        captureThread = HandlerThread("oreki-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        // 步骤 2：注册回调必须在 createVirtualDisplay 之前（Android 14 硬性要求）。
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection.onStop：系统收回投影")
                stopCapture("系统收回投影")
                stopSelf()
            }
        }
        projectionCallback = cb
        projection!!.registerCallback(cb, captureHandler)

        reader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 4)
        reader!!.setOnImageAvailableListener({ r -> onImageAvailable(r) }, captureHandler)

        virtualDisplay = projection!!.createVirtualDisplay(
            "oreki-frames", outW, outH, c.outDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, captureHandler,
        )

        if (virtualDisplay == null) {
            FrameBus.setState("VirtualDisplay 创建失败")
            releaseAll()
            return false
        }

        // 统计与总线初始化
        FrameBus.config = c
        FrameBus.stats.reset()
        FrameBus.stats.targetWidth = outW
        FrameBus.stats.targetHeight = outH
        FrameBus.stats.targetFps = fps
        frameId = 0
        lastStatMono = System.nanoTime()

        val meta = Json.obj()
            .put("tool", "Oreki Frame Observer")
            .put("version", "0.1.0")
            .put("started_iso", SessionLog.isoNow())
            .put("config", c.toJson())
            .put("time_base", "System.nanoTime / CLOCK_MONOTONIC (ns)")
            .end()
        SessionLog.openNewSession(File(getExternalFilesDir(null), ""), meta)

        running = true
        startSaver()
        FrameBus.capturing = true
        FrameBus.setState("采集中 · " + c.describe())
        updateNotification()
        Log.i(TAG, "capture started " + c.describe())
        return true
    }

    // ------------------------------------------------------------------ 采集线程

    private fun onImageAvailable(r: ImageReader) {
        val img: Image? = try {
            // acquireLatestImage 会自动丢弃并关闭比最新帧更旧的缓冲，
            // 这正是我们想要的行为：宁可丢中间帧，也要保证画面是「现在」。
            r.acquireLatestImage()
        } catch (_: IllegalStateException) {
            // maxImages 用尽（saver 一时没跟上的极端情况）。计一次丢帧，不中断。
            FrameBus.stats.framesDropped++
            return
        }
        if (img == null) return
        FrameBus.stats.framesAvailable++

        if (!running) {
            img.close()
            return
        }
        if (!queue.offer(img)) {
            // 队列满：丢最旧的，留最新的。
            queue.poll()?.let {
                it.close()
                FrameBus.stats.framesDropped++
            }
            if (!queue.offer(img)) {
                img.close()
                FrameBus.stats.framesDropped++
            }
        }
    }

    // ------------------------------------------------------------------ 编码线程

    private fun startSaver() {
        saveThread = Thread({
            while (running) {
                val img = try {
                    queue.poll(300, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                if (img == null) continue
                try {
                    handleFrame(img)
                } catch (t: Throwable) {
                    // 单帧失败绝不能拖垮整条流水线（例如某帧 rowStride 异常）。
                    FrameBus.stats.writeErrors++
                    Log.w(TAG, "frame failed: $t")
                } finally {
                    try {
                        img.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }, "oreki-save")
        saveThread!!.start()
    }

    private fun handleFrame(img: Image) {
        val nowNs = System.nanoTime()
        val srcNs = if (img.timestamp > 0) img.timestamp else nowNs
        val captureLatencyMs = (nowNs - srcNs) / 1_000_000.0

        var w = img.width
        var h = img.height
        if (w != outW || h != outH) {
            // 理论上不会变（VirtualDisplay 尺寸固定）；真变了就重建缓冲。
            outW = w
            outH = h
            reuseBitmap = null
            reusePixels = null
        }
        // 用局部非空类型变量承接：Kotlin 的智能转换不会跟踪 `var` 属性后续赋值，
        // 直接对 `pixels` 传参会退化成 IntArray? 并在编译期报类型不匹配。
        val pixels: IntArray
        var bmp = reuseBitmap
        if (bmp == null) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pixels = IntArray(w * h)
            reuseBitmap = bmp
            reusePixels = pixels
        } else {
            pixels = reusePixels ?: IntArray(w * h).also { reusePixels = it }
        }

        val t0 = System.nanoTime()
        val plane = img.planes[0]
        val buf = plane.buffer
        buf.rewind()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (pixelStride == 4 && rowStride == w * 4) {
            // 快路径：行间无 padding，整块拷进 Bitmap（ARGB_8888 在内存里是 RGBA 字节序，
            // 与 ImageReader 的 RGBA_8888 完全一致，不需要任何颜色通道交换）。
            bmp.copyPixelsFromBuffer(buf)
        } else {
            // 慢路径：按行取，跳过每行尾部的 padding。
            val ib = buf.asIntBuffer()
            val strideInts = rowStride / 4
            for (y in 0 until h) {
                ib.position(y * strideInts)
                ib.get(pixels, y * w, w)
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        baos.reset()
        bmp.compress(Bitmap.CompressFormat.JPEG, cfg!!.jpegQuality, baos)
        val jpeg = baos.toByteArray()

        val fid = ++frameId
        val dir = FrameBus.framesDir()
        var writeOk = false
        if (cfg!!.recordToDisk && dir != null && fid <= MAX_FRAMES) {
            val f = File(dir, String.format(Locale.US, "f_%06d.jpg", fid))
            try {
                FileOutputStream(f).use { it.write(jpeg) }
                writeOk = true
                FrameBus.stats.bytesWritten += jpeg.size.toLong()
            } catch (_: IOException) {
                FrameBus.stats.writeErrors++
            }
        }

        val t1 = System.nanoTime()
        val encodeMs = (t1 - t0) / 1_000_000.0
        // 只走 onFrameProcessed 自增，绝不在这里直接赋值 framesProcessed ——
        // 否则「赋值成 fid」+「再 ++」会让计数比真实处理数多 1，进而让丢帧率算错。
        FrameBus.stats.onFrameProcessed(nowNs, captureLatencyMs, encodeMs)

        // Frame Bridge：每帧一条记录，字段与 docs/接口说明.md 一一对应。
        SessionLog.frame(
            Json.obj()
                .put("frame_id", fid)
                .put("capture_timestamp_ns", nowNs)
                .put("source_timestamp_ns", srcNs)
                .put("wall_ms", System.currentTimeMillis())
                .put("width", w)
                .put("height", h)
                .put("pixel_format", "RGBA_8888")
                .put("row_stride", rowStride)
                .put("capture_latency_ms", captureLatencyMs)
                .put("encode_ms", encodeMs)
                .put("dropped_frame_count", FrameBus.stats.framesDropped)
                .put("jpeg_bytes", jpeg.size)
                .put("saved", writeOk)
                .end(),
        )

        if (FrameBus.previewFresh()) FrameBus.publishFrame(fid, jpeg, nowNs, srcNs)

        val now = System.nanoTime()
        if (now - lastStatMono > 5_000_000_000L) {
            lastStatMono = now
            val cpu = proc.sampleCpu()
            SessionLog.stat(
                Json.obj()
                    .put("t_mono_ns", now)
                    .put("actual_fps", FrameBus.stats.actualFps())
                    .put("target_fps", cfg!!.fps)
                    .put("frames_available", FrameBus.stats.framesAvailable)
                    .put("frames_processed", FrameBus.stats.framesProcessed)
                    .put("frames_dropped", FrameBus.stats.framesDropped)
                    .put("drop_rate_pct", FrameBus.stats.dropRate())
                    .put("cpu_pct", cpu)
                    .put("java_heap_mb", ProcStats.javaHeapBytes() / 1048576.0)
                    .put("pss_mb", ProcStats.nativeHeapBytes() / 1048576.0)
                    .put("threads", Thread.activeCount())
                    .end(),
            )
        }
    }

    // ------------------------------------------------------------------ 收尾

    private fun stopCapture(reason: String) {
        if (!running) return
        running = false
        FrameBus.capturing = false
        // 唤醒 saver 让它尽快退出
        var leftover = queue.poll()
        while (leftover != null) {
            leftover.close()
            leftover = queue.poll()
        }
        saveThread?.let { t ->
            t.interrupt()
            try {
                t.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        saveThread = null
        releaseAll()
        SessionLog.stat(
            Json.obj()
                .put("t_mono_ns", System.nanoTime())
                .put("final", true)
                .put("reason", reason)
                .put("actual_fps", FrameBus.stats.actualFps())
                .put("frames_processed", FrameBus.stats.framesProcessed)
                .put("frames_dropped", FrameBus.stats.framesDropped)
                .end(),
        )
        SessionLog.closeQuietly()
        FrameBus.setState("已停止：$reason")
        Log.i(TAG, "capture stopped: $reason frames=" + FrameBus.stats.framesProcessed)
        reuseBitmap?.recycle()
        reuseBitmap = null
        reusePixels = null
    }

    private fun releaseAll() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        projectionCallback = null
        captureThread?.let { t ->
            t.quitSafely()
            try {
                t.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        captureThread = null
        captureHandler = null
    }

    override fun onDestroy() {
        stopCapture("服务销毁")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val ch = NotificationChannel(CHANNEL, "画面采集", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.description = "Oreki Frame Observer 的屏幕采集前台服务"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val stop = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val pi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Oreki Frame Observer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", pi).build())
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val c = cfg
        val text = if (c == null) "准备中" else (if (c.recordToDisk) "录制中 · " else "仅预览 · ") + c.describe()
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "OrekiCapture"

        const val ACTION_START = "com.oreki.frameobserver.action.START"
        const val ACTION_STOP = "com.oreki.frameobserver.action.STOP"
        const val ACTION_TOGGLE_RECORD = "com.oreki.frameobserver.action.TOGGLE_RECORD"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DIVISOR = "divisor"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_RECORD = "record"

        private const val CHANNEL = "oreki_capture"
        private const val NOTIF_ID = 0x0F0B

        /** 队列容量。3 是在「编码跟不上」与「内存占用」之间的折中：最多同时持有 3 张 Image。 */
        private const val QUEUE_CAPACITY = 3

        /** 单会话最大帧数，防止无人值守录制把磁盘写满。 */
        private const val MAX_FRAMES = 20000
    }
}
