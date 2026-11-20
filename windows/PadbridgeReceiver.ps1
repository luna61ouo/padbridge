# Padbridge 的 Windows 接收端：收手機經 Wi-Fi 送來的 UDP 簽章封包，用 SendInput 移動游標、按鍵、捲動。
# 封包：v2|流水號|m|dx|dy|簽章  /  v2|流水號|w|格數|簽章  /  v2|流水號|b|鍵(1左 2右 4中)|1按下或0放開|簽章
#       /  v2|流水號|a|x|y|按鍵|螢幕編號|簽章（繪圖板：x、y＝0～65535 對應那個螢幕；按鍵 1 按下左鍵、0 放開、-1 不動；沒螢幕編號＝主螢幕）
#       /  v2|流水號|f|x|y|寬|高|輸出寬|輸出高|簽章（繪圖板要畫面，見 PadbridgeFrames）
#       /  v2|流水號|s|wifi|手機名稱|簽章  /  v2|流水號|n|新名稱|簽章（簽章＝HMAC-SHA256(配對碼, 前面整段) 前 16 字）
# 配對碼放在 %LOCALAPPDATA%\Padbridge\phone-<id>.token（不會出現在網路上），簽章對不上的封包一律丟掉。
# 右下角有一個月亮小圖示：滑過去看狀態，右鍵可以暫停／結束（能動滑鼠的程式要看得到在跑）。
# 必須跑在使用者的桌面工作階段（排程「登入時、只在登入時執行」），從 SSH 的 session 0 跑游標不會動。

