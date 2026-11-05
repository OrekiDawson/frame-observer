package com.oreki.frameobserver

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.oreki.frameobserver.net.FrameHttpServer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面。
 *
 * 职责刻意保持很薄：只做「申请授权 → 拉起服务 → 显示状态」，所有采集逻辑都在
 * [CaptureService]。这样即使 Activity 被系统回收，采集也不会中断。
 *
 * 预览解码放在独立的 `oreki-decode` 线程：JPEG 解码在 540×1170 上约 8-12ms，
 * 若放在主线程以 12.5Hz 执行会持续占用主线程 12% 以上，属于「采集阻塞 UI」。
 */
class MainActivity : Activity(), FrameBus.Listener {

    private lateinit var tvState: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvPaths: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRecord: Button
    private lateinit var btnSnapshot: Button
    private lateinit var btnA11y: Button
    private lateinit var spScale: Spinner
    private lateinit var spFps: Spinner
    private lateinit var spQuality: Spinner

    private val ui = Handler(Looper.getMainLooper())
    private var decodeHandler: Handler? = null
    private var decodeThread: HandlerThread? = null

    private val scaleVals = intArrayOf(1, 2, 3, 4)
    private val fpsVals = intArrayOf(60, 30, 15)
    private val qualityVals = intArrayOf(95, 80, 60)

    @Volatile
    private var decodedFrameId = -1L

    @Volatile
    private var decoding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvState = findViewById(R.id.tv_state)
        tvStats = findViewById(R.id.tv_stats)
        tvPaths = findViewById(R.id.tv_paths)
        ivPreview = findViewById(R.id.iv_preview)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnRecord = findViewById(R.id.btn_record)
        btnSnapshot = findViewById(R.id.btn_snapshot)
        btnA11y = findViewById(R.id.btn_a11y)
        spScale = findViewById(R.id.sp_scale)
        spFps = findViewById(R.id.sp_fps)
        spQuality = findViewById(R.id.sp_quality)

        decodeThread = HandlerThread("oreki-decode").also { it.start() }
        decodeHandler = Handler(decodeThread!!.looper)

        // 无障碍绑定自愈：`am force-stop` 会把 Settings 里的绑定清空，
        // 不写回去 /ui 永远 503。这里只试一次，失败不阻塞界面
        // （没授予 WRITE_SECURE_SETTINGS 时必然失败，状态会体现在 /stats 的 a11y 字段）。
        val bound = com.oreki.frameobserver.a11y.A11yBinder.ensureBound(this)
        Log.i(
            "OrekiA11y",
            "onCreate ensureBound=$bound state=" + com.oreki.frameobserver.a11y.A11yBinder.state(this),
        )

        setupSpinner(spScale, arrayOf("1× (原分辨率)", "1/2", "1/3", "1/4"), 1)
        setupSpinner(spFps, arrayOf("60 fps", "30 fps", "15 fps"), 0)
        setupSpinner(spQuality, arrayOf("质量 95", "质量 80", "质量 60"), 1)

