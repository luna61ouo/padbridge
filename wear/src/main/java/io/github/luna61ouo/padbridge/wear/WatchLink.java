package io.github.luna61ouo.padbridge.wear;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import io.github.luna61ouo.padbridge.watch.WatchProto;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 手錶這端的連線：叫醒手機 → 藍牙直連 → 每 10 ms 送一個狀態框（路上最多 8 框，滿了就把位移累加到下一框）。
 * 畫面只呼叫 move／wheel／click／finger，不管連線細節。
 */
final class WatchLink {
    interface Ui { void onStatus(String text); void onQuality(String pc, int rttMs);     /** 電腦清單：每台 {pcId, 名稱, 在線 "1"/"0", 選中 "1"/"0"}。 */
        void onPcList(java.util.List<String[]> pcs);
    }

    private static final String TAG = "PadbridgeWatch";
    private final Context ctx;
    private final Ui ui;
    private volatile boolean running;
    private volatile BluetoothSocket sock;

    // 累積中、還沒送出去的動作（畫面執行緒寫，送框執行緒讀）
    private final Object lock = new Object();
    private float accX, accY;
    private int accWheel, buttons;
    // 一次點擊＝按下、放開兩框；點兩下＝四框。排在這裡，每框取一個疊在 buttons 上送。
    private final java.util.ArrayDeque<Integer> btnSeq = new java.util.ArrayDeque<>();
    private int lastBtn;
    private volatile boolean finger;
    private volatile long lastActivity;

    // 路上的框
    private final long[] sentAt = new long[65536];
    private volatile int inFlight;
    private volatile String pcName = "";
    private volatile int rttEwma = -1;

    WatchLink(Context c, Ui ui) { this.ctx = c.getApplicationContext(); this.ui = ui; }

    void start() {
        if (running) return;
        running = true;
        new Thread(this::run, "pb-watch-link").start();
    }

