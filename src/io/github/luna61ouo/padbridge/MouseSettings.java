package io.github.luna61ouo.padbridge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 所有使用者設定集中在這裡：每個功能一個開關、一段說明，設定畫面照這張表自動長出來。
 * 新增設定：在 ALL 加一筆 Toggle，程式裡用 on(KEY) 讀。只給除錯用的旗標不放這裡（MouseHub.setFlag）。
 */
public final class MouseSettings {
    public static final String ANNOUNCE_IN_USE = "announceInUse";
    public static final String WIFI_LOW_LATENCY = "wifiLowLatency";
    public static final String FIXED_ADDRESS = "daemon";   // 沿用舊 key（固定位址＝小幫手模式）

    public static final class Toggle {
        public final String key, title, help; public final boolean def;
        Toggle(String key, String title, String help, boolean def) { this.key = key; this.title = title; this.help = help; this.def = def; }
    }

    public static final Toggle[] ALL = {
        new Toggle(WIFI_LOW_LATENCY, "Wi-Fi 低延遲",
                "用觸控板時讓手機的 Wi-Fi 不進省電，游標比較不會一頓一頓。停手 5 秒恢復省電。", true),
        new Toggle(ANNOUNCE_IN_USE, "通知其他 App「滑鼠使用中」",
                "使用中對外廣播 io.github.luna61ouo.padbridge.action.IN_USE（附 transport＝ble／wifi、sec）。"
                        + "其他 App 可以收聽來暫時讓出藍牙（例如智慧眼鏡），要不要讓由它們決定。沒有 App 收聽就沒有作用。", true),
        new Toggle(FIXED_ADDRESS, "藍牙：固定位址（小幫手）",
                "藍牙滑鼠改由手機上的小幫手負責：用手機真實位址、App 被關掉也不斷線，電腦比較能自己連回來。"
                        + "小幫手要用無線偵錯（adb）啟動一次，沒啟動時打開這個會連不上。", false),
    };

    private final SharedPreferences p;
    public MouseSettings(Context c) { p = c.getSharedPreferences("mouse", Context.MODE_PRIVATE); }

    public boolean on(String key) {
        for (Toggle t : ALL) if (t.key.equals(key)) return p.getBoolean(key, t.def);
        return p.getBoolean(key, false);
    }
    public void set(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }

    /** 這支手機的名稱（電腦上的 Padbridge 圖示的「手機」清單上顯示）。預設用系統的裝置名稱。 */
    public String phoneName(Context c) {
        String n = p.getString("phoneName", "");
        if (n.isEmpty()) {
            try { n = android.provider.Settings.Global.getString(c.getContentResolver(), "device_name"); } catch (Exception ignored) {}
            if (n == null || n.isEmpty()) n = android.os.Build.MODEL;
        }
        return n.replace("|", " ").trim();
    }
    public void setPhoneName(String n) { p.edit().putString("phoneName", n == null ? "" : n.replace("|", " ").trim()).apply(); }

    public float sensitivity() { return p.getFloat("sens2", 0.45f); }
    public void setSensitivity(float s) { p.edit().putFloat("sens2", s).apply(); }
}
