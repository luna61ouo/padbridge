package io.github.luna61ouo.padbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 鍵盤模組（11-20）：跟觸控板、繪圖板並列的第三個模組，兩種模式。
 *  - 電腦鍵盤（PcKeyboard）：畫一副美式鍵盤，送「現在按著哪些鍵」（Windows 虛擬鍵碼）。電腦當成實體按鍵，
 *    快捷鍵、F 鍵、遊戲按住 W 都可以；中文用電腦自己的輸入法。
 *    組合鍵兩種按法：多指（壓著 Ctrl 再點 C），或不用多指的按鍵鎖——
 *      修飾鍵（Ctrl／Shift／Alt／Win）點一下＝鎖一次（按下一顆鍵後放開），再點＝一直鎖住，再點＝解除；
 *      「🔒 鎖」再點任何一顆鍵＝那顆一直按著（自動長按），再點一次那顆才放開。
 *  - 手機打字（TypeCapture）：叫出手機輸入法，選定的字才送出（注音選字過程不送），電腦用 Unicode 直接打出來。
 */
final class Keyboards {
    private Keyboards() {}

    static final int VK_BACK = 0x08, VK_TAB = 0x09, VK_ENTER = 0x0D, VK_ESC = 0x1B, VK_DEL = 0x2E,
            VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28, VK_HOME = 0x24, VK_END = 0x23;
    static final Set<Integer> MODS = new HashSet<>(java.util.Arrays.asList(0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0x5B));

    static final class Key {
        final String label; final int vk; final float w;
        RectF r = new RectF();
        Key(String label, int vk, float w) { this.label = label; this.vk = vk; this.w = w; }
    }
    private static Key k(String l, int vk) { return new Key(l, vk, 1f); }
    private static Key k(String l, int vk, float w) { return new Key(l, vk, w); }

    /** 美式排列，每排總寬 15 格。第一排（功能鍵）矮一點。 */
    static List<List<Key>> layout() {
        List<List<Key>> rows = new ArrayList<>();
        List<Key> r0 = new ArrayList<>();
        r0.add(k("Esc", VK_ESC, 0.75f));
        for (int i = 1; i <= 12; i++) r0.add(k("F" + i, 0x6F + i, 0.75f));
        r0.add(k("PrtSc", 0x2C, 0.75f)); r0.add(k("Ins", 0x2D, 0.75f)); r0.add(k("Del", VK_DEL, 0.75f));
        r0.add(k("Home", VK_HOME, 0.75f)); r0.add(k("End", VK_END, 0.75f)); r0.add(k("PgUp", 0x21, 0.75f)); r0.add(k("PgDn", 0x22, 0.75f));
        rows.add(r0);
        List<Key> r1 = new ArrayList<>();
        r1.add(k("`", 0xC0));
        for (int i = 1; i <= 9; i++) r1.add(k("" + i, 0x30 + i));
        r1.add(k("0", 0x30)); r1.add(k("-", 0xBD)); r1.add(k("=", 0xBB)); r1.add(k("⌫", VK_BACK, 2f));
        rows.add(r1);
        List<Key> r2 = new ArrayList<>();
        r2.add(k("Tab", VK_TAB, 1.5f));
        for (char c : "QWERTYUIOP".toCharArray()) r2.add(k("" + c, c));
        r2.add(k("[", 0xDB)); r2.add(k("]", 0xDD)); r2.add(k("\\", 0xDC, 1.5f));
        rows.add(r2);
        List<Key> r3 = new ArrayList<>();
        r3.add(k("Caps", 0x14, 1.75f));
        for (char c : "ASDFGHJKL".toCharArray()) r3.add(k("" + c, c));
        r3.add(k(";", 0xBA)); r3.add(k("'", 0xDE)); r3.add(k("Enter", VK_ENTER, 2.25f));
        rows.add(r3);
        List<Key> r4 = new ArrayList<>();
        r4.add(k("Shift", 0xA0, 2.25f));
        for (char c : "ZXCVBNM".toCharArray()) r4.add(k("" + c, c));
        r4.add(k(",", 0xBC)); r4.add(k(".", 0xBE)); r4.add(k("/", 0xBF)); r4.add(k("Shift", 0xA1, 1.75f)); r4.add(k("↑", VK_UP));
        rows.add(r4);
        List<Key> r5 = new ArrayList<>();
        r5.add(k("Ctrl", 0xA2, 1.5f)); r5.add(k("Win", 0x5B, 1.25f)); r5.add(k("Alt", 0xA4, 1.25f));
        r5.add(k("Space", 0x20, 5.5f));
        r5.add(k("Alt", 0xA5, 1.25f)); r5.add(k("Ctrl", 0xA3, 1.25f));
        r5.add(k("←", VK_LEFT)); r5.add(k("↓", VK_DOWN)); r5.add(k("→", VK_RIGHT));
        rows.add(r5);
        return rows;
    }

