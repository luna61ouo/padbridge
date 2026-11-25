package io.github.luna61ouo.padbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 滑鼠 API 的登記名單（11-25）：哪些 App 可以透過 {@link ApiService} 操作滑鼠。
 *
 * 認人的規則（防偽造）：
 *  - 呼叫者身分一律用系統給的 uid（Binder／Message.sendingUid），App 自己報的名字不算數。
 *  - 一個 uid 只能對到一個套件（共用 uid 的一律拒絕）。
 *  - 登記時記下簽章指紋（SHA-256）。同名但換了簽章（刪掉重裝成別人的 App）＝陌生人。
 *  - 核准只能在 Padbridge 自己的畫面按（{@link ApiApproveActivity}，不對外開放、擋疊加畫面）。
 * 權限：第一版只有 scroll、click（捲動、左右鍵），不能移動游標、不能打字。
 */
final class ApiApps {
    static final String TAG = "PadbridgeApi";
    static final String SCOPE_V1 = "scroll,click";
    private static final int NOTI_ACTIVE = 8;          // 「某某正在控制滑鼠」
    private static final int NOTI_ASK_BASE = 200;      // 核准請求（每個套件一則）
    private static final long ASK_COOLDOWN_MS = 60_000;
    private static final long ACTIVE_IDLE_MS = 15_000;
    private static final long CALLER_CACHE_MS = 30_000;
    private static final double RATE_PER_SEC = 30, RATE_BURST = 30;

    static final class Caller {
        final int uid; final String pkg; final String digest; final String label; final long at;
        Caller(int uid, String pkg, String digest, String label, long at) { this.uid = uid; this.pkg = pkg; this.digest = digest; this.label = label; this.at = at; }
    }