$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:LOCALAPPDATA 'Padbridge'
$port = 47801
$log = Join-Path $dir 'receiver.log'
# 身分：pcId 由手機在安裝檔裡給；舊安裝沒有就自己產一個。名稱以這裡的 name.txt 為準（手機或電腦端圖示都能改）。
$pcidFile = Join-Path $dir 'pcid.txt'
if (-not (Test-Path $pcidFile)) { [IO.File]::WriteAllText($pcidFile, (-join ((1..8) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) }))) }
$pcId = (Get-Content $pcidFile -Raw).Trim()
$nameFile = Join-Path $dir 'name.txt'
function Get-PcName { if (Test-Path $nameFile) { (Get-Content $nameFile -Raw -Encoding UTF8).Trim() } else { '' } }
function Set-PcName($n) { $n = ($n -replace '[|\r\n]', ' ').Trim(); if ($n.Length -gt 40) { $n = $n.Substring(0, 40) }; [IO.File]::WriteAllText($nameFile, $n, (New-Object Text.UTF8Encoding $false)) }
# ---- 多支手機：每支手機一把自己的配對碼，存成 phone-<id>.token；名稱另存 phone-<id>.name ----
# 封包不帶「我是誰」：用哪一把配對碼驗得過，就是哪一支（手機不多，逐把試很便宜）。各自的流水號分開記（防重播）。
# 舊版只有一把 token.txt ⇒ 第一次啟動時改名成 phone-first.token。移除一支手機＝刪掉它的檔＝撤銷配對碼。
$legacy = Join-Path $dir 'token.txt'
if (Test-Path $legacy) { Move-Item -Force $legacy (Join-Path $dir 'phone-first.token') }
$script:phones = [ordered]@{}
function New-PhoneObj($id, $tok) {
    $nf = Join-Path $dir "phone-$id.name"
    [pscustomobject]@{
        id = $id; token = $tok
        hmac = New-Object System.Security.Cryptography.HMACSHA256 (, [Text.Encoding]::UTF8.GetBytes($tok))
        name = $(if (Test-Path $nf) { (Get-Content $nf -Raw -Encoding UTF8).Trim() } else { '' })
        lastCtr = [int64]0; lastPkt = $null; mode = ''; ip = $null
    }
}
function Load-Phones {
    foreach ($f in Get-ChildItem $dir -Filter 'phone-*.token' -ErrorAction SilentlyContinue) {
        $id = $f.BaseName.Substring(6)
        if (-not $script:phones.Contains($id)) {
            $tok = (Get-Content $f.FullName -Raw).Trim()
            if ($tok.Length -ge 16) { $script:phones[$id] = New-PhoneObj $id $tok }
        }
    }
}
Load-Phones
function Save-PhoneField($ph, $field, $val) {
    $val = ("$val" -replace '[|\r\n]', ' ').Trim(); if ($val.Length -gt 40) { $val = $val.Substring(0, 40) }
    $ph.$field = $val
    [IO.File]::WriteAllText((Join-Path $dir "phone-$($ph.id).$field"), $val, (New-Object Text.UTF8Encoding $false))
}
function Remove-Phone($id) {
    Get-ChildItem $dir -Filter "phone-$id.*" -ErrorAction SilentlyContinue | Remove-Item -Force
    $script:phones.Remove($id)
    "$(Get-Date -Format s) phone removed $id" | Out-File -Append -Encoding utf8 $log
}
function Get-Hmac($ph, $msg) {
    $d = $ph.hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($msg))
    [BitConverter]::ToString($d, 0, 8).Replace('-', '').ToLower()
}
function Phone-Label($ph) { if ($ph.name) { $ph.name } else { '手機（' + $ph.id.Substring(0, [Math]::Min(6, $ph.id.Length)) + '）' } }
# 封包驗證：v2|流水號|指令|參數…|簽章。配對碼不在線上傳；
# 流水號要比該手機上一包大、而且跟電腦時間差 10 分鐘內（防止把錄下來的封包再送一次）。回驗過的那支手機，或 $null。
$script:lastPhoneId = $null
function Test-Packet([string]$text) {
    $i = $text.LastIndexOf('|')
    if ($i -lt 0 -or -not $text.StartsWith('v2|')) { return $null }
    $body = $text.Substring(0, $i); $sig = $text.Substring($i + 1)
    $cands = @()
    if ($script:lastPhoneId -and $script:phones.Contains($script:lastPhoneId)) { $cands += $script:phones[$script:lastPhoneId] }
    $cands += @($script:phones.Values | Where-Object { $_.id -ne $script:lastPhoneId })
    foreach ($ph in $cands) {
        if ((Get-Hmac $ph $body) -ne $sig) { continue }
        $ctr = [int64]0
        if (-not [int64]::TryParse($body.Split('|')[1], [ref]$ctr)) { return $null }
        $nowUs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000
        if ($ctr -le $ph.lastCtr -or [math]::Abs($ctr - $nowUs) -gt 600000000) { return $null }
        $ph.lastCtr = $ctr
        $script:lastPhoneId = $ph.id
        return $ph
    }
    return $null
}
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class PadbridgeInput {
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public MOUSEINPUT mi; }
    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint n, INPUT[] inputs, int size);
    static void Send(int dx, int dy, uint data, uint flags) {
        INPUT[] a = new INPUT[1];
        a[0].type = 0;
        a[0].mi.dx = dx; a[0].mi.dy = dy; a[0].mi.mouseData = data; a[0].mi.dwFlags = flags;
        SendInput(1, a, Marshal.SizeOf(typeof(INPUT)));
    }
    public static void Move(int dx, int dy) { Send(dx, dy, 0, 0x0001); }
    // 絕對座標：MOVE|ABSOLUTE，0～65535 對應主螢幕（不加 VIRTUALDESK）
    public static void Abs(int x, int y) { Send(x, y, 0, 0x8001); }
    // 多螢幕：0～65535 對應整個虛擬桌面（MOVE|ABSOLUTE|VIRTUALDESK）
    public static void AbsV(int x, int y) { Send(x, y, 0, 0xC001); }
    public static void Wheel(int n) { Send(0, 0, unchecked((uint)(n * 120)), 0x0800); }
    public static void Button(int btn, bool down) {
        uint f = 0;
        if (btn == 1) f = down ? 0x0002u : 0x0004u;
        else if (btn == 2) f = down ? 0x0008u : 0x0010u;
        else if (btn == 4) f = down ? 0x0020u : 0x0040u;
        if (f != 0) Send(0, 0, 0, f);
    }
}
"@

