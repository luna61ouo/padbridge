package io.github.luna61ouo.padbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Properties;

/**
 * 滑鼠小幫手。用 adb shell 身分跑，跟 App 不同生命：App 被殺、更新、滑掉都不影響滑鼠。
 *   CLASSPATH=<padbridge.apk> app_process / io.github.luna61ouo.padbridge.Daemon <token>
 * 為什麼要 shell 身分：
 *  ① 固定位址——用手機真實公開位址廣播（BLUETOOTH_PRIVILEGED），電腦永遠撥得到；App 只能用會換的化名，
 *     Windows 撥的是最後看到的化名，一換就連不回來（Windows 藍牙事件追蹤實證）。
 *  ② 滑鼠服務（GATT）放在這裡——App 被殺時服務不會在電腦連著時消失（消失的話 Windows 會把滑鼠那層刪掉、不再自己重連）。
 * App 透過 127.0.0.1:47820 UDP 送移動量（要 token），這裡負責合併、照連線間隔送給電腦。
 */
public final class Daemon {
    static final String TAG = "PadbridgeD";
    static final int PORT = 47820;
    static volatile String status = "啟動中";
    static BleMouse ble;
    static volatile BluetoothAdapter adapter;
    static ShellContext shellCtx;

    /** 沒有 App 的行程沒有 Context：做一個 shell 身分的假 Context，夠藍牙 API 用就好。 */
    static final class ShellContext extends ContextWrapper {
        final AttributionSource src = new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        ShellContext() { super(null); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public AttributionSource getAttributionSource() { return src; }
        @Override public String getAttributionTag() { return null; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Looper getMainLooper() { return Looper.getMainLooper(); }
        @Override public Object getSystemService(String name) {
            if (Context.BLUETOOTH_SERVICE.equals(name)) {
                try {   // 每次都新建：藍牙關開後舊的會失效
                    java.lang.reflect.Constructor<BluetoothManager> c = BluetoothManager.class.getDeclaredConstructor(Context.class);
                    c.setAccessible(true);
                    return c.newInstance(this);
                } catch (Exception e) { Log.e(TAG, "BluetoothManager " + e); }
            }
            return null;
        }
    }

    /** 設定存檔：bleHost、kickHost／kickPort／kickToken（最後一哩）、selfAddr（拿不到真實位址時的備用）。 */
    static final class FileStore implements BleMouse.Store {
        final File f = new File("/data/local/tmp/padbridge-daemon.properties");
        final Properties p = new Properties();
        FileStore() {
            try (FileInputStream in = new FileInputStream(f)) { p.load(in); } catch (Exception ignored) {}
        }
        public String get(String k, String def) { return p.getProperty(k, def); }
        public synchronized void put(String k, String v) {
            p.setProperty(k, v);
            try (FileOutputStream o = new FileOutputStream(f)) { p.store(o, "luna mouse daemon"); } catch (Exception e) { Log.w(TAG, "store " + e); }
        }
        public boolean flag(String k) { return false; }
    }

    // ---- 合併＋送出（跟 App 的 BLE 分支同一套規則）----
    static final Object lock = new Object();
    static int accX, accY, accW, sentButtons;
    static final ArrayDeque<Integer> btnQueue = new ArrayDeque<>();

    static void senderLoop() {
        while (true) {
            int dx, dy, w, b;
            synchronized (lock) {
                while (accX == 0 && accY == 0 && accW == 0 && btnQueue.isEmpty()) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                dx = accX; dy = accY; w = accW; accX = accY = accW = 0;
                b = btnQueue.isEmpty() ? sentButtons : btnQueue.poll();
            }
            BleMouse m = ble;
            boolean ok = true;
            if (m != null && m.connected()) {
                if (b != sentButtons && (dx != 0 || dy != 0 || w != 0)) {
                    ok = report(m, sentButtons, dx, dy, w);
                    if (ok) { dx = dy = w = 0; }
                }
                if (ok) ok = report(m, b, dx, dy, w);
            }
            if (ok) sentButtons = b;
            else synchronized (lock) {
                accX += dx; accY += dy; accW += w;
                if (b != sentButtons) btnQueue.addFirst(b);
            }
            if (!ok) { try { Thread.sleep(3); } catch (InterruptedException e) { return; } }
        }
    }

    static int clamp(int v) { return Math.max(-127, Math.min(127, v)); }

    static boolean report(BleMouse m, int btn, int dx, int dy, int wheel) {
        int sx = clamp(dx), sy = clamp(dy), sw = clamp(wheel);
        if (!m.send(new byte[]{(byte) btn, (byte) sx, (byte) sy, (byte) sw})) return false;
        int rx = dx - sx, ry = dy - sy, rw = wheel - sw;
        if (rx != 0 || ry != 0 || rw != 0) synchronized (lock) { accX += rx; accY += ry; accW += rw; }
        return true;
    }

    // 閒置：沒電腦連著超過 10 分鐘就停止廣播（不跟手機上其他藍牙裝置搶時間）；App 那邊一碰觸控板（A／M 封包）就恢復。
    static long IDLE_MS = 10 * 60 * 1000;   // 第二個參數可改秒數（測試用）
    static volatile long lastBusy = System.currentTimeMillis();
    static volatile boolean idle;
    static void touched() {
        lastBusy = System.currentTimeMillis();
        if (idle) { idle = false; BleMouse m = ble; if (m != null) m.resumeAdvertising(); log("idle → advertising"); }
        BleMouse m = ble;
        if (kickWindowStart == 0 && m != null && !m.connected()) armKick();
    }

    // ---- BLE 最後一哩：手機藍牙關開後 Windows 不會自己連回來，透過 Wi-Fi 叫電腦端接收程式主動撥一次 ----
    // 條件：沒電腦連著、沒在閒置、藍牙開著、設過電腦位址。每次「斷掉／藍牙重開／碰觸控板」起算，每 5 秒叫一次、最多 2 分鐘。
    static FileStore store;
    static volatile long kickWindowStart;   // 0＝不在叫
    static String selfAddr = "";
    static volatile boolean btOn = true;
    static void armKick() { kickWindowStart = System.currentTimeMillis(); }

    static void kickLoop() {
        DatagramSocket s = null;
        long lastKick = 0; boolean wasConnected = false;
        while (true) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            BleMouse m = ble;
            boolean conn = m != null && m.connected();
            if (conn) { kickWindowStart = 0; wasConnected = true; continue; }
            if (wasConnected) { wasConnected = false; armKick(); }
            long now = System.currentTimeMillis();
            String host = store.get("kickHost", "");
            if (kickWindowStart == 0 || idle || !btOn || host.isEmpty() || selfAddr.isEmpty() || store.get("bleHost", "").isEmpty()) continue;   // 沒有配對過的電腦就不叫
            if (now - kickWindowStart > 120_000) { kickWindowStart = 0; log("kick: 2 分鐘沒接回，先停"); continue; }
            if (now - kickWindowStart < 3000 || now - lastKick < 5000) continue;
            lastKick = now;
            try {
                if (s == null) s = new DatagramSocket();
                String msg = Wire.seal(store.get("kickToken", ""), "k|" + selfAddr);
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                s.send(new DatagramPacket(b, b.length, InetAddress.getByName(host), Integer.parseInt(store.get("kickPort", "47801"))));
                log("kick → " + host);
            } catch (Exception e) { log("kick send " + e); if (s != null) { s.close(); s = null; } }
        }
    }