    private static ApiApps sInstance;
    static synchronized ApiApps get(Context c) {
        if (sInstance == null) sInstance = new ApiApps(c.getApplicationContext());
        return sInstance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Caller> callers = new HashMap<>();
    private final Map<String, Long> askedAt = new HashMap<>();
    private final Map<Integer, double[]> buckets = new HashMap<>();   // uid → {tokens, lastMs}
    private String activePkg;
    private long activeShownAt;

    private ApiApps(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("api", Context.MODE_PRIVATE);
    }

    // ---------- 認人 ----------
    /** uid → 呼叫者（查不到、共用 uid、拿不到簽章 ⇒ null）。 */
    synchronized Caller resolve(int uid) {
        long now = System.currentTimeMillis();
        Caller c = callers.get(uid);
        if (c != null && now - c.at < CALLER_CACHE_MS) return c;
        callers.remove(uid);
        PackageManager pm = ctx.getPackageManager();
        String[] pk = pm.getPackagesForUid(uid);
        if (pk == null || pk.length != 1) { Log.w(TAG, "uid " + uid + " packages=" + Arrays.toString(pk)); return null; }
        String d = certDigest(ctx, pk[0]);
        if (d == null) return null;
        String label = pk[0];
        try { label = pm.getApplicationLabel(pm.getApplicationInfo(pk[0], 0)).toString(); } catch (Exception ignored) { }
        c = new Caller(uid, pk[0], d, label, now);
        callers.put(uid, c);
        return c;
    }

    /** 套件目前的簽章指紋：所有簽署者的 SHA-256 排序後以逗號串起來。 */
    static String certDigest(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null) return null;
            Signature[] sigs = pi.signingInfo.getApkContentsSigners();
            if (sigs == null || sigs.length == 0) return null;
            List<String> out = new ArrayList<>();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs) out.add(hex(md.digest(s.toByteArray())));
            java.util.Collections.sort(out);
            return android.text.TextUtils.join(",", out);
        } catch (Exception e) { Log.w(TAG, "digest " + pkg + " " + e); return null; }
    }

    // ---------- 名單 ----------
    private JSONObject apps() {
        try { return new JSONObject(prefs.getString("apps", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    private JSONArray denied() {
        try { return new JSONArray(prefs.getString("denied", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** 這個呼叫者有沒有被核准做這件事（名字、簽章、權限三樣都要對）。 */
    synchronized boolean allowed(Caller c, String scope) {
        JSONObject a = apps().optJSONObject(c.pkg);
        if (a == null) return false;
        if (!c.digest.equals(a.optString("d"))) { Log.w(TAG, c.pkg + " 簽章跟登記時不同，不放行"); return false; }
        return ("," + a.optString("scope") + ",").contains("," + scope + ",");
    }

    synchronized boolean isDenied(String pkg) {
        JSONArray d = denied();
        for (int i = 0; i < d.length(); i++) if (pkg.equals(d.optString(i))) return true;
        return false;
    }

    synchronized boolean isRegistered(Caller c) {
        JSONObject a = apps().optJSONObject(c.pkg);
        return a != null && c.digest.equals(a.optString("d"));
    }

    /** 核准（只由 ApiApproveActivity 呼叫）。核准當下重算簽章，跟請求時看到的不同就不收。 */
    synchronized boolean approve(String pkg, String digestSeen, String label) {
        String now = certDigest(ctx, pkg);
        if (now == null || !now.equals(digestSeen)) { Log.w(TAG, "approve " + pkg + "：簽章在請求後變了，不收"); return false; }
        try {
            JSONObject all = apps();
            all.put(pkg, new JSONObject().put("d", now).put("label", label).put("scope", SCOPE_V1).put("t", System.currentTimeMillis()));
            prefs.edit().putString("apps", all.toString()).putString("denied", without(denied(), pkg).toString()).apply();
        } catch (Exception e) { return false; }
        cancelAsk(pkg);
        return true;
    }

    synchronized void deny(String pkg) {
        JSONArray d = without(denied(), pkg);
        d.put(pkg);
        prefs.edit().putString("denied", d.toString()).apply();
        cancelAsk(pkg);
    }

    /** 撤銷／移除（拒絕名單也一併清掉，下次再要會重新問）。 */
    synchronized void remove(String pkg) {
        JSONObject all = apps();
        all.remove(pkg);
        prefs.edit().putString("apps", all.toString()).putString("denied", without(denied(), pkg).toString()).apply();
        if (pkg.equals(activePkg)) clearActive();
    }

    /** 已核准的 App：{套件, 顯示名稱}。 */
    synchronized List<String[]> list() {
        List<String[]> out = new ArrayList<>();
        JSONObject all = apps();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            out.add(new String[]{k, all.optJSONObject(k) == null ? k : all.optJSONObject(k).optString("label", k)});
        }
        return out;
    }

    synchronized String info() {
        StringBuilder sb = new StringBuilder("approved:\n");
        JSONObject all = apps();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject a = all.optJSONObject(k);
            sb.append("  ").append(k).append(" [").append(a.optString("label")).append("] scope=").append(a.optString("scope"))
              .append(" cert=").append(a.optString("d").length() > 16 ? a.optString("d").substring(0, 16) : a.optString("d")).append('\n');
        }
        sb.append("denied: ").append(denied().toString()).append('\n');
        return sb.toString();
    }

    private static JSONArray without(JSONArray a, String pkg) {
        JSONArray o = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!pkg.equals(a.optString(i))) o.put(a.optString(i));
        return o;
    }

    // ---------- 頻率上限 ----------
    synchronized boolean takeToken(int uid) {
        long now = System.currentTimeMillis();
        double[] b = buckets.get(uid);
        if (b == null) { b = new double[]{RATE_BURST, now}; buckets.put(uid, b); }
        b[0] = Math.min(RATE_BURST, b[0] + (now - b[1]) / 1000.0 * RATE_PER_SEC);
        b[1] = now;
        if (b[0] < 1) return false;
        b[0] -= 1;
        return true;
    }

    // ---------- 通知 ----------
    private NotificationManager nm() {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("api_ask", "App 要求控制滑鼠", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel("api_active", "App 正在控制滑鼠", NotificationManager.IMPORTANCE_LOW));
        return nm;
    }

    /** 發一則「某某想控制滑鼠」的通知，點了開核准畫面。回傳 false＝冷卻中沒發。 */
    synchronized boolean ask(Caller c) {
        long now = System.currentTimeMillis();
        Long last = askedAt.get(c.pkg);
        if (last != null && now - last < ASK_COOLDOWN_MS) return false;
        askedAt.put(c.pkg, now);
        Intent i = new Intent(ctx, ApiApproveActivity.class)
                .putExtra("pkg", c.pkg).putExtra("digest", c.digest).putExtra("label", c.label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, c.pkg.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, "api_ask")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("「" + c.label + "」想控制滑鼠")
                .setContentText("點這裡決定要不要允許（只能捲動、點左右鍵）")
                .setContentIntent(pi).setAutoCancel(true).build();
        nm().notify(NOTI_ASK_BASE + (c.pkg.hashCode() & 0xffff), n);
        Log.i(TAG, "ask " + c.pkg);
        return true;
    }

    private void cancelAsk(String pkg) {
        nm().cancel(NOTI_ASK_BASE + (pkg.hashCode() & 0xffff));
        askedAt.remove(pkg);
    }

    private final Runnable idle = this::clearActive;

    /** 有 App 剛操作過：常駐顯示「某某正在控制滑鼠」＋撤銷鍵，停 15 秒自己收掉。 */
    synchronized void touchActive(Caller c) {
        long now = System.currentTimeMillis();
        if (!c.pkg.equals(activePkg) || now - activeShownAt > 5_000) {
            activePkg = c.pkg;
            activeShownAt = now;
            Intent r = new Intent(ctx, ApiRevokeReceiver.class).putExtra("pkg", c.pkg);
            PendingIntent rp = PendingIntent.getBroadcast(ctx, c.pkg.hashCode(), r, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent open = PendingIntent.getActivity(ctx, 1, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(ctx, "api_active")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("「" + c.label + "」正在控制滑鼠")
                    .setContentText("不是你在用的話，按「撤銷」")
                    .setContentIntent(open).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "撤銷", rp).build())
                    .build();
            nm().notify(NOTI_ACTIVE, n);
        }
        main.removeCallbacks(idle);
        main.postDelayed(idle, ACTIVE_IDLE_MS);
    }

    synchronized void clearActive() {
        activePkg = null;
        main.removeCallbacks(idle);
        nm().cancel(NOTI_ACTIVE);
    }

    synchronized void forgetCaller(String pkg) {
        for (Iterator<Map.Entry<Integer, Caller>> it = callers.entrySet().iterator(); it.hasNext(); )
            if (it.next().getValue().pkg.equals(pkg)) it.remove();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