    // ---------------------------------------------------------------------------------------------
    static class PcKeyboard extends View {
        interface LockListener { void onLockArmed(boolean armed); }
        private final MouseHub hub;
        private final float dens;
        private final List<List<Key>> rows = Keyboards.layout();
        private final Map<Integer, Key> down = new HashMap<>();       // 觸控點 → 壓著的鍵
        private final Set<Integer> ignored = new HashSet<>();          // 這次觸碰只是用來解除鎖，不算按下
        private final Map<Integer, Integer> modState = new HashMap<>(); // 修飾鍵：1＝鎖一次、2＝一直鎖住
        private final Set<Integer> modUsed = new HashSet<>();          // 壓著修飾鍵時有按別的鍵（＝真的按住在用，放開不改鎖）
        private final Set<Integer> locked = new HashSet<>();           // 按鍵鎖：一直按著的一般鍵
        private boolean lockArmed;
        private LockListener lockListener;
        private final Paint keyP = new Paint(Paint.ANTI_ALIAS_FLAG), txtP = new Paint(Paint.ANTI_ALIAS_FLAG), lineP = new Paint(Paint.ANTI_ALIAS_FLAG);

        PcKeyboard(Context c, MouseHub hub) {
            super(c);
            this.hub = hub;
            dens = c.getResources().getDisplayMetrics().density;
            txtP.setTextAlign(Paint.Align.CENTER);
            lineP.setStyle(Paint.Style.STROKE);
            lineP.setStrokeWidth(2 * dens);
            lineP.setColor(0xFFBAA0FF);
            setHapticFeedbackEnabled(true);
        }
        void setLockListener(LockListener l) { lockListener = l; }
        /** 「🔒 鎖」：下一顆點的鍵變成一直按著。再按一次「鎖」＝取消。 */
        void toggleLockArm() { lockArmed = !lockArmed; if (lockListener != null) lockListener.onLockArmed(lockArmed); invalidate(); }
        /** 全部放開、所有鎖解除（離開鍵盤畫面時）。 */
        void releaseAll() {
            down.clear(); ignored.clear(); modState.clear(); modUsed.clear(); locked.clear();
            if (lockArmed) toggleLockArm();
            hub.releaseKeys(); invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float gap = 3 * dens;
            float unitH = h / (rows.size() - 1 + 0.7f);   // 功能鍵那排 0.7 高
            float y = 0;
            for (int ri = 0; ri < rows.size(); ri++) {
                float rh = ri == 0 ? unitH * 0.7f : unitH;
                float total = 0; for (Key key : rows.get(ri)) total += key.w;
                float unitW = w / total, x = 0;
                for (Key key : rows.get(ri)) {
                    key.r.set(x + gap / 2, y + gap / 2, x + key.w * unitW - gap / 2, y + rh - gap / 2);
                    x += key.w * unitW;
                }
                y += rh;
            }
        }

        private Key hit(float x, float y) {
            for (List<Key> r : rows) for (Key key : r) if (key.r.contains(x, y)) return key;
            Key best = null; double bd = Double.MAX_VALUE;   // 縫裡：給最近的那顆
            for (List<Key> r : rows) for (Key key : r) {
                double d = Math.hypot(key.r.centerX() - x, key.r.centerY() - y);
                if (d < bd) { bd = d; best = key; }
            }
            return bd < 24 * dens ? best : null;
        }