# ---- 繪圖板畫面預覽：手機要（f 封包、每秒一次續約），這裡在背景執行緒截「畫布對應的那一塊」、縮小、加密，分段用 UDP 回給手機 ----
# 加密：AES-256-CBC＋HMAC-SHA256（先加密再簽），金鑰從那支手機的配對碼導出；畫面可能有私人內容，一定要加密。
# 分段：每段 "PBF1"＋畫格號(4)＋第幾段(2)＋共幾段(2)＋最多 1200 位元組。整格＝IV(16)＋密文＋MAC(32)，MAC 蓋 畫格號＋IV＋密文。
Add-Type -ReferencedAssemblies System.Drawing -Path (Join-Path $dir 'PadbridgeFrames.cs')
[PadbridgeFrames]::DpiAware()   # 截圖要真實像素（不然有縮放的螢幕會截錯位置）；SendInput 絕對座標本來就是比例，不受影響

# ---- 多螢幕：清單順序＝Screen.AllScreens（DPI aware 之後是真實像素）。找電腦回應第 5 欄送「寬x高@x,y[*];…」，* ＝主螢幕 ----
Add-Type -AssemblyName System.Windows.Forms
function Update-Monitors {
    $script:mons = @([System.Windows.Forms.Screen]::AllScreens | ForEach-Object { $_ })
    $script:vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $script:monList = ($script:mons | ForEach-Object { "$($_.Bounds.Width)x$($_.Bounds.Height)@$($_.Bounds.X),$($_.Bounds.Y)" + $(if ($_.Primary) { '*' } else { '' }) }) -join ';'
}
Update-Monitors
function Get-MonIndex([string]$i) {
    $k = -1; if ([int]::TryParse($i, [ref]$k) -and $k -ge 0 -and $k -lt $script:mons.Count) { return $k }
    for ($j = 0; $j -lt $script:mons.Count; $j++) { if ($script:mons[$j].Primary) { return $j } }
    0
}
function Get-Mon([string]$i) {
    $k = -1; if (-not [int]::TryParse($i, [ref]$k) -or $k -lt 0 -or $k -ge $script:mons.Count) { return ($script:mons | Where-Object { $_.Primary } | Select-Object -First 1) }
    $script:mons[$k]
}
"$(Get-Date -Format s) start port=$port" | Out-File -Append -Encoding utf8 $log
$udp = New-Object System.Net.Sockets.UdpClient($port)
$ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
$bad = 0
# 滑順度統計：每一段連續滑動（中間停超過 300ms 算一段）結束時，把封包間隔寫進 receiver.log（只記 20 包以上的段）
$sw = [Diagnostics.Stopwatch]::StartNew()
$lastM = -1.0
$gaps = New-Object System.Collections.Generic.List[double]
function Flush-Stats {
    if ($gaps.Count -ge 20) {
        $a = $gaps.ToArray(); [Array]::Sort($a)
        $n = $a.Length
        $big = ($a | Where-Object { $_ -gt 20 }).Count
        "$(Get-Date -Format s) burst n=$n med=$([math]::Round($a[[int]($n*0.5)],1)) p90=$([math]::Round($a[[int]($n*0.9)],1)) p99=$([math]::Round($a[[int]($n*0.99)],1)) max=$([math]::Round($a[$n-1],1)) over20ms=$big" | Out-File -Append -Encoding utf8 $log
    }
    $gaps.Clear()
}
# ---- 右下角小圖示（淡紫色彎月；暫停時變灰）----
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$bmp = New-Object System.Drawing.Bitmap 32, 32
$gr = [System.Drawing.Graphics]::FromImage($bmp)
$gr.SmoothingMode = 'AntiAlias'
$gr.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 186, 160, 255))), 3, 3, 26, 26)
$gr.CompositingMode = 'SourceCopy'
$gr.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Transparent)), 12, 0, 24, 24)
$gr.Dispose()
$icoOn = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
$bmp2 = New-Object System.Drawing.Bitmap $bmp
for ($x = 0; $x -lt 32; $x++) { for ($y = 0; $y -lt 32; $y++) { $c = $bmp2.GetPixel($x, $y); if ($c.A -gt 0) { $v = [int](($c.R + $c.G + $c.B) / 3); $bmp2.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($c.A, $v, $v, $v)) } } }
$icoOff = [System.Drawing.Icon]::FromHandle($bmp2.GetHicon())

