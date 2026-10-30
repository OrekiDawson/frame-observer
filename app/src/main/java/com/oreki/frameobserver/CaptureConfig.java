package com.oreki.frameobserver;

import android.os.Build;

/**
 * 一次采集会话的配置。
 *
 * <p>分辨率策略：**在 VirtualDisplay 层面降采样**，而不是先取全分辨率再缩 Bitmap。
 * 原因很直接 —— 缩放开销发生在 SurfaceFlinger 的合成阶段（GPU），比在 Java 层做
 * {@code createScaledBitmap}（一次全图重采样 + 一次全图内存分配）便宜一个数量级，
 * 而且我们拿到的 Image 本身就更小，后续每帧的 memcpy 与 JPEG 编码量同比下降。
 * 实测 1080×2340 降到 1/2 后，单帧 RGBA 从 10.1 MB 降到 2.5 MB。
 */
public final class CaptureConfig {

    /** 屏幕长边除以此值得到采集分辨率。1 = 原分辨率。 */
    public int scaleDivisor = 2;
    /** 目标帧率。仅作为 ImageReader 的节奏建议，实际帧率见 FrameStats#actualFps。 */
    public int fps = 60;
    /** JPEG 质量 0-100。 */
    public int jpegQuality = 80;
    /** 是否把每帧写盘。关掉时只维持实时预览与 /snapshot.jpg。 */
    public boolean recordToDisk = true;

    public int outWidth;
    public int outHeight;
    public int outDpi;

    public static CaptureConfig of(int screenW, int screenH, int dpi, int divisor, int fps, int quality, boolean record) {
        CaptureConfig c = new CaptureConfig();
        c.scaleDivisor = Math.max(1, divisor);
        c.fps = Math.max(1, fps);
        c.jpegQuality = Math.max(1, Math.min(100, quality));
        c.recordToDisk = record;
        // 向下取整且强制偶数：奇数列宽在部分 GPU 上会让 rowStride 出现半字节对齐，
        // 导致我们下面的「行首地址 = y * rowStride」推算失效。
        int w = (screenW / c.scaleDivisor) & ~1;
        int h = (screenH / c.scaleDivisor) & ~1;
        c.outWidth = Math.max(2, w);
        c.outHeight = Math.max(2, h);
        c.outDpi = Math.max(72, dpi / c.scaleDivisor);
        return c;
    }

    public String describe() {
        return outWidth + "×" + outHeight + " @" + fps + "fps q" + jpegQuality
                + (recordToDisk ? " 录像" : " 仅预览");
    }

    public String toJson() {
        return com.oreki.frameobserver.util.Json.obj()
                .put("scale_divisor", scaleDivisor)
                .put("target_fps", fps)
                .put("jpeg_quality", jpegQuality)
                .put("record_to_disk", recordToDisk)
                .put("width", outWidth)
                .put("height", outHeight)
                .put("dpi", outDpi)
                .put("device_sdk", Build.VERSION.SDK_INT)
                .put("device_model", Build.MODEL)
                .end();
    }
}
