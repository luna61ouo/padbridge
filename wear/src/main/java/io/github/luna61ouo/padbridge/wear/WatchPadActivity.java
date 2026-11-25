package io.github.luna61ouo.padbridge.wear;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 手錶觸控板：整個螢幕滑動＝移動游標、轉錶圈＝滾輪；
 * 點一下＝左鍵、點兩下＝雙擊、點一下再按住滑（或點一下再按住不動）＝拖曳、按住不動 0.5 秒＝右鍵（震一下）。
 * 判斷方式跟手機觸控板一樣（MainActivity.Touchpad）：單點先等 250 ms，看會不會變成雙擊或點後拖。
 */
public class WatchPadActivity extends Activity implements WatchLink.Ui {
    private WatchLink link;
    private TextView top, bottom;
    private float lastX, lastY, downX, downY;
    private long downAt;
    private boolean moved, second, tapPending, dragging, longFired, multi;
    private AirMotion air;
    private FrameLayout root;
    private android.widget.ScrollView menu;
    private android.widget.LinearLayout menuBox;
    private java.util.List<String[]> pcs = new java.util.ArrayList<>();
    private static final long TAP_WINDOW = 250, TAP_MAX = 280, LONG_MS = 500;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable flushTap = () -> { if (tapPending) { tapPending = false; link.click(1); } };
    private final Runnable longPress = () -> {
        if (moved) return;
        longFired = true;
        if (second) {   // 點一下再按住不動＝開始拖曳（前一下不另外點）
            second = false; tapPending = false; dragging = true;
            buzz(25); link.button(1, true);
        } else {        // 按住不動＝右鍵
            buzz(45); link.click(2);
        }
    };
    private float wheelAcc;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        Pad pad = new Pad();
        // 實驗（11-23）：三星「通用手勢」（捏手指／握拳）是協助工具，會把焦點移到可點的元件再「點」它。
        // 觸控板設成可聚焦、可點：通用手勢的「點」＝左鍵。觸控本身走 onTouchEvent，不經 onClick。
        pad.setFocusable(true);
        pad.setContentDescription("Padbridge 觸控板");
        pad.setOnClickListener(v -> { android.util.Log.i("PadbridgeWatch", "a11y click"); link.click(1); });
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        top = label(13, Color.rgb(0xC9, 0xB8, 0xF0));
        bottom = label(11, Color.rgb(0x8A, 0x80, 0xA0));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = dp(26);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bl.bottomMargin = dp(26);
        root.addView(top, tl);
        root.addView(bottom, bl);
        setContentView(root);
        link = new WatchLink(this, this);
        air = new AirMotion(this, link);
        top.setText("Padbridge");
        bottom.setText("");
    }

    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            top.setText("需要「附近的裝置」權限");
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 1);
            return;
        }
        link.start();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) link.start();
        else top.setText("沒有權限，沒辦法連手機");
    }

    @Override protected void onPause() {
        h.removeCallbacks(longPress); h.removeCallbacks(flushTap);
        flushTap.run();
        dragging = false;
        air.set(false);
        closeMenu();
        link.stop();
        super.onPause();
    }

    @Override public void onStatus(String text) { runOnUiThread(() -> { top.setText(text); bottom.setText(""); }); }

    @Override public void onQuality(String pc, int rttMs) {
        runOnUiThread(() -> {
            top.setText(pc == null || pc.isEmpty() ? "已連上手機" : pc);
            String q = rttMs < 0 ? link.transport : (link.transport + "・" + rttMs + " ms" + (rttMs > 150 ? "・連線不穩" : ""));
            bottom.setText(air.isOn() ? "空中・" + air.rate + " Hz・" + q : q);
        });
    }

    // ---------- 選單（返回鍵叫出；11-23 定：選電腦、切模式放手錶，離開用 Home 鍵） ----------
    // 返回鍵的按鍵訊號 App 攔不到（三星系統先吃掉），但「返回」這個動作會交給 App ⇒ 用 onBackPressed，
    // 一般 App 的正規做法、不需要協助工具權限（開源版也能用）。
    @Override public void onBackPressed() {
        if (menu != null) closeMenu(); else openMenu();
    }

    private void openMenu() {
        menu = new android.widget.ScrollView(this);
        menu.setBackgroundColor(Color.argb(240, 0x12, 0x0E, 0x1C));
        menu.setVerticalScrollBarEnabled(false);
        menuBox = new android.widget.LinearLayout(this);
        menuBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        menuBox.setPadding(dp(30), dp(40), dp(30), dp(60));
        menu.addView(menuBox);
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        menu.requestFocus();
        renderMenu();
        link.requestPcs();
    }

    private void closeMenu() {
        if (menu == null) return;
        root.removeView(menu);
        menu = null; menuBox = null;
    }

    private void renderMenu() {
        if (menuBox == null) return;
        menuBox.removeAllViews();
        item(air.isOn() ? "改用觸控板" : "改用空中滑鼠", Color.rgb(0xC9, 0xB8, 0xF0), v -> {
            air.set(!air.isOn()); buzz(air.isOn() ? 60 : 25); closeMenu();
        });
        TextView hd = label(11, Color.rgb(0x8A, 0x80, 0xA0));
        hd.setText(pcs.isEmpty() ? "電腦（向手機要清單中…）" : "電腦");
        hd.setPadding(0, dp(14), 0, dp(4));
        menuBox.addView(hd);
        for (String[] c : pcs) {
            boolean on = "1".equals(c[2]), sel = "1".equals(c[3]);
            int col = sel ? Color.rgb(0xE8, 0xDF, 0xFF) : on ? Color.rgb(0xB0, 0xA6, 0xC8) : Color.rgb(0x6A, 0x62, 0x7E);
            final String id = c[0];
            item((sel ? "● " : "　") + c[1], col, v -> {
                if (!"1".equals(c[3])) { link.pick(id); buzz(25); }
                closeMenu();
            });
        }
    }

    private void item(String text, int color, View.OnClickListener l) {
        TextView t = label(15, color);
        t.setText(text);
        t.setPadding(0, dp(9), 0, dp(9));
        t.setOnClickListener(l);
        menuBox.addView(t, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }

    @Override public void onPcList(java.util.List<String[]> l) {
        runOnUiThread(() -> { pcs = l; renderMenu(); });
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        android.util.Log.i("PadbridgeWatch", "key " + android.view.KeyEvent.keyCodeToString(e.getKeyCode()) + " act=" + e.getAction() + " src=" + e.getSource());
        return super.dispatchKeyEvent(e);
    }

    /** 錶圈：一格一個滾輪刻度（順時針往下捲）。 */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (menu != null && e.getAction() == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            menu.smoothScrollBy(0, Math.round(-e.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(40)));   // 選單開著：錶圈捲選單
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            wheelAcc += e.getAxisValue(MotionEvent.AXIS_SCROLL);
            int n = (int) wheelAcc;
            if (n != 0) { wheelAcc -= n; link.wheel(n); }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    private final class Pad extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        Pad() {
            super(WatchPadActivity.this);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(Color.rgb(0x4A, 0x3D, 0x6B));
        }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            float r = Math.min(getWidth(), getHeight()) / 2f - dp(4);
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, ring);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            android.util.Log.i("PadbridgeWatch", "a11y action " + action);
            return super.performAccessibilityAction(action, args);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER || e.getDeviceId() < 0)
                android.util.Log.i("PadbridgeWatch", "touch tool=" + e.getToolType(0) + " dev=" + e.getDeviceId() + " act=" + e.getActionMasked());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    h.removeCallbacks(flushTap);
                    second = tapPending;   // 上一下單點還在等 ⇒ 這次可能是雙擊或點後拖
                    lastX = downX = e.getX(); lastY = downY = e.getY(); downAt = e.getEventTime();
                    moved = false; dragging = false; longFired = false; multi = false;
                    h.postDelayed(longPress, LONG_MS);
                    link.finger(true);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // 兩指點一下＝切換空中模式（測試用的切法，之後換掉）
                    if (!multi) {
                        multi = true;
                        h.removeCallbacks(longPress);
                        if (second) { second = false; tapPending = false; link.click(1); }
                        if (dragging) { dragging = false; link.button(1, false); }
                        air.set(!air.isOn());
                        buzz(air.isOn() ? 60 : 25);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (multi) return true;
                    for (int h = 0; h < e.getHistorySize(); h++) step(e.getHistoricalX(h), e.getHistoricalY(h));
                    step(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    h.removeCallbacks(longPress);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    long dur = e.getEventTime() - downAt;
                    if (multi) {
                        // 兩指切換，不當點擊
                    } else if (dragging) {
                        link.button(1, false);
                    } else if (longFired) {
                        // 右鍵已經送了
                    } else if (second) {
                        // 第二下沒滑：短點＝雙擊，否則只送前一下
                        second = false; tapPending = false;
                        link.click(1);
                        if (up && !moved && dur < TAP_MAX) link.click(1);
                    } else if (up && !moved && dur < TAP_MAX) {
                        tapPending = true; h.postDelayed(flushTap, TAP_WINDOW);
                    }
                    dragging = false;
                    link.finger(air.isOn());
                    return true;
                }
            }
            return true;
        }
        private int samples; private long sampleT0;
        private void step(float x, float y) {
            samples++;
            long n = android.os.SystemClock.uptimeMillis();
            if (n - sampleT0 >= 1000) { link.touchRate = samples; samples = 0; sampleT0 = n; }
            if (!moved && Math.hypot(x - downX, y - downY) > dp(4)) {
                moved = true;
                h.removeCallbacks(longPress);
                if (second) {   // 點後拖：先在原地按下左鍵再移動
                    second = false; tapPending = false; dragging = true;
                    buzz(15); link.button(1, true);
                }
            }
            if (moved) link.move(x - lastX, y - lastY);
            lastX = x; lastY = y;
        }
    }

    private void buzz(int ms) {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
