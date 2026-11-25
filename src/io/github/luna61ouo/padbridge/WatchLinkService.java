package io.github.luna61ouo.padbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.wearable.Wearable;

import io.github.luna61ouo.padbridge.watch.WatchProto;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 手錶連線（前景服務）：聽藍牙直連，把手錶送來的位移、滾輪、按鍵交給 MouseHub（跟手機觸控板同一個出口）。
 * 手錶被叫醒後才開；手錶斷線 60 秒沒再連回來就自己關。
 */
public class WatchLinkService extends Service {
    static final String TAG = MouseHub.TAG;
    private static final long IDLE_STOP_MS = 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final byte[] token = new byte[WatchProto.TOKEN_LEN];
    private volatile BluetoothServerSocket server, l2server;
    private volatile int psm = -1;
    private android.bluetooth.le.AdvertiseCallback advCb;
    private volatile BluetoothSocket current;
    private volatile boolean running;
    private final Runnable idleStop = this::stopSelf;
    /** 手錶連著時讓處理器保持清醒：手機鎖屏時 CPU 會休眠，計時器不準、藍牙收到的框會被攢成一批才處理（實測一坨一坨）。 */
    private android.os.PowerManager.WakeLock cpu;
    private synchronized void awake(boolean on) {
        if (cpu == null) { cpu = getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "padbridge:watch"); cpu.setReferenceCounted(false); }
        if (on && !cpu.isHeld()) cpu.acquire(2 * 60 * 60 * 1000L);
        else if (!on && cpu.isHeld()) cpu.release();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        new SecureRandom().nextBytes(token);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(7, notification("手錶滑鼠：等手錶連上"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        if (!running) {
            running = true;
            new Thread(this::serve, "pb-watch-server").start();
            new Thread(this::serveBle, "pb-watch-ble").start();
            for (int i = 0; i < 20 && psm < 0; i++) try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
        String node = intent == null ? null : intent.getStringExtra("node");
        if (node != null) sendReady(node);
        main.removeCallbacks(idleStop);
        if (current == null) main.postDelayed(idleStop, IDLE_STOP_MS);
        return START_NOT_STICKY;
    }

    private void sendReady(String node) {
        String bt = "";
        try { bt = getSystemService(BluetoothManager.class).getAdapter().getName(); } catch (Exception ignored) { }
        PcList.Pc pc = MouseHub.get(this).pcs().selected();
        String msg = "ok|" + hex(token) + "|" + bt + "|" + (pc == null ? "" : pc.label()) + "|" + psm;
        Wearable.getMessageClient(this).sendMessage(node, WatchProto.PATH_READY, msg.getBytes(StandardCharsets.UTF_8));
    }

    private void serve() {
        try {
            BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
            server = ad.listenUsingRfcommWithServiceRecord("Padbridge", WatchProto.RFCOMM_UUID);
            while (running) {
                BluetoothSocket s = server.accept();
                handle(s);
            }
        } catch (Exception e) {
            if (running) Log.w(TAG, "watch server end " + e);
        }
    }

    /** BLE：開 L2CAP 通道、用服務 UUID 廣播（可連線）。通關碼照樣要對，才收動作。 */
    private void serveBle() {
        try {
            BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
            l2server = ad.listenUsingInsecureL2capChannel();
            psm = l2server.getPsm();
            advCb = new android.bluetooth.le.AdvertiseCallback() {
                @Override public void onStartFailure(int code) { Log.w(TAG, "watch ble advertise failed " + code); }
            };
            ad.getBluetoothLeAdvertiser().startAdvertising(new android.bluetooth.le.AdvertiseSettings.Builder()
                            .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setConnectable(true).setTimeout(0)
                            .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
                    new android.bluetooth.le.AdvertiseData.Builder().setIncludeDeviceName(false)
                            .addServiceUuid(new android.os.ParcelUuid(WatchProto.ADV_UUID)).build(), advCb);
            Log.i(TAG, "watch ble psm=" + psm);
            while (running) handle(l2server.accept());
        } catch (Exception e) {
            if (running) Log.w(TAG, "watch ble server end " + e);
            psm = -1;
        }
    }

    /** 一次只服務一支手錶；新的連進來就換掉舊的。 */
    private void handle(BluetoothSocket s) {
        BluetoothSocket old = current;
        current = s;
        if (old != null) try { old.close(); } catch (Exception ignored) { }
        main.removeCallbacks(idleStop);
        new Thread(() -> link(s), "pb-watch-link").start();
    }

    private void link(BluetoothSocket s) {
        MouseHub hub = MouseHub.get(this);
        WatchSmoother sm = null;
        // 先等通關碼：3 秒內沒給對就斷。
        Runnable guard = () -> { try { s.close(); } catch (Exception ignored) { } };
        main.postDelayed(guard, 3000);
        try (BluetoothSocket sock = s) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            if (in.readByte() != WatchProto.HELLO) return;
            byte[] t = new byte[WatchProto.TOKEN_LEN];
            in.readFully(t);
            if (!Arrays.equals(t, token)) { Log.w(TAG, "watch: wrong token"); return; }
            main.removeCallbacks(guard);
            Log.i(TAG, "watch connected " + sock.getRemoteDevice().getAddress());
            awake(true);
            updateNotification("手錶滑鼠：手錶已連上");
            PcList.Pc pc = hub.pcs().selected();
            status(out, pc == null ? "手機沒有選電腦" : pc.label());
            hub.setActive(true);
            // 預設到就送（11-23 22:27 定）：電腦那段的一坨一坨是手機晶片藍牙／Wi-Fi 共存排程造成的，
            // 發生在平順器之後，手機端重排救不到，只會多 10～60 ms 延遲。實驗用：adb op=flag name=watchSmooth on=true 打開。
            sm = new WatchSmoother(hub, hub.settings().watchSensitivity(),
                    !getSharedPreferences("mouse", MODE_PRIVATE).getBoolean("watchSmooth", false));
            byte[] f = new byte[WatchProto.FRAME_LEN - 1];
            byte[] ack = new byte[3];
            String shownPc = pc == null ? "" : pc.label();
            long lastArr = 0, lastPcCheck = 0;
            long[] gaps = new long[200]; int ng = 0;   // 讀數：框到手機的間隔（找「一坨一坨到」在哪一段）
            while (true) {
                byte kind = in.readByte();
                if (kind == WatchProto.LIST_REQ) { sendPcList(hub, out, true); continue; }
                if (kind == WatchProto.PICK) {
                    byte[] id = new byte[in.readUnsignedByte()];
                    in.readFully(id);
                    String pid = new String(id, StandardCharsets.UTF_8);
                    if (hub.pcs().byId(pid) != null) { hub.pcs().select(pid); main.post(() -> hub.selectPc(pid)); Log.i(TAG, "watch picked pc " + pid); }
                    lastPcCheck = 0;   // 下一框就把新名稱告訴手錶
                    sendPcList(hub, out, false);
                    continue;
                }
                if (kind != WatchProto.FRAME) continue;
                in.readFully(f);
                int seq = ((f[0] & 0xff) << 8) | (f[1] & 0xff);
                int dx = (short) (((f[2] & 0xff) << 8) | (f[3] & 0xff));
                int dy = (short) (((f[4] & 0xff) << 8) | (f[5] & 0xff));
                int wheel = f[6];
                int btn = f[7] & 0xff;
                ack[0] = WatchProto.ACK; ack[1] = f[0]; ack[2] = f[1];
                synchronized (out) { out.write(ack); out.flush(); }
                long now = System.nanoTime();
                if (lastArr != 0) {
                    long g = now - lastArr;
                    if (g > 500_000_000L) ng = 0;            // 停手超過半秒：新的一段
                    else { gaps[ng++] = g; if (ng == gaps.length) { logGaps(gaps, f[8] & 0xff); Log.i(TAG, "watch smooth budget " + Math.round(sm.budgetMs) + "ms"); ng = 0; } }
                }
                lastArr = now;
                if (now - lastPcCheck > 1_000_000_000L) {   // 手機換選電腦：告訴手錶
                    lastPcCheck = now;
                    PcList.Pc cur = hub.pcs().selected();
                    String nm = cur == null ? "手機沒有選電腦" : cur.label();
                    if (!nm.equals(shownPc)) { shownPc = nm; status(out, nm); }
                }
                sm.frame(f[8] & 0xff, dx, dy, wheel, btn);
            }
        } catch (Exception e) {
            Log.i(TAG, "watch link end " + e);
        } finally {
            main.removeCallbacks(guard);
            // 斷線時一律放開：拖曳到一半手錶斷了，電腦的左鍵不能一直按著（WatchSmoother.stop 會做）。
            if (sm != null) sm.stop();
            if (current == s) {
                current = null;
                awake(false);
                updateNotification("手錶滑鼠：等手錶連上");
                main.postDelayed(idleStop, IDLE_STOP_MS);
            }
        }
    }

    private static void logGaps(long[] g, int lastSendGapMs) {
        long[] s = g.clone(); java.util.Arrays.sort(s);
        int over20 = 0; for (long x : s) if (x > 20_000_000L) over20++;
        Log.i(TAG, String.format(java.util.Locale.US, "watch arrivals n=%d med=%.1f p90=%.1f max=%.1f over20ms=%d (watch send gap %dms)",
                s.length, s[s.length / 2] / 1e6, s[s.length * 9 / 10] / 1e6, s[s.length - 1] / 1e6, over20, lastSendGapMs));
    }

    /** 電腦清單給手錶：先送一次手上有的，要的話再找一次電腦（約 0.8 秒）、送更新後的（在線狀態）。 */
    private void sendPcList(MouseHub hub, OutputStream out, boolean discover) {
        try { writePcList(hub, out); } catch (Exception e) { Log.w(TAG, "watch pc list " + e); }
        if (!discover) return;
        new Thread(() -> {
            try { hub.pcs().discover(); writePcList(hub, out); } catch (Exception e) { Log.w(TAG, "watch pc list " + e); }
        }, "pb-watch-pcs").start();
    }

    private static void writePcList(MouseHub hub, OutputStream out) throws java.io.IOException {
        PcList.Pc sel = hub.pcs().selected();
        StringBuilder sb = new StringBuilder();
        for (PcList.Pc p : hub.pcs().sorted())
            sb.append(p.id).append('\t').append(p.label().replace('\t', ' ').replace('\n', ' ')).append('\t')
              .append(p.online ? '1' : '0').append('\t').append(sel != null && sel.id.equals(p.id) ? '1' : '0').append('\n');
        byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
        int n = Math.min(65535, b.length);
        byte[] f = new byte[3 + n];
        f[0] = WatchProto.LIST; f[1] = (byte) (n >> 8); f[2] = (byte) n;
        System.arraycopy(b, 0, f, 3, n);
        synchronized (out) { out.write(f); out.flush(); }
    }

    private static void status(OutputStream out, String text) throws java.io.IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(255, b.length);
        byte[] f = new byte[2 + n];
        f[0] = WatchProto.STATUS; f[1] = (byte) n;
        System.arraycopy(b, 0, f, 2, n);
        synchronized (out) { out.write(f); out.flush(); }
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("watch", "手錶滑鼠", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "watch").setContentTitle("Padbridge").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(pi).setOngoing(true).build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(7, notification(text));
    }

    @Override public void onDestroy() {
        running = false;
        awake(false);
        main.removeCallbacksAndMessages(null);
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (l2server != null) l2server.close(); } catch (Exception ignored) { }
        try { if (advCb != null) getSystemService(BluetoothManager.class).getAdapter().getBluetoothLeAdvertiser().stopAdvertising(advCb); } catch (Exception ignored) { }
        try { if (current != null) current.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