$script:paused = $false
$script:running = $true
$script:lastPkt = $null
$tray = New-Object System.Windows.Forms.NotifyIcon
$tray.Icon = $icoOn
$tray.Visible = $true
$menu = New-Object System.Windows.Forms.ContextMenuStrip
$miState = $menu.Items.Add('Padbridge')
$miState.Enabled = $false
# 電腦的區網 IP（手機 App 沒辦法自動配對時，照這裡手動輸入）。每次打開選單重抓，IP 換了也跟得上。
function Get-LanIPs {
    [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
        Where-Object { $_.OperationalStatus -eq 'Up' -and $_.NetworkInterfaceType -ne 'Loopback' } |
        ForEach-Object {
            $ipp = $_.GetIPProperties()
            if ($ipp.GatewayAddresses.Count -eq 0) { return }   # 沒有預設閘道＝不是對外的那張卡（Tailscale、虛擬網卡）
            $ipp.UnicastAddresses | Where-Object { $_.Address.AddressFamily -eq 'InterNetwork' } | ForEach-Object { $_.Address.ToString() }
        } | Where-Object { $_ -notlike '169.254.*' -and $_ -notlike '100.*' } | Select-Object -Unique
}
$miName = $menu.Items.Add('名稱：…（點一下改）')
$miName.add_Click({
    Add-Type -AssemblyName Microsoft.VisualBasic
    $cur = Get-PcName; if (-not $cur) { $cur = $env:COMPUTERNAME }
    $n = [Microsoft.VisualBasic.Interaction]::InputBox("手機的電腦清單上要顯示什麼名字？`n（主機名 $env:COMPUTERNAME 會一起顯示）", 'Padbridge：這台電腦的名稱', $cur)
    if ($n) { Set-PcName $n }
})
$miIp = $menu.Items.Add('電腦 IP：…')
$miIp.add_Click({
    $ips = @(Get-LanIPs)
    if ($ips.Count) { [System.Windows.Forms.Clipboard]::SetText($ips[0]) }
})
# ---- 配對 QR：每支手機一把配對碼，用 QR 交給手機（螢幕到相機，網路上的人看不到）。QR 在本機產生（PadbridgeQr.cs）。----
Add-Type -Path (Join-Path $dir 'PadbridgeQr.cs')
$script:qrForm = $null
function Show-PairQr($ph) {
    $ips = @(Get-LanIPs)
    if (-not $ips.Count) { [System.Windows.Forms.MessageBox]::Show('這台電腦好像沒連上區網，手機會找不到它。', 'Padbridge') | Out-Null; return }
    $nm = Get-PcName; if (-not $nm) { $nm = $env:COMPUTERNAME }
    $url = "padbridge://pair#v=1&pc=$pcId&ip=$($ips[0])&port=$port&t=$($ph.token)&n=" + [Uri]::EscapeDataString($nm)
    $mx = [PadbridgeQr]::Encode($url); $n = $mx.GetLength(0); $sc = 7; $q = 4
    $bmpq = New-Object System.Drawing.Bitmap (($n + 2 * $q) * $sc), (($n + 2 * $q) * $sc)
    $g = [System.Drawing.Graphics]::FromImage($bmpq); $g.Clear([System.Drawing.Color]::White)
    for ($y = 0; $y -lt $n; $y++) { for ($x = 0; $x -lt $n; $x++) { if ($mx[$y, $x]) { $g.FillRectangle([System.Drawing.Brushes]::Black, ($x + $q) * $sc, ($y + $q) * $sc, $sc, $sc) } } }
    $g.Dispose()
    if ($script:qrForm -and -not $script:qrForm.IsDisposed) { $script:qrForm.Close() }
    $f = New-Object System.Windows.Forms.Form
    $f.Text = "Padbridge：配對手機（$(Phone-Label $ph)）"; $f.TopMost = $true; $f.StartPosition = 'CenterScreen'
    $f.FormBorderStyle = 'FixedDialog'; $f.MaximizeBox = $false; $f.MinimizeBox = $false; $f.Icon = $icoOn; $f.BackColor = [System.Drawing.Color]::White
    $pb = New-Object System.Windows.Forms.PictureBox
    $pb.Image = $bmpq; $pb.SizeMode = 'AutoSize'; $pb.Left = 20; $pb.Top = 16
    $l = New-Object System.Windows.Forms.Label
    $l.Left = 20; $l.Top = $bmpq.Height + 24; $l.Width = [Math]::Max(360, $bmpq.Width); $l.Height = 120
    $l.Font = New-Object System.Drawing.Font('Microsoft JhengHei UI', 10)
    $l.Text = "用手機 Padbridge 的「掃 QR 加電腦」，或手機相機掃這個 QR。`n掃不了的話，在 App「選電腦」→「手動輸入」填：`n電腦 IP：$($ips -join ' 或 ')　port：$port`n配對碼：$($ph.token)"
    $f.Controls.Add($pb); $f.Controls.Add($l)
    $f.ClientSize = New-Object System.Drawing.Size(([Math]::Max(360, $bmpq.Width) + 40), ($bmpq.Height + 150))
    $script:qrForm = $f
    $f.Show(); $f.Activate()
}
$miAddPhone = $menu.Items.Add('加一支手機（掃 QR）…')
$miAddPhone.add_Click({
    # 發一把新的配對碼給下一支手機（每支手機各一把，移除時只撤銷它那把）；手機掃了、第一次連上就會帶名字回來
    $id = 'm' + (-join ((1..5) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) }))
    $tok = -join ((1..12) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) })
    [IO.File]::WriteAllText((Join-Path $dir "phone-$id.token"), $tok)
    $script:phones[$id] = New-PhoneObj $id $tok
    "$(Get-Date -Format s) phone added (QR) $id" | Out-File -Append -Encoding utf8 $log
    Show-PairQr $script:phones[$id]
})
# 手機清單：每支一行（名稱＋狀態），點進去可以移除（撤銷它的配對碼）
$miPhones = New-Object System.Windows.Forms.ToolStripMenuItem '手機'
$null = $menu.Items.Insert(1, $miPhones)
function Phone-Status($ph) {
    $act = $ph.lastPkt -and ((Get-Date) - $ph.lastPkt).TotalSeconds -lt 5
    if ($act) { return 'Wi-Fi 使用中' }
    $st = $(if ($ph.mode) { 'Wi-Fi 待命' } else { '等待配對（還沒連過）' })
    if ($ph.lastPkt) { $st += '，最後 ' + $ph.lastPkt.ToString('HH:mm') }
    $st
}
function Build-PhoneMenu {
    $miPhones.DropDownItems.Clear()
    $n = $script:phones.Count
    $miPhones.Text = "手機（$n 支）"
    if ($n -eq 0) { $x = $miPhones.DropDownItems.Add('還沒有手機'); $x.Enabled = $false; return }
    foreach ($ph in @($script:phones.Values)) {
        $it = New-Object System.Windows.Forms.ToolStripMenuItem ("$(Phone-Label $ph)　—　$(Phone-Status $ph)")
        $qr = $it.DropDownItems.Add('顯示配對 QR')
        $qr.Tag = $ph.id
        $qr.add_Click({ param($sender) if ($script:phones.Contains($sender.Tag)) { Show-PairQr $script:phones[$sender.Tag] } })
        $rm = $it.DropDownItems.Add('移除這支手機（撤銷它的配對碼）')
        $rm.Tag = $ph.id
        $rm.add_Click({
            param($sender)
            $pid2 = $sender.Tag
            $ph2 = $script:phones[$pid2]
            $ok = [System.Windows.Forms.MessageBox]::Show("移除「$(Phone-Label $ph2)」？`n之後它就不能再用 Wi-Fi 控制這台電腦，要用得重新加。", 'Padbridge', 'OKCancel', 'Warning')
            if ($ok -eq 'OK') { Remove-Phone $pid2 }
        })
        $null = $miPhones.DropDownItems.Add($it)
    }
}
$menu.add_Opening({
    Load-Phones
    Build-PhoneMenu
    $nm = Get-PcName
    $miName.Text = "名稱：$(if ($nm) { $nm } else { $env:COMPUTERNAME })（主機名 $env:COMPUTERNAME）— 點一下改"
    $ips = @(Get-LanIPs)
    $miIp.Text = $(if ($ips.Count) { "電腦 IP：$($ips -join ', ')（點一下複製）" } else { '電腦 IP：找不到（沒連上家裡網路？）' })
})
$null = $menu.Items.Add('-')
$miPause = $menu.Items.Add('暫停（手機動不了滑鼠）')
$miPause.add_Click({
    $script:paused = -not $script:paused
    if ($script:paused) { foreach ($b in 1, 2, 4) { [PadbridgeInput]::Button($b, $false) } }
    $miPause.Text = $(if ($script:paused) { '繼續' } else { '暫停（手機動不了滑鼠）' })
    $tray.Icon = $(if ($script:paused) { $icoOff } else { $icoOn })
    "$(Get-Date -Format s) paused=$($script:paused)" | Out-File -Append -Encoding utf8 $log
})
$miQuit = $menu.Items.Add('結束（下次登入會再自己啟動）')
$miQuit.add_Click({ $script:running = $false })
$tray.ContextMenuStrip = $menu
$script:phoneMode = ''
function Update-Tip {
    $nm = Get-PcName; if (-not $nm) { $nm = $env:COMPUTERNAME }
    $wifiActive = $script:lastPkt -and ((Get-Date) - $script:lastPkt).TotalSeconds -lt 5
    $act = $null
    if ($script:lastPhoneId -and $script:phones.Contains($script:lastPhoneId)) { $act = Phone-Label $script:phones[$script:lastPhoneId] }
    $who = $(if ($act) { $act } else { '手機' })
    $now = $(if ($wifiActive -or $script:phoneMode) { "$who 已連線" } else { '手機：還沒連過' })
    if ($script:phones.Count -gt 1) { $now += "（共 $($script:phones.Count) 支）" }
    if ($script:paused) { $now = '暫停中（手機動不了滑鼠）' }
    $wifiTxt = $(if ($wifiActive) { '使用中' } else { '待命' })
    $t = "Padbridge［$nm］`n$now`n$wifiTxt"
    if ($t.Length -gt 63) { $t = $t.Substring(0, 63) }
    $tray.Text = $t
    $miState.Text = "$now　（$wifiTxt）"
    $tray.Icon = $(if ($script:paused) { $icoOff } else { $icoOn })
}
Update-Tip
$tipTimer = [Diagnostics.Stopwatch]::StartNew()
$script:tick = 0

