package io.github.luna61ouo.padbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wi-Fi 模式的電腦清單：一支手機可以換著控制好幾台電腦。
 * 每台電腦各自一組 pcId＋配對碼，存在 prefs（JSON）。
 * 找電腦：UDP 廣播 `LMD|<nonce>` 到 47801，電腦回 `LMH|pcId|主機名|自訂名稱|螢幕清單（寬x高，* 主螢幕，; 分隔）|hmac`，
 * hmac＝HMAC-SHA256(配對碼, nonce|pcId) 前 16 字——區網上別人冒充不了某台電腦（冒充的話滑鼠訊號跟配對碼會被騙走）。
 * 名稱以電腦端 name.txt 為準：手機改名送 `n`，電腦端圖示改名下次被找到時同步回來。
 */
public final class PcList {
    static final String TAG = "Padbridge";

    public static final class Pc {
        public String id, token, name = "", hostname = "", ip = "";
        public int port = 47801;
        public int scrW, scrH;   // 電腦主螢幕像素（繪圖板畫比例用；0＝不知道，舊版電腦端不回）
        public String ver = "";   // 電腦端版本（找電腦回應第 7 欄）；"" ＝沒回報＝舊版、不能自動更新
        public String mons = "";  // 電腦回報的螢幕清單原文，例 "1920x1080*;2560x1440"
        public int mon = -1;      // 舊欄位（11-20 09:3x 單選版）：-1＝主螢幕、-2＝全部；新版改用 monSel，讀到舊值時轉過去
        public static final int MON_ALL = -2;
        public String monSel = "";   // 繪圖板勾選了哪些螢幕（編號逗號分隔；空＝只有主螢幕）
        /** 勾選的螢幕編號（照編號排序、去掉不存在的；一個都沒有＝主螢幕）。 */
        public List<Integer> selectedMons() {
            List<int[]> ms = monitors();
            java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
            if (monSel.isEmpty() && mon == MON_ALL) for (int i = 0; i < ms.size(); i++) set.add(i);
            else if (monSel.isEmpty() && mon >= 0) set.add(mon);
            for (String t : monSel.split(",")) try { set.add(Integer.parseInt(t.trim())); } catch (Exception ignored) {}
            List<Integer> l = new ArrayList<>();
            for (int i : set) if (i >= 0 && i < ms.size()) l.add(i);
            if (l.isEmpty()) { for (int i = 0; i < ms.size(); i++) if (ms.get(i)[2] == 1) l.add(i); }
            if (l.isEmpty() && !ms.isEmpty()) l.add(0);
            return l;
        }
        public void setSelectedMons(List<Integer> l) {
            StringBuilder b = new StringBuilder();
            for (int i : l) { if (b.length() > 0) b.append(','); b.append(i); }
            monSel = b.toString(); mon = -1;
        }
        /** 螢幕清單：每個 {寬, 高, 是不是主螢幕, x, y}（x、y＝在虛擬桌面上的位置；舊電腦端沒給就是 0）。 */
        public List<int[]> monitors() {
            List<int[]> l = new ArrayList<>();
            for (String m : mons.split(";")) {
                java.util.regex.Matcher x = java.util.regex.Pattern.compile("^(\\d{3,5})x(\\d{3,5})(?:@(-?\\d{1,6}),(-?\\d{1,6}))?(\\*?)$").matcher(m.trim());
                if (x.find()) l.add(new int[]{Integer.parseInt(x.group(1)), Integer.parseInt(x.group(2)), x.group(5).isEmpty() ? 0 : 1,
                        x.group(3) == null ? 0 : Integer.parseInt(x.group(3)), x.group(4) == null ? 0 : Integer.parseInt(x.group(4))});
            }
            return l;
        }
        /** 全部同時顯示（而且真的有兩個以上螢幕）。 */
        public boolean showAll() { return selectedMons().size() > 1; }
        /** 目前選的螢幕在清單裡的編號（沒選或超出＝主螢幕；完全不知道＝-1）。 */
        public int monIndex() {
            List<int[]> l = monitors();
            List<Integer> sel = selectedMons();
            if (!sel.isEmpty()) return sel.get(0);
            if (mon >= 0 && mon < l.size()) return mon;
            for (int i = 0; i < l.size(); i++) if (l.get(i)[2] == 1) return i;
            return l.isEmpty() ? -1 : 0;
        }
        /** 選的螢幕的寬高比（不知道就用主螢幕、再不知道 16:9）。 */
        public float aspect() {
            int i = monIndex();
            if (i >= 0) { int[] m = monitors().get(i); return m[0] / (float) m[1]; }
            return scrW > 0 && scrH > 0 ? scrW / (float) scrH : 16f / 9f;
        }
        public long lastSeen;
        public transient boolean online;
        public String label() {
            String n = name.isEmpty() ? (hostname.isEmpty() ? ip : hostname) : name;
            return (!hostname.isEmpty() && !hostname.equalsIgnoreCase(n)) ? n + "（" + hostname + "）" : n;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("token", token).put("name", name).put("hostname", hostname)
                    .put("ip", ip).put("port", port).put("lastSeen", lastSeen).put("scrW", scrW).put("scrH", scrH).put("mons", mons).put("mon", mon).put("monSel", monSel).put("ver", ver);
        }
        static Pc from(JSONObject o) {
            Pc p = new Pc();
            p.id = o.optString("id"); p.token = o.optString("token"); p.name = o.optString("name");
            p.hostname = o.optString("hostname"); p.ip = o.optString("ip");
            p.port = o.optInt("port", 47801); p.lastSeen = o.optLong("lastSeen");
            p.scrW = o.optInt("scrW"); p.scrH = o.optInt("scrH");
            p.mons = o.optString("mons"); p.mon = o.optInt("mon", -1); p.monSel = o.optString("monSel"); p.ver = o.optString("ver");
            return p;
        }
    }

