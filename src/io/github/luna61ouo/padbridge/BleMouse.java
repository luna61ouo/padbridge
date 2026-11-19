package io.github.luna61ouo.padbridge;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/**
 * BLE 版滑鼠（HID over GATT）。選這條的原因：傳統藍牙 HID 在 HyperOS 上每送一包就切一次省電模式（sniff），
 * 偶發延遲；BLE 由電腦訂固定的連線間隔，沒有這個切換。
 * 送出採流量控制：上一包 onNotificationSent 回來才送下一包，節奏自動跟連線間隔對齊。
 */
final class BleMouse {
    static final String TAG = "Padbridge";

    interface Status { void set(String s); }

    private static UUID u16(int v) { return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v)); }
    static final UUID HID_SVC = u16(0x1812), HID_INFO = u16(0x2A4A), REPORT_MAP = u16(0x2A4B),
            HID_CTRL = u16(0x2A4C), REPORT = u16(0x2A4D), PROTO_MODE = u16(0x2A4E),
            BAT_SVC = u16(0x180F), BAT_LVL = u16(0x2A19),
            DIS_SVC = u16(0x180A), PNP_ID = u16(0x2A50), MANUF = u16(0x2A29),
            CCCD = u16(0x2902), REPORT_REF = u16(0x2908);

    private final Context ctx;
    private final byte[] reportMap;
    private final Status status;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser adv;
    private BluetoothGattCharacteristic reportChar;
    private final ArrayDeque<BluetoothGattService> pending = new ArrayDeque<>();
    private volatile BluetoothDevice host;
    private volatile boolean notifyOn;
    private BluetoothGatt clientForPriority;
    private final Object sentLock = new Object();
    private boolean inFlight;

    /** 設定存取：App 用 SharedPreferences；小幫手（shell 行程，沒有自己的資料夾）用檔案。 */
    interface Store { String get(String k, String def); void put(String k, String v); boolean flag(String k); }

    private final Store store;
    /** 小幫手模式：用手機真實的公開位址廣播（要 BLUETOOTH_PRIVILEGED，只有 shell 身分有）；不註冊廣播接收器（由外面呼叫 onAdapterState）。 */
    private final boolean standalone;

    BleMouse(Context c, byte[] map, Status s) {
        this(c, map, s, new Store() {
            final android.content.SharedPreferences p = c.getSharedPreferences("mouse", Context.MODE_PRIVATE);
            public String get(String k, String def) { return p.getString(k, def); }
            public void put(String k, String v) { p.edit().putString(k, v).apply(); }
            public boolean flag(String k) { return p.getBoolean(k, false); }
        }, false);
    }

    BleMouse(Context c, byte[] map, Status s, Store st, boolean standalone) {
        ctx = c; reportMap = map; status = s; store = st; this.standalone = standalone;
    }

    boolean connected() { return host != null && notifyOn; }

    // 手機藍牙關掉再開時 GATT 伺服器跟廣播都死了，App 原本不知道、狀態停在「已連上」。
    // 收藍牙開關事件：關掉時清狀態，開回來時自己重建（只在使用者要 BLE 模式時）。
    private volatile boolean wantRunning;
    private android.content.BroadcastReceiver btStateRx;
    private final Object discLock = new Object();

    void onAdapterState(int st) {
                Log.i(TAG, "BLE adapter state=" + st);
                if (st == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF || st == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                    // 這裡不能 server.close()——電腦還連著時撤服務，電腦會收到「滑鼠服務消失」，
                    // Windows 就把滑鼠那層刪掉、之後不會自己重連。藍牙關掉時直接放手，讓連線自然斷。
                    abandon();
                    status.set("BLE：手機藍牙關閉，等它開回來");
                } else if (st == android.bluetooth.BluetoothAdapter.STATE_ON && wantRunning) {
                    start();
                }
    }

    private void watchAdapter() {
        if (standalone || btStateRx != null) return;
        btStateRx = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                onAdapterState(i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1));
            }
        };
        ctx.registerReceiver(btStateRx, new android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    void start() {
        wantRunning = true;
        watchAdapter();
        if (server != null) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        try {
            server = bm.openGattServer(ctx, cb);
        } catch (SecurityException se) { status.set("BLE：缺權限"); return; }
        if (server == null) { status.set("BLE：開不了 GATT 服務"); return; }
        adv = bm.getAdapter().getBluetoothLeAdvertiser();

        int RE = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED;
        int WE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED;

        BluetoothGattService hidSvc = new BluetoothGattService(HID_SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        hidSvc.addCharacteristic(ch(HID_INFO, BluetoothGattCharacteristic.PROPERTY_READ, RE));
        hidSvc.addCharacteristic(ch(REPORT_MAP, BluetoothGattCharacteristic.PROPERTY_READ, RE));
        hidSvc.addCharacteristic(ch(HID_CTRL, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, WE));
        hidSvc.addCharacteristic(ch(PROTO_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, RE | WE));
        reportChar = ch(REPORT, BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY, RE);
        reportChar.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED));
        reportChar.addDescriptor(new BluetoothGattDescriptor(REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED));
        hidSvc.addCharacteristic(reportChar);

        BluetoothGattService bat = new BluetoothGattService(BAT_SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic lvl = ch(BAT_LVL,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        lvl.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        bat.addCharacteristic(lvl);

        BluetoothGattService dis = new BluetoothGattService(DIS_SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        dis.addCharacteristic(ch(PNP_ID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
        dis.addCharacteristic(ch(MANUF, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));

        pending.clear();
        pending.add(hidSvc); pending.add(bat); pending.add(dis);
        addNext();
        status.set("BLE：準備中…");
    }

    private static BluetoothGattCharacteristic ch(UUID u, int props, int perms) {
        return new BluetoothGattCharacteristic(u, props, perms);
    }

    private void addNext() {
        BluetoothGattService s = pending.poll();
        try {
            if (s != null) server.addService(s);
            else advertise();
        } catch (SecurityException ignored) {}
    }

    // 先試用固定的公開位址廣播（像一般滑鼠一樣位址不變，Windows 才認得回來、會自動重連）；
    // 系統不准就退回預設的隨機位址。
    // 改用「一組長期的廣播」（AdvertisingSet）。斷線時只暫停／繼續，不再整組停掉重開——
    // 每重開一次手機就換一個化名（隨機位址），而 Windows 重連時撥的是「最後看到的化名」（Windows 藍牙事件追蹤實證），
    // 化名一換它就撥空號、不會自己回來。固定公開位址要 BLUETOOTH_PRIVILEGED，App 拿不到（實測）。
    private android.bluetooth.le.AdvertisingSet advSet;
    private boolean advStarting;

    private int advRetries;
    private final android.os.Handler retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void advertise() {
        if (adv == null) { status.set("BLE：這台不支援廣播"); return; }
        // 固定位址小幫手（adb 身分、公開位址廣播）在跑的時候，App 自己不廣播——否則電腦可能挑到會換化名的那個。
        if (!standalone && store.flag("extAdv")) {
            if (host == null) status.set("BLE：固定位址廣播中（小幫手），等電腦連線");
            return;
        }
        if (advSet != null) {
            try { advSet.enableAdvertising(true, 0, 0); Log.i(TAG, "BLE advertising resume (same set)"); }
            catch (SecurityException se) { status.set("BLE：缺廣播權限"); }
            return;
        }
        if (advStarting) return;
        android.bluetooth.le.AdvertisingSetParameters.Builder params = new android.bluetooth.le.AdvertisingSetParameters.Builder()
                .setLegacyMode(true).setConnectable(true).setScannable(true)
                .setInterval(android.bluetooth.le.AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(android.bluetooth.le.AdvertisingSetParameters.TX_POWER_MEDIUM);
        if (standalone) {
            // 固定位址：0＝ADDRESS_TYPE_PUBLIC（公開 SDK 沒有這個方法，反射呼叫）
            try { android.bluetooth.le.AdvertisingSetParameters.Builder.class.getMethod("setOwnAddressType", int.class).invoke(params, 0); }
            catch (Throwable t) { Log.w(TAG, "BLE setOwnAddressType failed: " + t); }
        }
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(HID_SVC)).build();
        try {
            advStarting = true;
            adv.startAdvertisingSet(params.build(), data, null, null, null, setCb);
        } catch (SecurityException se) { advStarting = false; status.set("BLE：缺廣播權限"); }
    }

    /** 小幫手閒置省電用：沒電腦連時暫停／恢復廣播（同一組廣播、位址不變）。 */
    void pauseAdvertising() { if (host == null) { stopAdvertising(); status.set("BLE：閒置中（碰一下觸控板就恢復）"); } }
    void resumeAdvertising() { if (host == null && server != null) advertise(); }

    /** 暫停廣播（保留同一組、同一個化名）。 */
    private void stopAdvertising() {
        try { if (advSet != null) advSet.enableAdvertising(false, 0, 0); } catch (Exception ignored) {}
    }

    /** 真的收掉整組廣播（只在關閉滑鼠／藍牙關掉時用）。 */
    private void destroyAdvertising() {
        try { if (adv != null) adv.stopAdvertisingSet(setCb); } catch (Exception ignored) {}
        advSet = null; advStarting = false;
    }

    private final android.bluetooth.le.AdvertisingSetCallback setCb = new android.bluetooth.le.AdvertisingSetCallback() {
        @Override public void onAdvertisingSetStarted(android.bluetooth.le.AdvertisingSet set, int txPower, int st) {
            advStarting = false;
            Log.i(TAG, "BLE advertising set started status=" + st);
            if (st == ADVERTISE_SUCCESS) {
                advSet = set;
                if (host == null) status.set("BLE：等電腦連線（電腦藍牙設定 → 新增裝置 → 藍牙 → 選這支手機）");
                advRetries = 0;
            } else {
                status.set("BLE：廣播失敗 " + st);
                // 手機藍牙關 30 秒再開，第一次開廣播回 2（太多廣播者），之後就卡著不重試、電腦永遠連不回來。
                // 收掉這組再重開，每 2 秒一次、最多 15 次。
                if (wantRunning && server != null && advRetries < 15) {
                    advRetries++;
                    retryHandler.postDelayed(() -> {
                        if (!wantRunning || server == null || advSet != null || host != null) return;
                        try { if (adv != null) adv.stopAdvertisingSet(setCb); } catch (Exception ignored) {}
                        advStarting = false;
                        Log.i(TAG, "BLE advertising retry " + advRetries);
                        advertise();
                    }, 2000);
                }
            }
        }
        @Override public void onAdvertisingEnabled(android.bluetooth.le.AdvertisingSet set, boolean en, int st) {
            Log.i(TAG, "BLE advertising enabled=" + en + " status=" + st);
            if (en && st == ADVERTISE_SUCCESS && host == null)
                status.set("BLE：等電腦連線（電腦藍牙設定 → 新增裝置 → 藍牙 → 選這支手機）");
        }
        @Override public void onAdvertisingSetStopped(android.bluetooth.le.AdvertisingSet set) {
            Log.i(TAG, "BLE advertising set stopped");
        }
    };

    void stop() {
        wantRunning = false;
        if (btStateRx != null) { try { ctx.unregisterReceiver(btStateRx); } catch (Exception ignored) {} btStateRx = null; }
        teardown(true);
    }

    /**
     * 原本是 cancelConnection 後立刻 close。close 會把滑鼠服務從手機上撤掉，
     * 這時電腦還連著，會收到「服務變了」⇒ Windows 把滑鼠那層裝置標成不存在，之後就不會自己重連。
     * 改成先斷線、等斷線回報（最多 1.5 秒）再 close。
     */
    /**
     * 手機藍牙要關了——搶在系統撤掉滑鼠服務之前先掛斷電腦。
     * 撤服務時電腦還連著的話，會收到「服務變了」⇒ Windows 把滑鼠那層刪掉、之後不會自己重連。
     */
    void hangUpNow() {
        BluetoothDevice h = host;
        if (h == null) return;
        // cancelConnection 太溫和：實測連線 4.5 秒後才真的斷，系統 0.24 秒就撤服務了。
        // BluetoothDevice.disconnect() 是特權 API（shell 身分有），直接切斷這台的所有連線。
        try { Object r = BluetoothDevice.class.getMethod("disconnect").invoke(h); Log.i(TAG, "BLE hard disconnect before BT off r=" + r); }
        catch (Throwable t) {
            Log.w(TAG, "hard disconnect failed " + t);
            try { BluetoothGattServer sv = server; if (sv != null) sv.cancelConnection(h); } catch (Exception ignored) {}
        }
    }

    private void abandon() {
        advSet = null; advStarting = false;
        server = null; adv = null; host = null; notifyOn = false; clientForPriority = null;
        synchronized (sentLock) { inFlight = false; sentLock.notifyAll(); }
    }

    private void teardown(boolean graceful) {
        destroyAdvertising();
        try {
            if (clientForPriority != null) { clientForPriority.close(); clientForPriority = null; }
            if (server != null) {
                BluetoothDevice h = host;
                if (h != null && graceful) {
                    server.cancelConnection(h);
                    long until = System.currentTimeMillis() + 1500;
                    synchronized (discLock) {
                        while (host != null && System.currentTimeMillis() < until) {
                            try { discLock.wait(100); } catch (InterruptedException ie) { break; }
                        }
                    }
                    Log.i(TAG, "BLE stop: host " + (host == null ? "disconnected" : "still connected") + " before close");
                }
                server.close();
            }
        } catch (Exception ignored) {}
        server = null; host = null; notifyOn = false;
        synchronized (sentLock) { inFlight = false; sentLock.notifyAll(); }
    }

    // 連線間隔（微秒）。由電腦決定，onConnectionUpdated 會更新；送出節奏照它走。
    private volatile int intervalUs = 15000;
    private long nextSendAtNs;

    /**
     * 送一包（4 bytes：按鍵、X、Y、滾輪），兩包之間至少隔一個連線間隔。
     * 實測：三種錯法：
     *   - 每 7ms 硬送（間隔 15ms）→ 手機裡排隊，越滑越延遲
     *   - 等 onNotificationSent 再送 → 小米 1ms 內就回報（只是交給晶片，不是送到電腦），等於每 4ms 狂送，電腦被灌爆斷線
     *   - 所以：照電腦約定的連線間隔計時送出
     * 回傳 false＝這拍沒送出，呼叫端把移動量留到下一拍合併。
     */
    boolean send(byte[] r) {
        BluetoothGattServer s = server; BluetoothDevice d = host;
        if (s == null || d == null || !notifyOn) return false;
        long now = System.nanoTime();
        long wait = nextSendAtNs - now;
        if (wait > 0) {
            try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); } catch (InterruptedException e) { return false; }
        }
        boolean ok;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) ok = s.notifyCharacteristicChanged(d, reportChar, false, r) == 0;
            else { reportChar.setValue(r); ok = s.notifyCharacteristicChanged(d, reportChar, false); }
        } catch (SecurityException se) { ok = false; }
        nextSendAtNs = System.nanoTime() + intervalUs * 1000L;
        return ok;
    }

    /** 隱藏 API（BluetoothGattServerCallback 裡是 @hide，但覆寫一樣會被呼叫）：電腦更新連線參數時通知。 */
    void onInterval(BluetoothDevice d, int interval) {
        if (d.equals(host) && interval > 0) {
            intervalUs = interval * 1250;
            Log.i(TAG, "BLE host interval=" + (intervalUs / 1000f) + "ms");
            if (intervalUs > 16000) ensurePriority();   // 電腦放慢了才去要
        }
    }

    private byte[] valueFor(UUID u) {
        if (u.equals(HID_INFO)) return new byte[]{0x11, 0x01, 0x00, 0x02};
        if (u.equals(REPORT_MAP)) return reportMap;
        if (u.equals(PROTO_MODE)) return new byte[]{0x01};
        if (u.equals(REPORT)) return new byte[]{0, 0, 0, 0};
        if (u.equals(BAT_LVL)) return new byte[]{100};
        if (u.equals(PNP_ID)) return new byte[]{0x02, (byte) 0xD1, 0x18, 0x01, 0x00, 0x01, 0x00};
        if (u.equals(MANUF)) return "Padbridge".getBytes();
        return new byte[0];
    }

    private static byte[] slice(byte[] v, int off) {
        if (off >= v.length) return new byte[0];
        return Arrays.copyOfRange(v, off, v.length);
    }

    private String savedHost() {
        return store.get("bleHost", "");
    }
    private void saveHost(String a) {
        store.put("bleHost", a);
    }

    private long lastPriorityAt;

    /** 觸控開始時呼叫：距離上次要求超過 10 秒就再要求一次高優先，不讓電腦把節奏放慢。 */
    void ensurePriority() {
        BluetoothGatt g = clientForPriority;
        if (g == null || host == null) return;
        if (intervalUs <= 16000) return;   // 電腦給的已經夠快，不去交涉
        long now = System.currentTimeMillis();
        if (now - lastPriorityAt < 10000) return;
        lastPriorityAt = now;
        requestFastest(g);
    }

    /**
     * 要求高優先（HIGH＝11.25～15ms）。
     * ⛔ 不要用 DCK（=3）：實測：，DCK 一送筆電就交涉逾時（TRANSACTION_RESPONSE_TIMEOUT 0x22）斷線。
     * ⛔ 不要頻繁要求：每次都是一次交涉，有失敗斷線的風險 ⇒ 只在電腦把間隔放慢（>16ms）時才要（ensurePriority）。
     */
    private boolean requestFastest(BluetoothGatt g) {
        try { return g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); }
        catch (SecurityException e) { return false; }
    }

    private void bumpPriority(BluetoothDevice d) {
        // GATT 伺服器端沒有 API 要求連線間隔；另開一條 client 連線到同一台電腦（走同一條實體連線）再要求高優先，
        // 讓連線間隔落在 7.5～15ms。失敗也沒關係，電腦會用它自己的預設。
        try {
            clientForPriority = d.connectGatt(ctx, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
                    if (ns == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            // 連上時不主動要求：Windows 一開始常給 7.5ms，要 HIGH 反而變 15ms。
                            // 等它放慢（onInterval 看到 >16ms）再要。
                            boolean ok = true;
                            Log.i(TAG, "BLE priority HIGH requested=" + ok);
                        } catch (SecurityException ignored) {}
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException ignored) {}
    }

    private final BluetoothGattServerCallback cb = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int st, BluetoothGattService s) { addNext(); }

        @Override public void onConnectionStateChange(BluetoothDevice d, int st, int ns) {
            // ⚠️ GATT 伺服器會收到手機上「所有」BLE 連線的事件（智慧眼鏡、戒指等）。
            // 只有真正訂閱滑鼠報告的那台（onDescriptorWriteRequest）才算電腦；其他裝置一律不碰。
            Log.i(TAG, "BLE conn " + d.getAddress() + " state=" + ns + " status=" + st + (d.equals(host) ? " (host)" : ""));
            // 已配對的電腦重連時不會再寫一次訂閱（照規格訂閱狀態要跨連線保留），
            // 所以認得的電腦一連上就直接當成已訂閱。實測：重開 App 後 Windows 接回來但沒重新訂閱，游標不動。
            if (ns == BluetoothProfile.STATE_CONNECTED && d.getAddress().equals(savedHost())) {
                host = d; notifyOn = true;
                stopAdvertising();
                status.set("BLE：已連上 " + d.getAddress());
                bumpPriority(d);
            }
            if (ns == BluetoothProfile.STATE_DISCONNECTED && d.equals(host)) {
                host = null; notifyOn = false;
                synchronized (discLock) { discLock.notifyAll(); }
                if (clientForPriority != null) { try { clientForPriority.close(); } catch (Exception ignored) {} clientForPriority = null; }
                synchronized (sentLock) { inFlight = false; sentLock.notifyAll(); }
                status.set("BLE：電腦已斷線，重新連線中…");
                if (server != null && wantRunning) {
                    // 18:20 起：同一組廣播繼續，不重開（重開會換化名）
                    advertise();
                    // 再由手機主動連回認得的電腦（autoConnect＝對方一出現在範圍內就接上）
                    try { server.connect(d, true); Log.i(TAG, "BLE 主動重連 " + d.getAddress()); }
                    catch (SecurityException ignored) {}
                }
            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice d, int id, int off, BluetoothGattCharacteristic c) {
            try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, off, slice(valueFor(c.getUuid()), off)); }
            catch (SecurityException ignored) {}
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice d, int id, BluetoothGattCharacteristic c,
                                                           boolean prep, boolean needResp, int off, byte[] v) {
            if (needResp) try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, off, null); } catch (SecurityException ignored) {}
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice d, int id, int off, BluetoothGattDescriptor desc) {
            byte[] v;
            if (desc.getUuid().equals(REPORT_REF)) v = new byte[]{0x01, 0x01};   // Report ID 1、Input
            else if (desc.getUuid().equals(CCCD)) v = new byte[]{(byte) (notifyOn ? 1 : 0), 0};
            else v = new byte[0];
            try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, off, slice(v, off)); } catch (SecurityException ignored) {}
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice d, int id, BluetoothGattDescriptor desc,
                                                       boolean prep, boolean needResp, int off, byte[] v) {
            if (desc.getUuid().equals(CCCD) && desc.getCharacteristic().getUuid().equals(REPORT)) {
                notifyOn = v != null && v.length > 0 && (v[0] & 1) == 1;
                boolean newHost = !d.equals(host);
                host = d;
                if (notifyOn) saveHost(d.getAddress());
                if (notifyOn && newHost) { stopAdvertising(); bumpPriority(d); }
                // 實測：Windows 設定期間給 7.5ms，訂閱完 1 秒內就放慢成 56ms（interval=45），造成均勻延遲。
                // 要求 HIGH 會落在 11.25～15ms，所以一定要要求，而且之後觸控開始時再補要求（ensurePriority）。
                String name;
                try { name = d.getName(); } catch (SecurityException e) { name = null; }
                status.set(notifyOn ? "BLE：已連上 " + (name == null ? d.getAddress() : name) : "BLE：電腦取消訂閱");
                Log.i(TAG, "BLE report notify=" + notifyOn);
            }
            if (needResp) try { server.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, off, null); } catch (SecurityException ignored) {}
        }

        @SuppressWarnings("unused")
        public void onConnectionUpdated(BluetoothDevice d, int interval, int latency, int timeout, int status) {
            onInterval(d, interval);
        }

        @Override public void onNotificationSent(BluetoothDevice d, int st) {
            synchronized (sentLock) { inFlight = false; sentLock.notifyAll(); }
        }

        @Override public void onMtuChanged(BluetoothDevice d, int mtu) { Log.i(TAG, "BLE mtu=" + mtu); }
    };
}
