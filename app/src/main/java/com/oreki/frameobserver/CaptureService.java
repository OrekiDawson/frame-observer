package com.oreki.frameobserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.oreki.frameobserver.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 采集服务：MediaProjection → VirtualDisplay → ImageReader → JPEG。
 *
 * <p><b>Android 14 的三条硬约束</b>（任一条不满足都会抛异常，且报错信息不直观）：
 * <ol>
 *   <li>持有投影的服务必须以 {@code mediaProjection} 类型前台运行，且 {@code startForeground()}
 *       必须在 {@code getMediaProjection()} <b>之前</b>调用，否则 SecurityException。</li>
 *   <li>{@code createVirtualDisplay()} 之前必须 {@code registerCallback()}，否则
 *       IllegalStateException("Must register a callback before starting capture")。</li>
 *   <li>每轮投影授权只能用于一次捕获会话；{@code onStop()} 后令牌失效，必须重新向用户申请。</li>
 * </ol>
 *
 * <p><b>线程模型</b>（这是性能要求「不得阻塞 UI 主线程」的落点）：
 * <pre>
 *   主线程            startForeground / 建 VirtualDisplay
 *   captureThread     onImageAvailable → acquireLatestImage → 入有界队列（只做指针交接，零拷贝）
 *   saveThread        取 Image → 拷像素 → Bitmap → JPEG 编码 → 落盘 → close()
 * </pre>
 * 关键点：像素拷贝与 JPEG 编码全部发生在 saveThread，captureThread 上只有一次
 * {@code acquireLatestImage()} 和一次 {@code queue.offer()}（纳秒级）。
 * 队列容量 3 且<b>满时丢最旧</b>，保证内存有界、优先最新画面。
 */
public class CaptureService extends Service {

    private static final String TAG = "OrekiCapture";

    public static final String ACTION_START = "com.oreki.frameobserver.action.START";
    public static final String ACTION_STOP = "com.oreki.frameobserver.action.STOP";
    public static final String ACTION_TOGGLE_RECORD = "com.oreki.frameobserver.action.TOGGLE_RECORD";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_DIVISOR = "divisor";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_RECORD = "record";

    private static final String CHANNEL = "oreki_capture";
    private static final int NOTIF_ID = 0x0F0B;

    /** 队列容量。3 是在「编码跟不上」与「内存占用」之间的折中：最多同时持有 3 张 Image。 */
    private static final int QUEUE_CAPACITY = 3;
    /** 单会话最大帧数，防止无人值守录制把磁盘写满。 */
    private static final int MAX_FRAMES = 20000;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private Thread saveThread;

    private ImageReader reader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;

    private final ArrayBlockingQueue<Image> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running;

    private long frameId;
    private int outW;
    private int outH;
    private CaptureConfig cfg;

    // —— saveThread 独占的可复用缓冲，避免每帧分配 ——
    private Bitmap reuseBitmap;
    private int[] reusePixels;
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream(512 * 1024);

