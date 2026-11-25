package io.github.luna61ouo.padbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 被控端設定畫面（11-25）：讓這台 Android（例如車機、舊平板）被別支手機當觸控板控制。
 * 跟 Windows 電腦端同一套配對：這裡發配對碼、秀 QR（padbridge://pair#v=1&pc=…&ip=…&port=…&t=…&n=…），
 * 控制的那支手機用「選電腦 → 掃 QR 加電腦」掃，手機端完全不用改。配對碼只在螢幕上，不上網路。
 *
 * 三步：① 打開「Padbridge 被控端」無障礙服務（側載的 App 在 Android 13 起要先「允許受限制的設定」）
 *       ② 加一支手機（秀 QR）③ 兩台連同一個 Wi-Fi。
 */
public class RemoteSetupActivity extends Activity {
    private LinearLayout box;
    private SharedPreferences sp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("remote", MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));
        sv.addView(box);
        sv.setFitsSystemWindows(true);   // 標題別被狀態列壓到
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        RemoteWatch.check(this);
    }

    private void render() {
        box.removeAllViews();
        title("讓這台被控制（被控端）");
        note("用另一支裝了 Padbridge 的手機，當這台的觸控板：移動游標、點、滑、返回。兩台要連同一個 Wi-Fi（或同一個手機熱點）。");

        // ① 服務
        boolean on = RemoteWatch.serviceEnabled(this);
        head("① 打開「" + getString(R.string.remote_label) + "」" + (on ? "　✓ 已開" : "　✗ 還沒開"));
        if (!on) {
            note("點下面的按鈕到「無障礙」，找到「" + getString(R.string.remote_label) + "」打開。它只用來在這台畫游標、代你點按，不讀畫面上的文字。");
            note("開關是灰色、按不動？那是 Android 對「不是從商店裝的 App」的保護：到 設定 → 應用程式 → Padbridge → 右上角 ⋮ → 「允許受限制的設定」，再回來開。");
            btn("去打開", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            btn("開不了？到 Padbridge 的應用程式資訊", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))));
        }

        // ② 名稱
        head("② 這台的名稱（控制的手機上顯示）");
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(sp.getString("name", ""));
        et.setHint(android.os.Build.MODEL);
        box.addView(et);
        btn("存名稱", () -> { sp.edit().putString("name", et.getText().toString().replace("|", " ").trim()).apply(); render(); });

        // ③ 手機
        Map<String, String> ts = tokens();
        head("③ 可以控制這台的手機：" + (ts.isEmpty() ? "還沒有" : ts.size() + " 支"));
        List<String> ids = new ArrayList<>(ts.keySet());
        Collections.sort(ids);
        for (final String id : ids) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(label(id));
            t.setTextSize(15);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            Button qr = new Button(this); qr.setAllCaps(false); qr.setText("QR");
            qr.setOnClickListener(v -> showQr(id, ts.get(id)));
            r.addView(qr);
            Button rm = new Button(this); rm.setAllCaps(false); rm.setText("移除");
            rm.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("移除「" + label(id) + "」？那支手機就再也控制不了這台，要用得重新掃 QR。")
                    .setPositiveButton("移除", (d, w) -> { RemoteService.putToken(this, id, ""); render(); })
                    .setNegativeButton("取消", null).show());
            r.addView(rm);
            box.addView(r);
        }
        btn("＋ 加一支手機（顯示 QR）", this::addPhone);

        Inet4Address ip = PcList.wifiAddress();
        note(ip == null ? "⚠️ 這台現在沒有連 Wi-Fi，別的手機找不到它。" : "這台的 IP：" + ip.getHostAddress() + "（換了也沒關係，手機會自己找回來）");
    }

    private void addPhone() {
        if (!RemoteWatch.serviceEnabled(this)) {
            new AlertDialog.Builder(this).setMessage("先完成 ①：「" + getString(R.string.remote_label) + "」沒開的話，加了手機也控制不了。").setPositiveButton("好", null).show();
            return;
        }
        if (PcList.wifiAddress() == null) {
            new AlertDialog.Builder(this).setMessage("這台還沒連 Wi-Fi。先連上跟控制手機同一個 Wi-Fi，再加手機。").setPositiveButton("好", null).show();
            return;
        }
        String id = "p" + new java.text.SimpleDateFormat("MMddHHmmss", java.util.Locale.ROOT).format(new java.util.Date());
        String token = hex(16);
        RemoteService.putToken(this, id, token);
        render();
        showQr(id, token);
    }

    private void showQr(String id, String token) {
        Inet4Address ip = PcList.wifiAddress();
        String ipS = ip == null ? "" : ip.getHostAddress();
        String name = sp.getString("name", "");
        if (name.isEmpty()) name = android.os.Build.MODEL;
        String url;
        try {
            url = "padbridge://pair#v=1&pc=" + pcId() + "&ip=" + ipS + "&port=" + RemoteService.PORT + "&t=" + token
                    + "&n=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (Exception e) { return; }
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp(16), dp(8), dp(16), 0);
        ImageView iv = new ImageView(this);
        int px = Math.min(dp(280), Math.min(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) - dp(120));
        iv.setImageBitmap(qr(url, px));
        v.addView(iv, new LinearLayout.LayoutParams(px, px));
        TextView t = new TextView(this);
        t.setText("在要當觸控板的那支手機：開 Padbridge →「選電腦」→「掃 QR 加電腦」，掃這個。\n\n"
                + "沒辦法掃的話，選「手動輸入」：\nIP " + ipS + "　連接埠 " + RemoteService.PORT + "\n配對碼 " + token
                + "\n\n配對碼等於鑰匙，別給不認識的人看。");
        t.setTextSize(14);
        t.setPadding(0, dp(10), 0, 0);
        v.addView(t);
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        new AlertDialog.Builder(this).setTitle("加一支手機：" + label(id)).setView(sv).setPositiveButton("好了", null).show();
    }

    // ---------- 小工具 ----------
    private String pcId() {
        String id = sp.getString("pcId", "");
        if (id.isEmpty()) { id = hex(8); sp.edit().putString("pcId", id).apply(); }
        return id;
    }
    private Map<String, String> tokens() {
        Map<String, String> m = new java.util.HashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(sp.getString("tokens", "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.getString(k)); }
        } catch (Exception ignored) {}
        return m;
    }
    /** id 是 p＋加入時間（MMddHHmmss）；舊的（adb 設的）照原名顯示。 */
    private static String label(String id) {
        if (id.matches("p\\d{10}")) return "手機（" + id.substring(1, 3) + "-" + id.substring(3, 5) + " " + id.substring(5, 7) + ":" + id.substring(7, 9) + " 加入）";
        return id;
    }
    private static String hex(int bytes) {
        byte[] r = new byte[bytes]; new SecureRandom().nextBytes(r);
        StringBuilder b = new StringBuilder(); for (byte x : r) b.append(String.format("%02x", x));
        return b.toString();
    }
    private static Bitmap qr(String text, int px) {
        try {
            Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
            h.put(EncodeHintType.MARGIN, 2);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, h);
            int[] pix = new int[px * px];
            for (int y = 0; y < px; y++) for (int x = 0; x < px; x++) pix[y * px + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(pix, px, px, Bitmap.Config.ARGB_8888);
        } catch (Exception e) { return null; }
    }
    private void title(String s) { TextView t = new TextView(this); t.setText(s); t.setTextSize(22); t.setPadding(0, 0, 0, dp(8)); box.addView(t); }
    private void head(String s) { TextView t = new TextView(this); t.setText(s); t.setTextSize(17); t.setPadding(0, dp(18), 0, dp(4)); box.addView(t); }
    private void note(String s) { TextView t = new TextView(this); t.setText(s); t.setTextSize(14); t.setTextColor(0xFF9A8FB0); t.setPadding(0, dp(2), 0, dp(6)); box.addView(t); }
    private void btn(String s, Runnable r) { Button b = new Button(this); b.setAllCaps(false); b.setText(s); b.setOnClickListener(v -> r.run()); box.addView(b); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
