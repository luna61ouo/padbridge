package io.github.luna61ouo.padbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 滑鼠訊號的唯一出口。上游（觸控板畫面、adb 測試、之後的手錶）只呼叫 move / button / wheel，
 * 不管下游是藍牙還是 Wi-Fi。
 *
 * 藍牙：手機 用 HID Device profile 假裝成一隻藍牙滑鼠，電腦不用裝任何東西。
 * Wi-Fi：UDP 文字封包送到電腦上的接收程式（windows/PadbridgeReceiver.ps1）。
 */
public final class MouseHub {
    static final String TAG = "Padbridge";
    public static final int MODE_BT = 0, MODE_WIFI = 1, MODE_BLE = 2;
    public static final int BTN_LEFT = 1, BTN_RIGHT = 2, BTN_MIDDLE = 4;

    // Report ID 1：按鍵 3 bit + 補 5 bit、X、Y、滾輪（各 int8）
    static final byte[] DESCRIPTOR = {
        0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
        0x09, 0x01, (byte) 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
        (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06,
        (byte) 0xC0, (byte) 0xC0
    };

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
    private int seq;

    private int buttons;

    // 藍牙送出端：所有報告集中在一條執行緒，移動量合併後最多約 80 次／秒送一包。
    // 實測：每個觸控事件都送一包（~100 次／秒）時，HyperOS 的藍牙一直想切省電模式又被叫醒，
    // 卡到 pm_pending_link maxed out，游標變卡、最後斷線。
    private final Object btLock = new Object();
    private int accX, accY, accW;
    private final ArrayDeque<Integer> btnQueue = new ArrayDeque<>();
    private int sentButtons;
    private Thread btThread;
    private final Handler main = new Handler(Looper.getMainLooper());
    // 手指放在觸控板上（或之後手錶在操作）時為 true：期間每 12ms 送一包空報告，
    // 不讓藍牙在滑動途中切去省電（sniff），否則每次醒來都要等，手感會卡。
    private volatile boolean active;

    private MouseHub(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("mouse", Context.MODE_PRIVATE);
        pcList = new PcList(c);
        settings = new MouseSettings(c);
        startLanListener();
    }

    // ---------- 設定 ----------
    /** 傳統藍牙已移除：舊設定存的 MODE_BT 一律當 BLE。 */
    public int mode() { int m = prefs.getInt("mode", MODE_BLE); return m == MODE_BT ? MODE_BLE : m; }
    public void setMode(int m) { prefs.edit().putInt("mode", m).apply(); notifyStatus(); tellPcMode(); }

    /** 告訴選中的電腦「手機現在用哪條」（電腦端圖示顯示用）。 */
    public void tellPcMode() {
        PcList.Pc p = pcList.selected();
        if (p == null || p.ip.isEmpty()) return;
        pcList.send(p, "s|" + (mode() == MODE_WIFI ? "wifi" : "ble") + "|" + settings.phoneName(ctx));
    }
    // Wi-Fi 目標＝電腦清單裡選中的那台
    public PcList pcs() { return pcList; }
    public String wifiHost() { PcList.Pc p = pcList.selected(); return p == null ? "" : p.ip; }
    public int wifiPort() { PcList.Pc p = pcList.selected(); return p == null ? 47801 : p.port; }
    public String wifiToken() { PcList.Pc p = pcList.selected(); return p == null ? "" : p.token; }
    public void selectPc(String id) { pcList.select(id); pushWifiToDaemon(); notifyStatus(); tellPcMode(); }
    public float sensitivity() { return settings.sensitivity(); }
    /** 手動輸入一台電腦（或 adb 的 op=wifi）：加進清單並選它。 */
    public void setWifi(String host, int port, String token) {
        PcList.Pc cur = pcList.selected();
        String h = host != null ? host.trim() : (cur != null ? cur.ip : "");
        String t = token != null ? token.trim() : (cur != null ? cur.token : "");
        if (h.isEmpty()) return;
        PcList.Pc p = pcList.addManual(h, port, t);
        pcList.select(p.id);
        pushWifiToDaemon();
        notifyStatus();
    }
    /**
     * 把「藍牙連的那台電腦」的 Wi-Fi 位址交給小幫手，讓它在手機藍牙關開後叫那台把 BLE 接回。
     * 哪台：清單裡藍牙位址＝小幫手正在連（或記得）的電腦；對不上就用選中的那台。小幫手會存檔，重複送無妨。
     */
    public void pushWifiToDaemon() {
        if (!daemonMode()) return;
        PcList.Pc t = null;
        String bh = bleHostAddr.replace(":", "");
        if (!bh.isEmpty()) for (PcList.Pc p : pcList.all()) if (p.bt.equalsIgnoreCase(bh)) t = p;
        if (t == null) t = pcList.selected();
        if (t == null || t.ip.isEmpty() || t.token.isEmpty()) return;
        String key = t.ip + "|" + t.port + "|" + t.token;
        if (key.equals(lastKickPush)) return;
        lastKickPush = key;
        sendDaemon("W|" + key);
    }
    private volatile String bleHostAddr = "", lastKickPush = "";
    /** 量測／除錯用旗標（noSmooth、noYield），只給 adb 用。 */
    public void setFlag(String name, boolean on) { prefs.edit().putBoolean(name, on).apply(); }
    public void setSensitivity(float s) { settings.setSensitivity(s); }

    public void setListener(Listener l) { listener = l; notifyStatus(); }

    private BleMouse ble;
    private volatile String bleState = "BLE：未啟動";

    public void startBle() {
        if (daemonMode()) {
            // 小幫手模式：滑鼠本體在 shell 身分的小幫手（Daemon）裡，App 只轉送觸控
            if (ble != null) { ble.stop(); ble = null; }
            startPinger();
            return;
        }
        if (ble == null) ble = new BleMouse(ctx, DESCRIPTOR, t -> { bleState = t; notifyStatus(); });
        ble.start();
        ensureBtThread();
    }

    public void stopBle() {
        stopPinger();
        if (ble != null) ble.stop();
        bleState = "BLE：未啟動";
        notifyStatus();
    }

    // ---------- 電腦叫手機（電腦端圖示的「用藍牙連這支手機」）----------
    // 收 UDP 47822：v2|流水號|P|電腦藍牙位址(12 位)|簽章——簽章要用清單裡某台電腦的配對碼驗得過才理。
    // 交給小幫手準備（換掉舊電腦、自動同意配對），回「v2|…|OK|手機藍牙位址|簽章」（同一把配對碼簽，電腦驗得過才去配對：
    // 不然區網上的人可以假冒手機，騙電腦去配對他的裝置——那可能是一把會打字的鍵盤）。
    public static final int LAN_PORT = 47822;
    private final java.util.Map<String, Long> lastCtrByPc = new java.util.HashMap<>();
    private void startLanListener() {
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(LAN_PORT)) {
                byte[] buf = new byte[512];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    int i = text.lastIndexOf('|');
                    if (i < 0 || !text.startsWith("v2|")) continue;
                    String body = text.substring(0, i), sig = text.substring(i + 1);
                    String[] f = body.split("\\|");
                    if (f.length < 4) continue;
                    PcList.Pc pc = null;
                    for (PcList.Pc c2 : pcList.all()) if (PcList.hmac(c2.token, body).equals(sig)) pc = c2;
                    if (pc == null) { Log.w(TAG, "lan: bad signature from " + p.getAddress()); continue; }
                    long ctr;
                    try { ctr = Long.parseLong(f[1]); } catch (Exception e) { continue; }
                    long nowUs = System.currentTimeMillis() * 1000;
                    Long last = lastCtrByPc.get(pc.id);
                    if ((last != null && ctr <= last) || Math.abs(ctr - nowUs) > 600_000_000L) { Log.w(TAG, "lan: replay/clock"); continue; }
                    lastCtrByPc.put(pc.id, ctr);
                    String reply;
                    if ("P".equals(f[2])) reply = pairForPc(pc, f[3]);
                    else continue;
                    byte[] rb = Wire.seal(pc.token, reply).getBytes(StandardCharsets.UTF_8);
                    s.send(new DatagramPacket(rb, rb.length, p.getAddress(), p.getPort()));
                }
            } catch (Exception e) { Log.w(TAG, "lan listener " + e); }
        }, "padbridge-lan").start();
    }

    private String pairForPc(PcList.Pc pc, String hex) {
        if (!hex.matches("[0-9A-Fa-f]{12}")) return "ERR|位址格式不對";
        if (!daemonMode()) return "ERR|手機沒開「固定位址」（小幫手）";
        StringBuilder a = new StringBuilder();
        for (int k = 0; k < 12; k += 2) { if (k > 0) a.append(':'); a.append(hex.substring(k, k + 2).toUpperCase()); }
        try (DatagramSocket d = new DatagramSocket()) {
            d.setSoTimeout(3000);
            byte[] q = (daemonToken() + "|PAIR|" + a).getBytes(StandardCharsets.UTF_8);
            d.send(new DatagramPacket(q, q.length, InetAddress.getByName("127.0.0.1"), Daemon.PORT));
            byte[] buf = new byte[256];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            d.receive(r);
            String res = new String(r.getData(), 0, r.getLength(), StandardCharsets.UTF_8);
            if (res.startsWith("OK|")) { pc.bt = hex.toUpperCase(); pcList.save(); lastKickPush = ""; }
            return res;
        } catch (Exception e) { return "ERR|手機的小幫手沒回應（要用 adb 啟動）"; }
    }

    // ---------- 小幫手（Daemon）----------
    public boolean daemonMode() { return settings.on(MouseSettings.FIXED_ADDRESS); }

    /** 跟小幫手對暗號用；啟動腳本用 adb 向 App 要（op=token）再交給小幫手。 */
    public String daemonToken() {
        String t = prefs.getString("daemonToken", null);
        if (t == null) {
            byte[] r = new byte[12]; new java.security.SecureRandom().nextBytes(r);
            StringBuilder b = new StringBuilder(); for (byte x : r) b.append(String.format("%02x", x));
            t = b.toString(); prefs.edit().putString("daemonToken", t).commit();
        }
        return t;
    }

    private DatagramSocket dsock;
    private InetAddress dAddr;
    private void sendDaemon(final String body) {
        final String msg = daemonToken() + "|" + body;
        io.execute(() -> {
            try {
                if (dsock == null) { dsock = new DatagramSocket(); dAddr = InetAddress.getByName("127.0.0.1"); }
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                dsock.send(new DatagramPacket(b, b.length, dAddr, Daemon.PORT));
            } catch (Exception e) { Log.w(TAG, "daemon send " + e); }
        });
    }

    public void daemonQuit() { sendDaemon("Q"); }

    private volatile Thread pinger;
    private void startPinger() {
        if (pinger != null && pinger.isAlive()) return;
        pinger = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(1000);
                InetAddress lo = InetAddress.getByName("127.0.0.1");
                byte[] buf = new byte[512];
                while (pinger == Thread.currentThread()) {
                    byte[] q = (daemonToken() + "|P").getBytes(StandardCharsets.UTF_8);
                    String st;
                    try {
                        s.send(new DatagramPacket(q, q.length, lo, Daemon.PORT));
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        st = "（小幫手）" + new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    } catch (Exception e) { st = "BLE：小幫手沒在跑（要用 adb 啟動）"; }
                    if (!st.equals(bleState)) {
                        if (bleState.contains("沒在跑") || bleState.startsWith("BLE：未")) lastKickPush = "";   // 小幫手（重新）出現：重交一次
                        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("([0-9A-F]{2}(:[0-9A-F]{2}){5})").matcher(st);
                        if (st.contains("已連上") && mm.find()) bleHostAddr = mm.group(1);
                        bleState = st; notifyStatus();
                        pushWifiToDaemon();
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                }
            } catch (Exception e) { Log.w(TAG, "pinger " + e); }
        }, "padbridge-pinger");
        pinger.start();
    }
    private void stopPinger() { Thread t = pinger; pinger = null; if (t != null) t.interrupt(); }

    public String statusText() {
        if (mode() == MODE_BLE) return bleState;
        PcList.Pc p = pcList.selected();
        return p == null ? "Wi-Fi：還沒選電腦（按「選電腦」）" : "Wi-Fi → " + p.label() + "  " + p.ip;
    }

    private void notifyStatus() {
        Listener l = listener;
        if (l != null) l.onStatus(statusText());
    }

    private void ensureBtThread() {
        synchronized (btLock) {
            if (btThread != null && btThread.isAlive()) return;
            btThread = new Thread(this::btLoop, "padbridge-bt");
            btThread.setPriority(Thread.MAX_PRIORITY);
            btThread.start();
        }
    }

    private void queueBt(int dx, int dy, int wheel, Integer newButtons) {
        if (mode() == MODE_BLE && daemonMode()) {
            if (dx != 0 || dy != 0 || wheel != 0) sendDaemon("M|" + dx + "|" + dy + "|" + wheel);
            if (newButtons != null) sendDaemon("B|" + newButtons);
            return;
        }
        synchronized (btLock) {
            accX += dx; accY += dy; accW += wheel;
            if (newButtons != null) btnQueue.add(newButtons);
            btLock.notifyAll();
        }
        ensureBtThread();
    }

    private void btLoop() {
        while (true) {
            int dx, dy, w, b;
            boolean moreButtons;
            synchronized (btLock) {
                while (accX == 0 && accY == 0 && accW == 0 && btnQueue.isEmpty()) {
                    try { btLock.wait(); } catch (InterruptedException e) { return; }
                }
                dx = accX; dy = accY; w = accW;   // BLE 不做平滑：連線間隔本身就是固定節拍（實測：平滑反而多延遲）
                accX -= dx; accY -= dy; accW -= w;
                b = btnQueue.isEmpty() ? sentButtons : btnQueue.poll();
                moreButtons = !btnQueue.isEmpty();
            }
            boolean bleMode = mode() == MODE_BLE;
            if (bleMode) {
                BleMouse m = ble;
                boolean ok = true;
                if (m != null && m.connected()) {
                    if (b != sentButtons && (dx != 0 || dy != 0 || w != 0)) {
                        ok = bleReport(m, sentButtons, dx, dy, w);
                        if (ok) { dx = dy = w = 0; }
                    }
                    if (ok) ok = bleReport(m, b, dx, dy, w);
                }
                if (ok) sentButtons = b;
                else synchronized (btLock) {
                    // 系統佇列滿：移動量放回去下一拍再送，按鍵狀態也放回佇列最前面
                    accX += dx; accY += dy; accW += w;
                    if (b != sentButtons) btnQueue.addFirst(b);
                }
                // 節奏由 send() 等送達決定；這裡只在沒送出時稍等，避免空轉
                if (!ok) { try { Thread.sleep(3); } catch (InterruptedException e) { return; } }
                continue;
            }
            sentButtons = b;   // 不是 BLE（Wi-Fi 不走這條執行緒）：丟掉
        }
    }

    private boolean bleReport(BleMouse m, int btn, int dx, int dy, int wheel) {
        int sx = clamp(dx), sy = clamp(dy), sw = clamp(wheel);
        boolean ok = m.send(new byte[]{(byte) btn, (byte) sx, (byte) sy, (byte) sw});
        if (!ok) return false;
        int rx = dx - sx, ry = dy - sy, rw = wheel - sw;
        if (rx != 0 || ry != 0 || rw != 0) synchronized (btLock) { accX += rx; accY += ry; accW += rw; }
        return true;
    }

    private static int clamp(int v) { return Math.max(-127, Math.min(127, v)); }

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
        announceInUse();   // 手指在板上滑時持續續約（setActive 只在按下那一刻）
        if (mode() == MODE_WIFI) holdWifiLowLatency();
        if (mode() != MODE_WIFI) queueBt(dx, dy, 0, null); else sendWifi("m|" + dx + "|" + dy);
    }

    public void wheel(int notches) {
        if (notches == 0) return;
        if (mode() != MODE_WIFI) queueBt(0, 0, notches, null); else sendWifi("w|" + notches);
    }

    public void button(int btn, boolean down) {
        buttons = down ? (buttons | btn) : (buttons & ~btn);
        if (mode() != MODE_WIFI) queueBt(0, 0, 0, buttons);
        else sendWifi("b|" + btn + "|" + (down ? 1 : 0));
    }

    /** 觸控開始／結束。藍牙模式下開始時保持連線清醒。 */
    public void setActive(boolean on) {
        active = on;
        if (on && mode() == MODE_BLE && daemonMode()) sendDaemon("A|1");
        else if (on && mode() == MODE_BLE && ble != null) ble.ensurePriority();
        if (on) announceInUse();
        if (on && mode() == MODE_WIFI) holdWifiLowLatency();
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

    private long lastYieldAt;
    /**
     * 「滑鼠使用中」廣播：
     * 不指定收件者，誰要聽誰聽；附 transport（ble／wifi）讓收聽的 App 自己決定要不要讓（例如智慧眼鏡只在 ble 時讓出藍牙）。
     * 每 2 秒最多一次、每次宣告 5 秒。設定「通知其他 App」關掉就不送。
     */
    private void announceInUse() {
        if (!settings.on(MouseSettings.ANNOUNCE_IN_USE) || prefs.getBoolean("noYield", false)) return;
        long now = System.currentTimeMillis();
        if (now - lastYieldAt < 2000) return;
        lastYieldAt = now;
        try {
            android.content.Intent i = new android.content.Intent("io.github.luna61ouo.padbridge.action.IN_USE");
            i.putExtra("transport", mode() == MODE_WIFI ? "wifi" : "ble");
            i.putExtra("sec", 5);
            ctx.sendBroadcast(i);
        } catch (Exception e) { Log.w(TAG, "in-use broadcast " + e); }
    }

    public void click(int btn) { button(btn, true); button(btn, false); }
}
