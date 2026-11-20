// Padbridge 鍵盤（11-20）：手機鍵盤模組送來的加密封包 → SendInput。
//  解密：x|base64(IV 16＋AES-256-CBC 密文)，金鑰＝HMAC-SHA256(配對碼, "padbridge-key-enc")。外層 v2 簽章已在 ps1 驗過。
//  K|vk,vk,…＝「現在按著哪些鍵」的完整清單（Windows 虛擬鍵碼）：跟上一份比，少的放開、多的按下。
//    送掃描碼（KEYEVENTF_SCANCODE），遊戲也認得；PrtSc 這種沒有一般掃描碼的走虛擬鍵碼。
//    連發在這裡模擬（實體鍵盤的連發是鍵盤硬體做的，注入的按鍵不會自己連發）：最後按下的一般鍵，0.5 秒後每 33ms 再送一次按下。
//    保險：按著鍵卻超過 1 秒沒收到手機的狀態（手機每 300ms 重送一次）＝全部放開，掉包或手機斷線都不會卡住 Ctrl。
//  t|文字＝手機輸入法打好的字，用 KEYEVENTF_UNICODE 直接打（不經電腦的輸入法）；換行＝Enter、Tab＝Tab 鍵。
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

public static class PadbridgeKeys {
    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion u; }
    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, INPUT[] a, int size);
    [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint type);

    const uint EXT = 0x0001, UP = 0x0002, UNICODE = 0x0004, SCANCODE = 0x0008;
    static readonly HashSet<int> Ext = new HashSet<int> { 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5C, 0x5D, 0x6F, 0x90, 0xA3, 0xA5 };
    static readonly HashSet<int> NoRepeat = new HashSet<int> { 0x10, 0x11, 0x12, 0x14, 0x5B, 0x5C, 0x90, 0x91, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5 };
    static readonly HashSet<int> held = new HashSet<int>();
    static readonly Stopwatch sw = Stopwatch.StartNew();
    static int rep; static long repAt, lastState;
    public static int RepeatDelay = 500, RepeatEvery = 33, Timeout = 1000;
    public static int Held { get { return held.Count; } }

    static INPUT KeyInput(int vk, bool down) {
        INPUT i = new INPUT(); i.type = 1;
        uint sc = vk == 0x2C ? 0 : MapVirtualKey((uint)vk, 0);
        uint f = down ? 0 : UP;
        if (Ext.Contains(vk)) f |= EXT;
        if (sc != 0) { i.u.ki.wScan = (ushort)sc; f |= SCANCODE; } else i.u.ki.wVk = (ushort)vk;
        i.u.ki.dwFlags = f;
        return i;
    }
    static void Send(List<INPUT> l) { if (l.Count > 0) SendInput((uint)l.Count, l.ToArray(), Marshal.SizeOf(typeof(INPUT))); }
    static void Key(int vk, bool down) { Send(new List<INPUT> { KeyInput(vk, down) }); }

    public static void SetState(int[] vks) {
        lastState = sw.ElapsedMilliseconds;
        HashSet<int> want = new HashSet<int>();
        foreach (int v in vks) if (v > 0 && v < 256) want.Add(v);
        List<INPUT> l = new List<INPUT>();
        foreach (int v in new List<int>(held)) if (!want.Contains(v)) { l.Add(KeyInput(v, false)); held.Remove(v); }
        foreach (int v in vks) if (want.Contains(v) && !held.Contains(v)) {
            l.Add(KeyInput(v, true)); held.Add(v);
            if (!NoRepeat.Contains(v)) { rep = v; repAt = lastState + RepeatDelay; }
        }
        if (rep != 0 && !held.Contains(rep)) rep = 0;
        Send(l);
    }

    public static void Tick() {
        if (held.Count == 0) return;
        long now = sw.ElapsedMilliseconds;
        if (now - lastState > Timeout) { ReleaseAll(); return; }
        if (rep != 0 && now >= repAt) { Key(rep, true); repAt = now + RepeatEvery; }
    }

    public static void ReleaseAll() {
        List<INPUT> l = new List<INPUT>();
        foreach (int v in held) l.Add(KeyInput(v, false));
        held.Clear(); rep = 0;
        Send(l);
    }

    public static void Type(string s) {
        List<INPUT> l = new List<INPUT>();
        for (int k = 0; k < s.Length; k++) {
            char c = s[k];
            if (c == '\r') { if (k + 1 < s.Length && s[k + 1] == '\n') continue; c = '\n'; }
            if (c == '\n' || c == '\t') { int vk = c == '\n' ? 0x0D : 0x09; l.Add(KeyInput(vk, true)); l.Add(KeyInput(vk, false)); continue; }
            if (c < 0x20 || c == 0x7F) continue;
            INPUT d = new INPUT(); d.type = 1; d.u.ki.wScan = c; d.u.ki.dwFlags = UNICODE;
            INPUT u = d; u.u.ki.dwFlags = UNICODE | UP;
            l.Add(d); l.Add(u);
        }
        Send(l);
    }

    public static byte[] DeriveKey(string token) {
        using (HMACSHA256 h = new HMACSHA256(Encoding.UTF8.GetBytes(token))) return h.ComputeHash(Encoding.UTF8.GetBytes("padbridge-key-enc"));
    }
    /** 解不開回 null。 */
    public static string Open(byte[] key, string b64) {
        try {
            byte[] a = Convert.FromBase64String(b64);
            if (a.Length < 32 || (a.Length - 16) % 16 != 0) return null;
            using (Aes x = Aes.Create()) {
                x.KeySize = 256; x.Key = key; x.Mode = CipherMode.CBC; x.Padding = PaddingMode.PKCS7;
                byte[] iv = new byte[16]; Buffer.BlockCopy(a, 0, iv, 0, 16); x.IV = iv;
                using (ICryptoTransform t = x.CreateDecryptor()) return Encoding.UTF8.GetString(t.TransformFinalBlock(a, 16, a.Length - 16));
            }
        } catch { return null; }
    }
}