        btnStart.setOnClickListener { requestProjection() }
        btnStop.setOnClickListener {
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP),
            )
        }
        btnRecord.setOnClickListener {
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_TOGGLE_RECORD),
            )
        }
        btnSnapshot.setOnClickListener { saveSnapshot() }
        btnA11y.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "请开启「Oreki Frame Observer 界面观测」", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "打不开无障碍设置：$e", Toast.LENGTH_LONG).show()
            }
        }

        // 本机 HTTP / WebSocket 服务，供外部工具读取实时画面。
        // init() 必须传 applicationContext：/capture/stop 要靠它发 Intent 真停服务。
        FrameHttpServer.get().init(applicationContext)
        FrameHttpServer.get().start(8731)

        FrameBus.listeners.add(this)
        refreshUi()
        ui.post(statsTick)
        requestNotifPermissionIfNeeded()
    }

    override fun onDestroy() {
        FrameBus.listeners.remove(this)
        ui.removeCallbacks(statsTick)
        decodeThread?.quitSafely()
        decodeThread = null
        decodeHandler = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 授权

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mgr == null) {
            Toast.makeText(this, "系统不支持屏幕捕获", Toast.LENGTH_LONG).show()
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
        } catch (e: Exception) {
            Toast.makeText(this, "无法申请授权：$e", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
        }
    }

    @Deprecated("Android 13 起推荐 ActivityResultLauncher，本工具保持旧 API 以兼容 minSdk 29")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "已取消屏幕捕获授权", Toast.LENGTH_SHORT).show()
            return
        }
        // 把授权令牌交给前台服务；服务内部会先 startForeground 再 getMediaProjection。
        val svc = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureService.EXTRA_DIVISOR, scaleVals[spScale.selectedItemPosition])
            .putExtra(CaptureService.EXTRA_FPS, fpsVals[spFps.selectedItemPosition])
            .putExtra(CaptureService.EXTRA_QUALITY, qualityVals[spQuality.selectedItemPosition])
            .putExtra(CaptureService.EXTRA_RECORD, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        ui.postDelayed({ refreshUi() }, 400)
    }

    // ---------------------------------------------------------------- 预览

    override fun onNewFrame(frameId: Long) {
        if (frameId <= decodedFrameId || decoding) return
        val jpeg = FrameBus.latestJpeg ?: return
        val handler = decodeHandler ?: return
        decoding = true
        handler.post {
            val bmp: Bitmap? = try {
                // inPreferredConfig 让解码在高分辨率下也保持低成本；预览不需要原图质量。
                val o = BitmapFactory.Options()
                o.inPreferredConfig = Bitmap.Config.RGB_565
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            } catch (_: Throwable) {
                null
            }
            decodedFrameId = frameId
            decoding = false
            if (bmp == null) return@post
            ui.post { ivPreview.setImageBitmap(bmp) }
        }
    }

    override fun onStateChanged(state: String) {
        ui.post { refreshUi() }
    }

    private fun saveSnapshot() {
        val jpeg = FrameBus.latestJpeg
        if (jpeg == null) {
            Toast.makeText(this, "还没有可用画面", Toast.LENGTH_SHORT).show()
            return
        }
        var dir = FrameBus.sessionDir
        dir = if (dir == null) File(getExternalFilesDir(null), "snapshots") else File(dir, "snapshots")
        dir.mkdirs()
        val name = "snap_" + SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date()) +
            "_f" + FrameBus.latestFrameId + ".jpg"
        val f = File(dir, name)
        try {
            FileOutputStream(f).use { it.write(jpeg) }
            Toast.makeText(this, "已保存 " + f.name, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：$e", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- 状态刷新

    private val statsTick = object : Runnable {
        override fun run() {
            tvStats.text = FrameBus.stats.toHuman() + "\n\n进程：" + ProcStats.human()
            refreshUi()
            ui.postDelayed(this, 500)
        }
    }

    private fun refreshUi() {
        val cap = FrameBus.capturing
        tvState.text = FrameBus.state
        btnStart.isEnabled = !cap
        btnStop.isEnabled = cap
        btnRecord.text = if (FrameBus.config?.recordToDisk == true) "录像：开" else "录像：关"
        tvPaths.text = FrameBus.pathsHuman() +
            "\n\n接口（先执行 adb forward tcp:8731 tcp:8731）：" +
            "\n  http://127.0.0.1:8731/           状态页" +
            "\n  http://127.0.0.1:8731/snapshot.jpg  当前帧" +
            "\n  http://127.0.0.1:8731/stats      统计" +
            "\n  ws://127.0.0.1:8731/ws           实时流"
    }

    private fun setupSpinner(sp: Spinner, labels: Array<String>, def: Int) {
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = a
        sp.setSelection(def)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    companion object {
        private const val REQ_PROJECTION = 1001
        private const val REQ_NOTIF = 1002
    }
}
