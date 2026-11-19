// Padbridge：最小的 QR 產生器（位元組模式、錯誤更正 M、版本 1～10、八種遮罩挑扣分最低）。
// 不用外部套件：電腦端圖示把這段跟 PowerShell 一起編（Add-Type），配對 QR 在本機產生，配對碼不經過任何網路服務。
// 演算法照 QR 規格（ISO/IEC 18004）；結構參考 Nayuki 的 QR Code generator（MIT）。
using System;
using System.Collections.Generic;
using System.Text;

public static class PadbridgeQr {
    // 版本 1～10、等級 M：每塊錯誤更正碼數、第一組塊數、第一組每塊資料數、第二組塊數、第二組每塊資料數
    static readonly int[,] M = {
        {10,1,16,0,0},{16,1,28,0,0},{26,1,44,0,0},{18,2,32,0,0},{24,2,43,0,0},
        {16,4,27,0,0},{18,4,31,0,0},{22,2,38,2,39},{22,3,36,2,37},{26,4,43,1,44}
    };
    static readonly int[][] ALIGN = {
        new int[0], new[]{6,18}, new[]{6,22}, new[]{6,26}, new[]{6,30}, new[]{6,34},
        new[]{6,22,38}, new[]{6,24,42}, new[]{6,26,46}, new[]{6,28,50}
    };

    public static bool[,] Encode(string text) {
        byte[] data = Encoding.UTF8.GetBytes(text);
        int ver = 0, dataCap = 0;
        for (int v = 1; v <= 10; v++) {
            int cap = M[v-1,1]*M[v-1,2] + M[v-1,3]*M[v-1,4];
            int bits = 4 + (v <= 9 ? 8 : 16) + data.Length * 8;
            if (bits <= cap * 8) { ver = v; dataCap = cap; break; }
        }
        if (ver == 0) throw new ArgumentException("text too long for QR v10-M");
        // 位元串：模式 0100、字數、資料、結束、補齊
        var bb = new List<bool>();
        Action<int,int> put = (val, n) => { for (int i = n - 1; i >= 0; i--) bb.Add(((val >> i) & 1) != 0); };
        put(4, 4); put(data.Length, ver <= 9 ? 8 : 16);
        foreach (byte b in data) put(b, 8);
        put(0, Math.Min(4, dataCap * 8 - bb.Count));
        while (bb.Count % 8 != 0) bb.Add(false);
        for (int pad = 0xEC; bb.Count < dataCap * 8; pad ^= 0xEC ^ 0x11) put(pad, 8);
        byte[] dc = new byte[dataCap];
        for (int i = 0; i < bb.Count; i++) if (bb[i]) dc[i >> 3] |= (byte)(0x80 >> (i & 7));

        // 分塊、算 RS 錯誤更正、交錯
        int ecLen = M[ver-1,0];
        var blocks = new List<byte[]>(); var ecs = new List<byte[]>();
        int off = 0;
        for (int g = 0; g < 2; g++) {
            int cnt = M[ver-1, 1 + g*2], len = M[ver-1, 2 + g*2];
            for (int k = 0; k < cnt; k++) {
                byte[] blk = new byte[len]; Array.Copy(dc, off, blk, 0, len); off += len;
                blocks.Add(blk); ecs.Add(Rs(blk, ecLen));
            }
        }
        var all = new List<byte>();
        int maxLen = 0; foreach (var b in blocks) maxLen = Math.Max(maxLen, b.Length);
        for (int i = 0; i < maxLen; i++) foreach (var b in blocks) if (i < b.Length) all.Add(b[i]);
        for (int i = 0; i < ecLen; i++) foreach (var e in ecs) all.Add(e[i]);

        int size = ver * 4 + 17;
        bool[,] best = null; int bestPen = int.MaxValue;
        for (int mask = 0; mask < 8; mask++) {
            bool[,] mod = new bool[size, size]; bool[,] fn = new bool[size, size];
            DrawFunction(mod, fn, ver, size, mask);
            DrawData(mod, fn, all.ToArray(), size, mask);
            int pen = Penalty(mod, size);
            if (pen < bestPen) { bestPen = pen; best = mod; }
        }
        return best;   // [y, x]，true＝黑
    }

    static void Set(bool[,] m, bool[,] f, int x, int y, bool dark) { m[y, x] = dark; f[y, x] = true; }

