package io.github.luna61ouo.padbridge;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 「電腦還沒裝 Padbridge？」的區網備用下載：手機開一個暫時的小網站，電腦瀏覽器打開手機的 IP，選一種：
 *  - 快速直接下載：從手機直接拿 Windows 通用安裝包（跟 GitHub Releases 上的是同一個檔，不含任何配對碼）。
 *    走 HTTP，同網路的人有機會在半路換掉檔案 ⇒ 按下去先跳提醒，只在自己信任的網路用。
 *  - 高安全性下載：頁面只給 GitHub Releases 的連結，檔案由瀏覽器經 HTTPS 從 GitHub 拿。
 *    這個連結本身也走 HTTP，所以請使用者確認網址列是 github.com/luna61ouo/padbridge。
 * 裝好之後的配對一律掃電腦螢幕上的 QR（不經網路）。只在使用者按下時開；10 分鐘或關掉對話框就關。
 */
public final class SetupServer {
    static final String TAG = "Padbridge";
    public static final int PORT = 47830;
    public static final String RELEASES_URL = "https://github.com/luna61ouo/padbridge/releases/latest";
    static final String ASSET = "PadbridgeSetup-windows.zip";
    public interface Listener { void onEvent(String text, boolean done); }

    private final Context ctx;
    private final Listener l;
    private final String version;
    private final boolean openWifi;
    private volatile ServerSocket ss;
    private byte[] zip;
    private String sha256 = "";