    private final SharedPreferences prefs;
    private final List<Pc> pcs = new ArrayList<>();

    PcList(Context c) {
        prefs = c.getSharedPreferences("mouse", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(prefs.getString("pcs", "[]"));
            for (int i = 0; i < a.length(); i++) pcs.add(Pc.from(a.getJSONObject(i)));
        } catch (Exception e) { Log.w(TAG, "pcs load " + e); }
        // 舊版只存一台（host/port/token）：轉成清單的第一台；pcId 等電腦第一次回應時用 IP 對上再換成真的
        if (pcs.isEmpty() && !prefs.getString("host", "").isEmpty()) {
            Pc p = new Pc();
            p.id = "m-" + randHex(4); p.ip = prefs.getString("host", ""); p.port = prefs.getInt("port", 47801);
            p.token = prefs.getString("token", "");
            pcs.add(p); select(p.id); save();
        }
    }

    public synchronized List<Pc> all() { return new ArrayList<>(pcs); }

    public synchronized Pc selected() {
        String id = prefs.getString("pcSel", "");
        for (Pc p : pcs) if (p.id.equals(id)) return p;
        return null;
    }

    public synchronized void select(String id) { prefs.edit().putString("pcSel", id).apply(); }

    public synchronized Pc byId(String id) { for (Pc p : pcs) if (p.id.equals(id)) return p; return null; }

    public synchronized void remove(String id) {
        for (int i = pcs.size() - 1; i >= 0; i--) if (pcs.get(i).id.equals(id)) pcs.remove(i);
        if (id.equals(prefs.getString("pcSel", ""))) prefs.edit().remove("pcSel").apply();
        save();
    }

