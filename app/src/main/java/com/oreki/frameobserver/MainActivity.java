package com.oreki.frameobserver;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.oreki.frameobserver.net.FrameHttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 主界面。
 *
 * <p>职责刻意保持很薄：只做「申请授权 → 拉起服务 → 显示状态」，所有采集逻辑都在
 * {@link CaptureService}。这样即使 Activity 被系统回收，采集也不会中断。
 *
 * <p>预览解码放在独立的 {@code oreki-decode} 线程：JPEG 解码在 540×1170 上约 8-12ms，
 * 若放在主线程以 12.5Hz 执行会持续占用主线程 12% 以上，属于「采集阻塞 UI」。
 */
public class MainActivity extends Activity implements FrameBus.Listener {

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_NOTIF = 1002;

    private TextView tvState, tvStats, tvPaths;
    private ImageView ivPreview;
    private Button btnStart, btnStop, btnRecord, btnSnapshot, btnA11y;
    private Spinner spScale, spFps, spQuality;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Handler decodeHandler;
    private HandlerThread decodeThread;

    private final int[] scaleVals = {1, 2, 3, 4};
    private final int[] fpsVals = {60, 30, 15};
    private final int[] qualityVals = {95, 80, 60};

    private volatile long decodedFrameId = -1;
    private volatile boolean decoding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvState = findViewById(R.id.tv_state);
        tvStats = findViewById(R.id.tv_stats);
        tvPaths = findViewById(R.id.tv_paths);
        ivPreview = findViewById(R.id.iv_preview);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnRecord = findViewById(R.id.btn_record);
        btnSnapshot = findViewById(R.id.btn_snapshot);
        btnA11y = findViewById(R.id.btn_a11y);
        spScale = findViewById(R.id.sp_scale);
        spFps = findViewById(R.id.sp_fps);
        spQuality = findViewById(R.id.sp_quality);

        decodeThread = new HandlerThread("oreki-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        // 无障碍绑定自愈：`am force-stop` 会把 Settings 里的绑定清空，
        // 不写回去 /ui 永远 503。这里只试一次，失败不阻塞界面
        // （没授予 WRITE_SECURE_SETTINGS 时必然失败，状态会体现在 /stats 的 a11y 字段）。
        final boolean bound = com.oreki.frameobserver.a11y.A11yBinder.ensureBound(this);
        android.util.Log.i("OrekiA11y", "onCreate ensureBound=" + bound
                + " state=" + com.oreki.frameobserver.a11y.A11yBinder.state(this));

        setupSpinner(spScale, new String[]{"1× (原分辨率)", "1/2", "1/3", "1/4"}, 1);
        setupSpinner(spFps, new String[]{"60 fps", "30 fps", "15 fps"}, 0);
        setupSpinner(spQuality, new String[]{"质量 95", "质量 80", "质量 60"}, 1);

        btnStart.setOnClickListener(v -> requestProjection());
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP);
            startService(i);
        });
        btnRecord.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_TOGGLE_RECORD);
            startService(i);
        });
        btnSnapshot.setOnClickListener(v -> saveSnapshot());
        btnA11y.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "请开启「Oreki Frame Observer 界面观测」", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "打不开无障碍设置：" + e, Toast.LENGTH_LONG).show();
            }
        });

        // 本机 HTTP / WebSocket 服务，供 WorkBuddy 读取实时画面。
        // init() 必须传 applicationContext：/capture/stop 要靠它发 Intent 真停服务。
        FrameHttpServer.get().init(getApplicationContext());
        FrameHttpServer.get().start(8731);

        FrameBus.listeners.add(this);
        refreshUi();
        ui.post(statsTick);
        requestNotifPermissionIfNeeded();
    }

    @Override
    protected void onDestroy() {
        FrameBus.listeners.remove(this);
        ui.removeCallbacks(statsTick);
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 授权

    private void requestProjection() {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            Toast.makeText(this, "系统不支持屏幕捕获", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            Toast.makeText(this, "无法申请授权：" + e, Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "已取消屏幕捕获授权", Toast.LENGTH_SHORT).show();
            return;
        }
        // 把授权令牌交给前台服务；服务内部会先 startForeground 再 getMediaProjection。
        Intent svc = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_DIVISOR, scaleVals[spScale.getSelectedItemPosition()])
                .putExtra(CaptureService.EXTRA_FPS, fpsVals[spFps.getSelectedItemPosition()])
                .putExtra(CaptureService.EXTRA_QUALITY, qualityVals[spQuality.getSelectedItemPosition()])
                .putExtra(CaptureService.EXTRA_RECORD, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        ui.postDelayed(this::refreshUi, 400);
    }

    // ---------------------------------------------------------------- 预览

    @Override
    public void onNewFrame(long frameId) {
        if (frameId <= decodedFrameId || decoding) {
            return;
        }
        final byte[] jpeg = FrameBus.latestJpeg;
        final long id = frameId;
        if (jpeg == null || decodeHandler == null) {
            return;
        }
        decoding = true;
        decodeHandler.post(() -> {
            Bitmap bmp = null;
            try {
                // inSampleSize 让解码在高分辨率下也保持低成本；预览不需要原图质量。
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            } catch (Throwable ignored) {
            }
            final Bitmap out = bmp;
            decodedFrameId = id;
            decoding = false;
            if (out == null) {
                return;
            }
            ui.post(() -> {
                ivPreview.setImageBitmap(out);
            });
        });
    }

    @Override
    public void onStateChanged(String state) {
        ui.post(this::refreshUi);
    }

    private void saveSnapshot() {
        byte[] jpeg = FrameBus.latestJpeg;
        if (jpeg == null) {
            Toast.makeText(this, "还没有可用画面", Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = FrameBus.sessionDir;
        if (dir == null) {
            dir = new File(getExternalFilesDir(null), "snapshots");
        } else {
            dir = new File(dir, "snapshots");
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String name = "snap_" + new SimpleDateFormat("HHmmss_SSS", Locale.US).format(new Date())
                + "_f" + FrameBus.latestFrameId + ".jpg";
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(jpeg);
            Toast.makeText(this, "已保存 " + f.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- 状态刷新

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            tvStats.setText(FrameBus.stats.toHuman() + "\n\n进程：" + ProcStats.human());
            refreshUi();
            ui.postDelayed(this, 500);
        }
    };

    private void refreshUi() {
        boolean cap = FrameBus.capturing;
        tvState.setText(FrameBus.state);
        btnStart.setEnabled(!cap);
        btnStop.setEnabled(cap);
        btnRecord.setText(FrameBus.config != null && FrameBus.config.recordToDisk ? "录像：开" : "录像：关");
        tvPaths.setText(FrameBus.pathsHuman()
                + "\n\nWorkBuddy 接口（先执行 adb forward tcp:8731 tcp:8731）："
                + "\n  http://127.0.0.1:8731/           状态页"
                + "\n  http://127.0.0.1:8731/snapshot.jpg  当前帧"
                + "\n  http://127.0.0.1:8731/stats      统计"
                + "\n  ws://127.0.0.1:8731/ws           实时流");
    }

    private void setupSpinner(Spinner sp, String[] labels, int def) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(def);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
}