    static void DrawFunction(bool[,] m, bool[,] f, int ver, int size, int mask) {
        for (int i = 0; i < size; i++) { Set(m, f, 6, i, i % 2 == 0); Set(m, f, i, 6, i % 2 == 0); }
        Finder(m, f, 3, 3, size); Finder(m, f, size - 4, 3, size); Finder(m, f, 3, size - 4, size);
        int[] al = ALIGN[ver-1];
        for (int i = 0; i < al.Length; i++) for (int j = 0; j < al.Length; j++) {
            if ((i == 0 && j == 0) || (i == 0 && j == al.Length - 1) || (i == al.Length - 1 && j == 0)) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++)
                Set(m, f, al[i] + dx, al[j] + dy, Math.Max(Math.Abs(dx), Math.Abs(dy)) != 1);
        }
        // 格式資訊（等級 M＝00）
        int data = (0 << 3) | mask, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) Set(m, f, 8, i, Bit(bits, i));
        Set(m, f, 8, 7, Bit(bits, 6)); Set(m, f, 8, 8, Bit(bits, 7)); Set(m, f, 7, 8, Bit(bits, 8));
        for (int i = 9; i < 15; i++) Set(m, f, 14 - i, 8, Bit(bits, i));
        for (int i = 0; i < 8; i++) Set(m, f, size - 1 - i, 8, Bit(bits, i));
        for (int i = 8; i < 15; i++) Set(m, f, 8, size - 15 + i, Bit(bits, i));
        Set(m, f, 8, size - 8, true);
        if (ver >= 7) {
            int r = ver;
            for (int i = 0; i < 12; i++) r = (r << 1) ^ ((r >> 11) * 0x1F25);
            long vb = ((long)ver << 12) | (uint)r;
            for (int i = 0; i < 18; i++) {
                bool d = ((vb >> i) & 1) != 0; int a = size - 11 + i % 3, b = i / 3;
                Set(m, f, a, b, d); Set(m, f, b, a, d);
            }
        }
    }

    static void Finder(bool[,] m, bool[,] f, int cx, int cy, int size) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int d = Math.Max(Math.Abs(dx), Math.Abs(dy)), x = cx + dx, y = cy + dy;
            if (x >= 0 && x < size && y >= 0 && y < size) Set(m, f, x, y, d != 2 && d != 4);
        }
    }

    static bool Bit(int x, int i) { return ((x >> i) & 1) != 0; }

    static void DrawData(bool[,] m, bool[,] f, byte[] cw, int size, int mask) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) for (int j = 0; j < 2; j++) {
                int x = right - j;
                bool up = ((right + 1) & 2) == 0;
                int y = up ? size - 1 - vert : vert;
                if (f[y, x]) continue;
                bool d = i < cw.Length * 8 && ((cw[i >> 3] >> (7 - (i & 7))) & 1) != 0;
                i++;
                bool inv;
                switch (mask) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                m[y, x] = d ^ inv;
            }
        }
    }

    // 扣分：同色連續、2×2 同色塊、像定位圖案的 1:1:3:1:1、黑白比例
    static int Penalty(bool[,] m, int size) {
        int p = 0;
        for (int pass = 0; pass < 2; pass++) for (int a = 0; a < size; a++) {
            int run = 1;
            for (int b = 1; b < size; b++) {
                bool c = pass == 0 ? m[a, b] : m[b, a], prev = pass == 0 ? m[a, b-1] : m[b-1, a];
                if (c == prev) { run++; if (run == 5) p += 3; else if (run > 5) p++; } else run = 1;
            }
            for (int b = 0; b + 10 < size; b++) {
                bool[] r = new bool[11];
                for (int k = 0; k < 11; k++) r[k] = pass == 0 ? m[a, b + k] : m[b + k, a];
                bool core = r[4] && !r[5] && r[6] && r[7] && r[8] && !r[9] && r[10];
                bool core2 = r[0] && !r[1] && r[2] && r[3] && r[4] && !r[5] && r[6];
                if ((core && !r[0] && !r[1] && !r[2] && !r[3]) || (core2 && !r[7] && !r[8] && !r[9] && !r[10])) p += 40;
            }
        }
        for (int y = 0; y < size - 1; y++) for (int x = 0; x < size - 1; x++) {
            bool c = m[y, x];
            if (c == m[y, x+1] && c == m[y+1, x] && c == m[y+1, x+1]) p += 3;
        }
        int dark = 0; foreach (bool c in m) if (c) dark++;
        int total = size * size;
        int k2 = (Math.Abs(dark * 20 - total * 10) + total - 1) / total - 1;
        if (k2 > 0) p += k2 * 10;
        return p;
    }

    // Reed-Solomon（GF(256)、本原多項式 0x11D）
    static byte[] Rs(byte[] data, int deg) {
        byte[] gen = new byte[deg]; gen[deg - 1] = 1; int root = 1;
        for (int i = 0; i < deg; i++) {
            for (int j = 0; j < deg; j++) { gen[j] = Mul(gen[j], (byte)root); if (j + 1 < deg) gen[j] ^= gen[j + 1]; }
            root = Mul((byte)root, 2);
        }
        byte[] res = new byte[deg];
        foreach (byte b in data) {
            byte factor = (byte)(b ^ res[0]);
            Array.Copy(res, 1, res, 0, deg - 1); res[deg - 1] = 0;
            for (int i = 0; i < deg; i++) res[i] ^= Mul(gen[i], factor);
        }
        return res;
    }
    static byte Mul(byte x, byte y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >> 7) * 0x11D); z ^= ((y >> i) & 1) * x; }
        return (byte)z;
    }
}
