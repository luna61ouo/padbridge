// Padbridge 繪圖板畫面預覽（接收程式用 Add-Type -Path 載入）。
// 手機每秒送一次 f 封包續約（每個要看的螢幕各一包）；這裡在背景執行緒輪流截每個「有人在看」的螢幕上那一塊、
// 縮小成 JPEG、用那支手機配對碼導出的金鑰加密，分段用 UDP 回到發 f 的那個位置。加密：AES-256-CBC＋HMAC-SHA256（先加密再簽）。
// 分段：每段 "PBF2"＋畫格號(4)＋第幾段(2)＋共幾段(2)＋螢幕編號(1)＋最多 1200 位元組。
// 整格＝IV(16)＋密文＋MAC(32)，MAC 蓋 畫格號(4)＋螢幕編號(1)＋IV＋密文。
// 檔案要 UTF-8 BOM（沒有的話 csc 用系統字碼讀，中文註解會吃掉換行）。
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
public static class PadbridgeFrames {
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    public static void DpiAware() { try { SetProcessDPIAware(); } catch { } }

    class Req {
        public IPEndPoint Dest; public byte[] EncKey, MacKey;
        public int X, Y, W, H, OutW, OutH;          // 要截的範圍（0～65535，相對於螢幕）與輸出大小
        public int MonX, MonY, MonW, MonH;           // 那個螢幕在虛擬桌面上的像素位置
        public DateTime Until;
    }
    static readonly object L = new object();
    static readonly Dictionary<int, Req> reqs = new Dictionary<int, Req>();   // 螢幕編號 → 請求（同時看好幾個螢幕）
    static Thread th;
    public static int Sent;
    public static string LastError = "";
    static uint fid;

    static byte[] Derive(string token, string label) {
        using (HMACSHA256 m = new HMACSHA256(Encoding.UTF8.GetBytes(token))) return m.ComputeHash(Encoding.UTF8.GetBytes(label));
    }

    // x、y、ww、hh＝0～65535，相對於螢幕 (monX, monY, monW, monH)；mon＝螢幕編號（回傳畫格會帶著）
    public static void Request(string ip, int port, string token, int x, int y, int ww, int hh, int ow, int oh,
                               int monX, int monY, int monW, int monH, int mon) {
        Req r = new Req();
        r.Dest = new IPEndPoint(IPAddress.Parse(ip), port);
        r.EncKey = Derive(token, "padbridge-frame-enc"); r.MacKey = Derive(token, "padbridge-frame-mac");
        r.X = x; r.Y = y; r.W = Math.Max(1, ww); r.H = Math.Max(1, hh);
        r.OutW = Math.Max(16, Math.Min(1280, ow)); r.OutH = Math.Max(16, Math.Min(1280, oh));
        r.MonX = monX; r.MonY = monY; r.MonW = Math.Max(1, monW); r.MonH = Math.Max(1, monH);
        r.Until = DateTime.UtcNow.AddSeconds(3);
        lock (L) {
            reqs[mon & 0xff] = r;
            if (th == null) { th = new Thread(Loop); th.IsBackground = true; th.Priority = ThreadPriority.BelowNormal; th.Start(); }
        }
    }

