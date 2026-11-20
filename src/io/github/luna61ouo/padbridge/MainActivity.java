package io.github.luna61ouo.padbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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
    private Button actA, actB;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hub = MouseHub.get(this);
        buildUi();
        handlePairLink(getIntent());
    }

    // ---------- 觸控板／繪圖板兩種畫面 ----------
    // manifest 設了 configChanges＝轉向不重建 Activity，切模式時自己換畫面。
    private boolean tabletMode() { return getSharedPreferences("mouse", MODE_PRIVATE).getBoolean("tabletMode", false); }
    private void setTabletMode(boolean on) {
        adjustingArea = false;
        getSharedPreferences("mouse", MODE_PRIVATE).edit().putBoolean("tabletMode", on).apply();
        buildUi();
    }
    private boolean tabletLandscape() { return getSharedPreferences("mouse", MODE_PRIVATE).getBoolean("tabletLandscape", false); }
    private void setTabletLandscape(boolean on) {
        getSharedPreferences("mouse", MODE_PRIVATE).edit().putBoolean("tabletLandscape", on).apply();
        buildUi();
    }
    // 鍵盤模組：""＝不在鍵盤、"type"＝手機打字、"pc"＝電腦鍵盤（固定橫放）
    private String kbMode() { return getSharedPreferences("mouse", MODE_PRIVATE).getString("kbMode", ""); }
    private void setKbMode(String m) {
        if (pcKb != null) { pcKb.releaseAll(); pcKb = null; }
        hub.releaseKeys();
        getSharedPreferences("mouse", MODE_PRIVATE).edit().putString("kbMode", m).apply();
        buildUi();
    }
    private Keyboards.PcKeyboard pcKb;
    private void buildUi() {
        String kb = kbMode();
        if (!kb.isEmpty()) { buildKeyboard(kb); refresh(hub.statusText()); return; }
        boolean land = tabletMode() && tabletLandscape();
        setRequestedOrientation(land ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                     : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (!tabletMode()) buildTouchpad(); else if (land) buildTabletLandscape(); else buildTablet();
        // 系統列控制器要在 setContentView 之後才拿得到（之前 DecorView 還沒建，會 NPE）
        android.view.WindowInsetsController wic = getWindow().getInsetsController();
        if (wic != null) {
            if (land) {   // 橫放：全螢幕，畫布越大越好；從邊緣滑進來可以暫時叫出系統列
                wic.hide(android.view.WindowInsets.Type.systemBars());
                wic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else wic.show(android.view.WindowInsets.Type.systemBars());
        }
        refresh(hub.statusText());
    }

    // 橫放（要大畫布、寬螢幕用）：畫布佔左邊，右邊一欄放按鈕。手掌會在畫布上，用觸控筆比較適合。
    private void buildTabletLandscape() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF15121C);
        final int p = dp(6);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(android.view.WindowInsets.Type.displayCutout());
            v.setPadding(p + bars.left, p + bars.top, p + bars.right, p + bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        pad = new TabletPad(this);
        root.addView(pad, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), 0, 0, 0);
        status = new TextView(this);
        status.setTextColor(0xFF9A8FB0);
        status.setTextSize(11);
        status.setSingleLine(true);
        col.addView(status);
        LinearLayout r1 = row();
        r1.addView(btn("← 觸控板", v -> setTabletMode(false)), weight());
        r1.addView(btn("直放", v -> setTabletLandscape(false)), weight());
        col.addView(r1, new LinearLayout.LayoutParams(-1, dp(40)));
        actA = btn("", v -> pcDialog());
        actA.setTextSize(11);
        actA.setSingleLine(true);
        col.addView(actA, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout r2 = row();
        modeAuto = btn("自動", v -> setTabletPress(true));
        modeKey = btn("按鍵", v -> setTabletPress(false));
        r2.addView(modeAuto, weight());
        r2.addView(modeKey, weight());
        col.addView(r2, new LinearLayout.LayoutParams(-1, dp(40)));
        previewBtn = btn("", v -> togglePreview());
        col.addView(previewBtn, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout r3 = row();
        monBtn = btn("", v -> monitorDialog());
        adjustBtn = btn("", v -> toggleAdjust());
        adjustBtn.setOnLongClickListener(v -> { if (pad != null) pad.resetPadArea(); return true; });
        monBtn.setTextSize(12); adjustBtn.setTextSize(12);
        r3.addView(monBtn, weight());
        r3.addView(adjustBtn, weight());
        col.addView(r3, new LinearLayout.LayoutParams(-1, dp(40)));
        col.addView(holdBtn("左鍵", MouseHub.BTN_LEFT), new LinearLayout.LayoutParams(-1, 0, 2f));
        col.addView(holdBtn("右鍵", MouseHub.BTN_RIGHT), new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(col, new LinearLayout.LayoutParams(dp(170), -1));
        setContentView(root);
        showTabletPress();
    }

    // ---------- 鍵盤模組 ----------
    private void buildKeyboard(String kb) {
        boolean pc = "pc".equals(kb);
        setRequestedOrientation(pc ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                   : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF15121C);
        final int p = dp(pc ? 6 : 12);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int types = android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime()
                    | (pc ? 0 : android.view.WindowInsets.Type.systemBars());
            android.graphics.Insets bars = in.getInsets(types);
            v.setPadding(p + bars.left, (pc ? dp(4) : dp(8)) + bars.top, p + bars.right, (pc ? dp(4) : dp(8)) + bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        status = new TextView(this);
        status.setTextColor(0xFF9A8FB0);
        status.setTextSize(pc ? 11 : 14);
        status.setSingleLine(true);
        LinearLayout top = row();
        top.addView(btn("← 觸控板", v -> setKbMode("")), weight());
        top.addView(btn(pc ? "手機打字" : "電腦鍵盤", v -> setKbMode(pc ? "type" : "pc")), weight());
        actA = btn("", v -> pcDialog());
        actA.setSingleLine(true);
        actA.setTextSize(pc ? 11 : 13);
        if (pc) {
            pcKb = new Keyboards.PcKeyboard(this, hub);
            final Button lock = btn("🔒 鎖", v -> pcKb.toggleLockArm());
            pcKb.setLockListener(armed -> lock.setText(armed ? "點一顆鍵…（取消）" : "🔒 鎖"));
            top.addView(lock, weight());
            top.addView(actA, new LinearLayout.LayoutParams(0, -1, 1.6f));
            status.setPadding(dp(8), 0, 0, 0);
            top.addView(status, new LinearLayout.LayoutParams(0, -1, 1.6f));
            status.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(top, new LinearLayout.LayoutParams(-1, dp(38)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
            lp.topMargin = dp(4);
            root.addView(pcKb, lp);
        } else {
            root.addView(status);
            top.addView(actA, new LinearLayout.LayoutParams(0, -2, 1.6f));
            root.addView(top);
            final Keyboards.TypeCapture cap = new Keyboards.TypeCapture(this, hub);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, dp(56));
            cl.topMargin = dp(8);
            root.addView(cap, cl);
            // 常用的特殊鍵（按住會連發，例如按住 ⌫）
            String[] lb = {"Esc", "Tab", "←", "↑", "↓", "→", "Del", "⌫", "Enter"};
            int[] vk = {Keyboards.VK_ESC, Keyboards.VK_TAB, Keyboards.VK_LEFT, Keyboards.VK_UP, Keyboards.VK_DOWN, Keyboards.VK_RIGHT, Keyboards.VK_DEL, Keyboards.VK_BACK, Keyboards.VK_ENTER};
            LinearLayout keys = row();
            for (int i = 0; i < lb.length; i++) {
                Button b = keyBtn(lb[i], vk[i]);
                keys.addView(b, new LinearLayout.LayoutParams(0, -1, i == lb.length - 1 ? 1.5f : 1f));
            }
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, dp(48));
            kl.topMargin = dp(6);
            root.addView(keys, kl);
            // 小觸控板：打字中要點別的地方、移游標，不用切回觸控板
            Touchpad tp = new Touchpad(this);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, 0, 1f);
            tl.topMargin = dp(8);
            root.addView(tp, tl);
            LinearLayout btnRow = row();
            btnRow.addView(holdBtn("左鍵", MouseHub.BTN_LEFT), weight());
            btnRow.addView(holdBtn("右鍵", MouseHub.BTN_RIGHT), weight());
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(52));
            bl.topMargin = dp(6);
            root.addView(btnRow, bl);
            ui.postDelayed(cap::showIme, 250);
        }
        setContentView(root);
        android.view.WindowInsetsController wic = getWindow().getInsetsController();
        if (wic != null) {
            if (pc) {
                wic.hide(android.view.WindowInsets.Type.systemBars());
                wic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else wic.show(android.view.WindowInsets.Type.systemBars());
        }
    }

    /** 一顆電腦鍵（按住＝按住，電腦那邊會連發）。 */
    private Button keyBtn(String t, final int vk) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                hub.setKeys(java.util.Collections.singletonList(vk));
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                hub.releaseKeys();
            }
            return true;
        });
        return b;
    }

    @Override public void onBackPressed() {
        if (!kbMode().isEmpty()) { setKbMode(""); return; }
        if (tabletMode()) { setTabletMode(false); return; }
        super.onBackPressed();
    }

    // 繪圖板（直放）：上面畫布、中間模式切換、下面大左鍵／右鍵。手放在手機下方的桌面上，不會壓到螢幕。
    private Button modeAuto, modeKey;
    private void buildTablet() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF15121C);
        final int p = dp(12);
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
        LinearLayout bar = row();
        actA = btn("", v -> pcDialog());
        bar.addView(btn("← 觸控板", v -> setTabletMode(false)), new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(actA, new LinearLayout.LayoutParams(0, -2, 1.6f));
        bar.addView(btn("橫放", v -> setTabletLandscape(true)), new LinearLayout.LayoutParams(0, -2, 0.8f));
        root.addView(bar);

        LinearLayout modeRow = row();
        modeAuto = btn("自動左鍵", v -> setTabletPress(true));
        modeKey = btn("按鍵觸發", v -> setTabletPress(false));
        modeRow.addView(modeAuto, weight());
        modeRow.addView(modeKey, weight());
        previewBtn = btn("", v -> togglePreview());
        modeRow.addView(previewBtn, weightSmall());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(48));
        mlp.topMargin = dp(8);
        root.addView(modeRow, mlp);
        LinearLayout row2 = row();
        monBtn = btn("", v -> monitorDialog());
        adjustBtn = btn("", v -> toggleAdjust());
        adjustBtn.setOnLongClickListener(v -> { if (pad != null) pad.resetPadArea(); return true; });
        row2.addView(monBtn, weight());
        row2.addView(adjustBtn, weight());
        root.addView(row2, new LinearLayout.LayoutParams(-1, dp(48)));
        final TextView hint = new TextView(this);
        hint.setTextColor(0xFF9A8FB0);
        hint.setTextSize(13);
        hint.setPadding(0, dp(4), 0, dp(6));
        hint.setTag("tabletHint");
        root.addView(hint);

        LinearLayout btnRow = row();
        btnRow.addView(holdBtn("左鍵", MouseHub.BTN_LEFT), new LinearLayout.LayoutParams(0, -1, 2f));
        btnRow.addView(holdBtn("右鍵", MouseHub.BTN_RIGHT), weight());
        root.addView(btnRow, new LinearLayout.LayoutParams(-1, dp(80)));

        // 畫布（照電腦比例、貼上緣），下面剩下的空間是手掌區：手放上去什麼都不會發生
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = dp(10);
        pad = new TabletPad(this);
        root.addView(pad, lp);
        setContentView(root);
        showTabletPress();
    }

    // 調整手機範圍／選螢幕
    private boolean adjustingArea;
    private TabletPad pad;
    private Button adjustBtn, monBtn;
    private void toggleAdjust() {
        adjustingArea = !adjustingArea;
        showTabletPress();
        if (pad != null) pad.invalidate();
    }
    private void monitorDialog() {
        final PcList.Pc pc = hub.pcs().selected();
        if (pc == null) { pcDialog(); return; }
        java.util.List<int[]> ms = pc.monitors();
        if (ms.size() <= 1) {
            new AlertDialog.Builder(this).setMessage(ms.isEmpty() ? "這台電腦的 Padbridge 版本比較舊，沒有回報螢幕清單（請更新電腦端）。" : "這台電腦只有一個螢幕。").setPositiveButton("好", null).show();
            return;
        }
        String[] items = new String[ms.size()];
        final boolean[] checked = new boolean[ms.size()];
        for (int i : pc.selectedMons()) checked[i] = true;
        for (int i = 0; i < ms.size(); i++)
            items[i] = "螢幕 " + (i + 1) + "　" + ms.get(i)[0] + "×" + ms.get(i)[1] + (ms.get(i)[2] == 1 ? "（主螢幕）" : "");
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("繪圖板顯示哪些螢幕（勾兩個以上＝同時顯示）")
                .setMultiChoiceItems(items, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("好", (d, w) -> {
                    java.util.List<Integer> l = new java.util.ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) l.add(i);
                    pc.setSelectedMons(l); hub.pcs().save();   // 全部取消＝回到主螢幕（selectedMons 會補）
                    showTabletPress();
                    if (pad != null) { pad.invalidate(); pad.post(() -> pad.requestFrame(true)); }
                })
                .setNeutralButton("全選", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        // 「全選」不關對話框：勾滿讓人再確認
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            for (int i = 0; i < checked.length; i++) { checked[i] = true; dlg.getListView().setItemChecked(i, true); }
        });
    }

    private boolean previewOn() { return getSharedPreferences("mouse", MODE_PRIVATE).getBoolean("tabPreview", true); }
    private Button previewBtn;
    private void togglePreview() {
        getSharedPreferences("mouse", MODE_PRIVATE).edit().putBoolean("tabPreview", !previewOn()).apply();
        showTabletPress();
        View v = getWindow().getDecorView();
        v.invalidate();
        buildUi();
    }

    private void setTabletPress(boolean auto) { hub.settings().set(MouseSettings.TABLET_PRESS, auto); showTabletPress(); }
    private void showTabletPress() {
        boolean auto = hub.settings().on(MouseSettings.TABLET_PRESS);
        if (modeAuto != null) { modeAuto.setAlpha(auto ? 1f : 0.45f); modeKey.setAlpha(auto ? 0.45f : 1f); }
        if (previewBtn != null) previewBtn.setText(previewOn() ? "預覽：開" : "預覽：關");
        if (adjustBtn != null) adjustBtn.setText(adjustingArea ? "完成" : "調整範圍");
        if (monBtn != null) {
            PcList.Pc pc = hub.pcs().selected();
            int n = pc == null ? 0 : pc.monitors().size(), i = pc == null ? -1 : pc.monIndex();
            String lab = "螢幕 ▾";
            if (n > 1) {
                java.util.List<Integer> sel = pc.selectedMons();
                StringBuilder b = new StringBuilder("螢幕 ");
                for (int k = 0; k < sel.size(); k++) { if (k > 0) b.append('+'); b.append(sel.get(k) + 1); }
                lab = (sel.size() == n && n > 2 ? "全部螢幕" : b.toString()) + " ▾";
            }
            monBtn.setText(lab);
        }
        View h = getWindow().getDecorView().findViewWithTag("tabletHint");
        if (h instanceof TextView) ((TextView) h).setText(auto
                ? "碰到畫布＝按住左鍵，可以直接畫。"
                : "碰到畫布只移動游標（像筆懸空）；按上面的左鍵、或手機音量鍵（下＝左鍵、上＝右鍵）才點下去。");
    }

    // 繪圖板模式下，音量鍵當滑鼠鍵（按住＝按住）：直放時大拇指按得到，按鍵觸發模式最順手。
    @Override public boolean onKeyDown(int code, android.view.KeyEvent e) {
        int b = tabletVolumeButton(code);
        if (b == 0) return super.onKeyDown(code, e);
        if (e.getRepeatCount() == 0) { hub.setActive(true); hub.button(b, true); }
        return true;
    }
    @Override public boolean onKeyUp(int code, android.view.KeyEvent e) {
        int b = tabletVolumeButton(code);
        if (b == 0) return super.onKeyUp(code, e);
        hub.button(b, false);
        return true;
    }
    private int tabletVolumeButton(int code) {
        if (!tabletMode()) return 0;
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return MouseHub.BTN_LEFT;
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP) return MouseHub.BTN_RIGHT;
        return 0;
    }

    private void buildTouchpad() {
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

        LinearLayout topRow = row();
        actA = btn("", v -> pcDialog());
        actB = btn("⚙ 設定", v -> settingsDialog());
        Button tab = btn("繪圖板", v -> setTabletMode(true));
        Button kbd = btn("鍵盤", v -> setKbMode("type"));
        topRow.addView(actA, new LinearLayout.LayoutParams(0, -2, 2f));
        topRow.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
        topRow.addView(kbd, new LinearLayout.LayoutParams(0, -2, 1f));
        topRow.addView(actB, new LinearLayout.LayoutParams(0, -2, 0.9f));
        root.addView(topRow);

        // 更新提示（誰舊誰提示；平常藏起來）
        updBanner = btn("", v -> onUpdateBanner());
        updBanner.setTextSize(13);
        updBanner.setBackgroundColor(0xFF4A3F6E);
        updBanner.setTextColor(0xFFFFE6A8);
        updBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-1, -2);
        ul.topMargin = dp(8);
        root.addView(updBanner, ul);

        Touchpad pad = new Touchpad(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = dp(10); lp.bottomMargin = dp(10);
        root.addView(pad, lp);

        LinearLayout btnRow = row();
        btnRow.addView(holdBtn("左鍵", MouseHub.BTN_LEFT), weight());
        btnRow.addView(holdBtn("中鍵", MouseHub.BTN_MIDDLE), weightSmall());
        btnRow.addView(holdBtn("右鍵", MouseHub.BTN_RIGHT), weight());
        root.addView(btnRow, new LinearLayout.LayoutParams(-1, dp(72)));

        final TextView sl = new TextView(this);
        sl.setTextColor(0xFF9A8FB0);
        sl.setPadding(0, dp(8), 0, 0);
        root.addView(sl);
        // 速度：前段 0.1～1.5 每格 0.1（日常細調），後段 1.5～5.0 每格 0.25（高速，例如玩 osu! 要小幅手指掃過整個螢幕）
        SeekBar sb = new SeekBar(this);
        sb.setMax(SPEED_STEPS);
        sb.setProgress(speedToStep(hub.sensitivity()));
        sl.setText(speedLabel(hub.sensitivity()));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean f) { float g = stepToSpeed(v); hub.setSensitivity(g); sl.setText(speedLabel(g)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);

        setContentView(root);
    }

    static final int SPEED_STEPS = 28;
    static float stepToSpeed(int v) { return v <= 14 ? (v + 1) / 10f : 1.5f + (v - 14) * 0.25f; }
    static int speedToStep(float g) {
        int v = g <= 1.5f ? Math.round(g * 10) - 1 : 14 + Math.round((g - 1.5f) / 0.25f);
        return Math.max(0, Math.min(SPEED_STEPS, v));
    }
    static String speedLabel(float g) { return String.format(java.util.Locale.ROOT, "速度 ×%.2f", g).replaceAll("0$", ""); }

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
        hub.selectPc(p.id);
        refresh(hub.statusText());
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("加電腦").setMessage("正在連「" + p.label() + "」…").setPositiveButton("好", null).show();
        new Thread(() -> {
            hub.pcs().discover();
            boolean ok = p.online;
            ui.post(() -> { d.setMessage(ok ? "✓ 加好了：" + p.label() + "\n已經切到這台，可以用了。" : "已加入「" + p.label() + "」，但現在找不到它：手機跟電腦要在同一個 Wi-Fi。"); refresh(hub.statusText()); });
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
        // 回到 App：找一次電腦（拿到它的版本）、需要的話看一下 GitHub，然後更新提示
        final boolean gh = hub.settings().on(MouseSettings.UPDATE_CHECK);
        new Thread(() -> {
            hub.pcs().discover();
            if (gh) Updates.githubLatest(this, false);
            ui.post(() -> refresh(hub.statusText()));
        }).start();
    }

    @Override protected void onPause() {
        hub.setListener(null);
        if (pcKb != null) pcKb.releaseAll(); else hub.releaseKeys();   // 離開畫面時不留按著的鍵
        super.onPause();
    }

    // ---------- 更新提示 ----------
    private Button updBanner;
    private int updAction;   // 1＝電腦端太舊要手動、2＝推電腦端更新、3＝App 有新版
    private String updVer = "";
    private void showUpdateBanner() {
        if (updBanner == null) return;
        String my = Updates.myVersion(this);
        PcList.Pc sel = hub.pcs().selected();
        boolean win = sel != null && sel.online && !sel.ver.startsWith("a");
        String gh = getSharedPreferences("mouse", MODE_PRIVATE).getString("ghLatest", "");
        String newer = gh;
        if (win && Updates.cmp(sel.ver, newer) > 0) newer = sel.ver;
        updAction = 0;
        if (win && sel.ver.isEmpty()) { updAction = 1; updBanner.setText("這台電腦的 Padbridge 是舊版，不能自動更新：按這裡手動更新一次"); }
        else if (win && Updates.cmp(sel.ver, my) < 0 && Updates.canPush(this)) { updAction = 2; updVer = my; updBanner.setText("電腦端 " + sel.ver + " 比手機舊：按這裡更新到 " + my); }
        else if (Updates.cmp(newer, my) > 0) { updAction = 3; updVer = newer; updBanner.setText("手機 App 有新版 " + newer + "（目前 " + my + "）：按這裡到 GitHub 下載"); }
        updBanner.setVisibility(updAction == 0 ? View.GONE : View.VISIBLE);
    }
    private void onUpdateBanner() {
        if (updAction == 1) setupDialog();
        else if (updAction == 3) startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SetupServer.RELEASES_URL)));
        else if (updAction == 2) {
            final PcList.Pc sel = hub.pcs().selected();
            new AlertDialog.Builder(this).setTitle("更新電腦端")
                    .setMessage("把「" + (sel == null ? "電腦" : sel.label()) + "」的 Padbridge 更新到 " + updVer + "？\n\n電腦會先檢查發布簽名才安裝，接收程式重啟幾秒，這段時間滑鼠會停一下。")
                    .setPositiveButton("更新", (d, w) -> new Thread(() -> {
                        boolean ok = hub.pushUpdate();
                        ui.post(() -> new AlertDialog.Builder(this).setMessage(ok ? "已送出。電腦下載、驗證後會自己重啟，大約 10 秒。" : "送不出去：這版 App 沒有帶發布簽名，或開不了下載。").setPositiveButton("好", null).show());
                        if (ok) { try { Thread.sleep(12000); } catch (InterruptedException ignored) {} hub.pcs().discover(); ui.post(() -> refresh(hub.statusText())); }
                    }).start())
                    .setNegativeButton("取消", null).show();
        }
    }

    private void refresh(String text) {
        showUpdateBanner();
        if (status == null || actA == null) return;
        status.setText(text);
        if (tabletMode()) { showTabletPress(); if (pad != null) pad.invalidate(); }   // 找到電腦後螢幕清單／比例可能變了
        PcList.Pc sel = hub.pcs().selected();
        actA.setText("選電腦：" + (sel == null ? "（還沒選）" : sel.label()) + "  ▾");
    }


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
                .setTitle("用哪台電腦")
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
     * 點一下後 TAP_WINDOW 內再碰並滑動＝拖曳（點後拖）；所以單點的左鍵要等這段時間過了才送。
     * 這段時間內再點一下（沒滑）＝雙擊，兩下一起送。
     */
    class Touchpad extends View {
        private static final long TAP_WINDOW = 250;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX, lastY, startX, startY, remX, remY, scrollAcc, lastAvgY;
        private long downAt;
        private int maxPtr, holdX, holdY;
        private boolean moved, dragging;
        private boolean tapPending;   // 上一下單點的左鍵還沒送，等看看會不會變成點後拖
        private boolean second;       // 這次觸碰是在單點後的等待時間內開始的
        private final Runnable flushTap = () -> {
            if (tapPending) { tapPending = false; hub.click(MouseHub.BTN_LEFT); }
        };
        private final Runnable longPress = () -> {
            if (!moved && maxPtr == 1) {
                second = false; tapPending = false;   // 點一下再按住不動也是拖曳，前一下不另外點
                dragging = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                hub.button(MouseHub.BTN_LEFT, true);
            }
        };

        Touchpad(Context c) {
            super(c);
            bg.setColor(0xFF2A2338);
            txt.setColor(0xFF9A8FB0);
            txt.setTextSize(dp(13));
            txt.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            c.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), dp(18), dp(18), bg);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            c.drawText("單指滑＝移動　點一下＝左鍵", cx, cy - dp(22), txt);
            c.drawText("雙指點＝右鍵　雙指上下滑＝捲動", cx, cy, txt);
            c.drawText("點一下再按住滑／按住不動一下再滑＝拖曳", cx, cy + dp(22), txt);
        }

        /** 單點後的第二下不是點後拖（多了一指）：前一下照常當左鍵送出。 */
        private void giveUpSecond() {
            if (second) { second = false; flushTap.run(); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 不等畫面更新才批次交付，觸控事件用感測器原本的頻率送進來，移動更連續
                    if (android.os.Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e);
                    removeCallbacks(flushTap);
                    second = tapPending;   // 前一下單點還在等 ⇒ 這次可能是點後拖或雙擊
                    downAt = e.getEventTime();
                    startX = lastX = e.getX(); startY = lastY = e.getY();
                    remX = remY = 0; scrollAcc = 0; holdX = holdY = 0;
                    maxPtr = 1; moved = false; dragging = false;
                    hub.setActive(true);
                    postDelayed(longPress, 450);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    maxPtr = Math.max(maxPtr, e.getPointerCount());
                    lastAvgY = avgY(e);
                    removeCallbacks(longPress);
                    giveUpSecond();
                    if (holdX != 0 || holdY != 0) { hub.move(holdX, holdY); holdX = holdY = 0; }
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
                        // 線性、不自己加速：Windows 本身已經有指標加速，兩層疊在一起會忽快忽慢
                        float gain = hub.sensitivity();
                        remX += dx * gain; remY += dy * gain;
                        int ix = (int) remX, iy = (int) remY;
                        remX -= ix; remY -= iy;
                        if (!moved && Math.hypot(x - startX, y - startY) > dp(6)) {
                            removeCallbacks(longPress);
                            moved = true;
                            if (second) {
                                // 點後拖：先在原地按下左鍵，再把剛才暫存的位移補上，物件才抓得到
                                second = false; tapPending = false;
                                dragging = true;
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                                hub.button(MouseHub.BTN_LEFT, true);
                            }
                            ix += holdX; iy += holdY; holdX = holdY = 0;
                        }
                        if (second && !moved) { holdX += ix; holdY += iy; }   // 還沒判定前先不動游標
                        else hub.move(ix, iy);
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
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (dragging) {
                        hub.button(MouseHub.BTN_LEFT, false);
                    } else if (second) {
                        // 第二下沒滑：短點＝雙擊（兩下一起送），否則只送前一下
                        second = false; tapPending = false;
                        hub.click(MouseHub.BTN_LEFT);
                        if (up && !moved && dur < 280) hub.click(MouseHub.BTN_LEFT);
                    } else if (!moved && dur < 280 && up) {
                        if (maxPtr >= 2) hub.click(MouseHub.BTN_RIGHT);
                        else { tapPending = true; postDelayed(flushTap, TAP_WINDOW); }
                    }
                    if (holdX != 0 || holdY != 0) { hub.move(holdX, holdY); holdX = holdY = 0; }
                    dragging = false;
                    break;
            }
            return true;
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(longPress);
            removeCallbacks(flushTap);
            flushTap.run();
        }

        private float avgY(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
            return s / e.getPointerCount();
        }
    }

    /**
     * 繪圖板：畫面上一塊跟電腦主螢幕同比例的區域，碰到哪裡游標就到哪裡（絕對座標）。
     * 只認第一根手指／筆；用過觸控筆之後 3 秒內不理手指（手掌放在螢幕上不會亂點）。
     * 會懸停的筆：靠近不碰到時只移動游標。
     */
    class TabletPad extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                txt = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), tag = new Paint(Paint.ANTI_ALIAS_FLAG),
                img = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF area = new RectF();
        private int activeId = -1;
        private boolean pressing, touchedOnce;
        private long lastStylusAt;
        private float dotX = -1, dotY = -1;
        // 對應範圍：電腦主螢幕上的一塊（比例跟螢幕一樣），左上 (rx, ry)、邊長比例 z（1＝整個螢幕）。兩指縮放／拖曳改它。
        private final android.content.SharedPreferences rp = getSharedPreferences("mouse", MODE_PRIVATE);
        private float rx = rp.getFloat("tabRx", 0f), ry = rp.getFloat("tabRy", 0f), z = rp.getFloat("tabZ", 1f);
        private boolean gesture, rebase;
        private float g0Span, g0Cx, g0Cy, g0Rx, g0Ry, g0Z;
        private android.graphics.Bitmap frame;
        private final java.util.Map<Integer, android.graphics.Bitmap> frames = new java.util.HashMap<>();   // 全部同時顯示：每個螢幕一張
        private long lastReq;
        // 全部同時顯示：每個螢幕在畫布上的方框（monRects[i] 對應電腦的螢幕編號 monIds[i]）；碰到哪個就送哪個
        private final java.util.List<RectF> monRects = new java.util.ArrayList<>();
        private final java.util.List<Integer> monIds = new java.util.ArrayList<>();
        private int curMon = 0;   // 目前這一筆在哪個方框（monRects 的位置）
        // 手機上的作用範圍（像繪圖板驅動的「作用區域」）：最大可用區的 padS 倍、中心在 (padCx, padCy)×畫面寬高；-1＝預設置中。
        private float padS = rp.getFloat("padS", 1f), padCx = rp.getFloat("padCx", -1f), padCy = rp.getFloat("padCy", -1f);
        private final RectF maxArea = new RectF();
        private float a0Cx, a0Cy, a0S, a0Fx, a0Fy, a0Span;
        private int a0Count;

        TabletPad(Context c) {
            super(c);
            bg.setColor(0xFF2A2338);
            edge.setColor(0xFF6B5A8E); edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(dp(1));
            txt.setColor(0xFF6E6382); txt.setTextSize(dp(13)); txt.setTextAlign(Paint.Align.CENTER);
            dot.setColor(0xFFBFA8FF);
            tag.setColor(0xCCE8DDF5); tag.setTextSize(dp(12));
        }

        // ---- 畫面預覽：掛在畫面上時每秒續約一次；範圍一變就馬上要一張 ----
        private final Runnable tick = new Runnable() { @Override public void run() { requestFrame(true); postDelayed(this, 1000); } };
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            hub.setFrameListener((mon, b) -> post(() -> { frames.put(mon, b); frame = b; invalidate(); }));
            post(tick);
        }
        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            hub.setFrameListener(null);
            super.onDetachedFromWindow();
        }
        private void requestFrame(boolean force) {
            if (!previewOn() || area.width() < 10) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (!force && now - lastReq < 150) return;
            lastReq = now;
            if (multi()) {   // 每個螢幕各要一份整個螢幕的畫面，大小照它在手機上的方框
                for (int i = 0; i < monRects.size(); i++) {
                    RectF r = monRects.get(i);
                    float k = Math.min(1f, 960f / Math.max(1f, r.width()));
                    hub.requestFrame(0, 0, 65535, 65535, Math.round(r.width() * k), Math.round(r.height() * k), monIds.get(i));
                }
                return;
            }
            float sc = Math.min(1f, 960f / area.width());
            hub.requestFrame(Math.round(rx * 65535), Math.round(ry * 65535), Math.round(z * 65535), Math.round(z * 65535),
                    Math.round(area.width() * sc), Math.round(area.height() * sc));
        }

        private void layoutArea() {
            PcList.Pc pc = hub.pcs().selected();
            float w = getWidth(), h = getHeight();
            float[][] units = null;
            if (multi()) {
                java.util.List<int[]> all = pc.monitors(), ms = new java.util.ArrayList<>();
                java.util.List<Integer> ids = pc.selectedMons();
                for (int id : ids) ms.add(all.get(id));
                units = arrange(ms, w, h);
                for (int i = 0; i < units.length - 1; i++) units[i][4] = ids.get((int) units[i][4]);   // 換回電腦的螢幕編號
            }   // 每個 {x, y, 寬, 高, 螢幕編號}，最後一筆是 {總寬, 總高}
            float ar = units != null ? units[units.length - 1][0] / units[units.length - 1][1] : (pc != null ? pc.aspect() : 16f / 9f);
            float aw = w, ah = w / ar;
            if (ah > h) { ah = h; aw = h * ar; }
            float l = (w - aw) / 2, t = tabletLandscape() ? (h - ah) / 2 : 0;   // 直放：貼上緣，空白（手掌區）留在下面
            maxArea.set(l, t, l + aw, t + ah);
            float sw = aw * padS, sh = ah * padS;
            float cx = padCx < 0 ? maxArea.centerX() : padCx * w, cy = padCy < 0 ? maxArea.centerY() : padCy * h;
            cx = Math.max(sw / 2, Math.min(w - sw / 2, cx)); cy = Math.max(sh / 2, Math.min(h - sh / 2, cy));
            area.set(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2);
            monRects.clear(); monIds.clear();
            if (units != null) {
                float k = area.width() / units[units.length - 1][0];
                for (int i = 0; i < units.length - 1; i++) {
                    float[] u = units[i];
                    monRects.add(new RectF(area.left + u[0] * k, area.top + u[1] * k, area.left + (u[0] + u[2]) * k, area.top + (u[1] + u[3]) * k));
                    monIds.add((int) u[4]);
                }
                // 使用者自己排過的螢幕：照存的位置與大小（中心＝畫面寬高的比例、寬＝畫面寬的比例；高照那個螢幕的比例）
                java.util.List<int[]> all = pc.monitors();
                for (int i = 0; i < monRects.size(); i++) {
                    float[] c3 = customLayout(pc, monIds.get(i));
                    if (c3 == null) continue;
                    int[] m = all.get(monIds.get(i));
                    float rw = c3[2] * w, rh = rw * m[1] / (float) m[0];
                    float ccx = Math.max(rw / 2, Math.min(w - rw / 2, c3[0] * w)), ccy = Math.max(rh / 2, Math.min(h - rh / 2, c3[1] * h));
                    monRects.get(i).set(ccx - rw / 2, ccy - rh / 2, ccx + rw / 2, ccy + rh / 2);
                }
                // 整體範圍＝所有方框的聯集（手掌區、觸碰判斷用）
                RectF u0 = new RectF(monRects.get(0));
                for (RectF r : monRects) u0.union(r);
                area.set(u0);
            }
        }

        private String layKey(PcList.Pc pc, int mon) { return "lay_" + pc.id + "_" + mon; }
        private float[] customLayout(PcList.Pc pc, int mon) {
            String v = rp.getString(layKey(pc, mon), "");
            String[] f = v.split(",");
            if (f.length != 3) return null;
            try { return new float[]{Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2])}; } catch (Exception e) { return null; }
        }

        private boolean multi() { PcList.Pc pc = hub.pcs().selected(); return pc != null && pc.showAll(); }

        /**
         * 把所有螢幕排成一排或疊成一列（不照真實的相對位置——為了在手機上放得最大），挑在 w×h 裡放得比較大的那種。
         * 順序照真實位置：橫排由左到右、直疊由上到下（位置一樣就看另一軸）。螢幕之間留一點縫。
         */
        private float[][] arrange(java.util.List<int[]> ms, float w, float h) {
            int n = ms.size();
            Integer[] ord = new Integer[n];
            for (int i = 0; i < n; i++) ord[i] = i;
            float gap = 0;
            for (int[] m : ms) gap += m[1];
            gap = gap / n * 0.04f;
            float rowW = 0, rowH = 0, colW = 0, colH = 0;
            for (int[] m : ms) { rowW += m[0]; rowH = Math.max(rowH, m[1]); colW = Math.max(colW, m[0]); colH += m[1]; }
            rowW += gap * (n - 1); colH += gap * (n - 1);
            boolean row = Math.min(w / rowW, h / rowH) >= Math.min(w / colW, h / colH);
            final boolean byX = row;
            java.util.Arrays.sort(ord, (a, b) -> {
                int[] A = ms.get(a), B = ms.get(b);
                return byX ? (A[3] != B[3] ? Integer.compare(A[3], B[3]) : Integer.compare(A[4], B[4]))
                           : (A[4] != B[4] ? Integer.compare(A[4], B[4]) : Integer.compare(A[3], B[3]));
            });
            float[][] u = new float[n + 1][];
            float pos = 0;
            for (int k = 0; k < n; k++) {
                int[] m = ms.get(ord[k]);
                u[k] = row ? new float[]{pos, (rowH - m[1]) / 2f, m[0], m[1], ord[k]} : new float[]{(colW - m[0]) / 2f, pos, m[0], m[1], ord[k]};
                pos += (row ? m[0] : m[1]) + gap;
            }
            u[n] = row ? new float[]{rowW, rowH} : new float[]{colW, colH};
            return u;
        }

        /** 離 (x, y) 最近的螢幕方框（在裡面就是它）。 */
        private int pickMon(float x, float y) {
            int best = 0; float bd = Float.MAX_VALUE;
            for (int i = 0; i < monRects.size(); i++) {
                RectF r = monRects.get(i);
                float dx = Math.max(0, Math.max(r.left - x, x - r.right)), dy = Math.max(0, Math.max(r.top - y, y - r.bottom));
                float d = dx * dx + dy * dy;
                if (d < bd) { bd = d; best = i; }
            }
            return best;
        }

        @Override protected void onDraw(Canvas c) {
            layoutArea();
            if (multi()) { drawMulti(c); return; }
            c.drawRoundRect(area, dp(10), dp(10), bg);
            if (frame != null && previewOn()) {
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(area, dp(10), dp(10), android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                c.drawBitmap(frame, null, area, img);
                c.restore();
            } else if (!touchedOnce) {
                float cx = area.centerX(), cy = area.centerY();
                c.drawText(z < 0.999f ? "這一塊＝電腦螢幕的一部分（" + Math.round(z * 100) + "%）" : "這一塊＝整個電腦螢幕", cx, cy - dp(10), txt);
                c.drawText("碰哪裡，游標就到哪裡；兩指縮放／拖曳換範圍", cx, cy + dp(12), txt);
            }
            c.drawRoundRect(area, dp(10), dp(10), edge);
            if (z < 0.999f) c.drawText("範圍 " + Math.round(z * 100) + "%", area.left + dp(8), area.top + dp(18), tag);
            if (dotX >= 0) c.drawCircle(dotX, dotY, dp(pressing ? 6 : 4), dot);
            if (adjustingArea) {
                Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG); hl.setStyle(Paint.Style.STROKE); hl.setStrokeWidth(dp(3)); hl.setColor(0xFFBFA8FF);
                c.drawRoundRect(area, dp(10), dp(10), hl);
                boolean below = area.bottom + dp(44) < getHeight();
                float ty = below ? area.bottom + dp(20) : area.top - dp(30);
                c.drawText("單指拖曳＝移動　兩指捏合＝縮放（" + Math.round(padS * 100) + "%）", getWidth() / 2f, ty, txt);
                c.drawText("長按「完成」＝恢復整片", getWidth() / 2f, ty + dp(20), txt);
                return;
            }
            if (padS < 0.999f) c.drawText("手機範圍 " + Math.round(padS * 100) + "%", area.left + dp(8), area.bottom - dp(8), tag);
            if (!tabletLandscape() && padS > 0.999f && getHeight() - area.bottom > dp(60))
                c.drawText("手掌可以放在這一區（不會觸發）", getWidth() / 2f, (area.bottom + getHeight()) / 2f, txt);
        }

        private void drawMulti(Canvas c) {
            for (int i = 0; i < monRects.size(); i++) {
                RectF r = monRects.get(i);
                c.drawRoundRect(r, dp(8), dp(8), bg);
                android.graphics.Bitmap b = frames.get(monIds.get(i));
                if (b != null && previewOn()) {
                    c.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addRoundRect(r, dp(8), dp(8), android.graphics.Path.Direction.CW);
                    c.clipPath(clip);
                    c.drawBitmap(b, null, r, img);
                    c.restore();
                } else c.drawText("螢幕 " + (monIds.get(i) + 1), r.centerX(), r.centerY(), txt);
                c.drawRoundRect(r, dp(8), dp(8), edge);
                c.drawText(String.valueOf(monIds.get(i) + 1), r.left + dp(8), r.top + dp(18), tag);
            }
            if (dotX >= 0) c.drawCircle(dotX, dotY, dp(pressing ? 6 : 4), dot);
            if (adjustingArea) {
                Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG); hl.setStyle(Paint.Style.STROKE); hl.setStrokeWidth(dp(3)); hl.setColor(0xFFBFA8FF);
                for (RectF r : monRects) c.drawRoundRect(r, dp(8), dp(8), hl);
                boolean below = area.bottom + dp(44) < getHeight();
                float ty = below ? area.bottom + dp(20) : (area.top - dp(30) > dp(20) ? area.top - dp(30) : getHeight() / 2f);
                // 說明可能疊在預覽上：墊一塊半透明深色底才看得清楚
                Paint bk = new Paint(Paint.ANTI_ALIAS_FLAG); bk.setColor(0xCC15121C);
                c.drawRoundRect(new RectF(dp(8), ty - dp(18), getWidth() - dp(8), ty + dp(28)), dp(8), dp(8), bk);
                c.drawText("碰哪個螢幕就調哪個：單指拖曳＝移動　兩指捏合＝縮放", getWidth() / 2f, ty, txt);
                c.drawText("長按「完成」＝全部恢復自動排列", getWidth() / 2f, ty + dp(20), txt);
            } else if (!tabletLandscape() && getHeight() - area.bottom > dp(60))
                c.drawText("手掌可以放在這一區（不會觸發）", getWidth() / 2f, (area.bottom + getHeight()) / 2f, txt);
        }

        private void send(float x, float y, int press) {
            if (multi() && !monRects.isEmpty()) {
                // 筆滑進另一個螢幕的方框就跟著換過去；在縫裡維持原本那個
                for (int i = 0; i < monRects.size(); i++) if (monRects.get(i).contains(x, y)) curMon = i;
                if (curMon >= monRects.size()) curMon = 0;
                RectF r = monRects.get(curMon);
                float fx = Math.max(0f, Math.min(1f, (x - r.left) / r.width())), fy = Math.max(0f, Math.min(1f, (y - r.top) / r.height()));
                hub.absolute(Math.round(fx * 65535), Math.round(fy * 65535), press, monIds.get(curMon));
                dotX = r.left + fx * r.width(); dotY = r.top + fy * r.height();
                touchedOnce = true;
                invalidate();
                return;
            }
            float fx = Math.max(0f, Math.min(1f, (x - area.left) / area.width()));
            float fy = Math.max(0f, Math.min(1f, (y - area.top) / area.height()));
            hub.absolute(Math.round((rx + fx * z) * 65535), Math.round((ry + fy * z) * 65535), press);
            dotX = area.left + fx * area.width(); dotY = area.top + fy * area.height();
            touchedOnce = true;
            invalidate();
        }

        private boolean isPen(MotionEvent e, int i) {
            int t = e.getToolType(i);
            return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER;
        }
        private boolean onCanvas(float x, float y) {
            float m = dp(8);
            if (multi() && !monRects.isEmpty()) {   // 自己排過之後方框之間可能有大空隙：只認方框本身（加一點邊）
                for (RectF r : monRects) if (x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m) return true;
                return false;
            }
            return x >= area.left - m && x <= area.right + m && y >= area.top - m && y <= area.bottom + m;
        }

        @Override public boolean onHoverEvent(MotionEvent e) {
            if (!isPen(e, 0)) return false;
            lastStylusAt = e.getEventTime();
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_HOVER_MOVE || a == MotionEvent.ACTION_HOVER_ENTER) send(e.getX(), e.getY(), -1);
            return true;
        }

        // ---- 兩指：縮放＋拖曳對應範圍（像看地圖）。進來時如果第一指正按著左鍵，先放開。 ----
        private void startGesture(MotionEvent e) {
            if (activeId >= 0 && pressing) {
                int i = e.findPointerIndex(activeId);
                if (i >= 0) send(e.getX(i), e.getY(i), 0);
            }
            pressing = false; activeId = -1;
            gesture = true;
            g0Span = span(e); g0Cx = cx(e); g0Cy = cy(e); g0Rx = rx; g0Ry = ry; g0Z = z;
        }
        private void moveGesture(MotionEvent e) {
            float sp = span(e);
            if (g0Span < dp(20) || sp < dp(20)) return;
            float nz = Math.max(0.2f, Math.min(1f, g0Z * g0Span / sp));
            // 手指中心底下的那個螢幕點跟著手指走
            float sx = g0Rx + (g0Cx - area.left) / area.width() * g0Z, sy = g0Ry + (g0Cy - area.top) / area.height() * g0Z;
            float nx = sx - (cx(e) - area.left) / area.width() * nz, ny = sy - (cy(e) - area.top) / area.height() * nz;
            z = nz; rx = Math.max(0f, Math.min(1f - nz, nx)); ry = Math.max(0f, Math.min(1f - nz, ny));
            frame = null;   // 舊畫面是舊範圍的，先不畫，等新的一張
            requestFrame(false);
            invalidate();
        }
        private float span(MotionEvent e) { return e.getPointerCount() < 2 ? 0 : (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); }
        private float cx(MotionEvent e) { return e.getPointerCount() < 2 ? e.getX() : (e.getX(0) + e.getX(1)) / 2; }
        private float cy(MotionEvent e) { return e.getPointerCount() < 2 ? e.getY() : (e.getY(0) + e.getY(1)) / 2; }

        // 調整手機範圍：單指拖曳移動、兩指捏合縮放（25%～100%）。手指數變了就重設基準。
        private int adjIdx = -1;
        private RectF adj0 = new RectF();
        // 同時顯示時調整：碰到的那個螢幕方框自己移動／縮放
        private void adjustMulti(MotionEvent e) {
            int a = e.getActionMasked(), n = e.getPointerCount();
            PcList.Pc pc = hub.pcs().selected();
            if (pc == null || monRects.isEmpty()) return;
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (adjIdx >= 0 && adjIdx < monRects.size()) {
                    RectF r = monRects.get(adjIdx);
                    rp.edit().putString(layKey(pc, monIds.get(adjIdx)),
                            (r.centerX() / getWidth()) + "," + (r.centerY() / getHeight()) + "," + (r.width() / getWidth())).apply();
                }
                a0Count = 0;
                requestFrame(true);
                return;
            }
            if (a == MotionEvent.ACTION_DOWN) adjIdx = pickMon(e.getX(), e.getY());
            if (adjIdx < 0 || adjIdx >= monRects.size()) return;
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN || a == MotionEvent.ACTION_POINTER_UP || n != a0Count) {
                a0Count = a == MotionEvent.ACTION_POINTER_UP ? -1 : n;
                adj0.set(monRects.get(adjIdx)); a0Fx = cx(e); a0Fy = cy(e); a0Span = span(e);
                return;
            }
            if (a == MotionEvent.ACTION_MOVE) {
                float k = (n >= 2 && a0Span > dp(20)) ? span(e) / a0Span : 1f;
                float nw = Math.max(dp(80), Math.min(getWidth(), adj0.width() * k)), nh = nw * adj0.height() / adj0.width();
                float ncx = adj0.centerX() + cx(e) - a0Fx, ncy = adj0.centerY() + cy(e) - a0Fy;
                // 直接改方框，再存成自訂（下一次 layoutArea 會照存的值）
                rp.edit().putString(layKey(pc, monIds.get(adjIdx)), (ncx / getWidth()) + "," + (ncy / getHeight()) + "," + (nw / getWidth())).apply();
                layoutArea();
                invalidate();
            }
        }

        private void adjustTouch(MotionEvent e) {
            if (multi()) { adjustMulti(e); return; }
            int a = e.getActionMasked(), n = e.getPointerCount();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                a0Count = 0;
                rp.edit().putFloat("padS", padS).putFloat("padCx", padCx).putFloat("padCy", padCy).apply();
                requestFrame(true);
                return;
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN || a == MotionEvent.ACTION_POINTER_UP || n != a0Count) {
                int keep = a == MotionEvent.ACTION_POINTER_UP ? n - 1 : n;
                a0Count = keep; a0Cx = area.centerX(); a0Cy = area.centerY(); a0S = padS;
                a0Fx = cx(e); a0Fy = cy(e); a0Span = span(e);
                if (a == MotionEvent.ACTION_POINTER_UP) a0Count = -1;   // 下一次移動再取基準（離開那根還在這個事件裡）
                return;
            }
            if (a == MotionEvent.ACTION_MOVE) {
                if (n >= 2 && a0Span > dp(20)) padS = Math.max(0.25f, Math.min(1f, a0S * span(e) / a0Span));
                padCx = (a0Cx + cx(e) - a0Fx) / getWidth(); padCy = (a0Cy + cy(e) - a0Fy) / getHeight();
                layoutArea();
                padCx = area.centerX() / getWidth(); padCy = area.centerY() / getHeight();   // 存夾住之後的位置
                invalidate();
            }
        }
        void resetPadArea() {
            padS = 1f; padCx = padCy = -1f;
            android.content.SharedPreferences.Editor ed = rp.edit().putFloat("padS", 1f).putFloat("padCx", -1f).putFloat("padCy", -1f);
            PcList.Pc pc = hub.pcs().selected();
            if (pc != null) for (String k : rp.getAll().keySet()) if (k.startsWith("lay_" + pc.id + "_")) ed.remove(k);   // 每個螢幕自己排的也清掉
            ed.apply();
            invalidate(); requestFrame(true);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            layoutArea();
            if (adjustingArea) { adjustTouch(e); return true; }
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                gesture = false;
                if (!onCanvas(e.getX(), e.getY())) return true;   // 範圍外（手掌區）
                if (!isPen(e, 0) && e.getEventTime() - lastStylusAt < 3000) return true;   // 剛用過筆：當成手掌，不理
                if (isPen(e, 0)) lastStylusAt = e.getEventTime();
                if (android.os.Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e);
                activeId = e.getPointerId(0);
                curMon = pickMon(e.getX(), e.getY());
                pressing = hub.settings().on(MouseSettings.TABLET_PRESS);
                hub.setActive(true);
                send(e.getX(), e.getY(), pressing ? 1 : -1);
                return true;
            }
            if (gesture) {
                if (a == MotionEvent.ACTION_POINTER_DOWN || a == MotionEvent.ACTION_POINTER_UP) rebase = true;   // 手指數變了：下一次移動重設基準
                if (a == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) {
                    if (rebase) { rebase = false; g0Span = span(e); g0Cx = cx(e); g0Cy = cy(e); g0Rx = rx; g0Ry = ry; g0Z = z; }
                    else moveGesture(e);
                }
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    gesture = false;
                    rp.edit().putFloat("tabRx", rx).putFloat("tabRy", ry).putFloat("tabZ", z).apply();
                    hub.setActive(false);
                    requestFrame(true);
                }
                return true;
            }
            if (a == MotionEvent.ACTION_POINTER_DOWN) {
                int k = e.getActionIndex();
                if (!onCanvas(e.getX(k), e.getY(k))) return true;
                if (activeId < 0) {
                    // 先放在手掌區的那根不算：之後落在畫布上的第一根才是筆
                    activeId = e.getPointerId(k);
                    curMon = pickMon(e.getX(k), e.getY(k));
                    pressing = hub.settings().on(MouseSettings.TABLET_PRESS);
                    hub.setActive(true);
                    send(e.getX(k), e.getY(k), pressing ? 1 : -1);
                    return true;
                }
                // 畫布上已經有一根、又來一根：兩指手勢（換範圍）
                int i0 = e.findPointerIndex(activeId);
                if (!multi() && i0 >= 0 && onCanvas(e.getX(i0), e.getY(i0)) && !isPen(e, k) && !isPen(e, i0)) startGesture(e);   // 全部同時顯示時不做局部放大
                return true;
            }
            int i = activeId < 0 ? -1 : e.findPointerIndex(activeId);
            if (i < 0) return true;
            if (isPen(e, i)) lastStylusAt = e.getEventTime();
            switch (a) {
                case MotionEvent.ACTION_MOVE:
                    send(e.getX(i), e.getY(i), -1);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (e.getPointerId(e.getActionIndex()) != activeId) break;
                    // 主要那根先離開：當成放開
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    send(e.getX(i), e.getY(i), pressing ? 0 : -1);
                    pressing = false;
                    activeId = -1;
                    hub.setActive(false);
                    invalidate();
                    break;
            }
            return true;
        }
    }
}