# ---- 主迴圈：有封包就處理，沒有就讓小圖示的選單有機會回應（Poll 一收到封包就會返回，不會多延遲）----
try {
    while ($script:running) {
        if ($udp.Client.Poll(30000, [System.Net.Sockets.SelectMode]::SelectRead)) {
            while ($udp.Available -gt 0) {
                $bytes = $udp.Receive([ref]$ep)
                $text = [System.Text.Encoding]::UTF8.GetString($bytes)
                $p = $text.Split('|')
                if ($p[0] -eq 'LMD' -and $p.Length -ge 2 -and $p[1].Length -le 32) {
                    # 手機在找電腦：回身分＋用配對碼算的證明（手機驗得過才會認這台）
                    $nm = Get-PcName
                    # 每把配對碼各算一個證明（逗號分隔）：手機拿自己那把對得上任何一個，就知道是這台、而且這台認得它
                    $proofs = (@($script:phones.Values | ForEach-Object { Get-Hmac $_ "$($p[1])|$pcId" }) -join ',')
                    # 第 5 欄：主螢幕寬x高（手機的繪圖板照這個比例畫；只要比例，DPI 縮放不影響）
                    $r = [Text.Encoding]::UTF8.GetBytes("LMH|$pcId|$env:COMPUTERNAME|$nm|$($script:monList)|$proofs")
                    $null = $udp.Send($r, $r.Length, $ep)
                    continue
                }
                $ph = $(if ($p.Length -ge 4) { Test-Packet $text } else { $null })
                if (-not $ph) {
                    $bad++
                    if ($bad % 50 -eq 1) { "$(Get-Date -Format s) rejected from $($ep.Address) (count $bad)" | Out-File -Append -Encoding utf8 $log }
                    continue
                }
                $ph.ip = $ep.Address
                try {
                    switch ($p[2]) {
                        'm' {
                            if ($script:paused) { break }
                            [PadbridgeInput]::Move([int]$p[3], [int]$p[4])
                            $script:lastPkt = Get-Date; $ph.lastPkt = $script:lastPkt
                            $t = $sw.Elapsed.TotalMilliseconds
                            if ($lastM -ge 0) { $g = $t - $lastM; if ($g -gt 300) { Flush-Stats } else { $gaps.Add($g) } }
                            $lastM = $t
                        }
                        'a' {
                            if ($script:paused) { break }
                            if ($p.Length -ge 8) {   # 新版手機帶螢幕編號：換算成整個虛擬桌面上的位置
                                $mb = (Get-Mon $p[6]).Bounds; $v = $script:vs
                                $gx = $mb.X + [double]$p[3] / 65535 * ($mb.Width - 1); $gy = $mb.Y + [double]$p[4] / 65535 * ($mb.Height - 1)
                                [PadbridgeInput]::AbsV([int](($gx - $v.X) * 65535 / [Math]::Max(1, $v.Width - 1)), [int](($gy - $v.Y) * 65535 / [Math]::Max(1, $v.Height - 1)))
                            } else { [PadbridgeInput]::Abs([int]$p[3], [int]$p[4]) }
                            if ($p[5] -eq '1') { [PadbridgeInput]::Button(1, $true) } elseif ($p[5] -eq '0') { [PadbridgeInput]::Button(1, $false) }
                            $script:lastPkt = Get-Date; $ph.lastPkt = $script:lastPkt
                        }
                        'f' {   # 繪圖板要畫面：f|x|y|寬|高（0～65535，相對於那個螢幕）|輸出寬|輸出高|螢幕編號；回到發這包的那個位置
                            if ($p.Length -ge 10) {
                                $mi = Get-MonIndex $(if ($p.Length -ge 11) { $p[9] } else { '' }); $mb = $script:mons[$mi].Bounds
                                [PadbridgeFrames]::Request($ep.Address.ToString(), $ep.Port, $ph.token, [int]$p[3], [int]$p[4], [int]$p[5], [int]$p[6], [int]$p[7], [int]$p[8], $mb.X, $mb.Y, $mb.Width, $mb.Height, $mi)
                            }
                        }
                        'w' { if (-not $script:paused) { [PadbridgeInput]::Wheel([int]$p[3]); $script:lastPkt = Get-Date; $ph.lastPkt = $script:lastPkt } }
                        'b' { if (-not $script:paused) { [PadbridgeInput]::Button([int]$p[3], $p[4] -eq '1'); $script:lastPkt = Get-Date; $ph.lastPkt = $script:lastPkt } }
                        's' {   # 手機回報名稱（s|wifi|名稱；第二欄是舊版的模式欄）
                            $script:phoneMode = $p[3]; $ph.mode = $p[3]
                            if ($p.Length -ge 6 -and $p[4] -and $p[4] -ne $ph.name) { Save-PhoneField $ph 'name' $p[4] }
                        }
                        'n' { if ($p.Length -ge 4) { Set-PcName $p[3]; "$(Get-Date -Format s) renamed by phone" | Out-File -Append -Encoding utf8 $log } }
                    }
                } catch { }
            }
        }
        [System.Windows.Forms.Application]::DoEvents()
        if ($tipTimer.ElapsedMilliseconds -gt 1000) {
            Update-Tip; $tipTimer.Restart()
            if ((++$script:tick % 10) -eq 0) { Load-Phones; Update-Monitors }   # 螢幕插拔、改解析度也跟得上   # 安裝檔新加的手機（phone-*.token）不用重開也認得
        }
    }
} finally {
    "$(Get-Date -Format s) exit" | Out-File -Append -Encoding utf8 $log
    $tray.Visible = $false
    $tray.Dispose()
    $udp.Close()
}
