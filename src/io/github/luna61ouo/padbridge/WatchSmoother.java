package io.github.luna61ouo.padbridge;

import android.util.Log;

import java.util.ArrayDeque;

/**
 * 手錶動作的「重新排節奏」：藍牙和 Wi-Fi 都會把框擠成一坨一坨到（實測約每 40 ms 一批），
 * 照到就送的話游標一頓一頓。手錶每框帶「距上一框幾 ms」，這裡照手錶原本的節奏一框一框放出去。
 *
 * 做法：同一段滑動裡，手錶時間 tw（累加間隔）對上手機到達時間，最快到的那框定為基準；
 * 每框排在「手錶時間＋基準＋緩衝」放出，緩衝＝最近抖動的 p90（10～60 ms）。晚到的照到就放。
 * 停手超過半秒算新的一段，基準重算（兩邊時鐘不用對）。
 */
final class WatchSmoother {
    private static final String TAG = MouseHub.TAG;
    private static final class Ev { double at; int dx, dy, wheel, btn; }

    private final MouseHub hub;
    private final boolean bypass;
    private final ArrayDeque<Ev> q = new ArrayDeque<>();
    private volatile boolean running = true;
    private double tw, base = Double.NaN, lastArr;
    private final double[] jit = new double[64];
    private int nj;
    private float accX, accY;
    private int held;
    double budgetMs = 30;

    WatchSmoother(MouseHub hub, float sens, boolean bypass) {
        this.hub = hub; this.bypass = bypass;
        if (!bypass) new Thread(this::loop, "pb-watch-smooth").start();
    }

    void frame(int gapMs, int dx, int dy, int wheel, int btn) {
        Ev e = new Ev();
        e.dx = dx; e.dy = dy; e.wheel = wheel; e.btn = btn;
        if (bypass) { apply(e); return; }
        double now = System.nanoTime() / 1e6;
        if (lastArr == 0 || now - lastArr > 500) { tw = 0; base = Double.NaN; nj = 0; }
        else tw += gapMs;
        lastArr = now;
        double d = now - tw;
        if (Double.isNaN(base) || d < base) base = d;
        jit[nj % jit.length] = d - base;
        nj++;
        if (nj >= 8) budgetMs = Math.max(10, Math.min(60, p90(Math.min(nj, jit.length))));
        e.at = tw + base + budgetMs;
        synchronized (q) { q.add(e); q.notifyAll(); }
    }

    private double p90(int n) {
        double[] s = java.util.Arrays.copyOf(jit, n);
        java.util.Arrays.sort(s);
        return s[(int) Math.floor(n * 0.9)];
    }

    private void loop() {
        try {
            while (running) {
                Ev e;
                synchronized (q) {
                    e = q.peek();
                    if (e == null) { q.wait(50); continue; }
                    double wait = e.at - System.nanoTime() / 1e6;
                    if (wait > 0.5) { q.wait(Math.max(1, (long) wait)); continue; }
                    q.poll();
                }
                apply(e);
            }
        } catch (InterruptedException ignored) { }
    }

    private void apply(Ev e) {
        if (e.dx != 0 || e.dy != 0) {
            float sens = hub.settings().watchSensitivity();   // 每框重讀：設定頁拉了馬上生效
            accX += e.dx * sens; accY += e.dy * sens;
            int mx = (int) accX, my = (int) accY;
            accX -= mx; accY -= my;
            hub.move(mx, my);
        }
        if (e.wheel != 0) hub.wheel(e.wheel);
        if (e.btn != held) {
            for (int b : new int[]{1, 2, 4})
                if ((e.btn & b) != (held & b)) hub.button(b, (e.btn & b) != 0);
            held = e.btn;
        }
    }

    /** 斷線：還沒放的照順序放完，最後一律放開按鍵。 */
    void stop() {
        running = false;
        Ev e;
        synchronized (q) { q.notifyAll(); }
        while ((e = pollNow()) != null) apply(e);
        for (int b : new int[]{1, 2, 4}) if ((held & b) != 0) hub.button(b, false);
        held = 0;
        Log.i(TAG, "watch smoother stop, budget " + Math.round(budgetMs) + "ms");
    }

    private Ev pollNow() { synchronized (q) { return q.poll(); } }
}