    // ---- 電腦端圖示的「用藍牙連這支手機」----
    // 手機的藍牙一次只服務一台電腦：換成新的那台時，先斷掉舊電腦、移除舊電腦在手機上的配對（它就連不回來），
    // 再用 shell 身分自動同意新電腦的配對請求（不用去點手機通知）。
    static String pairPrepare(String target) {
        // 每次新拿一個 adapter：藍牙關開過之後舊的會失效
        BluetoothAdapter ad = null;
        try { ad = ((BluetoothManager) shellCtx.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter(); } catch (Exception e) { log("pair: adapter " + e); }
        if (ad == null) ad = adapter;
        int ast = ad == null ? -1 : ad.getState();
        log("pair: request " + target + " bt state " + ast);
        if (ast != BluetoothAdapter.STATE_ON) return "ERR|手機藍牙沒開（狀態 " + ast + "）";
        if (!BluetoothAdapter.checkBluetoothAddress(target)) return "ERR|位址格式不對";
        if (selfAddr.isEmpty()) return "ERR|手機藍牙位址拿不到";
        String old = store.get("bleHost", "");
        BleMouse m = ble;
        if (!old.isEmpty() && !old.equalsIgnoreCase(target)) {
            if (m != null && m.connected()) m.hangUpNow();
            try { Object r = android.bluetooth.BluetoothDevice.class.getMethod("removeBond").invoke(ad.getRemoteDevice(old)); log("pair: removed old " + old + " -> " + r); }
            catch (Throwable t) { log("pair: removeBond old " + t); }
        }
        store.put("bleHost", target);
        touched();   // 閒置中就恢復廣播
        final android.bluetooth.BluetoothDevice d = ad.getRemoteDevice(target);
        new Thread(() -> {
            long end = System.currentTimeMillis() + 90_000; boolean confirmed = false;
            try {
                while (System.currentTimeMillis() < end) {
                    int st = d.getBondState();
                    if (st == android.bluetooth.BluetoothDevice.BOND_BONDED && (confirmed || System.currentTimeMillis() > end - 85_000)) { log("pair: bonded " + target); return; }
                    if (st == android.bluetooth.BluetoothDevice.BOND_BONDING && !confirmed) {
                        Thread.sleep(400);
                        confirmed = d.setPairingConfirmation(true);
                        log("pair: confirm " + target + " -> " + confirmed);
                    }
                    Thread.sleep(200);
                }
                log("pair: timeout bond=" + d.getBondState());
            } catch (Exception e) { log("pair: " + e); }
        }, "pair").start();
        log("pair: ready for " + target + (old.isEmpty() || old.equalsIgnoreCase(target) ? "" : " (replaced " + old + ")"));
        return "OK|" + selfAddr;
    }

    static void udpLoop(String token) {
        try (DatagramSocket s = new DatagramSocket(PORT, InetAddress.getByName("127.0.0.1"))) {
            byte[] buf = new byte[256];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String[] f = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\\|");
                if (f.length < 2 || !token.equals(f[0])) continue;
                try {
                    switch (f[1]) {
                        case "W": {   // App 交來 Wi-Fi 電腦位址：W|host|port|wifiToken（給最後一哩用）
                            if (f.length >= 5 && !(f[2].equals(store.get("kickHost", "")) && f[3].equals(store.get("kickPort", "")) && f[4].equals(store.get("kickToken", "")))) {
                                store.put("kickHost", f[2]); store.put("kickPort", f[3]); store.put("kickToken", f[4]);
                                log("kick host set " + f[2] + ":" + f[3]);
                            }
                            break;
                        }
                        case "PAIR": {   // 電腦端圖示要用藍牙連這支手機：PAIR|電腦藍牙位址（XX:XX:…）。取代舊電腦、自動同意這台的配對 90 秒
                            String target = f[2].toUpperCase();
                            String r = pairPrepare(target);
                            byte[] rb = r.getBytes(StandardCharsets.UTF_8);
                            s.send(new DatagramPacket(rb, rb.length, p.getAddress(), p.getPort()));
                            break;
                        }
                        case "M":
                            touched();
                            synchronized (lock) { accX += Integer.parseInt(f[2]); accY += Integer.parseInt(f[3]); accW += Integer.parseInt(f[4]); lock.notifyAll(); }
                            break;
                        case "B":
                            synchronized (lock) { btnQueue.add(Integer.parseInt(f[2])); lock.notifyAll(); }
                            break;
                        case "A":
                            touched();
                            BleMouse m = ble; if (m != null && "1".equals(f[2])) m.ensurePriority();
                            break;
                        case "Q": {   // 要換新版本：先硬斷電腦、等連線真的斷了才退出（服務在連線中消失會讓 Windows 不再自己重連）
                            log("quit requested");
                            BleMouse q = ble;
                            if (q != null && q.connected()) {
                                q.hangUpNow();
                                for (int i = 0; i < 40 && q.connected(); i++) Thread.sleep(50);
                                Thread.sleep(300);
                            }
                            log("bye (connected=" + (q != null && q.connected()) + ")");
                            System.exit(0);
                        }
                        case "P": {
                            byte[] r = status.getBytes(StandardCharsets.UTF_8);
                            s.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
                            break;
                        }
                    }
                } catch (Exception e) { Log.w(TAG, "bad packet " + e); }
            }
        } catch (Exception e) {
            Log.e(TAG, "udp " + e); System.out.println("udp failed: " + e); System.exit(2);
        }
    }

