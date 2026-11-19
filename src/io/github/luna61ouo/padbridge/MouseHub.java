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
                if (udp == null) udp = new DatagramSocket();
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
}