    /** 手動輸入（IP＋port＋配對碼）：同 IP 的就更新，不然新增一台。 */
    public synchronized Pc addManual(String ip, int port, String token) {
        Pc p = null;
        for (Pc q : pcs) if (q.ip.equals(ip)) p = q;
        if (p == null) { p = new Pc(); p.id = "m-" + randHex(4); pcs.add(p); }
        p.ip = ip; p.port = port > 0 ? port : 47801; p.token = token;
        save(); return p;
    }

    /** 掃電腦上的 Padbridge 圖示的配對 QR：電腦給的 pcId＋IP＋配對碼，同 pcId 的就更新（重掃＝換新配對碼）。 */
    public synchronized Pc addFromQr(String pcId, String ip, int port, String token, String name) {
        Pc p = byId(pcId);
        if (p == null) { p = new Pc(); p.id = pcId; pcs.add(p); }
        p.ip = ip; p.port = port > 0 ? port : 47801; p.token = token;
        if (name != null && !name.isEmpty()) p.name = name;
        save(); return p;
    }

    /** 網頁安裝：先建一筆「等它裝好」的電腦，pcId 跟配對碼都由手機產生、包進安裝檔。 */
    public synchronized Pc addPending(String ip) {
        Pc p = new Pc();
        p.id = randHex(8); p.token = randHex(12); p.ip = ip; p.name = "";
        pcs.add(p); save(); return p;
    }

