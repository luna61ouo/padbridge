package io.github.luna61ouo.padbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private MouseHub hub;
    private TextView status;
    private Button bleMode, wifiMode, actA, actB;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hub = MouseHub.get(this);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF15121C);
        final int p = dp(12);
        // Android 15 起畫面會畫到狀態列／導覽列底下（edge-to-edge），上下會擠到邊。照系統列實際高度留白。
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(p + bars.left, dp(8) + bars.top, p + bars.right, dp(12) + bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });

        status = new TextView(this);
        status.setTextColor(0xFFE8DDF5);
        status.setTextSize(15);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        LinearLayout modeRow = row();
        bleMode = btn("藍牙", v -> switchMode(MouseHub.MODE_BLE));
        wifiMode = btn("Wi-Fi", v -> switchMode(MouseHub.MODE_WIFI));
        actB = btn("⚙ 設定", v -> settingsDialog());
        modeRow.addView(bleMode, weight());
        modeRow.addView(wifiMode, weight());
        modeRow.addView(actB, weightSmall());
        root.addView(modeRow);

        actA = btn("", v -> onActionA());
        root.addView(actA, new LinearLayout.LayoutParams(-1, -2));

        Touchpad pad = new Touchpad(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = dp(10); lp.bottomMargin = dp(10);
        root.addView(pad, lp);

        LinearLayout btnRow = row();
        btnRow.addView(holdBtn("左鍵", MouseHub.BTN_LEFT), weight());
        btnRow.addView(holdBtn("中鍵", MouseHub.BTN_MIDDLE), weightSmall());
        btnRow.addView(holdBtn("右鍵", MouseHub.BTN_RIGHT), weight());
        root.addView(btnRow, new LinearLayout.LayoutParams(-1, dp(72)));

        TextView sl = new TextView(this);
        sl.setTextColor(0xFF9A8FB0);
        sl.setText("速度");
        sl.setPadding(0, dp(8), 0, 0);
        root.addView(sl);
        SeekBar sb = new SeekBar(this);
        sb.setMax(14);
        sb.setProgress(Math.round(hub.sensitivity() * 10) - 1);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean f) { hub.setSensitivity((v + 1) / 10f); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);

        setContentView(root);
        askPermissions();
        handlePairLink(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handlePairLink(i); }

    /** 掃到電腦上的 Padbridge 圖示的配對 QR（padbridge://pair#…）：加進電腦清單、切過去、找一次確認連得到。 */
    private void handlePairLink(Intent i) {
        if (i == null || i.getData() == null || !"padbridge".equals(i.getData().getScheme())) return;
        String frag = i.getData().getEncodedFragment();
        if (frag == null) return;
        java.util.Map<String, String> q = new java.util.HashMap<>();
        for (String kv : frag.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) try { q.put(kv.substring(0, e), java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8")); } catch (Exception ignored) {}
        }
        String pc = q.get("pc"), ip = q.get("ip"), t = q.get("t");
        if (pc == null || ip == null || t == null || !pc.matches("[0-9a-f]{4,32}") || !ip.matches("[0-9.]{7,15}") || !t.matches("[0-9a-f]{16,64}")) {
            new AlertDialog.Builder(this).setMessage("這個 QR 看不懂，請在電腦上的 Padbridge 圖示重新顯示一次。").setPositiveButton("好", null).show();
            return;
        }
        int port = 47801; try { port = Integer.parseInt(q.get("port")); } catch (Exception ignored) {}
        final PcList.Pc p = hub.pcs().addFromQr(pc, ip, port, t, q.get("n"));
        setIntent(new Intent());   // 轉向／回前景時別再處理一次
        hub.setMode(MouseHub.MODE_WIFI); hub.stopBle();
        hub.selectPc(p.id);
        refresh(hub.statusText());
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("加電腦").setMessage("正在連「" + p.label() + "」…").setPositiveButton("好", null).show();
        new Thread(() -> {
            hub.pcs().discover();
            boolean ok = p.online;
            ui.post(() -> { d.setMessage(ok ? "✓ 加好了：" + p.label() + "\n已經切到這台，可以用 Wi-Fi 滑鼠了。" : "已加入「" + p.label() + "」，但現在找不到它：手機跟電腦要在同一個 Wi-Fi。"); refresh(hub.statusText()); });
            hub.tellPcMode();
        }).start();
    }

    // 小米等手機的相機掃到配對 QR 只會給「複製文字」：回到 App 時看剪貼簿，有配對網址就問要不要加
    private String lastClipAsked = "";
    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (!has) return;
        try {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return;
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
            final String t = cs == null ? "" : cs.toString().trim();
            if (!t.startsWith("padbridge://pair#") || t.equals(lastClipAsked)) return;
            lastClipAsked = t;
            new AlertDialog.Builder(this).setTitle("剪貼簿裡有電腦的配對 QR")
                    .setMessage("要把這台電腦加進來嗎？")
                    .setPositiveButton("加入", (d, w) -> {
                        handlePairLink(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(t)));
                        try { cm.clearPrimaryClip(); } catch (Exception ignored) {}   // 配對碼別一直留在剪貼簿
                    })
                    .setNegativeButton("不要", null).show();
        } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        hub.setListener(t -> ui.post(() -> refresh(t)));
        hub.tellPcMode();
    }

    @Override protected void onPause() {
        hub.setListener(null);
        super.onPause();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String s : new String[]{"android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_ADVERTISE", "android.permission.POST_NOTIFICATIONS"}) {
            if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) need.add(s);
        }
        if (need.isEmpty()) startMouseService();
        else requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        startMouseService();
    }

    private void startMouseService() {
        if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            refresh("藍牙權限沒開：設定 → 應用程式 → Padbridge → 權限 → 附近的裝置");
            return;
        }
        startForegroundService(new Intent(this, MouseService.class));
    }

    private void switchMode(int m) {
        hub.setMode(m);
        if (m != MouseHub.MODE_BLE) hub.stopBle();
        if (m == MouseHub.MODE_BLE) hub.startBle();
        refresh(hub.statusText());
    }

    private void refresh(String text) {
        status.setText(text);
        int m = hub.mode();
        bleMode.setAlpha(m == MouseHub.MODE_BLE ? 1f : 0.45f);
        wifiMode.setAlpha(m == MouseHub.MODE_WIFI ? 1f : 0.45f);
        PcList.Pc sel = hub.pcs().selected();
        actA.setVisibility(m == MouseHub.MODE_WIFI ? View.VISIBLE : View.GONE);
        actA.setText("選電腦：" + (sel == null ? "（還沒選）" : sel.label()) + "  ▾");
    }

    private void onActionA() { pcDialog(); }

    // ---------- 設定（照 MouseSettings.ALL 自動長出來）----------
    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final MouseSettings st = hub.settings();
        TextView nl = new TextView(this);
        nl.setText("這支手機的名稱（電腦上顯示）");
        nl.setTextSize(16);
        final EditText nameEt = new EditText(this);
        nameEt.setSingleLine(true);
        nameEt.setText(st.phoneName(this));
        box.addView(nl);
        box.addView(nameEt);
        for (MouseSettings.Toggle t : MouseSettings.ALL) {
            android.widget.Switch sw = new android.widget.Switch(this);
            sw.setText(t.title);
            sw.setTextSize(16);
            sw.setChecked(st.on(t.key));
            sw.setOnCheckedChangeListener((b, on) -> {
                st.set(t.key, on);
                if (MouseSettings.FIXED_ADDRESS.equals(t.key) && hub.mode() == MouseHub.MODE_BLE) { hub.stopBle(); hub.startBle(); }
                refresh(hub.statusText());
            });
            TextView help = new TextView(this);
            help.setText(t.help);
            help.setTextColor(0xFF9A8FB0);
            help.setTextSize(13);
            help.setPadding(0, 0, 0, dp(14));
            box.addView(sw);
            box.addView(help);
        }
        Button test = new Button(this);
        test.setText("測試：游標畫一個圈");
        test.setOnClickListener(v -> onActionB());
        box.addView(test);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("設定").setView(sv).setPositiveButton("好", (d, w) -> {
            String n = nameEt.getText().toString().trim();
            if (!n.equals(st.phoneName(this))) { st.setPhoneName(n); hub.tellPcMode(); }
        }).show();
    }

    // ---------- 電腦清單----------
    // 連得到的在上面、可以點；存過但現在連不到的排下面、灰色。長按：改名／刪除。
    private void pcDialog() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final TextView hint = new TextView(this);
        hint.setTextColor(0xFF9A8FB0);
        hint.setText("找電腦中…");
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        final Button noApp = new Button(this);
        noApp.setAllCaps(false);
        noApp.setText("電腦還沒裝 Padbridge？");
        box.addView(noApp);
        box.addView(hint);
        box.addView(listBox);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Wi-Fi：用哪台電腦")
                .setView(box)
                .setPositiveButton("掃 QR 加電腦", (x, w) -> startActivity(new Intent(this, ScanActivity.class)))
                .setNeutralButton("手動輸入", (x, w) -> wifiDialog())
                .setNegativeButton("關閉", null)
                .create();
        noApp.setOnClickListener(v -> { d.dismiss(); setupDialog(); });
        final boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            listBox.removeAllViews();
            List<PcList.Pc> l = hub.pcs().sorted();
            PcList.Pc sel = hub.pcs().selected();
            hint.setText(l.isEmpty() ? "還沒有電腦。電腦裝好 Padbridge 後，在右下角圖示右鍵選「加一支手機（掃 QR）」，再按下面的「掃 QR 加電腦」。" : "點一下切換；長按可改名或刪除");
            for (PcList.Pc pc : l) {
                Button b = new Button(this);
                boolean isSel = sel != null && sel.id.equals(pc.id);
                b.setAllCaps(false);
                b.setText((isSel ? "● " : "") + pc.label() + (pc.online ? "" : "\n連不到" + (pc.ip.isEmpty() ? "" : "（上次 " + pc.ip + "）")));
                b.setAlpha(pc.online ? 1f : 0.4f);
                b.setEnabled(true);
                b.setOnClickListener(v -> {
                    if (!pc.online) { b.setText(pc.label() + "\n現在連不到：電腦沒開、或不在同一個 Wi-Fi"); return; }
                    hub.selectPc(pc.id); refresh(hub.statusText()); d.dismiss();
                });
                b.setOnLongClickListener(v -> { pcMenu(pc, render[0]); return true; });
                listBox.addView(b);
            }
        };
        render[0].run();
        d.show();
        new Thread(() -> {
            while (open[0]) {
                hub.pcs().discover();
                ui.post(() -> { if (open[0]) render[0].run(); });
                try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
            }
        }, "pc-discover").start();
    }

    // ---------- 電腦還沒裝 Padbridge ----------
    // 主路：GitHub Releases（瀏覽器本來就信任的 HTTPS）。備用：手機在區網開小網站，讓使用者自己選快速或高安全性。
    private void setupDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setText("在電腦的瀏覽器打開這個網址，下載 PadbridgeSetup-windows zip：");
        TextView url = new TextView(this);
        url.setTextSize(16);
        url.setTextColor(0xFFBFA8FF);
        url.setTextIsSelectable(true);
        url.setText(SetupServer.RELEASES_URL);
        url.setPadding(0, dp(6), 0, dp(6));
        TextView after = new TextView(this);
        after.setTextColor(0xFF9A8FB0);
        after.setTextSize(13);
        after.setText("解壓後雙擊 PadbridgeSetup.cmd。裝好後在電腦右下角的 Padbridge 圖示按右鍵 →「加一支手機（掃 QR）」，回來按「掃 QR 加電腦」。\n\n"
                + "網址太長不好打？可以讓電腦直接連到這支手機（要在同一個 Wi-Fi）。");
        box.addView(t); box.addView(url); box.addView(after);
        new AlertDialog.Builder(this).setTitle("電腦還沒裝 Padbridge？").setView(box)
                .setPositiveButton("複製網址", (x, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Padbridge", SetupServer.RELEASES_URL));
                })
                .setNeutralButton("讓電腦連到手機", (x, w) -> lanSetupDialog())
                .setNegativeButton("關閉", null).show();
    }

    private void lanSetupDialog() {
        if (PcList.wifiAddress() == null) {
            new AlertDialog.Builder(this).setMessage("手機現在沒連 Wi-Fi。請讓手機跟電腦連到同一個 Wi-Fi 再試。").setPositiveButton("好", null).show();
            return;
        }
        final boolean openWifi = PcList.unsafeWifiReason(this) != null;
        final TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setPadding(dp(20), dp(8), dp(20), 0);
        tv.setTextIsSelectable(true);
        final String[] last = {"等電腦連過來…"};
        final SetupServer[] srv = new SetupServer[1];
        srv[0] = new SetupServer(this, openWifi, (text, done) -> ui.post(() -> { last[0] = text; showLan(tv, srv[0], openWifi, last[0]); }));
        srv[0].start();
        showLan(tv, srv[0], openWifi, last[0]);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("讓電腦從這支手機下載").setView(sv)
                .setPositiveButton("關閉", null).create();
        d.setOnDismissListener(x -> srv[0].stop());
        d.show();
    }

    private void showLan(TextView tv, SetupServer s, boolean openWifi, String ev) {
        String u = s.url();
        tv.setText("在電腦的瀏覽器網址列輸入：\n\n    " + (u == null ? "（手機沒連 Wi-Fi）" : u) + "\n\n"
                + "網頁上有兩種下載方式，請電腦那邊自己選：\n"
                + "⚡ 快速直接下載：從手機拿安裝包。只在自己信任的網路（家裡、公司）用。\n"
                + "🔒 高安全性下載：轉到 GitHub 官方頁面下載，檔案不會被半路換掉。\n\n"
                + (openWifi ? "⚠️ 現在是開放的公共 Wi-Fi，建議選高安全性下載。\n\n" : "")
                + "安裝包的 SHA-256（可以跟電腦上 certutil 算出來的對照）：\n" + s.sha256() + "\n\n"
                + "狀態：" + ev + "\n（關掉這個視窗，小網站就會關閉；最多開 10 分鐘）");
    }

    private void pcMenu(PcList.Pc pc, Runnable after) {
        new AlertDialog.Builder(this)
                .setTitle(pc.label())
                .setItems(new String[]{"改名稱", "刪除這台"}, (x, which) -> {
                    if (which == 0) {
                        final EditText e = new EditText(this);
                        e.setText(pc.name.isEmpty() ? pc.hostname : pc.name);
                        new AlertDialog.Builder(this).setTitle("新名稱（電腦上的 Padbridge 圖示也會跟著改）").setView(e)
                                .setPositiveButton("好", (y, w) -> {
                                    String n = e.getText().toString().replace("|", " ").trim();
                                    pc.name = n; hub.pcs().save();
                                    if (pc.online) hub.pcs().send(pc, "n|" + n);
                                    after.run();
                                }).setNegativeButton("取消", null).show();
                    } else {
                        new AlertDialog.Builder(this).setMessage("從手機刪掉「" + pc.label() + "」？電腦上的程式不會被移除。")
                                .setPositiveButton("刪除", (y, w) -> { hub.pcs().remove(pc.id); refresh(hub.statusText()); after.run(); })
                                .setNegativeButton("取消", null).show();
                    }
                }).show();
    }


    private void onActionB() {
        for (int k = 0; k < 36; k++) {
            final double a = k * Math.PI / 18;
            ui.postDelayed(() -> hub.move((int) (Math.cos(a) * 18), (int) (Math.sin(a) * 18)), k * 25L);
        }
    }

    private void wifiDialog() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText host = new EditText(this);
        host.setHint("電腦 IP（Padbridge 圖示右鍵選單裡，例：192.168.1.23）");

        final EditText port = new EditText(this);
        port.setHint("port");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText("47801");
        final EditText token = new EditText(this);
        token.setHint("配對碼（Padbridge 圖示 →「加一支手機」視窗下方）");
        f.addView(host); f.addView(port); f.addView(token);
        new AlertDialog.Builder(this)
                .setTitle("手動加一台電腦")
                .setView(f)
                .setPositiveButton("儲存", (d, w) -> {
                    int pt = 0;
                    try { pt = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) {}
                    hub.setWifi(host.getText().toString(), pt, token.getText().toString());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 小工具 ----------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -1, 1f); }
    private LinearLayout.LayoutParams weightSmall() { return new LinearLayout.LayoutParams(0, -1, 0.6f); }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Button holdBtn(String t, final int which) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                hub.setActive(true);
                v.setPressed(true);
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                hub.button(which, true);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                hub.button(which, false);
                hub.setActive(false);
            }
            return true;
        });
        return b;
    }

    /**
     * 觸控板：單指滑＝移動、單點＝左鍵、雙指點＝右鍵、雙指上下滑＝捲動、
     * 單指按住不動 0.45 秒＝按住左鍵（拖曳），放開手指就放開。
     */
    class Touchpad extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX, lastY, startX, startY, remX, remY, scrollAcc, lastAvgY;
        private long downAt;
        private int maxPtr;
        private boolean moved, dragging;
        private final Runnable longPress = () -> {
            if (!moved && maxPtr == 1) {
                dragging = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                hub.button(MouseHub.BTN_LEFT, true);
            }
        };

        Touchpad(Context c) {
            super(c);
            bg.setColor(0xFF2A2338);
            txt.setColor(0xFF6E6382);
            txt.setTextSize(dp(13));
            txt.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            c.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), dp(18), dp(18), bg);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            c.drawText("單指滑＝移動　點一下＝左鍵", cx, cy - dp(22), txt);
            c.drawText("雙指點＝右鍵　雙指上下滑＝捲動", cx, cy, txt);
            c.drawText("按住不動一下再滑＝拖曳", cx, cy + dp(22), txt);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 不等畫面更新才批次交付，觸控事件用感測器原本的頻率送進來，移動更連續
                    if (android.os.Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e);
                    downAt = e.getEventTime();
                    startX = lastX = e.getX(); startY = lastY = e.getY();
                    remX = remY = 0; scrollAcc = 0;
                    maxPtr = 1; moved = false; dragging = false;
                    hub.setActive(true);
                    postDelayed(longPress, 450);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    maxPtr = Math.max(maxPtr, e.getPointerCount());
                    lastAvgY = avgY(e);
                    removeCallbacks(longPress);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() >= 2) {
                        float ay = avgY(e);
                        scrollAcc += ay - lastAvgY;
                        lastAvgY = ay;
                        int step = dp(28);
                        int n = (int) (scrollAcc / step);
                        if (n != 0) { hub.wheel(n); scrollAcc -= n * step; moved = true; }
                    } else if (maxPtr == 1) {
                        float x = e.getX(), y = e.getY();
                        float dx = x - lastX, dy = y - lastY;
                        lastX = x; lastY = y;
                        if (Math.hypot(x - startX, y - startY) > dp(6)) {
                            if (!moved) removeCallbacks(longPress);
                            moved = true;
                        }
                        // 線性、不自己加速：Windows 本身已經有指標加速，兩層疊在一起會忽快忽慢
                        float gain = hub.sensitivity();
                        remX += dx * gain; remY += dy * gain;
                        int ix = (int) remX, iy = (int) remY;
                        remX -= ix; remY -= iy;
                        hub.move(ix, iy);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    // 剩一指時以它為新的起點，避免游標跳
                    int keep = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(keep); lastY = e.getY(keep);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    hub.setActive(false);
                    long dur = e.getEventTime() - downAt;
                    if (dragging) {
                        hub.button(MouseHub.BTN_LEFT, false);
                    } else if (!moved && dur < 280 && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        hub.click(maxPtr >= 2 ? MouseHub.BTN_RIGHT : MouseHub.BTN_LEFT);
                    }
                    dragging = false;
                    break;
            }
            return true;
        }

        private float avgY(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
            return s / e.getPointerCount();
        }
    }
}