    void stop() {
        running = false;
        synchronized (lock) { buttons = 0; btnSeq.clear(); }
        BluetoothSocket s = sock;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    void move(float dx, float dy) { synchronized (lock) { accX += dx; accY += dy; } lastActivity = System.currentTimeMillis(); }
    void wheel(int n) { synchronized (lock) { accWheel += n; } lastActivity = System.currentTimeMillis(); }
    /** 點一下（btn＝WatchProto.BTN_*）。 */
    void click(int btn) { synchronized (lock) { btnSeq.add(btn); btnSeq.add(0); } lastActivity = System.currentTimeMillis(); }
    /** 按住／放開（拖曳用）。 */
    void button(int btn, boolean down) {
        synchronized (lock) { buttons = down ? (buttons | btn) : (buttons & ~btn); }
        lastActivity = System.currentTimeMillis();
    }
    // 控制訊息（要清單、選電腦）：排進送框那條執行緒一起送，不跟狀態框搶 socket
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> ctl = new java.util.concurrent.ConcurrentLinkedQueue<>();
    void requestPcs() { ctl.add(new byte[]{WatchProto.LIST_REQ}); }
    void pick(String pcId) {
        byte[] id = pcId.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[2 + id.length];
        b[0] = WatchProto.PICK; b[1] = (byte) id.length;
        System.arraycopy(id, 0, b, 2, id.length);
        ctl.add(b);
    }
    void finger(boolean down) { finger = down; lastActivity = System.currentTimeMillis(); }

    private void run() {
        int backoff = 1000;
        while (running) {
            try {
                session();
                backoff = 1000;
            } catch (Exception e) {
                Log.i(TAG, "link end " + e);
                if (!running) break;
                ui.onStatus(e.getMessage() == null ? "連線斷了，重連中…" : e.getMessage());
            } finally {
                BluetoothSocket s = sock; sock = null;
                if (s != null) try { s.close(); } catch (Exception ignored) { }
                closeGatt();
            }
            try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
            backoff = Math.min(10_000, backoff * 2);
        }
    }

    /** 一次連線：叫醒手機、連藍牙直連、送框直到斷線。 */
    private void session() throws Exception {
        ui.onStatus("叫醒手機…");
        String[] ready = hello();
        if (!"ok".equals(ready[0])) throw new Exception(ready.length > 1 ? ready[1] : "手機沒回應");
        byte[] token = unhex(ready[1]);
        String btName = ready.length > 2 ? ready[2] : "";
        pcName = ready.length > 3 ? ready[3] : "";
        int psm = ready.length > 4 ? parseInt(ready[4]) : -1;
        ui.onStatus("連線中…");
        BluetoothSocket s = psm > 0 ? connectBle(psm) : null;
        if (s == null) {                                   // 備援：傳統藍牙
            BluetoothDevice phone = findPhone(btName);
            if (phone == null) throw new Exception("找不到配對的手機（藍牙名稱：" + btName + "）");
            s = phone.createRfcommSocketToServiceRecord(WatchProto.RFCOMM_UUID);
            sock = s;
            s.connect();
            transport = "傳統藍牙";
        }
        sock = s;
        OutputStream out = s.getOutputStream();
        byte[] h = new byte[1 + WatchProto.TOKEN_LEN];
        h[0] = WatchProto.HELLO;
        System.arraycopy(token, 0, h, 1, WatchProto.TOKEN_LEN);
        out.write(h); out.flush();
        inFlight = 0; rttEwma = -1;
        final BluetoothSocket rs = s;
        Thread reader = new Thread(() -> read(rs), "pb-watch-read");
        reader.start();
        ui.onQuality(pcName, -1);
        send(out);
    }

    /** 送 HELLO 給附近的手機，等 READY（最多 10 秒）。 */
    private String[] hello() throws Exception {
        List<Node> nodes = Tasks.await(Wearable.getNodeClient(ctx).getConnectedNodes(), 10, TimeUnit.SECONDS);
        Node target = null;
        for (Node n : nodes) if (n.isNearby()) { target = n; break; }
        if (target == null && !nodes.isEmpty()) target = nodes.get(0);
        if (target == null) throw new Exception("手錶沒有連著手機");
        final String[][] got = {null};
        final CountDownLatch done = new CountDownLatch(1);
        MessageClient mc = Wearable.getMessageClient(ctx);
        MessageClient.OnMessageReceivedListener lis = e -> {
            if (!WatchProto.PATH_READY.equals(e.getPath())) return;
            got[0] = new String(e.getData(), StandardCharsets.UTF_8).split("\\|", -1);
            done.countDown();
        };
        Tasks.await(mc.addListener(lis), 10, TimeUnit.SECONDS);
        try {
            Tasks.await(mc.sendMessage(target.getId(), WatchProto.PATH_HELLO, new byte[]{WatchProto.VERSION}), 10, TimeUnit.SECONDS);
            if (!done.await(10, TimeUnit.SECONDS)) throw new Exception("手機沒回應：手機上有裝 Padbridge 嗎？");
            return got[0];
        } finally {
            mc.removeListener(lis);
        }
    }

    volatile String transport = "";
    private android.bluetooth.BluetoothGatt gatt;

    /** BLE：掃手機的廣播 → 開 GATT 連線要求高速檔 → 同一條連線上開 L2CAP。失敗回 null（改走傳統藍牙）。 */
    private BluetoothSocket connectBle(int psm) {
        try {
            android.bluetooth.BluetoothAdapter ad = ctx.getSystemService(BluetoothManager.class).getAdapter();
            android.bluetooth.le.BluetoothLeScanner sc = ad.getBluetoothLeScanner();
            final BluetoothDevice[] found = {null};
            final CountDownLatch fc = new CountDownLatch(1);
            android.bluetooth.le.ScanCallback cb = new android.bluetooth.le.ScanCallback() {
                @Override public void onScanResult(int type, android.bluetooth.le.ScanResult r) { if (found[0] == null) { found[0] = r.getDevice(); fc.countDown(); } }
            };
            sc.startScan(java.util.Collections.singletonList(new android.bluetooth.le.ScanFilter.Builder()
                            .setServiceUuid(new android.os.ParcelUuid(WatchProto.ADV_UUID)).build()),
                    new android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
            boolean ok = fc.await(5, TimeUnit.SECONDS);
            sc.stopScan(cb);
            if (!ok) { Log.i(TAG, "ble: no advert"); return null; }
            BluetoothDevice d = found[0];
            final CountDownLatch gc = new CountDownLatch(1);
            closeGatt();
            gatt = d.connectGatt(ctx, false, new android.bluetooth.BluetoothGattCallback() {
                @Override public void onConnectionStateChange(android.bluetooth.BluetoothGatt g, int st, int ns) {
                    if (ns == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        g.requestConnectionPriority(android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        gc.countDown();
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE);
            if (!gc.await(5, TimeUnit.SECONDS)) { Log.i(TAG, "ble: gatt timeout"); closeGatt(); return null; }
            BluetoothSocket s = d.createInsecureL2capChannel(psm);
            sock = s;
            s.connect();
            transport = "BLE";
            return s;
        } catch (Exception e) {
            Log.i(TAG, "ble failed " + e);
            closeGatt();
            return null;
        }
    }

    private void closeGatt() {
        android.bluetooth.BluetoothGatt g = gatt; gatt = null;
        if (g != null) try { g.disconnect(); g.close(); } catch (Exception ignored) { }
    }

    private static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; } }

    private BluetoothDevice findPhone(String btName) {
        java.util.Set<BluetoothDevice> bonded = ctx.getSystemService(BluetoothManager.class).getAdapter().getBondedDevices();
        for (BluetoothDevice d : bonded) if (btName.equals(d.getName())) return d;
        for (BluetoothDevice d : bonded)
            if (d.getBluetoothClass() != null && d.getBluetoothClass().getMajorDeviceClass() == android.bluetooth.BluetoothClass.Device.Major.PHONE) return d;
        return null;
    }

    private void read(BluetoothSocket s) {
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (true) {
                byte t = in.readByte();
                if (t == WatchProto.ACK) {
                    int seq = in.readUnsignedShort();
                    int rtt = (int) ((System.nanoTime() - sentAt[seq]) / 1_000_000L);
                    rttEwma = rttEwma < 0 ? rtt : (rttEwma * 7 + rtt) / 8;
                    if (inFlight > 0) inFlight--;
                } else if (t == WatchProto.STATUS) {
                    byte[] b = new byte[in.readUnsignedByte()];
                    in.readFully(b);
                    pcName = new String(b, StandardCharsets.UTF_8);
                    ui.onQuality(pcName, rttEwma);
                } else if (t == WatchProto.LIST) {
                    byte[] b = new byte[in.readUnsignedShort()];
                    in.readFully(b);
                    java.util.List<String[]> l = new java.util.ArrayList<>();
                    for (String line : new String(b, StandardCharsets.UTF_8).split("\n")) {
                        String[] c = line.split("\t");
                        if (c.length >= 4) l.add(c);
                    }
                    ui.onPcList(l);
                }
            }
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private void send(OutputStream out) throws Exception {
        int seq = 0;
        long nextUi = 0, lastSent = 0;
        long[] gaps = new long[200]; int ng = 0;
        byte[] f = new byte[WatchProto.FRAME_LEN];
        while (running) {
            long now = System.currentTimeMillis();
            for (byte[] c; (c = ctl.poll()) != null; ) { out.write(c); out.flush(); }
            if (inFlight < WatchProto.WINDOW) {
                int dx, dy, wh, btn; boolean has;
                synchronized (lock) {
                    dx = clamp((int) accX); dy = clamp((int) accY);
                    wh = Math.max(-127, Math.min(127, accWheel));
                    Integer ov = btnSeq.poll();
                    btn = buttons | (ov == null ? 0 : ov);
                    // 手機是先移動再處理按鍵：有新按下的鍵時這框不帶位移，拖曳才會從原地抓起
                    if ((btn & ~lastBtn) != 0) { dx = 0; dy = 0; }
                    has = dx != 0 || dy != 0 || wh != 0 || btn != 0 || btn != lastBtn || finger || now - lastActivity < 1000;
                    if (has) { accX -= dx; accY -= dy; accWheel -= wh; lastBtn = btn; }
                }
                if (has) {
                    f[0] = WatchProto.FRAME;
                    f[1] = (byte) (seq >> 8); f[2] = (byte) seq;
                    f[3] = (byte) (dx >> 8); f[4] = (byte) dx;
                    f[5] = (byte) (dy >> 8); f[6] = (byte) dy;
                    long t = System.nanoTime();
                    long gap = lastSent == 0 ? 0 : t - lastSent;
                    f[7] = (byte) wh; f[8] = (byte) btn; f[9] = (byte) Math.min(255, gap / 1_000_000L);
                    if (gap > 0 && gap < 500_000_000L) { gaps[ng++] = gap; if (ng == gaps.length) { logGaps(gaps); ng = 0; } }
                    lastSent = t;
                    sentAt[seq] = t;
                    inFlight++;
                    out.write(f); out.flush();
                    seq = (seq + 1) & 0xffff;
                }
            }
            if (now >= nextUi) { ui.onQuality(pcName, rttEwma); nextUi = now + 500; }
            Thread.sleep(WatchProto.TICK_MS);
        }
        // 離開前送一框「全部放開」
        f[0] = WatchProto.FRAME; f[1] = (byte) (seq >> 8); f[2] = (byte) seq;
        for (int i = 3; i < 10; i++) f[i] = 0;
        try { out.write(f); out.flush(); } catch (Exception ignored) { }
    }

    private void logGaps(long[] g) {
        long[] s = g.clone(); java.util.Arrays.sort(s);
        int over20 = 0; for (long x : s) if (x > 20_000_000L) over20++;
        Log.i(TAG, String.format(java.util.Locale.US, "send gaps n=%d med=%.1f p90=%.1f max=%.1f over20ms=%d inflight=%d touch=%d/s",
                s.length, s[s.length / 2] / 1e6, s[s.length * 9 / 10] / 1e6, s[s.length - 1] / 1e6, over20, inFlight, touchRate));
    }
    volatile int touchRate;

    private static int clamp(int v) { return Math.max(-32768, Math.min(32767, v)); }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
