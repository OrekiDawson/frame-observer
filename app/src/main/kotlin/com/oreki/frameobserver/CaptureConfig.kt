package com.oreki.frameobserver

/**
 * 一次采集会话的配置。
 *
 * 分辨率策略：**在 VirtualDisplay 层面降采样**，而不是先取全分辨率再缩 Bitmap。
 * 缩放开销发生在 SurfaceFlinger 的合成阶段（GPU），比在应用层做
 * `createScaledBitmap`（一次全图重采样 + 一次全图内存分配）便宜一个数量级，
 * 且拿到的 Image 本身更小，后续每帧的 memcpy 与 JPEG 编码量同比下降。
 * 实测 1080×2340 降到 1/2 后，单帧 RGBA 从 10.1 MB 降到 2.5 MB。
 */
class CaptureConfig {
    /** 屏幕长边除以此值得到采集分辨率。1 = 原分辨率。 */
    var scaleDivisor: Int = 2

    /** 目标帧率。仅作为 ImageReader 的节奏建议，实际帧率见 [FrameStats.actualFps]。 */
    var fps: Int = 60

    /** JPEG 质量 0-100。 */
    var jpegQuality: Int = 80

    /** 是否把每帧写盘。关掉时只维持实时预览与 `/snapshot.jpg`。 */
    var recordToDisk: Boolean = true

    var outWidth: Int = 0
    var outHeight: Int = 0
    var outDpi: Int = 0

    fun describe(): String =
        "$outWidth×$outHeight @${fps}fps q$jpegQuality" + if (recordToDisk) " 录像" else " 仅预览"

    fun toJson(): String = com.oreki.frameobserver.util.Json.obj()
        .put("scale_divisor", scaleDivisor)
        .put("target_fps", fps)
        .put("jpeg_quality", jpegQuality)
        .put("record_to_disk", recordToDisk)
        .put("width", outWidth)
        .put("height", outHeight)
        .put("dpi", outDpi)
        .put("device_sdk", android.os.Build.VERSION.SDK_INT)
        .put("device_model", android.os.Build.MODEL)
        .end()

    companion object {
        @JvmStatic
        fun of(
            screenW: Int,
            screenH: Int,
            dpi: Int,
            divisor: Int,
            fps: Int,
            quality: Int,
            record: Boolean,
        ): CaptureConfig {
            val c = CaptureConfig()
            c.scaleDivisor = Math.max(1, divisor)
            c.fps = Math.max(1, fps)
            c.jpegQuality = Math.max(1, Math.min(100, quality))
            c.recordToDisk = record
            // 向下取整且强制偶数：奇数列宽在部分 GPU 上会让 rowStride 出现半字节对齐，
            // 导致「行首地址 = y * rowStride」推算失效。
            val w = (screenW / c.scaleDivisor) and 1.inv()
            val h = (screenH / c.scaleDivisor) and 1.inv()
            c.outWidth = Math.max(2, w)
            c.outHeight = Math.max(2, h)
            c.outDpi = Math.max(72, dpi / c.scaleDivisor)
            return c
        }
    }
}