    static void log(String s) { System.out.println(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + " " + s); Log.i(TAG, s); }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].length() < 8) { System.out.println("usage: Daemon <token>"); System.exit(1); }
        final String token = args[0];
        if (args.length > 1) IDLE_MS = Long.parseLong(args[1]) * 1000;
        Looper.prepareMainLooper();
        // Bluetooth 是 mainline 模組，一般 App 啟動時由 ActivityThread 接好服務管理員；這裡要自己叫一次
        Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null);
        final ShellContext ctx = new ShellContext();
        shellCtx = ctx;
        store = new FileStore();
        ble = new BleMouse(ctx, MouseHub.DESCRIPTOR, t -> { status = t; log("status " + t); }, store, true);
        ble.start();
        new Thread(() -> udpLoop(token), "udp").start();
        Thread snd = new Thread(Daemon::senderLoop, "send"); snd.setPriority(Thread.MAX_PRIORITY); snd.start();
        // 藍牙開關：沒有廣播接收器可用，每 20ms 看一次（要搶在系統撤服務前掛斷電腦，見 BleMouse.hangUpNow）
        final BluetoothAdapter a = ((BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        adapter = a;
        final Handler h = new Handler(Looper.myLooper());
        final int[] last = {a.getState()};
        try { String ad = a.getAddress(); if (ad != null && !ad.startsWith("02:00:00")) selfAddr = ad.replace(":", ""); } catch (Exception e) { log("getAddress " + e); }
        if (selfAddr.isEmpty()) selfAddr = store.get("selfAddr", "").replace(":", "");
        log("self address " + (selfAddr.isEmpty() ? "(unknown, kick off)" : selfAddr));
        Thread kt = new Thread(Daemon::kickLoop, "kick"); kt.setDaemon(true); kt.start();
        armKick();
        h.postDelayed(new Runnable() { public void run() {
            int st = a.getState();
            btOn = st == BluetoothAdapter.STATE_ON;
            BleMouse bm = ble;
            if (bm != null && bm.connected()) lastBusy = System.currentTimeMillis();
            else if (!idle && st == BluetoothAdapter.STATE_ON && System.currentTimeMillis() - lastBusy > IDLE_MS && bm != null) {
                idle = true; bm.pauseAdvertising(); log("no PC for 10 min → stop advertising");
            }
            if (st != last[0]) {
                if (st == BluetoothAdapter.STATE_TURNING_OFF) ble.hangUpNow();
                last[0] = st; log("bt state " + st); ble.onAdapterState(st);
                if (st == BluetoothAdapter.STATE_ON) { idle = false; lastBusy = System.currentTimeMillis(); armKick(); }
            }
            h.postDelayed(this, 20);
        }}, 20);
        log("daemon up, port " + PORT);
        Looper.loop();
    }
}