    static void Loop() {
        UdpClient udp = new UdpClient();
        ImageCodecInfo jpg = null;
        foreach (ImageCodecInfo c in ImageCodecInfo.GetImageEncoders()) if (c.MimeType == "image/jpeg") jpg = c;
        EncoderParameters ep = new EncoderParameters(1);
        ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 55L);
        while (true) {
            List<KeyValuePair<int, Req>> live = new List<KeyValuePair<int, Req>>();
            lock (L) {
                DateTime now = DateTime.UtcNow;
                List<int> dead = new List<int>();
                foreach (KeyValuePair<int, Req> kv in reqs) { if (now < kv.Value.Until) live.Add(kv); else dead.Add(kv.Key); }
                foreach (int k in dead) reqs.Remove(k);
            }
            if (live.Count == 0) { Thread.Sleep(100); continue; }
            DateTime t0 = DateTime.UtcNow;
            foreach (KeyValuePair<int, Req> kv in live) {
                try { SendOne(udp, jpg, ep, (byte)kv.Key, kv.Value); Sent++; }
                catch (Exception ex) { LastError = ex.Message; }
            }
            int spent = (int)(DateTime.UtcNow - t0).TotalMilliseconds;
            if (spent < 100) Thread.Sleep(100 - spent);   // 每個螢幕目標每秒約 10 張
        }
    }

    static void SendOne(UdpClient udp, ImageCodecInfo jpg, EncoderParameters ep, byte mon, Req r) {
        int sw = r.MonW, sh = r.MonH;
        int px = (int)((long)r.X * sw / 65536), py = (int)((long)r.Y * sh / 65536);
        int pw = Math.Max(1, (int)((long)r.W * sw / 65536)), ph = Math.Max(1, (int)((long)r.H * sh / 65536));
        if (px + pw > sw) pw = sw - px; if (py + ph > sh) ph = sh - py;
        px += r.MonX; py += r.MonY;
        byte[] img;
        using (Bitmap src = new Bitmap(pw, ph, PixelFormat.Format24bppRgb)) {
            using (Graphics g = Graphics.FromImage(src)) g.CopyFromScreen(px, py, 0, 0, new Size(pw, ph));
            using (Bitmap dst = new Bitmap(r.OutW, r.OutH, PixelFormat.Format24bppRgb)) {
                using (Graphics g2 = Graphics.FromImage(dst)) { g2.InterpolationMode = InterpolationMode.Bilinear; g2.DrawImage(src, 0, 0, r.OutW, r.OutH); }
                using (MemoryStream ms = new MemoryStream()) { dst.Save(ms, jpg, ep); img = ms.ToArray(); }
            }
        }
        byte[] iv = new byte[16]; byte[] ct;
        using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) rng.GetBytes(iv);
        using (Aes a = Aes.Create()) {
            a.KeySize = 256; a.Key = r.EncKey; a.IV = iv; a.Mode = CipherMode.CBC; a.Padding = PaddingMode.PKCS7;
            using (ICryptoTransform e = a.CreateEncryptor()) ct = e.TransformFinalBlock(img, 0, img.Length);
        }
        uint f = ++fid;
        byte[] hdr = new byte[] { (byte)(f >> 24), (byte)(f >> 16), (byte)(f >> 8), (byte)f, mon };
        byte[] mac;
        using (HMACSHA256 m = new HMACSHA256(r.MacKey)) {
            m.TransformBlock(hdr, 0, 5, null, 0); m.TransformBlock(iv, 0, 16, null, 0); m.TransformFinalBlock(ct, 0, ct.Length); mac = m.Hash;
        }
        byte[] all = new byte[16 + ct.Length + 32];
        Buffer.BlockCopy(iv, 0, all, 0, 16); Buffer.BlockCopy(ct, 0, all, 16, ct.Length); Buffer.BlockCopy(mac, 0, all, 16 + ct.Length, 32);
        const int CH = 1200;
        int n = (all.Length + CH - 1) / CH;
        for (int i = 0; i < n; i++) {
            int len = Math.Min(CH, all.Length - i * CH);
            byte[] pk = new byte[13 + len];
            pk[0] = (byte)'P'; pk[1] = (byte)'B'; pk[2] = (byte)'F'; pk[3] = (byte)'2';
            Buffer.BlockCopy(hdr, 0, pk, 4, 4);
            pk[8] = (byte)(i >> 8); pk[9] = (byte)i; pk[10] = (byte)(n >> 8); pk[11] = (byte)n; pk[12] = mon;
            Buffer.BlockCopy(all, i * CH, pk, 13, len);
            udp.Send(pk, pk.Length, r.Dest);
        }
    }
}
