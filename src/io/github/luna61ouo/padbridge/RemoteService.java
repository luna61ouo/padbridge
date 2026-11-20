package io.github.luna61ouo.padbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 被控端（Android）：讓別支手機的 Padbridge 控制這台 Android（例如車機）。協定跟 Windows 接收程式一樣（UDP 47801、
 * LMD/LMH 找電腦、v2 簽章封包、流水號防重播），所以控制端不用改，這台會出現在「選電腦」清單裡。
 *
 * Android 不給一般 App 直接送滑鼠訊號，所以用無障礙服務：
 *  - 畫面上疊一個游標（TYPE_ACCESSIBILITY_OVERLAY，不用額外的懸浮窗權限），觸控板移動它
 *  - 左鍵＝在游標位置點一下（按久＝長按）；按住拖曳＝放開後沿路徑滑過去（第一版不是即時跟手）
 *  - 滾輪＝上下滑；右鍵＝返回；中鍵＝回主畫面
 *  - 繪圖板（a 封包）＝游標跳到那個位置，按下／放開同左鍵
 *  - 繪圖板預覽（f 封包）＝takeScreenshot（API 30+，系統限制約每 1/3 秒一張）→ 裁、縮、JPEG → 跟 Windows 端
 *    同一套 PBF2 加密分段回到發 f 的位置（格式見 windows/PadbridgeFrames.cs）
 * 配對碼存在 prefs「remote」：tokens（id → 配對碼 JSON）、pcId、name。第一版用 adb 設（MouseReceiver op=rxtoken）。
 */
public class RemoteService extends AccessibilityService {
    static final String TAG = "PadbridgeRx";
    static final int PORT = 47801;
    static volatile RemoteService instance;

    private SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile DatagramSocket sock;
    private final Map<String, Long> lastCtr = new HashMap<>();
    private String lastId;

    // 游標
    private WindowManager wm;
    private View cursor;
    private WindowManager.LayoutParams clp;
    private float cx, cy;
    private int scrW = 1280, scrH = 720;
    private final Runnable hideCursor = () -> { if (cursor != null) cursor.setVisibility(View.GONE); };

    // 左鍵按住中：記錄路徑，放開時決定是點、長按還是滑
    private boolean leftDown;
    private long downAt;
    private final List<float[]> path = new ArrayList<>();

