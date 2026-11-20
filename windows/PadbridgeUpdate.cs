// Padbridge 電腦端自動更新（11-20）。有新版先提示、按了才更新（手機上按，或右下角圖示上按）。
//  兩個來源：①手機（同版 App 裡帶著的安裝包，區網下載；手機送簽章過的 u 封包叫我去拿）②GitHub Releases（每天看一次）。
//  ⚠️ 只裝「發布金鑰簽過名」的安裝包：簽名＝RSA-3072 PKCS#1 v1.5 SHA-256，蓋整個 zip；公鑰寫在下面。
//    GitHub 帳號被盜、下載被調包、或拿到配對碼的人都推不了假更新。版本號在 zip 裡（PadbridgeSetup/version.txt）、
//    一起被簽，而且一定要比現在新才裝 ⇒ 不能拿舊的簽名包來降版。
//  下載、驗簽、解壓都在背景執行緒做（不卡滑鼠）；好了 ReadyDir 有值，換檔與重啟由 PadbridgeReceiver.ps1 做。
using System;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

public static class PadbridgeUpdate {
    const string PubModulus = "3Zg4rEPhUv8PrMlXr2znh9m5q7tSSZT2o9f/UT6v7xldUJ4yqnwlkNaXv5hbVlPGWKQA2nXD6mntW5jZuVA61baU6IkWJq8oLL1Y4Gb2cylBcmbzGtJ5AShVVZgZ7MZazBZwmFMUf0KEOM4HLg+LJBsTmluNjUhobszekOeplXGf/GO3dT/7YvWUgIvcD82/9Jg4MLKubs9YRVXSRcquHrOj4nUij3HfB14mJXuTEGS/+gyB7e6ass0TR/TxP6F5UkUZDxZ2E0uz6RJILM/WAOh/LUIkEgdpYKIPeV3J6Eh/xr3XVXHhWJ/zWzIlg0zOkgQjr/a3/dzMWdjCF4OotYYn7YgTny+xxYmZ057oKkdU4sAsfzAN2VDTCCA1cIqhdTpESPGp6zc3fV2LfiCsyzAmJK2OdmBWT9KUs2yAt/25QwQeRFGQq5b+4D9y5uAo20Brwj86yjDwnLMEcKd4LijCET1DufehcajZZdDEY/QtyGW2vXKIXKSH/kymBfyT";
    const string PubExponent = "AQAB";
    public const string Api = "https://api.github.com/repos/luna61ouo/padbridge/releases/latest";

    public static volatile string LatestVer = "", LatestZip = "", LatestSig = "";   // GitHub 上最新版（查到才有）
    public static volatile string ReadyDir = "", ReadyVer = "", Error = "";
    public static volatile bool Busy;

    public static bool Verify(byte[] data, byte[] sig) {
        try {
            using (RSACryptoServiceProvider r = new RSACryptoServiceProvider()) {
                RSAParameters p = new RSAParameters();
                p.Modulus = Convert.FromBase64String(PubModulus); p.Exponent = Convert.FromBase64String(PubExponent);
                r.ImportParameters(p);
                return r.VerifyData(data, CryptoConfig.MapNameToOID("SHA256"), sig);
            }
        } catch { return false; }
    }

    /** "0.13.0" 跟 "0.12.1" 比：a 比 b 新回正數。空字串＝最舊。 */
    public static int Compare(string a, string b) {
        string[] x = (a ?? "").TrimStart('v').Split('.'), y = (b ?? "").TrimStart('v').Split('.');
        for (int i = 0; i < Math.Max(x.Length, y.Length); i++) {
            int u = 0, v = 0;
            if (i < x.Length) int.TryParse(x[i], out u);
            if (i < y.Length) int.TryParse(y[i], out v);
            if (u != v) return u - v;
        }
        return 0;
    }

    static byte[] Get(string url, int timeoutMs, int maxBytes) {
        ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
        HttpWebRequest q = (HttpWebRequest)WebRequest.Create(url);
        q.UserAgent = "Padbridge-updater"; q.Timeout = timeoutMs; q.ReadWriteTimeout = timeoutMs; q.AllowAutoRedirect = true;
        using (WebResponse w = q.GetResponse()) using (Stream s = w.GetResponseStream()) using (MemoryStream m = new MemoryStream()) {
            byte[] buf = new byte[16384]; int n;
            while ((n = s.Read(buf, 0, buf.Length)) > 0) { m.Write(buf, 0, n); if (m.Length > maxBytes) throw new Exception("too big"); }
            return m.ToArray();
        }
    }

    /** 背景查 GitHub 最新版（找 PadbridgeSetup-windows-*.zip 與 .sig）。 */
    public static void CheckGithubAsync() {
        new Thread(() => {
            try {
                string j = Encoding.UTF8.GetString(Get(Api, 15000, 2000000));
                Match t = Regex.Match(j, "\"tag_name\"\\s*:\\s*\"v?([0-9.]+)\"");
                Match z = Regex.Match(j, "\"browser_download_url\"\\s*:\\s*\"(https://github\\.com/luna61ouo/padbridge/releases/download/[^\"]+/PadbridgeSetup-windows-[0-9.]+\\.zip)\"");
                Match g = Regex.Match(j, "\"browser_download_url\"\\s*:\\s*\"(https://github\\.com/luna61ouo/padbridge/releases/download/[^\"]+/PadbridgeSetup-windows-[0-9.]+\\.zip\\.sig)\"");
                if (t.Success && z.Success && g.Success) { LatestZip = z.Groups[1].Value; LatestSig = g.Groups[1].Value; LatestVer = t.Groups[1].Value; }
            } catch { }
        }) { IsBackground = true }.Start();
    }

    /** 背景：下載 zip＋簽名 → 驗簽 → 版本要比 curVer 新 → 解壓到暫存資料夾。成功設 ReadyDir，失敗設 Error。 */
    public static void FetchAsync(string zipUrl, string sigUrl, string curVer) {
        if (Busy) return;
        Busy = true; Error = "";
        new Thread(() => {
            try {
                byte[] zip = Get(zipUrl, 20000, 5000000);
                byte[] sig = Convert.FromBase64String(Encoding.ASCII.GetString(Get(sigUrl, 20000, 10000)).Trim());
                if (!Verify(zip, sig)) throw new Exception("簽名不對，不裝");
                string tmp = Path.Combine(Path.GetTempPath(), "padbridge-update-" + Guid.NewGuid().ToString("N").Substring(0, 8));
                Directory.CreateDirectory(tmp);
                string ver = "";
                using (ZipArchive a = new ZipArchive(new MemoryStream(zip), ZipArchiveMode.Read)) {
                    foreach (ZipArchiveEntry e in a.Entries) {
                        if (e.FullName.EndsWith("/")) continue;
                        string name = Path.GetFileName(e.FullName);
                        if (!Regex.IsMatch(name, "^[A-Za-z0-9._-]+$")) continue;   // 只收一般檔名，不跟著路徑走
                        string dst = Path.Combine(tmp, name);
                        using (Stream s = e.Open()) using (FileStream f = File.Create(dst)) s.CopyTo(f);
                    }
                }
                string vf = Path.Combine(tmp, "version.txt");
                if (File.Exists(vf)) ver = File.ReadAllText(vf).Trim();
                if (Compare(ver, curVer) <= 0) throw new Exception("不是比較新的版本（" + ver + "）");
                ReadyVer = ver; ReadyDir = tmp;
            } catch (Exception ex) { Error = ex.Message; }
            finally { Busy = false; }
        }) { IsBackground = true }.Start();
    }
}