    public synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pc p : pcs) a.put(p.json());
            prefs.edit().putString("pcs", a.toString()).apply();
        } catch (Exception e) { Log.w(TAG, "pcs save " + e); }
    }

    /** 送一個已驗證的指令給某台電腦（改名用）。 */
    public void send(Pc p, String body) {
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] b = Wire.seal(p.token, body).getBytes(StandardCharsets.UTF_8);
                s.send(new DatagramPacket(b, b.length, InetAddress.getByName(p.ip), p.port));
            } catch (Exception e) { Log.w(TAG, "pc send " + e); }
        }).start();
    }

    /**
     * 找一次電腦（會卡住約 0.8 秒，別在主執行緒叫）。回來的會更新 IP、名稱、online；
     * 認得但沒回的標成 offline。新裝好的電腦（pending）在這裡第一次被認出來。
     */
    public void discover() {
        List<Pc> snap = all();
        java.util.Set<String> found = new java.util.HashSet<>();
        String nonce = randHex(8);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(150);
            byte[] q = ("LMD|" + nonce).getBytes(StandardCharsets.UTF_8);
            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            InetAddress sub = subnetBroadcast(); if (sub != null) targets.add(sub);
            for (Pc p : snap) if (!p.ip.isEmpty()) try { targets.add(InetAddress.getByName(p.ip)); } catch (Exception ignored) {}
            for (int round = 0; round < 2; round++)
                for (InetAddress t : targets) try { s.send(new DatagramPacket(q, q.length, t, 47801)); } catch (Exception ignored) {}
            long end = System.currentTimeMillis() + 800;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < end) {
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                try { s.receive(r); } catch (SocketTimeoutException te) { continue; }
                String[] f = new String(r.getData(), 0, r.getLength(), StandardCharsets.UTF_8).split("\\|", -1);
                if (f.length < 6 || !"LMH".equals(f[0])) continue;
                String ip = r.getAddress().getHostAddress();
                synchronized (this) {
                    // f[5]＝電腦對它認得的每支手機各算一個證明（逗號分隔；一台電腦可以有多支手機）；我這把對得上其中一個才算
                    java.util.Set<String> proofs = new java.util.HashSet<>(java.util.Arrays.asList(f[5].split(",")));
                    Pc p = null;
                    for (Pc q2 : pcs) if (proofs.contains(hmac(q2.token, nonce + "|" + f[1]))) p = q2;
                    if (p == null) continue;
                    if (!p.id.equals(f[1])) {   // 手動輸入／網頁安裝時手機先給的暫時 id：換成電腦自己的 pcId
                        boolean wasSel = p.id.equals(prefs.getString("pcSel", ""));
                        Pc dup = byId(f[1]); if (dup != null && dup != p) pcs.remove(dup);
                        p.id = f[1];
                        if (wasSel) select(p.id);
                    }
                    p.ip = ip; p.hostname = f[2]; p.name = f[3];
                    // f[4]：螢幕清單「寬x高[*];…」（11-20 起）；只有一個「寬x高」是更早的版本；0.10.0 之前是藍牙位址或空的
                    if (f[4].matches("^[0-9x*;@,\\-]+$")) {
                        p.mons = f[4];
                        for (int[] m : p.monitors()) if (m[2] == 1 || p.monitors().size() == 1) { p.scrW = m[0]; p.scrH = m[1]; }
                    }
                    // f[6]：電腦端版本（11-20 起；沒有這欄＝舊版，不能自動更新）。有這欄但空的＝不知道版本，當最舊
                    p.ver = f.length >= 7 ? (f[6].isEmpty() ? "0" : f[6]) : "";
                    p.lastSeen = System.currentTimeMillis();
                    found.add(p.id);
                }
            }
        } catch (Exception e) { Log.w(TAG, "discover " + e); }
        synchronized (this) {
            for (Pc p : pcs) p.online = found.contains(p.id);
            // 一台電腦重裝會換新 pcId：同主機名、比較舊、現在找不到的那筆就是它的舊身分，收掉
            for (int i = pcs.size() - 1; i >= 0; i--) {
                Pc old = pcs.get(i);
                if (old.online || old.hostname.isEmpty()) continue;
                for (Pc n : pcs) if (n != old && n.online && n.hostname.equalsIgnoreCase(old.hostname) && n.lastSeen > old.lastSeen) { pcs.remove(i); break; }
            }
            save();
        }
    }

    /** 線上的排前面（照名稱），連不到的排後面。 */
    public synchronized List<Pc> sorted() {
        List<Pc> l = new ArrayList<>(pcs);
        Collections.sort(l, (a, b) -> a.online != b.online ? (a.online ? -1 : 1) : a.label().compareToIgnoreCase(b.label()));
        return l;
    }

    static String hmac(String key, String msg) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = m.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", h[i]));
            return b.toString();
        } catch (Exception e) { return "x"; }
    }

    static String randHex(int bytes) {
        byte[] r = new byte[bytes]; new SecureRandom().nextBytes(r);
        StringBuilder b = new StringBuilder(); for (byte x : r) b.append(String.format("%02x", x));
        return b.toString();
    }

    /**
     * 現在連的 Wi-Fi 是不是「開放網路」（沒密碼，或 Enhanced Open：有加密但誰都能連）。
     * 開放網路上不准加電腦／分享 App：安裝檔走沒加密的網頁，半路可能被換掉。
     * 回 null＝可以；否則回原因。拿不到安全類型時放行（只是不確定，不擋）。
     */
    static String unsafeWifiReason(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "手機現在沒連 Wi-Fi";
            if (android.os.Build.VERSION.SDK_INT >= 31 && nc.getTransportInfo() instanceof android.net.wifi.WifiInfo) {
                int t = ((android.net.wifi.WifiInfo) nc.getTransportInfo()).getCurrentSecurityType();
                if (t == android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN || t == android.net.wifi.WifiInfo.SECURITY_TYPE_OWE)
                    return "這是開放的公共 Wi-Fi（不用密碼就能連）";
            }
        } catch (Exception e) { Log.w(TAG, "wifi security " + e); }
        return null;
    }

    /** 手機在 Wi-Fi 上的 IPv4（給「加一台電腦」顯示網址）。 */
    static Inet4Address wifiAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return (Inet4Address) a;
            }
        } catch (Exception ignored) {}
        return null;
    }

    static InetAddress subnetBroadcast() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || !ni.getName().startsWith("wlan")) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) return ia.getBroadcast();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