    @Override protected void onServiceConnected() {
        instance = this;
        sp = getSharedPreferences("remote", MODE_PRIVATE);
        if (sp.getString("pcId", "").isEmpty()) {
            byte[] r = new byte[8]; new java.security.SecureRandom().nextBytes(r);
            StringBuilder b = new StringBuilder(); for (byte x : r) b.append(String.format("%02x", x));
            sp.edit().putString("pcId", b.toString()).apply();
        }
        wm = getSystemService(WindowManager.class);
        Rect bd = wm.getCurrentWindowMetrics().getBounds();
        scrW = bd.width(); scrH = bd.height();
        cx = scrW / 2f; cy = scrH / 2f;
        addCursor();
        startListener();
        Log.i(TAG, "remote service on " + scrW + "x" + scrH);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        instance = null;
        frameReq = null;
        if (frameThread != null) frameThread.quitSafely();
        DatagramSocket s = sock; sock = null;
        if (s != null) s.close();
        try { if (cursor != null) wm.removeView(cursor); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------- 配對碼 ----------
    Map<String, String> tokens() {
        Map<String, String> m = new HashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(sp.getString("tokens", "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.getString(k)); }
        } catch (Exception ignored) {}
        return m;
    }
    static void putToken(Context c, String id, String token) {
        SharedPreferences p = c.getSharedPreferences("remote", MODE_PRIVATE);
        try {
            org.json.JSONObject o = new org.json.JSONObject(p.getString("tokens", "{}"));
            if (token == null || token.isEmpty()) o.remove(id); else o.put(id, token);
            p.edit().putString("tokens", o.toString()).apply();
        } catch (Exception ignored) {}
    }
    static String info(Context c) {
        SharedPreferences p = c.getSharedPreferences("remote", MODE_PRIVATE);
        RemoteService i = instance;
        return "running=" + (i != null) + (i != null ? " framesSent=" + i.framesSent + " frameErr=" + i.frameErr : "") + " pcId=" + p.getString("pcId", "") + " tokens=" + p.getString("tokens", "{}").replaceAll("\"[0-9a-f]{16,}\"", "\"…\"");
    }

    // ---------- 收封包 ----------
    private void startListener() {
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(PORT));
                sock = s;
                byte[] buf = new byte[2048];
                while (sock == s) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    try { handle(s, p, text); } catch (Exception e) { Log.w(TAG, "handle " + e); }
                }
            } catch (Exception e) { Log.w(TAG, "listener " + e); }
        }, "padbridge-rx").start();
    }

    private void handle(DatagramSocket s, DatagramPacket p, String text) throws Exception {
        String[] f = text.split("\\|", -1);
        if ("LMD".equals(f[0]) && f.length >= 2 && f[1].length() <= 32) {
            // 有手機在找電腦：回身分＋每把配對碼各一個證明（手機拿自己那把對得上任何一個才認）
            String pcId = sp.getString("pcId", "");
            StringBuilder proofs = new StringBuilder();
            for (String t : tokens().values()) { if (proofs.length() > 0) proofs.append(','); proofs.append(PcList.hmac(t, f[1] + "|" + pcId)); }
            String name = sp.getString("name", "").replace("|", " ");
            String reply = "LMH|" + pcId + "|" + android.os.Build.MODEL.replace("|", " ") + "|" + name + "|" + scrW + "x" + scrH + "@0,0*|" + proofs + "|a" + Updates.myVersion(this);   // 第 7 欄 a＝Android 被控端（跟著 App 更新，不走電腦端更新）
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(b, b.length, p.getAddress(), p.getPort()));
            return;
        }
        String id = verify(text);
        if (id == null) return;
        if (f.length >= 9 && "f".equals(f[2])) { frameRequest(f, id, p.getAddress(), p.getPort()); return; }
        final String[] g = f;
        main.post(() -> command(g));
    }

    /** 簽章對、流水號比這支手機上一包大、跟這台時間差 10 分鐘內 → 回那支手機的 id；否則 null。 */
    private String verify(String text) {
        int i = text.lastIndexOf('|');
        if (i < 0 || !text.startsWith("v2|")) return null;
        String body = text.substring(0, i), sig = text.substring(i + 1);
        Map<String, String> ts = tokens();
        List<String> order = new ArrayList<>();
        if (lastId != null && ts.containsKey(lastId)) order.add(lastId);
        for (String k : ts.keySet()) if (!k.equals(lastId)) order.add(k);
        for (String k : order) {
            if (!PcList.hmac(ts.get(k), body).equals(sig)) continue;
            long ctr;
            try { ctr = Long.parseLong(body.split("\\|")[1]); } catch (Exception e) { return null; }
            Long last = lastCtr.get(k);
            long nowUs = System.currentTimeMillis() * 1000;
            if ((last != null && ctr <= last) || Math.abs(ctr - nowUs) > 600_000_000L) { Log.w(TAG, "replay/clock"); return null; }
            lastCtr.put(k, ctr);
            lastId = k;
            return k;
        }
        return null;
    }

    // ---------- 指令（主執行緒）----------
    private void command(String[] f) {
        if (f.length < 4) return;
        switch (f[2]) {
            case "m": moveBy(parse(f[3]), parse(f[4])); break;
            case "w": wheel(parse(f[3])); break;
            case "b": button(parse(f[3]), "1".equals(f[4])); break;
            case "a": {
                cx = Math.max(0, Math.min(scrW - 1, parse(f[3]) / 65535f * (scrW - 1)));
                cy = Math.max(0, Math.min(scrH - 1, parse(f[4]) / 65535f * (scrH - 1)));
                showCursor();
                if (leftDown) path.add(new float[]{cx, cy});
                if ("1".equals(f[5])) button(1, true); else if ("0".equals(f[5])) button(1, false);
                break;
            }
            case "s": if (f.length >= 6 && !f[4].isEmpty()) sp.edit().putString("phone_" + lastId, f[4]).apply(); break;
            case "n": sp.edit().putString("name", f[3].replace("|", " ").trim()).apply(); break;
        }
    }
    private static int parse(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

    private void moveBy(int dx, int dy) {
        cx = Math.max(0, Math.min(scrW - 1, cx + dx));
        cy = Math.max(0, Math.min(scrH - 1, cy + dy));
        if (leftDown) path.add(new float[]{cx, cy});
        showCursor();
    }

    private void button(int btn, boolean down) {
        if (btn == 2) { if (down) performGlobalAction(GLOBAL_ACTION_BACK); return; }
        if (btn == 4) { if (down) performGlobalAction(GLOBAL_ACTION_HOME); return; }
        if (btn != 1) return;
        if (down) {
            if (leftDown) return;
            leftDown = true; downAt = SystemClock.uptimeMillis();
            path.clear(); path.add(new float[]{cx, cy});
            showCursor();
            return;
        }
        if (!leftDown) return;
        leftDown = false;
        long held = SystemClock.uptimeMillis() - downAt;
        float[] a = path.get(0);
        float far = 0;
        for (float[] q : path) far = Math.max(far, (float) Math.hypot(q[0] - a[0], q[1] - a[1]));
        Path gp = new Path();
        gp.moveTo(a[0], a[1]);
        long dur;
        if (far < 12) {   // 沒怎麼動：點一下（按超過 0.5 秒＝長按）
            dur = held >= 500 ? Math.min(held, 3000) : 50;
        } else {          // 拖曳：沿著路徑滑（點太多就抽樣）
            int step = Math.max(1, path.size() / 60);
            for (int k = step; k < path.size(); k += step) gp.lineTo(path.get(k)[0], path.get(k)[1]);
            float[] z = path.get(path.size() - 1);
            gp.lineTo(z[0], z[1]);
            dur = Math.max(120, Math.min(held, 3000));
        }
        dispatch(gp, dur);
    }

    /** 滾輪：在游標位置上下滑。正＝往上捲（手指往下滑），跟 Windows 滾輪同方向。 */
    private void wheel(int n) {
        if (n == 0) return;
        float dist = Math.max(-scrH * 0.45f, Math.min(scrH * 0.45f, n * scrH * 0.12f));
        float y0 = Math.max(1, Math.min(scrH - 2, cy - dist / 2)), y1 = Math.max(1, Math.min(scrH - 2, cy + dist / 2));
        Path gp = new Path();
        gp.moveTo(cx, y0); gp.lineTo(cx, y1);
        dispatch(gp, 150);
        showCursor();
    }

    private void dispatch(Path p, long dur) {
        try {
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, dur))).build();
            dispatchGesture(g, null, null);
        } catch (Exception e) { Log.w(TAG, "gesture " + e); }
    }

    // ---------- 繪圖板畫面預覽 ----------
    // 同時只服務一個請求（被控端只有一個螢幕，最後一個要的人贏）；3 秒沒續約就停。
    private static final class FrameReq {
        java.net.InetAddress addr; int port; byte[] encKey, macKey;
        int x, y, w, h, outW, outH; long until;
    }
    private volatile FrameReq frameReq;
    private android.os.HandlerThread frameThread;
    private Handler frameH;
    private boolean frameBusy;
    private int frameId;
    volatile int framesSent; volatile String frameErr = "";

    private void frameRequest(String[] f, String id, java.net.InetAddress addr, int port) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        String tok = tokens().get(id);
        if (tok == null) return;
        FrameReq r = new FrameReq();
        r.addr = addr; r.port = port;
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(tok.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            r.encKey = m.doFinal("padbridge-frame-enc".getBytes(StandardCharsets.UTF_8));
            r.macKey = m.doFinal("padbridge-frame-mac".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return; }
        r.x = parse(f[3]); r.y = parse(f[4]); r.w = Math.max(1, parse(f[5])); r.h = Math.max(1, parse(f[6]));
        r.outW = Math.max(16, Math.min(1280, parse(f[7]))); r.outH = Math.max(16, Math.min(1280, parse(f[8])));
        r.until = SystemClock.uptimeMillis() + 3000;
        frameReq = r;
        synchronized (this) {
            if (frameThread == null) {
                frameThread = new android.os.HandlerThread("padbridge-frames");
                frameThread.start();
                frameH = new Handler(frameThread.getLooper());
            }
        }
        frameH.post(this::frameTick);
    }

    /** 在 frame 執行緒上：沒在截就截一張；截完排下一張（系統最短間隔約 333ms）。 */
    private void frameTick() {
        if (frameBusy) return;
        FrameReq r = frameReq;
        if (r == null || SystemClock.uptimeMillis() > r.until) { frameReq = null; return; }
        frameBusy = true;
        final long t0 = SystemClock.uptimeMillis();
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, frameH::post, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult res) {
                    try { sendFrame(r, res); framesSent++; } catch (Exception e) { frameErr = String.valueOf(e); Log.w(TAG, "frame " + e); }
                    frameDone(t0, 0);
                }
                @Override public void onFailure(int code) { frameErr = "screenshot " + code; frameDone(t0, 150); }
            });
        } catch (Exception e) { frameErr = String.valueOf(e); frameDone(t0, 500); }
    }
    private void frameDone(long t0, long extra) {
        frameBusy = false;
        long wait = Math.max(0, 340 - (SystemClock.uptimeMillis() - t0)) + extra;
        frameH.postDelayed(this::frameTick, wait);
    }

    private void sendFrame(FrameReq r, ScreenshotResult res) throws Exception {
        android.hardware.HardwareBuffer hb = res.getHardwareBuffer();
        byte[] img;
        try {
            android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
            if (hw == null) throw new IllegalStateException("wrap null");
            int sw = hw.getWidth(), sh = hw.getHeight();
            int px = (int) ((long) r.x * sw / 65536), py = (int) ((long) r.y * sh / 65536);
            int pw = Math.max(1, (int) ((long) r.w * sw / 65536)), ph = Math.max(1, (int) ((long) r.h * sh / 65536));
            px = Math.min(px, sw - 1); py = Math.min(py, sh - 1);
            if (px + pw > sw) pw = sw - px; if (py + ph > sh) ph = sh - py;
            android.graphics.Bitmap sw8 = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(r.outW, r.outH, android.graphics.Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(sw8, new Rect(px, py, px + pw, py + ph), new Rect(0, 0, r.outW, r.outH),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            sw8.recycle();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, bo);
            out.recycle();
            img = bo.toByteArray();
        } finally { hb.close(); }

        byte[] iv = new byte[16]; new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(r.encKey, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
        byte[] ct = c.doFinal(img);
        int fid = ++frameId;
        byte mon = 0;
        byte[] hdr = {(byte) (fid >> 24), (byte) (fid >> 16), (byte) (fid >> 8), (byte) fid, mon};
        javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
        m.init(new javax.crypto.spec.SecretKeySpec(r.macKey, "HmacSHA256"));
        m.update(hdr); m.update(iv); m.update(ct);
        byte[] mac = m.doFinal();
        byte[] all = new byte[16 + ct.length + 32];
        System.arraycopy(iv, 0, all, 0, 16); System.arraycopy(ct, 0, all, 16, ct.length); System.arraycopy(mac, 0, all, 16 + ct.length, 32);
        final int CH = 1200;
        int n = (all.length + CH - 1) / CH;
        DatagramSocket s = sock;
        if (s == null) return;
        for (int i = 0; i < n; i++) {
            int len = Math.min(CH, all.length - i * CH);
            byte[] pk = new byte[13 + len];
            pk[0] = 'P'; pk[1] = 'B'; pk[2] = 'F'; pk[3] = '2';
            System.arraycopy(hdr, 0, pk, 4, 4);
            pk[8] = (byte) (i >> 8); pk[9] = (byte) i; pk[10] = (byte) (n >> 8); pk[11] = (byte) n; pk[12] = mon;
            System.arraycopy(all, i * CH, pk, 13, len);
            s.send(new DatagramPacket(pk, pk.length, r.addr, r.port));
        }
    }

    // ---------- 游標 ----------
    private void addCursor() {
        final float d = getResources().getDisplayMetrics().density;
        cursor = new View(this) {
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            { fill.setColor(0xCCBFA8FF); ring.setColor(0xFF15121C); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(2 * d); }
            @Override protected void onDraw(Canvas c) {
                float r = getWidth() / 2f - 2 * d;
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, fill);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, ring);
            }
        };
        int size = Math.round(22 * d);
        clp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        clp.gravity = Gravity.TOP | Gravity.START;
        try { wm.addView(cursor, clp); } catch (Exception e) { Log.w(TAG, "cursor " + e); }
        cursor.setVisibility(View.GONE);
    }

    private void showCursor() {
        if (cursor == null) return;
        clp.x = Math.round(cx - clp.width / 2f); clp.y = Math.round(cy - clp.height / 2f);
        cursor.setVisibility(View.VISIBLE);
        try { wm.updateViewLayout(cursor, clp); } catch (Exception ignored) {}
        main.removeCallbacks(hideCursor);
        main.postDelayed(hideCursor, 6000);
    }
}
