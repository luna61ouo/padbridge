package io.github.luna61ouo.padbridge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 所有使用者設定集中在這裡：每個功能一個開關、一段說明，設定畫面照這張表自動長出來。
 * 新增設定：在 ALL 加一筆 Toggle，程式裡用 on(KEY) 讀。只給除錯用的旗標不放這裡（MouseHub.setFlag）。
 */
public final class MouseSettings {
    public static final String WIFI_LOW_LATENCY = "wifiLowLatency";
    public static final String TABLET_PRESS = "tabletPress";   // 繪圖板：true＝自動左鍵、false＝按鍵觸發（畫面上切，不放設定頁）

    public static final class Toggle {
        public final String key, title, help; public final boolean def;
        Toggle(String key, String title, String help, boolean def) { this.key = key; this.title = title; this.help = help; this.def = def; }
    }

    public static final Toggle[] ALL = {
        new Toggle(WIFI_LOW_LATENCY, "Wi-Fi 低延遲",
                "用觸控板時讓手機的 Wi-Fi 不進省電，游標比較不會一頓一頓。停手 5 秒恢復省電。", true),
    };

    private final SharedPreferences p;
    public MouseSettings(Context c) { p = c.getSharedPreferences("mouse", Context.MODE_PRIVATE); }

    public boolean on(String key) {
        for (Toggle t : ALL) if (t.key.equals(key)) return p.getBoolean(key, t.def);
        return p.getBoolean(key, TABLET_PRESS.equals(key));
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