    private final ProcStats proc = new ProcStats();
    private long lastStatMono;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture("用户停止");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_RECORD.equals(action)) {
            if (cfg != null) {
                cfg.recordToDisk = !cfg.recordToDisk;
                if (FrameBus.config != null) {
                    FrameBus.config.recordToDisk = cfg.recordToDisk;
                }
                FrameBus.setState(cfg.recordToDisk ? "采集中（录像开）" : "采集中（仅预览）");
                updateNotification();
            }
            return START_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            FrameBus.setState("授权数据缺失，未开始");
            stopSelf();
            return START_NOT_STICKY;
        }

        // 步骤 1：必须先以 mediaProjection 类型进入前台（Android 14 硬性要求）。
        Notification n = buildNotification("正在启动采集…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        int divisor = intent.getIntExtra(EXTRA_DIVISOR, 2);
        int fps = intent.getIntExtra(EXTRA_FPS, 60);
        int quality = intent.getIntExtra(EXTRA_QUALITY, 80);
        boolean record = intent.getBooleanExtra(EXTRA_RECORD, true);

        if (!beginCapture(resultCode, resultData, divisor, fps, quality, record)) {
            stopSelf();
        }
        return START_STICKY;
    }

    private boolean beginCapture(int resultCode, Intent data, int divisor, int fps, int quality, boolean record) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            FrameBus.setState("拿不到 WindowManager");
            return false;
        }
        wm.getDefaultDisplay().getRealMetrics(dm);
        cfg = CaptureConfig.of(dm.widthPixels, dm.heightPixels, dm.densityDpi, divisor, fps, quality, record);
        outW = cfg.outWidth;
        outH = cfg.outHeight;

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            FrameBus.setState("系统不支持屏幕捕获");
            return false;
        }
        projection = mgr.getMediaProjection(resultCode, data);
        if (projection == null) {
            // 用户点了「取消」，或令牌已被消耗。
            FrameBus.setState("投影授权失败（已取消或令牌失效）");
            return false;
        }

        captureThread = new HandlerThread("oreki-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        // 步骤 2：注册回调必须在 createVirtualDisplay 之前（Android 14 硬性要求）。
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection.onStop：系统收回投影");
                stopCapture("系统收回投影");
                stopSelf();
            }
        };
        projection.registerCallback(projectionCallback, captureHandler);

        reader = ImageReader.newInstance(outW, outH, android.graphics.PixelFormat.RGBA_8888, 4);
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "oreki-frames", outW, outH, cfg.outDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, captureHandler);

        if (virtualDisplay == null) {
            FrameBus.setState("VirtualDisplay 创建失败");
            releaseAll();
            return false;
        }

        // 统计与总线初始化
        FrameBus.config = cfg;
        FrameBus.stats.reset();
        FrameBus.stats.targetWidth = outW;
        FrameBus.stats.targetHeight = outH;
        FrameBus.stats.targetFps = fps;
        frameId = 0;
        lastStatMono = System.nanoTime();

        String meta = Json.obj()
                .put("tool", "Oreki Frame Observer")
                .put("version", "0.1.0")
                .put("started_iso", SessionLog.isoNow())
                .put("config", cfg.toJson())
                .put("time_base", "System.nanoTime / CLOCK_MONOTONIC (ns)")
                .end();
        SessionLog.openNewSession(new File(getExternalFilesDir(null), ""), meta);

        running = true;
        startSaver();
        FrameBus.capturing = true;
        FrameBus.setState("采集中 · " + cfg.describe());
        updateNotification();
        Log.i(TAG, "capture started " + cfg.describe());
        return true;
    }

    // ------------------------------------------------------------------ 采集线程

    private void onImageAvailable(ImageReader r) {
        Image img;
        try {
            // acquireLatestImage 会**自动丢弃并关闭**比最新帧更旧的缓冲，
            // 这正是我们想要的行为：宁可丢中间帧，也要保证画面是「现在」。
            img = r.acquireLatestImage();
        } catch (IllegalStateException e) {
            // maxImages 用尽（saver 一时没跟上的极端情况）。计一次丢帧，不中断。
            FrameBus.stats.framesDropped++;
            return;
        }
        if (img == null) {
            return;
        }
        FrameBus.stats.framesAvailable++;

        if (!running) {
            img.close();
            return;
        }
        if (!queue.offer(img)) {
            // 队列满：丢**最旧**的，留最新的（性能要求第 3 条）。
            Image old = queue.poll();
            if (old != null) {
                old.close();
                FrameBus.stats.framesDropped++;
            }
            if (!queue.offer(img)) {
                img.close();
                FrameBus.stats.framesDropped++;
            }
        }
    }

    // ------------------------------------------------------------------ 编码线程

    private void startSaver() {
        saveThread = new Thread(() -> {
            while (running) {
                Image img;
                try {
                    img = queue.poll(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (img == null) {
                    continue;
                }
                try {
                    handleFrame(img);
                } catch (Throwable t) {
                    // 单帧失败绝不能拖垮整条流水线（例如某帧 rowStride 异常）。
                    FrameBus.stats.writeErrors++;
                    Log.w(TAG, "frame failed: " + t);
                } finally {
                    try {
                        img.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "oreki-save");
        saveThread.start();
    }

    private void handleFrame(Image img) {
        final long nowNs = System.nanoTime();
        final long srcNs = img.getTimestamp() > 0 ? img.getTimestamp() : nowNs;
        final double captureLatencyMs = (nowNs - srcNs) / 1_000_000d;

        int w = img.getWidth();
        int h = img.getHeight();
        if (w != outW || h != outH) {
            // 理论上不会变（VirtualDisplay 尺寸固定）；真变了就重建缓冲。
            outW = w;
            outH = h;
            reuseBitmap = null;
            reusePixels = null;
        }
        if (reuseBitmap == null) {
            reuseBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            reusePixels = new int[w * h];
        }

        long t0 = System.nanoTime();
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        buf.rewind();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        if (pixelStride == 4 && rowStride == w * 4) {
            // 快路径：行间无 padding，整块拷进 Bitmap（ARGB_8888 在内存里是 RGBA 字节序，
            // 与 ImageReader 的 RGBA_8888 完全一致，不需要任何颜色通道交换）。
            reuseBitmap.copyPixelsFromBuffer(buf);
        } else {
            // 慢路径：按行取，跳过每行尾部的 padding。
            IntBuffer ib = buf.asIntBuffer();
            int strideInts = rowStride / 4;
            for (int y = 0; y < h; y++) {
                ib.position(y * strideInts);
                ib.get(reusePixels, y * w, w);
            }
            reuseBitmap.setPixels(reusePixels, 0, w, 0, 0, w, h);
        }

        baos.reset();
        reuseBitmap.compress(Bitmap.CompressFormat.JPEG, cfg.jpegQuality, baos);
        byte[] jpeg = baos.toByteArray();

        long fid = ++frameId;
        File dir = FrameBus.framesDir();
        boolean writeOk = false;
        long bytes = 0;
        if (cfg.recordToDisk && dir != null && fid <= MAX_FRAMES) {
            File f = new File(dir, String.format(java.util.Locale.US, "f_%06d.jpg", fid));
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(jpeg);
                writeOk = true;
                bytes = jpeg.length;
                FrameBus.stats.bytesWritten += bytes;
            } catch (IOException e) {
                FrameBus.stats.writeErrors++;
            }
        }

        long t1 = System.nanoTime();
        double encodeMs = (t1 - t0) / 1_000_000d;
        // 只走 onFrameProcessed 自增，绝不在这里直接赋值 framesProcessed ——
        // 否则「赋值成 fid」+「再 ++」会让计数比真实处理数多 1，进而让丢帧率算错。
        FrameBus.stats.onFrameProcessed(nowNs, captureLatencyMs, encodeMs);

        // Frame Bridge：每帧一条记录，字段与 docs/接口说明 一一对应。
        SessionLog.frame(Json.obj()
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
                .put("jpeg_bytes", jpeg.length)
                .put("saved", writeOk)
                .end());

        if (FrameBus.previewFresh()) {
            FrameBus.publishFrame(fid, jpeg, nowNs, srcNs);
        }

        long now = System.nanoTime();
        if (now - lastStatMono > 5_000_000_000L) {
            lastStatMono = now;
            double cpu = proc.sampleCpu();
            SessionLog.stat(Json.obj()
                    .put("t_mono_ns", now)
                    .put("actual_fps", FrameBus.stats.actualFps())
                    .put("target_fps", cfg.fps)
                    .put("frames_available", FrameBus.stats.framesAvailable)
                    .put("frames_processed", FrameBus.stats.framesProcessed)
                    .put("frames_dropped", FrameBus.stats.framesDropped)
                    .put("drop_rate_pct", FrameBus.stats.dropRate())
                    .put("cpu_pct", cpu)
                    .put("java_heap_mb", ProcStats.javaHeapBytes() / 1048576d)
                    .put("pss_mb", ProcStats.nativeHeapBytes() / 1048576d)
                    .put("threads", Thread.activeCount())
                    .end());
        }
    }

    // ------------------------------------------------------------------ 收尾

    private void stopCapture(String reason) {
        if (!running) {
            return;
        }
        running = false;
        FrameBus.capturing = false;
        // 唤醒 saver 让它尽快退出
        Image leftover = queue.poll();
        while (leftover != null) {
            leftover.close();
            leftover = queue.poll();
        }
        if (saveThread != null) {
            saveThread.interrupt();
            try {
                saveThread.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            saveThread = null;
        }
        releaseAll();
        SessionLog.stat(Json.obj()
                .put("t_mono_ns", System.nanoTime())
                .put("final", true)
                .put("reason", reason)
                .put("actual_fps", FrameBus.stats.actualFps())
                .put("frames_processed", FrameBus.stats.framesProcessed)
                .put("frames_dropped", FrameBus.stats.framesDropped)
                .end());
        SessionLog.closeQuietly();
        FrameBus.setState("已停止：" + reason);
        Log.i(TAG, "capture stopped: " + reason + " frames=" + FrameBus.stats.framesProcessed);
        if (reuseBitmap != null) {
            reuseBitmap.recycle();
            reuseBitmap = null;
            reusePixels = null;
        }
    }

    private void releaseAll() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignored) {
            }
            virtualDisplay = null;
        }
        if (reader != null) {
            try {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
            } catch (Exception ignored) {
            }
            reader = null;
        }
        if (projection != null) {
            try {
                if (projectionCallback != null) {
                    projection.unregisterCallback(projectionCallback);
                }
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
            projectionCallback = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            try {
                captureThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
            captureHandler = null;
        }
    }

    @Override
    public void onDestroy() {
        stopCapture("服务销毁");
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 通知

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "画面采集", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription("Oreki Frame Observer 的屏幕采集前台服务");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Oreki Frame Observer")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止", pi).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        String text = cfg == null ? "准备中" : (cfg.recordToDisk ? "录制中 · " : "仅预览 · ") + cfg.describe();
        nm.notify(NOTIF_ID, buildNotification(text));
    }
}
