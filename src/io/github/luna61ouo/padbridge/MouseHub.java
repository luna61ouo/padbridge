package io.github.luna61ouo.padbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 滑鼠訊號的唯一出口。上游（觸控板畫面、adb 測試、之後的手錶）只呼叫 move / button / wheel。
 * 送法：Wi-Fi UDP 簽章封包，送到電腦清單裡選中那台的接收程式（windows/PadbridgeReceiver.ps1）。
 * （藍牙模式 11-20 退役：穩定版要靠 adb shell 身分的小幫手，等於要使用者開無線偵錯、給系統級藍牙權限。）
 */
public final class MouseHub {
    static final String TAG = "Padbridge";
    public static final int BTN_LEFT = 1, BTN_RIGHT = 2, BTN_MIDDLE = 4;

    public interface Listener { void onStatus(String text); }

    private static MouseHub sInstance;
    public static synchronized MouseHub get(Context c) {
        if (sInstance == null) sInstance = new MouseHub(c.getApplicationContext());
        return sInstance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final PcList pcList;
    private final MouseSettings settings;
    public MouseSettings settings() { return settings; }
    private volatile Listener listener;

    private DatagramSocket udp;
    private InetAddress udpAddr;
    private String udpAddrHost;
    private int buttons;

    private MouseHub(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("mouse", Context.MODE_PRIVATE);
        pcList = new PcList(c);
        settings = new MouseSettings(c);
    }

    // ---------- 設定 ----------
    /** 告訴選中的電腦這支手機的名稱（電腦端圖示的手機清單顯示用）。第二欄沿用舊格式的模式欄，固定 wifi。 */
    public void tellPcMode() {
        PcList.Pc p = pcList.selected();
        if (p == null || p.ip.isEmpty()) return;
        pcList.send(p, "s|wifi|" + settings.phoneName(ctx));
    }
    public PcList pcs() { return pcList; }
    public String wifiHost() { PcList.Pc p = pcList.selected(); return p == null ? "" : p.ip; }
    public int wifiPort() { PcList.Pc p = pcList.selected(); return p == null ? 47801 : p.port; }
    public String wifiToken() { PcList.Pc p = pcList.selected(); return p == null ? "" : p.token; }
    public void selectPc(String id) { pcList.select(id); notifyStatus(); tellPcMode(); }
    public float sensitivity() { return settings.sensitivity(); }
    /** 手動輸入一台電腦（或 adb 的 op=wifi）：加進清單並選它。 */
    public void setWifi(String host, int port, String token) {
        PcList.Pc cur = pcList.selected();
        String h = host != null ? host.trim() : (cur != null ? cur.ip : "");
        String t = token != null ? token.trim() : (cur != null ? cur.token : "");
        if (h.isEmpty()) return;
        PcList.Pc p = pcList.addManual(h, port, t);
        pcList.select(p.id);
        notifyStatus();
    }
    /** 量測／除錯用旗標（noWifiLock），只給 adb 用。 */
    public void setFlag(String name, boolean on) { prefs.edit().putBoolean(name, on).apply(); }
    public void setSensitivity(float s) { settings.setSensitivity(s); }

    public void setListener(Listener l) { listener = l; notifyStatus(); }

    public String statusText() {
        PcList.Pc p = pcList.selected();
        return p == null ? "還沒選電腦（按「選電腦」）" : "→ " + p.label() + "  " + p.ip;
    }

    private void notifyStatus() {
        Listener l = listener;
        if (l != null) l.onStatus(statusText());
    }

    // ---------- Wi-Fi ----------
    private void sendWifi(final String body) {
        final String host = wifiHost();
        if (host.isEmpty()) return;
        final int port = wifiPort();
        final String token = wifiToken();
        io.execute(() -> {
            try {
                if (udp == null) { udp = new DatagramSocket(); udp.setSoTimeout(1000); }
                if (udpAddr == null || !host.equals(udpAddrHost)) {
                    udpAddr = InetAddress.getByName(host); udpAddrHost = host;
                }
                String msg = Wire.seal(token, body);
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                udp.send(new DatagramPacket(b, b.length, udpAddr, port));
            } catch (Exception e) {
                Log.w(TAG, "udp " + e);
            }
        });
    }

    // ---------- 繪圖板畫面預覽 ----------
    // 跟滑鼠封包同一個 socket、同一條排隊送出（流水號才不會插隊被電腦擋掉）；電腦把加密畫格分段回到這個 socket。
    // 畫格格式見 windows/PadbridgeFrames.cs。只收目前選中那台電腦送來、MAC 驗得過的。
    public interface FrameListener { void onFrame(int mon, android.graphics.Bitmap b); }   // mon＝螢幕編號（舊電腦端 PBF1＝-1）
    private volatile FrameListener frameListener;
    private Thread frameThread;
    public void setFrameListener(FrameListener l) { frameListener = l; if (l != null) ensureFrameThread(); }
    /** x、y、w、h＝0～65535（主螢幕比例），ow、oh＝要的像素大小。每秒叫一次當續約，電腦 3 秒沒收到就停。 */
    public void requestFrame(int x, int y, int w, int h, int ow, int oh) { requestFrame(x, y, w, h, ow, oh, Integer.MIN_VALUE); }
    /** mon：指定螢幕編號（全部同時顯示時每個螢幕各要一次）；MIN_VALUE＝用選的那個。 */
    public void requestFrame(int x, int y, int w, int h, int ow, int oh, int mon) {
        sendWifi("f|" + x + "|" + y + "|" + w + "|" + h + "|" + ow + "|" + oh + (mon == Integer.MIN_VALUE ? monSuffix() : "|" + mon));
        ensureFrameThread();
    }
    private synchronized void ensureFrameThread() {
        if (frameThread != null && frameThread.isAlive()) return;
        frameThread = new Thread(this::frameLoop, "padbridge-frames");
        frameThread.start();
    }
    private String keyTok; private byte[] encKey, macKey;
    private void frameLoop() {
        byte[] buf = new byte[1600];
        // 每個螢幕各自重組（全部同時顯示時兩個螢幕的畫格輪流來）；新的一格到了，舊的沒收齊就丟（不等重傳）
        java.util.Map<Integer, Object[]> asm = new java.util.HashMap<>();   // mon → {fid, byte[][] parts, int[] have}
        while (true) {
            DatagramSocket s = udp;
            if (s == null || frameListener == null) { try { Thread.sleep(200); } catch (InterruptedException e) { return; } continue; }
            try {
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                s.receive(pk);
                int n = pk.getLength();
                byte[] d = pk.getData();
                if (n < 12 || d[0] != 'P' || d[1] != 'B' || d[2] != 'F' || (d[3] != '1' && d[3] != '2')) continue;
                if (udpAddr == null || !udpAddr.equals(pk.getAddress())) continue;
                boolean v2 = d[3] == '2';
                int head = v2 ? 13 : 12;
                if (n < head) continue;
                int fid = ((d[4] & 0xff) << 24) | ((d[5] & 0xff) << 16) | ((d[6] & 0xff) << 8) | (d[7] & 0xff);
                int idx = ((d[8] & 0xff) << 8) | (d[9] & 0xff), cnt = ((d[10] & 0xff) << 8) | (d[11] & 0xff);
                int mon = v2 ? (d[12] & 0xff) : -1;
                if (cnt <= 0 || cnt > 2000 || idx >= cnt) continue;
                Object[] st = asm.get(mon);
                if (st == null || (int) st[0] != fid) { st = new Object[]{fid, new byte[cnt][], new int[]{0}}; asm.put(mon, st); }
                byte[][] parts = (byte[][]) st[1];
                if (parts.length != cnt || parts[idx] != null) continue;
                parts[idx] = java.util.Arrays.copyOfRange(d, head, n);
                if (++((int[]) st[2])[0] < cnt) continue;
                asm.remove(mon);
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                for (byte[] x : parts) all.write(x);
                android.graphics.Bitmap bm = openFrame(fid, v2 ? mon : -1, all.toByteArray());
                FrameListener l = frameListener;
                if (bm != null && l != null) l.onFrame(mon, bm);
            } catch (java.net.SocketTimeoutException te) { /* 沒畫格，繼續等 */
            } catch (Exception e) { Log.w(TAG, "frame " + e); try { Thread.sleep(200); } catch (InterruptedException ie) { return; } }
        }
    }
    /** mon≥0＝PBF2（MAC 多蓋螢幕編號）；-1＝舊的 PBF1。 */
    private android.graphics.Bitmap openFrame(int fid, int mon, byte[] a) throws Exception {
        if (a.length < 16 + 16 + 32) return null;
        String tok = wifiToken();
        if (!tok.equals(keyTok)) {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(tok.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            encKey = m.doFinal("padbridge-frame-enc".getBytes(StandardCharsets.UTF_8));
            macKey = m.doFinal("padbridge-frame-mac".getBytes(StandardCharsets.UTF_8));
            keyTok = tok;
        }
        int ctLen = a.length - 16 - 32;
        javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
        m.init(new javax.crypto.spec.SecretKeySpec(macKey, "HmacSHA256"));
        m.update(new byte[]{(byte) (fid >> 24), (byte) (fid >> 16), (byte) (fid >> 8), (byte) fid});
        if (mon >= 0) m.update((byte) mon);
        m.update(a, 0, 16 + ctLen);
        byte[] mac = m.doFinal();
        if (!java.security.MessageDigest.isEqual(mac, java.util.Arrays.copyOfRange(a, 16 + ctLen, a.length))) { Log.w(TAG, "frame: bad mac"); return null; }
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(encKey, "AES"), new javax.crypto.spec.IvParameterSpec(a, 0, 16));
        byte[] jpg = c.doFinal(a, 16, ctLen);
        return android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
    }

    // ---------- 給上游用的動作 ----------
    public void move(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        holdWifiLowLatency();
        sendWifi("m|" + dx + "|" + dy);
    }

    public void wheel(int notches) {
        if (notches == 0) return;
        sendWifi("w|" + notches);
    }

    public void button(int btn, boolean down) {
        buttons = down ? (buttons | btn) : (buttons & ~btn);
        sendWifi("b|" + btn + "|" + (down ? 1 : 0));
    }

    /** 觸控開始：先把 Wi-Fi 叫醒，第一下就不會慢。 */
    public void setActive(boolean on) {
        if (on) holdWifiLowLatency();
    }

    // ---------- Wi-Fi 低延遲鎖----------
    // 手機 Wi-Fi 平常會打瞌睡（省電模式），封包被攢著一批批送，游標就一頓一頓。觸控板在用時要低延遲鎖，停手 5 秒放掉。
    // 量測用開關：op=flag name=noWifiLock on=true
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private final android.os.Handler lockHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable releaseWifiLock = () -> {
        try { if (wifiLock != null && wifiLock.isHeld()) { wifiLock.release(); Log.i(TAG, "wifi lock released"); } } catch (Exception ignored) {}
    };
    private long lastLockAt;
    private void holdWifiLowLatency() {
        if (!settings.on(MouseSettings.WIFI_LOW_LATENCY) || prefs.getBoolean("noWifiLock", false)) return;
        long now = System.currentTimeMillis();
        if (now - lastLockAt < 500) return;
        lastLockAt = now;
        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "padbridge");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) { wifiLock.acquire(); Log.i(TAG, "wifi lock acquired"); }
        } catch (Exception e) { Log.w(TAG, "wifi lock " + e); }
        lockHandler.removeCallbacks(releaseWifiLock);
        lockHandler.postDelayed(releaseWifiLock, 5000);
    }

    public void click(int btn) { button(btn, true); button(btn, false); }

    // ---------- 繪圖板（絕對座標）----------
    // 多螢幕：電腦有回報螢幕清單才帶螢幕編號（舊電腦端不認得多出來的欄位，也不會帶錯）
    private String monSuffix() {
        PcList.Pc p = pcList.selected();
        int i = p == null || p.mons.isEmpty() ? -1 : p.monIndex();   // 全部同時顯示時呼叫端會自己指定螢幕
        return i < 0 ? "" : "|" + i;
    }
    // x、y 是 0～65535（對應電腦主螢幕左上到右下）；press：1＝按住左鍵、0＝放開、-1＝不動按鍵（懸停／只移動）。
    // 按鍵狀態跟著座標同一包送，按下的那一點跟移動不會錯開。
    public void absolute(int x, int y, int press) { absolute(x, y, press, Integer.MIN_VALUE); }
    /** mon：指定螢幕（全部同時顯示時看碰到哪個螢幕的圖）；MIN_VALUE＝用選的那個。 */
    public void absolute(int x, int y, int press, int mon) {
        holdWifiLowLatency();
        sendWifi("a|" + x + "|" + y + "|" + press + (mon == Integer.MIN_VALUE ? monSuffix() : "|" + mon));
    }
}