        private Set<Integer> held() {
            Set<Integer> s = new HashSet<>(locked);
            for (Map.Entry<Integer, Key> e : down.entrySet()) if (!ignored.contains(e.getKey())) s.add(e.getValue().vk);
            for (Map.Entry<Integer, Integer> e : modState.entrySet()) if (e.getValue() > 0) s.add(e.getKey());
            return s;
        }
        private void push() { hub.setKeys(held()); invalidate(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && android.os.Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e);
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int i = e.getActionIndex(), id = e.getPointerId(i);
                Key key = hit(e.getX(i), e.getY(i));
                if (key == null) return true;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                boolean mod = MODS.contains(key.vk);
                if (lockArmed) {   // 按鍵鎖：這顆一直按著
                    toggleLockArm();
                    if (mod) modState.put(key.vk, 2); else locked.add(key.vk);
                    ignored.add(id); down.put(id, key); push(); return true;
                }
                if (!mod && locked.remove(key.vk)) { ignored.add(id); down.put(id, key); push(); return true; }   // 再點一次＝放開
                down.put(id, key);
                if (!mod) for (Key d : down.values()) if (MODS.contains(d.vk)) modUsed.add(d.vk);
                push();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
                int id = e.getPointerId(e.getActionIndex());
                Key key = down.remove(id);
                boolean ign = ignored.remove(id);
                if (key == null || ign) { invalidate(); return true; }
                if (MODS.contains(key.vk)) {
                    if (!modUsed.remove(key.vk)) {   // 單點修飾鍵：關 → 鎖一次 → 一直鎖住 → 關
                        int st = modState.containsKey(key.vk) ? modState.get(key.vk) : 0;
                        modState.put(key.vk, (st + 1) % 3);
                    }
                    push();
                } else {
                    push();   // 先放開這顆（修飾鍵還按著）
                    boolean any = false;
                    for (Map.Entry<Integer, Integer> m : modState.entrySet()) if (m.getValue() == 1) { m.setValue(0); any = true; }
                    if (any) push();   // 「鎖一次」的修飾鍵用完放開
                }
            } else if (a == MotionEvent.ACTION_CANCEL) {
                down.clear(); ignored.clear(); modUsed.clear(); push();
            }
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            Set<Integer> pressed = new HashSet<>();
            for (Map.Entry<Integer, Key> e : down.entrySet()) if (!ignored.contains(e.getKey())) pressed.add(e.getValue().vk);
            float rad = 6 * dens;
            for (int ri = 0; ri < rows.size(); ri++) for (Key key : rows.get(ri)) {
                int st = modState.containsKey(key.vk) ? modState.get(key.vk) : 0;
                boolean lk = locked.contains(key.vk) || st == 2;
                int col = pressed.contains(key.vk) ? 0xFF6B5A9E : lk ? 0xFF8A70D0 : st == 1 ? 0xFF4A3F6E
                        : MODS.contains(key.vk) || key.w > 1.2f || ri == 0 ? 0xFF231D30 : 0xFF2E2740;
                keyP.setColor(col);
                c.drawRoundRect(key.r, rad, rad, keyP);
                if (st == 1) c.drawRoundRect(key.r, rad, rad, lineP);
                txtP.setColor(lk ? 0xFFFFFFFF : 0xFFE8DDF5);
                float ts = Math.min(key.r.height() * 0.42f, (ri == 0 ? 11 : key.label.length() > 2 ? 13 : 17) * dens);
                txtP.setTextSize(ts);
                c.drawText(key.label + (lk ? " 🔒" : ""), key.r.centerX(), key.r.centerY() + ts * 0.35f, txtP);
            }
            if (lockArmed) {
                lineP.setColor(0xFFFFD27A);
                c.drawRect(dens, dens, getWidth() - dens, getHeight() - dens, lineP);
                lineP.setColor(0xFFBAA0FF);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    /**
     * 手機打字：這塊收手機輸入法的字。選定才送（注音選字中的字只顯示在這裡），刪字／換行即時送。
     * 編輯區永遠保持空的，所以輸入法按刪除會變成「刪除鍵」事件或刪前面的字 ⇒ 都轉成電腦的 Backspace。
     */
    static class TypeCapture extends View {
        private final MouseHub hub;
        private final float dens;
        private String composing = "", lastSent = "";
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), hint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TypeCapture(Context c, MouseHub hub) {
            super(c);
            this.hub = hub;
            dens = c.getResources().getDisplayMetrics().density;
            setFocusable(true); setFocusableInTouchMode(true);
            bg.setColor(0xFF2A2338);
            txt.setColor(0xFFE8DDF5); txt.setTextSize(18 * dens);
            hint.setColor(0xFF9A8FB0); hint.setTextSize(13 * dens);
            setOnClickListener(v -> showIme());
        }
        void showIme() {
            requestFocus();
            InputMethodManager im = getContext().getSystemService(InputMethodManager.class);
            if (im != null) im.showSoftInput(this, 0);
        }
        @Override public boolean onCheckIsTextEditor() { return true; }

        private void sent(String s) { lastSent = (lastSent + s); if (lastSent.length() > 40) lastSent = lastSent.substring(lastSent.length() - 40); invalidate(); }
        private void key(int vk, String mark) {
            hub.tapKey(vk);
            if (vk == VK_BACK && !lastSent.isEmpty()) lastSent = lastSent.substring(0, lastSent.length() - 1);
            else sent(mark);
            invalidate();
        }
        void sendText(String s) {
            if (s == null || s.isEmpty()) return;
            hub.typeText(s);   // 換行、Tab 由電腦轉成 Enter／Tab 鍵
            sent(s.replace("\n", "⏎"));
        }

        @Override public InputConnection onCreateInputConnection(EditorInfo out) {
            out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
            return new BaseInputConnection(this, true) {
                private void clear() { android.text.Editable ed = getEditable(); if (ed != null) { removeComposingSpans(ed); ed.clear(); } composing = ""; invalidate(); }
                @Override public boolean commitText(CharSequence t, int pos) {
                    sendText(t == null ? "" : t.toString());
                    clear();
                    return true;
                }
                @Override public boolean setComposingText(CharSequence t, int pos) {
                    super.setComposingText(t, pos);
                    composing = t == null ? "" : t.toString();
                    invalidate();
                    return true;
                }
                @Override public boolean finishComposingText() {
                    String s = composing;
                    clear();
                    sendText(s);
                    return true;
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    for (int i = 0; i < Math.min(before, 64); i++) key(VK_BACK, "");
                    for (int i = 0; i < Math.min(after, 64); i++) key(VK_DEL, "");
                    return true;
                }
                @Override public boolean sendKeyEvent(KeyEvent ev) {
                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
                    switch (ev.getKeyCode()) {
                        case KeyEvent.KEYCODE_DEL: key(VK_BACK, ""); return true;
                        case KeyEvent.KEYCODE_FORWARD_DEL: key(VK_DEL, ""); return true;
                        case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: key(VK_ENTER, "⏎"); return true;
                        case KeyEvent.KEYCODE_TAB: key(VK_TAB, "⇥"); return true;
                        case KeyEvent.KEYCODE_DPAD_LEFT: key(VK_LEFT, ""); return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT: key(VK_RIGHT, ""); return true;
                        case KeyEvent.KEYCODE_DPAD_UP: key(VK_UP, ""); return true;
                        case KeyEvent.KEYCODE_DPAD_DOWN: key(VK_DOWN, ""); return true;
                        case KeyEvent.KEYCODE_ESCAPE: key(VK_ESC, ""); return true;
                    }
                    int u = ev.getUnicodeChar();
                    if (u != 0) sendText(new String(Character.toChars(u)));
                    return true;
                }
                @Override public boolean performEditorAction(int action) { key(VK_ENTER, "⏎"); return true; }
            };
        }

        @Override protected void onDraw(Canvas c) {
            float r = 14 * dens;
            c.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, bg);
            float x = 14 * dens;
            if (!composing.isEmpty()) {
                txt.setColor(0xFFFFD27A);
                c.drawText("選字中：" + composing, x, getHeight() / 2f + 6 * dens, txt);
            } else if (!lastSent.isEmpty()) {
                txt.setColor(0xFFE8DDF5);
                String s = lastSent;
                while (s.length() > 1 && txt.measureText("已送出：" + s) > getWidth() - 2 * x) s = s.substring(1);
                c.drawText("已送出：" + s, x, getHeight() / 2f + 6 * dens, txt);
            } else {
                c.drawText(hasFocus() ? "用手機輸入法打字，選定的字會直接出現在電腦上" : "點這裡叫出輸入法", x, getHeight() / 2f + 5 * dens, hint);
            }
        }
        @Override protected void onFocusChanged(boolean f, int d, android.graphics.Rect p) { super.onFocusChanged(f, d, p); invalidate(); }
    }
}