    SetupServer(Context c, boolean openWifi, Listener l) {
        ctx = c; this.l = l; this.openWifi = openWifi;
        String v = "";
        try { v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        version = v == null ? "" : v;
    }

    public String url() {
        java.net.Inet4Address a = PcList.wifiAddress();
        return a == null ? null : "http://" + a.getHostAddress() + ":" + PORT;
    }

    /** 安裝包的 SHA-256（小寫 16 進位）。手機畫面上也顯示，給想用 certutil 自己比對的人。 */
    public String sha256() {
        load();
        return sha256;
    }

    String fileName() { return "PadbridgeSetup-windows" + (version.isEmpty() ? "" : "-" + version) + ".zip"; }

    private synchronized void load() {
        if (zip != null) return;
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            zip = o.toByteArray();
            StringBuilder h = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(zip)) h.append(String.format("%02x", x));
            sha256 = h.toString();
        } catch (Exception e) {
            Log.w(TAG, "setup asset " + e);
            zip = new byte[0];
        }
    }

    public void start() {
        load();
        new Thread(() -> {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                ss = s;
                s.setSoTimeout(1000);
                long end = System.currentTimeMillis() + 600_000;
                while (ss == s && System.currentTimeMillis() < end) {
                    Socket c;
                    try { c = s.accept(); } catch (java.net.SocketTimeoutException te) { continue; }
                    try { handle(c); } catch (Exception e) { Log.w(TAG, "setup " + e); } finally { try { c.close(); } catch (Exception ignored) {} }
                }
                if (ss == s) l.onEvent("10 分鐘到了，小網站已關閉（要再下載就重開一次）", true);
            } catch (Exception e) {
                l.onEvent("小網站開不起來：" + e.getMessage(), true);
            }
            ss = null;
        }, "setup-web").start();
    }

    public void stop() { ServerSocket s = ss; ss = null; try { if (s != null) s.close(); } catch (Exception ignored) {} }

    private void handle(Socket c) throws Exception {
        c.setSoTimeout(5000);
        InputStream in = c.getInputStream();
        StringBuilder req = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) { req.append((char) ch); if (req.length() > 4096 || req.toString().endsWith("\r\n\r\n")) break; }
        String[] parts = req.toString().split("\r\n", 2)[0].split(" ");
        String path = parts.length > 1 ? parts[1] : "/";
        OutputStream out = c.getOutputStream();
        String ip = c.getInetAddress().getHostAddress();
        if (path.equals("/download")) {
            if (zip.length == 0) { page(out, "<p>這支手機的 App 裡沒有安裝包，請改用「高安全性下載」。</p><p><a href=\"/\">回上一頁</a></p>"); return; }
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\"" + fileName() + "\"\r\n"
                    + "Content-Length: " + zip.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(zip); out.flush();
            l.onEvent("電腦（" + ip + "）下載了安裝包", false);
            return;
        }
        if (path.equals("/github")) {
            // 經手機轉一手再去 GitHub：只是為了在手機上看得到「有人選了這條」
            out.write(("HTTP/1.1 302 Found\r\nLocation: " + RELEASES_URL + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            l.onEvent("電腦（" + ip + "）選了高安全性下載，已轉到 GitHub", false);
            return;
        }
        if (!path.equals("/")) { notFound(out); return; }
        l.onEvent("電腦（" + ip + "）打開了下載頁", false);
        String warn = openWifi
                ? "⚠️ 這支手機現在連的是開放的公共 Wi-Fi（不用密碼就能連）。在這種網路上，同網路的任何人都可能把檔案換掉——建議改用「高安全性下載」。\\n\\n確定還是要直接下載嗎？"
                : "請確認你現在在自己信任的網路（例如家裡或公司），不是咖啡廳、車站這類公共 Wi-Fi。\\n\\n這條路走的是一般 HTTP：同一個網路上的人有機會在半路把檔案換掉。不確定的話，請改用「高安全性下載」。\\n\\n確定要直接下載嗎？";
        page(out, "<p>這台電腦要先裝 Padbridge 電腦端，手機才能把滑鼠動作送過來。請選一種下載方式：</p>"
                + "<div class=\"card\"><h3>⚡ 快速直接下載</h3>"
                + "<p>從手機直接下載安裝包（" + fileName() + "）。最快，但只適合在<b>自己信任的網路</b>使用。</p>"
                + "<a class=\"btn\" href=\"/download\" onclick=\"return confirm('" + warn + "')\">直接下載</a>"
                + "<p class=\"small\">檔案的 SHA-256（手機畫面上也看得到，可以對照）：<br><code>" + sha256 + "</code><br>"
                + "想自己比對：在下載的資料夾開 PowerShell，輸入 <code>certutil -hashfile " + fileName() + " SHA256</code></p></div>"
                + "<div class=\"card\"><h3>🔒 高安全性下載</h3>"
                + "<p>到 GitHub 官方發布頁下載同一個安裝包，檔案經 HTTPS 傳送，不會被半路換掉。</p>"
                + "<a class=\"btn\" href=\"/github\">前往 GitHub 下載</a>"
                + "<p class=\"small\">打開後請確認瀏覽器網址列是 <b>https://github.com/luna61ouo/padbridge</b> 開頭、有鎖頭圖示，再下載 <code>PadbridgeSetup-windows-*.zip</code>。"
                + "網址不對就不要下載。你也可以自己在網址列輸入：<br><code>" + RELEASES_URL + "</code></p></div>"
                + "<h3>下載之後</h3><ol>"
                + "<li>把 zip 解壓縮，雙擊裡面的 <b>PadbridgeSetup.cmd</b></li>"
                + "<li>出現「Windows 已保護您的電腦」→ 按 <b>其他資訊</b> → <b>仍要執行</b>（安裝檔沒有付費數位簽章）</li>"
                + "<li>問要不要允許變更 → 按 <b>是</b></li>"
                + "<li>裝好後，右下角會出現 Padbridge 圖示：右鍵 →「加一支手機（掃 QR）…」，用手機 App 的「掃 QR 加電腦」掃螢幕上的 QR</li></ol>");
    }

    private static void notFound(OutputStream out) throws Exception {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void page(OutputStream out, String inner) throws Exception {
        String html = "<!doctype html><html lang=\"zh-Hant\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>下載 Padbridge 電腦端</title><style>body{font-family:'Microsoft JhengHei',sans-serif;background:#15121c;color:#e8ddf5;max-width:640px;margin:40px auto;padding:0 16px;line-height:1.7}"
                + ".card{background:#221c2e;border:1px solid #3d3352;border-radius:12px;padding:4px 18px 10px;margin:16px 0}"
                + ".btn{display:inline-block;font-size:18px;padding:10px 20px;border-radius:8px;background:#9b84e8;color:#15121c;text-decoration:none;font-weight:bold}"
                + ".small{font-size:13px;color:#a99cc0;word-break:break-all}code{background:#15121c;padding:1px 5px;border-radius:4px}a{color:#bfa8ff}</style></head>"
                + "<body><h2>🌙 Padbridge 電腦端下載</h2>" + inner + "</body></html>";
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b); out.flush();
    }
}
