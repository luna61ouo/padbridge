package io.github.luna61ouo.padbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 更新（11-20：誰舊誰提示、按了才更新）。
 *  - 手機 App：每天看一次 GitHub 最新版；比較新就提示，按了開 GitHub 下載頁（App 不多要「安裝應用程式」權限）。
 *  - 電腦端：找電腦回應第 7 欄報版本。比手機舊 ⇒ 提示，按了手機開一個 2 分鐘、只給那台電腦 IP 的臨時下載點，
 *    送簽章過的 u 封包叫它來拿 App 裡帶的安裝包＋發布簽名；電腦驗簽、確認比較新才裝（見 windows/PadbridgeUpdate.cs）。
 */
final class Updates {
    static final String TAG = "Padbridge";
    static final String API = "https://api.github.com/repos/luna61ouo/padbridge/releases/latest";
    private Updates() {}

    static String myVersion(Context c) {
        try { String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; return v == null ? "" : v; } catch (Exception e) { return ""; }
    }

    /** a 比 b 新回正數；"v0.13.0" 也可以。空字串＝最舊。 */
    static int cmp(String a, String b) {
        String[] x = (a == null ? "" : a.replaceFirst("^v", "")).split("\\."), y = (b == null ? "" : b.replaceFirst("^v", "")).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = 0, v = 0;
            try { if (i < x.length) u = Integer.parseInt(x[i]); } catch (Exception ignored) {}
            try { if (i < y.length) v = Integer.parseInt(y[i]); } catch (Exception ignored) {}
            if (u != v) return u - v;
        }
        return 0;
    }

    /** 一天最多查一次 GitHub（背景執行緒呼叫）。回傳最新版號（查不到回上次存的）。 */
    static String githubLatest(Context c, boolean force) {
        SharedPreferences p = c.getSharedPreferences("mouse", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("ghCheckedAt", 0) < 24L * 3600 * 1000) return p.getString("ghLatest", "");
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(8000);
            h.setRequestProperty("User-Agent", "Padbridge-app");
            h.setRequestProperty("Accept", "application/vnd.github+json");
            String body;
            try (InputStream in = h.getInputStream()) { body = new String(readAll(in, 2_000_000), StandardCharsets.UTF_8); }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9.]+)\"").matcher(body);
            if (m.find()) p.edit().putString("ghLatest", m.group(1)).putLong("ghCheckedAt", now).apply();
        } catch (Exception e) { Log.w(TAG, "github check " + e); p.edit().putLong("ghCheckedAt", now - 23L * 3600 * 1000).apply(); }   // 失敗：一小時後再試
        return p.getString("ghLatest", "");
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > max) throw new Exception("too big"); }
        return o.toByteArray();
    }

    /** App 裡帶的安裝包有發布簽名嗎（自己編、沒簽的版本不能推給電腦）。 */
    static boolean canPush(Context c) {
        try (InputStream in = c.getAssets().open("PadbridgeSetup-windows.zip.sig")) { return in.read() >= 0; } catch (Exception e) { return false; }
    }

    /**
     * 開臨時下載點：只回應 allowIp、2 分鐘後自己關。路徑是一次性的亂數。回傳 port，失敗 -1。
     * 走 HTTP 沒關係：電腦只裝發布簽名驗得過的檔，半路被換掉就驗不過。
     */
    static int serveOnce(Context c, final String allowIp, final String path) {
        final byte[] zip, sig;
        try (InputStream a = c.getAssets().open("PadbridgeSetup-windows.zip"); InputStream b = c.getAssets().open("PadbridgeSetup-windows.zip.sig")) {
            zip = readAll(a, 10_000_000); sig = readAll(b, 10_000);
        } catch (Exception e) { Log.w(TAG, "serveOnce assets " + e); return -1; }
        final ServerSocket ss;
        try { ss = new ServerSocket(); ss.setReuseAddress(true); ss.bind(new InetSocketAddress(0)); } catch (Exception e) { return -1; }
        final long until = System.currentTimeMillis() + 120_000;
        new Thread(() -> {
            try {
                ss.setSoTimeout(1000);
                while (System.currentTimeMillis() < until) {
                    Socket s;
                    try { s = ss.accept(); } catch (java.net.SocketTimeoutException te) { continue; }
                    try (Socket k = s) {
                        k.setSoTimeout(5000);
                        if (!k.getInetAddress().getHostAddress().equals(allowIp)) continue;
                        InputStream in = k.getInputStream();
                        StringBuilder line = new StringBuilder(); int ch;
                        while ((ch = in.read()) > 0 && ch != '\n' && line.length() < 300) line.append((char) ch);
                        String[] f = line.toString().trim().split(" ");
                        byte[] body = null; String type = "application/octet-stream";
                        if (f.length >= 2 && f[0].equals("GET")) {
                            if (f[1].equals("/" + path + "/zip")) { body = zip; type = "application/zip"; }
                            else if (f[1].equals("/" + path + "/sig")) { body = sig; type = "text/plain"; }
                        }
                        OutputStream out = k.getOutputStream();
                        String head = body == null ? "HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                : "HTTP/1.0 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                        out.write(head.getBytes(StandardCharsets.US_ASCII));
                        if (body != null) out.write(body);
                        out.flush();
                    } catch (Exception e) { Log.w(TAG, "serveOnce " + e); }
                }
            } catch (Exception e) { Log.w(TAG, "serveOnce loop " + e); }
            finally { try { ss.close(); } catch (Exception ignored) {} }
        }, "padbridge-update-serve").start();
        return ss.getLocalPort();
    }
}
