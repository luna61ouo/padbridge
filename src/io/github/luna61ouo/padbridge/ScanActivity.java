package io.github.luna61ouo.padbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * App 內建的「掃 QR 加電腦」：小米等手機的相機掃到 padbridge:// 只會顯示文字、不會打開 App，所以自己掃。
 * 相機預覽（舊 Camera API，夠用、不用另外的函式庫）＋ ZXing core 解碼；只認 padbridge://pair，掃到就交給 MainActivity。
 */
@SuppressWarnings("deprecation")
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera cam;
    private SurfaceView sv;
    private HandlerThread worker;
    private Handler wh;
    private volatile boolean busy, done;
    private final MultiFormatReader reader = new MultiFormatReader();
    private TextView hint;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class);
        h.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        h.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(h);
        FrameLayout root = new FrameLayout(this);
        sv = new SurfaceView(this);
        root.addView(sv);
        hint = new TextView(this);
        hint.setText("對準電腦上的 Padbridge 圖示的配對 QR");
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(18);
        hint.setBackgroundColor(0x88000000);
        hint.setPadding(32, 24, 32, 24);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 160;
        root.addView(hint, lp);
        setContentView(root);
        sv.getHolder().addCallback(this);
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 7);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else { hint.setText("沒有相機權限，沒辦法掃。可以改用電腦上顯示的配對碼手動輸入。"); }
    }

    @Override protected void onResume() {
        super.onResume();
        worker = new HandlerThread("qr-decode"); worker.start(); wh = new Handler(worker.getLooper());
        if (sv.getHolder().getSurface().isValid()) openCamera();
    }

    @Override protected void onPause() {
        closeCamera();
        if (worker != null) { worker.quitSafely(); worker = null; }
        super.onPause();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { openCamera(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { closeCamera(); }

    private void openCamera() {
        if (cam != null || done) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (!sv.getHolder().getSurface().isValid()) return;
        try {
            int id = 0;
            Camera.CameraInfo ci = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) { Camera.getCameraInfo(i, ci); if (ci.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; } }
            cam = Camera.open(id);
            Camera.Parameters p = cam.getParameters();
            if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            Camera.Size best = null;   // 挑接近 1280×720 的預覽大小：夠清楚、解碼又快
            for (Camera.Size s : p.getSupportedPreviewSizes())
                if (best == null || Math.abs(s.width * s.height - 921600) < Math.abs(best.width * best.height - 921600)) best = s;
            if (best != null) p.setPreviewSize(best.width, best.height);
            cam.setParameters(p);
            cam.setDisplayOrientation(90);
            cam.setPreviewDisplay(sv.getHolder());
            cam.setPreviewCallback(this);
            cam.startPreview();
        } catch (Exception e) {
            hint.setText("相機打不開：" + e.getMessage());
            closeCamera();
        }
    }

    private void closeCamera() {
        Camera c = cam; cam = null;
        if (c != null) { try { c.setPreviewCallback(null); c.stopPreview(); } catch (Exception ignored) {} c.release(); }
    }

    @Override public void onPreviewFrame(final byte[] data, Camera c) {
        if (busy || done || wh == null) return;
        busy = true;
        final Camera.Size s;
        try { s = c.getParameters().getPreviewSize(); } catch (Exception e) { busy = false; return; }
        wh.post(() -> {
            try {
                PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, s.width, s.height, 0, 0, s.width, s.height, false);
                Result r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
                final String text = r.getText();
                runOnUiThread(() -> onText(text));
            } catch (Exception ignored) {
            } finally { reader.reset(); busy = false; }
        });
    }

    private void onText(String text) {
        if (done) return;
        if (text == null || !text.startsWith("padbridge://pair")) { hint.setText("這不是 Padbridge 的配對 QR"); return; }
        done = true;
        closeCamera();
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(text)).setClass(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
