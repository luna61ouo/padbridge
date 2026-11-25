package io.github.luna61ouo.padbridge.wear;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * 空中滑鼠的移動（第一步，11-23）：讀陀螺儀的轉動速度，轉成游標位移交給 WatchLink。
 * 相對移動（像滑鼠在桌上推），不需要對準螢幕、不用校準。
 *
 * 方向跟手怎麼戴無關：用重力找出「垂直軸」，手腕繞垂直軸轉（左右轉）＝左右移；
 * 繞「水平、且跟前臂垂直」的軸轉（上下擺）＝上下移。前臂方向取手錶的 X 軸（錶帶方向的垂直線）。
 * 防手抖：很小的轉動當作沒動（死區）；慢慢轉時倍率較低、快甩時較高（精細與距離兼顧）。
 */
final class AirMotion implements SensorEventListener {
    private static final String TAG = "PadbridgeWatch";
    /** 每弧度幾個單位（手機再乘手錶速度，預設 ×2.5 ⇒ 轉約 30 度橫跨 1920 寬的螢幕）。 */
    private static final float GAIN = 1500f;
    private static final float DEAD = 0.03f;     // rad/s 以下當作沒動
    private static final float FULL = 1.2f;      // rad/s 以上用滿倍率

    private final SensorManager sm;
    private final WatchLink link;
    private final float[] g = {0, 0, 9.8f};
    private boolean hasG;
    private long lastNs;
    private boolean on;
    // 讀數：每秒幾筆陀螺儀
    private int n; private long n0;
    volatile int rate;

    AirMotion(Context c, WatchLink link) {
        sm = c.getSystemService(SensorManager.class);
        this.link = link;
    }

    boolean isOn() { return on; }

    void set(boolean want) {
        if (want == on) return;
        on = want;
        if (on) {
            Sensor gy = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            Sensor gr = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (gr == null) gr = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            lastNs = 0; hasG = false;
            sm.registerListener(this, gy, SensorManager.SENSOR_DELAY_FASTEST);
            sm.registerListener(this, gr, SensorManager.SENSOR_DELAY_GAME);
            link.finger(true);   // 一直送框，連線才不進省電節奏
            Log.i(TAG, "air on gyro=" + (gy == null ? "none" : gy.getName()) + " grav=" + (gr == null ? "none" : gr.getName()));
        } else {
            sm.unregisterListener(this);
            link.finger(false);
            Log.i(TAG, "air off");
        }
    }

    @Override public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t == Sensor.TYPE_GRAVITY || t == Sensor.TYPE_ACCELEROMETER) {
            float a = t == Sensor.TYPE_GRAVITY ? 1f : 0.1f;   // 加速度計要自己濾掉動作
            for (int i = 0; i < 3; i++) g[i] += a * (e.values[i] - g[i]);
            hasG = true;
            return;
        }
        if (t != Sensor.TYPE_GYROSCOPE) return;
        long now = e.timestamp;
        if (now - n0 > 1_000_000_000L) { rate = n; n = 0; n0 = now; }
        n++;
        if (lastNs == 0 || !hasG) { lastNs = now; return; }
        float dt = (now - lastNs) / 1e9f;
        lastNs = now;
        if (dt <= 0 || dt > 0.1f) return;

        // 垂直軸 v（重力方向，往上為正）
        float gl = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (gl < 1e-3f) return;
        float vx = g[0] / gl, vy = g[1] / gl, vz = g[2] / gl;
        // 水平軸 h＝v × X（跟前臂垂直、躺在水平面上）
        float hx = 0, hy = vz, hz = -vy;
        float hl = (float) Math.sqrt(hy * hy + hz * hz);
        float wx = e.values[0], wy = e.values[1], wz = e.values[2];
        float yaw = wx * vx + wy * vy + wz * vz;                   // 左右轉
        float pitch = hl < 0.2f ? 0 : (wy * hy + wz * hz) / hl;    // 上下擺（前臂快垂直時不算）
        link.move(shape(-yaw) * GAIN * dt, shape(pitch) * GAIN * dt);   // 上下：11-23 實測要反過來
    }

    /** 死區＋慢速降倍率。 */
    private static float shape(float w) {
        float a = Math.abs(w);
        if (a < DEAD) return 0;
        float k = 0.35f + 0.65f * Math.min(1f, (a - DEAD) / FULL);
        return Math.signum(w) * (a - DEAD) * k;
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }
}
