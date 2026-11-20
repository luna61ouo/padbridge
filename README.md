# Padbridge

**把手機變成電腦的輸入中繼站。** ／ **Turn your phone into an input relay for your computer.**

[中文](#中文) · [English](#english)

---

## 中文

### 這是什麼

臨時要用滑鼠卻沒帶？要從沙發遠端操作電腦？好幾個人想輪流控制同一台電腦？
Padbridge 讓 Android 手機變成電腦的觸控板，透過 **Wi-Fi** 控制電腦游標。

滑鼠只是第一步。Padbridge 的目標是當「輸入中繼站」：手機接住藍牙搖桿、鍵盤、滑鼠、手錶手勢，再透過 Wi-Fi 轉給沒有藍牙、或不方便配對的電腦。

### 現在能做什麼

- 電腦只要裝一個 Windows 小程式（右下角圖示）；手機不需要任何特殊權限
- 一支手機可以控多台電腦（App 裡切換），多支手機也可以控同一台電腦（各自配對）
- 跟眼鏡、耳機、手錶等藍牙裝置並存，不搶藍牙
- 觸控板：單指移動、點一下左鍵、雙指點右鍵、雙指上下捲動、按住再滑拖曳
- **繪圖板模式**（絕對座標，配一支觸控筆就是繪圖板）：
  - 畫布＝電腦螢幕，碰哪裡游標就到哪裡；直放（下方有手掌區）或橫放（大畫布）
  - 兩種點擊：「自動左鍵」碰到就按下；「按鍵觸發」碰到只移動（像筆懸空），用畫面按鍵或音量鍵點擊
  - 畫面預覽：電腦畫面加密後傳到手機當畫布背景（約每秒 10 張）
  - 兩指縮放／拖曳＝只對應螢幕的一部分；「調整範圍」＝只用手機畫面的一小塊（節奏遊戲常用）
  - 多螢幕：勾選要顯示哪些螢幕，勾兩個以上就同時排在畫布上、碰哪個到哪個，每個螢幕的觸控範圍可以各自調整
- 配對：電腦圖示顯示 QR，手機 App 掃一下就好
- 電腦端圖示：顯示連入的手機（名稱可自訂）與使用狀態，可暫停、可撤銷某支手機

### 安裝

**手機（Android 10 以上）**：到 [Releases](../../releases) 下載 `padbridge-*.apk` 安裝。

**電腦（Windows 10／11）**：
1. 到 [Releases](../../releases) 下載 `PadbridgeSetup-windows-*.zip`，解壓縮
2. 雙擊 `PadbridgeSetup.cmd`
   - 出現「Windows 已保護您的電腦」→ 按「其他資訊」→「仍要執行」（安裝檔沒有付費數位簽章）
   - 問要不要允許變更 → 按「是」（要設定防火牆）
3. 右下角出現 Padbridge 圖示就裝好了，之後每次登入會自己啟動

手機 App 也找得到下載入口：「選電腦」→「電腦還沒裝 Padbridge？」，會顯示上面的 Releases 網址。網址不好打的話，可以讓電腦瀏覽器連到手機的區網 IP，網頁上自己選：
- **快速直接下載**：從手機拿同一個安裝包（不含任何配對碼）。走一般 HTTP，同網路的人有機會把檔案換掉，只在自己信任的網路用。
- **高安全性下載**：轉到 GitHub 發布頁，經 HTTPS 下載；請確認網址列是 `github.com/luna61ouo/padbridge`。

兩邊是同一個檔案，手機畫面會顯示 SHA-256，可以跟 `certutil -hashfile <檔名> SHA256` 對照。

**配對**：電腦圖示按右鍵 →「加一支手機（掃 QR）」→ 手機 App 切到 Wi-Fi →「選電腦」→「掃 QR 加電腦」。

### 安全設計

- 每支手機對每台電腦各有一把配對碼，**配對碼不會在網路上傳**：每個封包附上用配對碼算的簽章（HMAC-SHA256）與流水號，電腦只收簽章正確、流水號遞增的封包，同網路的人無法偽造或重播點擊。
- 配對碼透過「螢幕上的 QR → 手機相機」交給手機，不經過網路。
- 電腦防火牆只開 UDP 47801，只收同一個區網。
- 電腦端圖示可以隨時暫停，或撤銷某支手機。
- 仍會公開的：同網路的人看得到「有人在動滑鼠」（內容沒加密）；找電腦時電腦會回覆主機名稱。

### 從原始碼建置

需要 Android SDK（platform 35、build-tools 35.0.0）與 JDK 11 以上，不用 Gradle：

```bash
./build.sh          # 產出 build/padbridge.apk（沒有金鑰時會自動產一把 debug 用的）
./release.sh        # 產出 dist/ 裡的 APK 與 Windows 安裝包
```

### 目前限制

- 電腦端目前只支援 Windows；App 介面目前是中文
- 規劃中：鍵盤／搖桿／手勢轉送、筆壓、低延遲畫面串流
- 0.9.0 的藍牙（BLE）模式已移除：要穩定得靠一個用 adb 權限跑的小幫手，對一般使用者來說要開的權限太大

### 授權

Apache License 2.0，見 [LICENSE](LICENSE)。使用的第三方元件見 [NOTICE](NOTICE)。

---

## English

### What is it

Need a mouse but didn't bring one? Want to control your PC from the couch? Several people taking turns on one computer?
Padbridge turns an Android phone into a touchpad for your computer, over **Wi-Fi**.

The mouse is just the first piece. Padbridge aims to be an **input relay**: the phone receives Bluetooth gamepads, keyboards, mice and watch gestures, and forwards them over Wi-Fi to computers that have no Bluetooth or are inconvenient to pair.

### What works today

- Install one small Windows receiver (tray icon) on the PC; the phone needs no special permissions
- One phone can drive several PCs (switch in the app), and several phones can share one PC (each paired separately)
- Coexists with Bluetooth glasses, headsets and watches — it doesn't use Bluetooth at all
- Touchpad: one-finger move, tap = left click, two-finger tap = right click, two-finger scroll, press-and-hold then move to drag
- **Drawing-tablet mode** (absolute positioning — add a stylus and it's a pen tablet):
  - The canvas is your PC screen: touch a point and the cursor jumps there; portrait (with a palm-rest zone) or landscape (bigger canvas)
  - Two click styles: "auto left button" presses on touch; "button trigger" only moves on touch (like a hovering pen) and clicks with on-screen buttons or the volume keys
  - Screen preview: the PC screen is encrypted and streamed to the phone as the canvas background (~10 fps)
  - Pinch / two-finger drag maps the canvas to part of the screen; "adjust area" uses only part of the phone screen (common for rhythm games)
  - Multiple monitors: tick which monitors to show; with two or more they're laid out together, touch one to use it, and each monitor's area can be moved and resized
- Pairing: the PC tray icon shows a QR code; scan it in the app
- Tray icon: lists connected phones (custom names) and their status, pause, revoke a phone

### Install

**Phone (Android 10+)**: download `padbridge-*.apk` from [Releases](../../releases).

**PC (Windows 10/11)**:
1. Download `PadbridgeSetup-windows-*.zip` from [Releases](../../releases) and extract it
2. Double-click `PadbridgeSetup.cmd`
   - "Windows protected your PC" → "More info" → "Run anyway" (the installer is not code-signed)
   - Allow the admin prompt (needed for the firewall rule)
3. The Padbridge tray icon appears; it starts automatically at every logon

The app can point you there too: "選電腦" (choose PC) → "電腦還沒裝 Padbridge？" (PC doesn't have Padbridge yet?) shows the Releases URL. If typing it is a pain, the PC's browser can open the phone's LAN address and choose:
- **Quick download**: the same setup zip served by the phone (it contains no pairing secret). Plain HTTP, so someone on the same network could swap the file — only use it on a network you trust.
- **High-security download**: redirects to the GitHub release page over HTTPS; check the address bar shows `github.com/luna61ouo/padbridge`.

Both are the same file; the phone shows its SHA-256 so you can compare with `certutil -hashfile <file> SHA256`.

**Pairing**: right-click the tray icon → "加一支手機（掃 QR）" (add a phone via QR) → in the app switch to Wi-Fi → "選電腦" (choose PC) → "掃 QR 加電腦" (scan QR).

### Security

- Each phone gets its own pairing key per PC. **The key never travels over the network**: every packet carries an HMAC-SHA256 signature and a strictly increasing counter, so others on the same network cannot forge or replay clicks.
- The key is handed over screen-to-camera via the QR code.
- The PC firewall opens only UDP 47801, local subnet only.
- Pause or revoke any phone from the tray icon.
- Still visible to others on the network: that someone is moving a mouse (payload is not encrypted), and the PC's hostname in discovery replies.

### Build from source

Requires the Android SDK (platform 35, build-tools 35.0.0) and JDK 11+. No Gradle:

```bash
./build.sh          # builds build/padbridge.apk (creates a debug key if none is configured)
./release.sh        # builds the APK and the Windows setup zip into dist/
```

### Limitations

- PC side is Windows-only for now; the app UI is in Chinese for now
- Planned: keyboard / gamepad / gesture relaying, pen pressure, low-latency screen streaming
- The Bluetooth LE mode from 0.9.0 has been removed: making it reliable required a helper running with adb privileges, which is too much access to ask of normal users

### License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party notices in [NOTICE](NOTICE).
